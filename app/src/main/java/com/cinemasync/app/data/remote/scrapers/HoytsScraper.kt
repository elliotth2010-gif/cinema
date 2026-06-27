package com.cinemasync.app.data.remote.scrapers

import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.CinemaChain
import com.cinemasync.app.data.model.Movie
import com.cinemasync.app.data.model.Session
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class HoytsScraper @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) : BaseCinemaScraper {

    private val baseUrl = "https://www.hoyts.com.au"

    override suspend fun findNearbyCinemas(lat: Double, lng: Double, radiusKm: Double): List<Cinema> {
        return try {
            val url = "$baseUrl/api/v3/cinema/list"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val json = gson.fromJson(body, JsonObject::class.java)
            val cinemaArray = json.getAsJsonArray("cinemas") ?: return emptyList()

            cinemaArray.mapNotNull { element ->
                val obj = element.asJsonObject
                val cinemaLat = obj.get("latitude")?.asDouble ?: return@mapNotNull null
                val cinemaLng = obj.get("longitude")?.asDouble ?: return@mapNotNull null
                val dist = haversineKm(lat, lng, cinemaLat, cinemaLng)
                if (dist <= radiusKm) {
                    Cinema(
                        id = "hoyts_${obj.get("id")?.asString ?: return@mapNotNull null}",
                        name = obj.get("name")?.asString ?: return@mapNotNull null,
                        chain = CinemaChain.HOYTS,
                        address = obj.get("address")?.asString ?: "",
                        suburb = obj.get("suburb")?.asString ?: "",
                        latitude = cinemaLat,
                        longitude = cinemaLng,
                        websiteUrl = "$baseUrl/cinemas/${obj.get("slug")?.asString ?: ""}",
                        distanceKm = dist
                    )
                } else null
            }.sortedBy { it.distanceKm }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchSessionsForDate(cinema: Cinema, dateMs: Long): ScraperResult {
        val cinemaId = cinema.id.removePrefix("hoyts_")
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(dateMs))

        return try {
            val url = "$baseUrl/api/v3/sessions/byCinema?cinemaId=$cinemaId&date=$dateStr"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return ScraperResult(cinema, emptyList(), emptyList())

            val body = response.body?.string() ?: return ScraperResult(cinema, emptyList(), emptyList())
            parseSessionsResponse(cinema, body, dateMs)
        } catch (e: Exception) {
            ScraperResult(cinema, emptyList(), emptyList())
        }
    }

    private fun parseSessionsResponse(cinema: Cinema, body: String, dateMs: Long): ScraperResult {
        val movies = mutableListOf<Movie>()
        val sessions = mutableListOf<Session>()

        try {
            val json = gson.fromJson(body, JsonObject::class.java)
            val moviesArray = json.getAsJsonArray("movies") ?: JsonArray()

            moviesArray.forEach { movieEl ->
                val m = movieEl.asJsonObject
                val movieId = "hoyts_movie_${m.get("id")?.asString ?: return@forEach}"
                val title = m.get("title")?.asString ?: return@forEach
                val duration = m.get("runtime")?.asInt ?: 120

                val movie = Movie(
                    id = movieId,
                    title = title,
                    synopsis = m.get("synopsis")?.asString ?: "",
                    ratingCode = m.get("rating")?.asString ?: "",
                    durationMinutes = duration,
                    genres = m.getAsJsonArray("genres")?.joinToString(", ") { it.asString } ?: "",
                    posterUrl = m.get("posterUrl")?.asString ?: ""
                )
                movies.add(movie)

                val sessionArray = m.getAsJsonArray("sessions") ?: return@forEach
                sessionArray.forEach { sessionEl ->
                    val s = sessionEl.asJsonObject
                    val sessionId = "hoyts_session_${s.get("id")?.asString ?: return@forEach}"
                    val startStr = s.get("startTime")?.asString ?: return@forEach
                    val startMs = parseIso8601(startStr) ?: return@forEach
                    val endMs = startMs + (duration * 60_000L)

                    sessions.add(
                        Session(
                            id = sessionId,
                            cinemaId = cinema.id,
                            movieId = movieId,
                            startTimeMs = startMs,
                            endTimeMs = endMs,
                            screenType = s.get("screenType")?.asString ?: "Standard",
                            bookingUrl = s.get("bookingUrl")?.asString
                                ?: "$baseUrl/booking?sessionId=${s.get("id")?.asString}"
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        return ScraperResult(cinema, movies, sessions)
    }

    private fun parseIso8601(dateStr: String): Long? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            sdf.parse(dateStr)?.time
        } catch (_: Exception) { null }
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
