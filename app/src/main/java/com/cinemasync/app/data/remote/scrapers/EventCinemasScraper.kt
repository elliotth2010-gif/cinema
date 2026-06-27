package com.cinemasync.app.data.remote.scrapers

import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.CinemaChain
import com.cinemasync.app.data.model.Movie
import com.cinemasync.app.data.model.Session
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class EventCinemasScraper @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) : BaseCinemaScraper {

    private val baseUrl = "https://www.eventcinemas.com.au"

    override suspend fun findNearbyCinemas(lat: Double, lng: Double, radiusKm: Double): List<Cinema> {
        return try {
            val url = "$baseUrl/api/cinemas/nearby?lat=$lat&lng=$lng&radius=${radiusKm.toInt()}"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .addHeader("Accept", "application/json")
                .addHeader("x-requested-with", "XMLHttpRequest")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            parseCinemasResponse(body, lat, lng)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseCinemasResponse(body: String, userLat: Double, userLng: Double): List<Cinema> {
        val cinemas = mutableListOf<Cinema>()
        try {
            val json = gson.fromJson(body, JsonObject::class.java)
            val results = json.getAsJsonArray("cinemas") ?: return emptyList()
            results.forEach { element ->
                val obj = element.asJsonObject
                val id = obj.get("id")?.asString ?: return@forEach
                val lat = obj.get("lat")?.asDouble ?: return@forEach
                val lng = obj.get("lng")?.asDouble ?: return@forEach
                cinemas.add(
                    Cinema(
                        id = "event_$id",
                        name = obj.get("name")?.asString ?: return@forEach,
                        chain = CinemaChain.EVENT,
                        address = obj.get("address")?.asString ?: "",
                        suburb = obj.get("suburb")?.asString ?: "",
                        latitude = lat,
                        longitude = lng,
                        websiteUrl = "$baseUrl/cinemas/${obj.get("slug")?.asString ?: id}",
                        distanceKm = haversineKm(userLat, userLng, lat, lng)
                    )
                )
            }
        } catch (_: Exception) {}
        return cinemas.sortedBy { it.distanceKm }
    }

    override suspend fun fetchSessionsForDate(cinema: Cinema, dateMs: Long): ScraperResult {
        val cinemaId = cinema.id.removePrefix("event_")
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(dateMs))

        return try {
            val graphqlQuery = """
                {
                  "query": "query Sessions(${'$'}cinemaId: ID!, ${'$'}date: String!) { sessions(cinemaId: ${'$'}cinemaId, date: ${'$'}date) { id movieId startTime endTime screenType bookingUrl movie { id title synopsis rating runtime genres posterUrl } } }",
                  "variables": { "cinemaId": "$cinemaId", "date": "$dateStr" }
                }
            """.trimIndent()

            val requestBody = graphqlQuery.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/graphql")
                .post(requestBody)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return fallbackHtmlScrape(cinema, cinemaId, dateStr)

            val body = response.body?.string() ?: return ScraperResult(cinema, emptyList(), emptyList())
            parseGraphqlResponse(cinema, body)
        } catch (e: Exception) {
            ScraperResult(cinema, emptyList(), emptyList())
        }
    }

    private fun parseGraphqlResponse(cinema: Cinema, body: String): ScraperResult {
        val movies = mutableListOf<Movie>()
        val sessions = mutableListOf<Session>()
        try {
            val json = gson.fromJson(body, JsonObject::class.java)
            val sessionsArray = json.getAsJsonObject("data")
                ?.getAsJsonArray("sessions") ?: return ScraperResult(cinema, emptyList(), emptyList())

            val movieMap = mutableMapOf<String, Movie>()
            sessionsArray.forEach { el ->
                val s = el.asJsonObject
                val movieObj = s.getAsJsonObject("movie") ?: return@forEach
                val movieId = "event_movie_${movieObj.get("id")?.asString ?: return@forEach}"
                val duration = movieObj.get("runtime")?.asInt ?: 120

                if (!movieMap.containsKey(movieId)) {
                    movieMap[movieId] = Movie(
                        id = movieId,
                        title = movieObj.get("title")?.asString ?: "",
                        synopsis = movieObj.get("synopsis")?.asString ?: "",
                        ratingCode = movieObj.get("rating")?.asString ?: "",
                        durationMinutes = duration,
                        genres = movieObj.getAsJsonArray("genres")?.joinToString(", ") { it.asString } ?: "",
                        posterUrl = movieObj.get("posterUrl")?.asString ?: ""
                    )
                }

                val startStr = s.get("startTime")?.asString ?: return@forEach
                val startMs = parseIso8601(startStr) ?: return@forEach
                val endStr = s.get("endTime")?.asString
                val endMs = if (endStr != null) parseIso8601(endStr) ?: (startMs + duration * 60_000L)
                            else startMs + duration * 60_000L

                sessions.add(
                    Session(
                        id = "event_session_${s.get("id")?.asString ?: UUID.randomUUID()}",
                        cinemaId = cinema.id,
                        movieId = movieId,
                        startTimeMs = startMs,
                        endTimeMs = endMs,
                        screenType = s.get("screenType")?.asString ?: "Standard",
                        bookingUrl = s.get("bookingUrl")?.asString ?: ""
                    )
                )
            }
            movies.addAll(movieMap.values)
        } catch (_: Exception) {}
        return ScraperResult(cinema, movies, sessions)
    }

    private fun fallbackHtmlScrape(cinema: Cinema, cinemaId: String, dateStr: String): ScraperResult {
        return ScraperResult(cinema, emptyList(), emptyList())
    }

    private fun parseIso8601(dateStr: String): Long? {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (fmt in formats) {
            try {
                return SimpleDateFormat(fmt, Locale.getDefault()).parse(dateStr)?.time
            } catch (_: Exception) {}
        }
        return null
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).pow2() +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).pow2()
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun Double.pow2() = this * this
}
