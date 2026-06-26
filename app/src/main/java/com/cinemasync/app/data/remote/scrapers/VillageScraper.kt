package com.cinemasync.app.data.remote.scrapers

import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.CinemaChain
import com.cinemasync.app.data.model.Movie
import com.cinemasync.app.data.model.Session
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class VillageScraper @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) : BaseCinemaScraper {

    private val baseUrl = "https://www.villagecinemas.com.au"

    override suspend fun findNearbyCinemas(lat: Double, lng: Double, radiusKm: Double): List<Cinema> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/cinemas?lat=$lat&lng=$lng")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            parseCinemaListJson(body, lat, lng, radiusKm)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCinemaListJson(body: String, userLat: Double, userLng: Double, radiusKm: Double): List<Cinema> {
        val cinemas = mutableListOf<Cinema>()
        try {
            val arr = gson.fromJson(body, com.google.gson.JsonArray::class.java)
            arr.forEach { el ->
                val obj = el.asJsonObject
                val lat = obj.get("latitude")?.asDouble ?: return@forEach
                val lng = obj.get("longitude")?.asDouble ?: return@forEach
                val dist = haversineKm(userLat, userLng, lat, lng)
                if (dist <= radiusKm) {
                    cinemas.add(
                        Cinema(
                            id = "village_${obj.get("id")?.asString ?: return@forEach}",
                            name = obj.get("name")?.asString ?: return@forEach,
                            chain = CinemaChain.VILLAGE,
                            address = obj.get("address")?.asString ?: "",
                            suburb = obj.get("suburb")?.asString ?: "",
                            latitude = lat,
                            longitude = lng,
                            websiteUrl = "$baseUrl/cinemas/${obj.get("slug")?.asString ?: ""}",
                            distanceKm = dist
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return cinemas.sortedBy { it.distanceKm }
    }

    override suspend fun fetchSessionsForDate(cinema: Cinema, dateMs: Long): ScraperResult {
        val cinemaId = cinema.id.removePrefix("village_")
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(dateMs))

        return try {
            val url = "$baseUrl/sessions?cinemaId=$cinemaId&date=$dateStr"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .addHeader("Accept", "application/json, text/html")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return ScraperResult(cinema, emptyList(), emptyList())

            val bodyStr = response.body?.string() ?: return ScraperResult(cinema, emptyList(), emptyList())
            val contentType = response.header("content-type") ?: ""

            if (contentType.contains("json")) {
                parseJsonResponse(cinema, bodyStr)
            } else {
                parseHtmlResponse(cinema, bodyStr)
            }
        } catch (_: Exception) {
            ScraperResult(cinema, emptyList(), emptyList())
        }
    }

    private fun parseJsonResponse(cinema: Cinema, body: String): ScraperResult {
        val movies = mutableListOf<Movie>()
        val sessions = mutableListOf<Session>()
        try {
            val json = gson.fromJson(body, JsonObject::class.java)
            val moviesArr = json.getAsJsonArray("movies") ?: return ScraperResult(cinema, emptyList(), emptyList())
            moviesArr.forEach { el ->
                val m = el.asJsonObject
                val movieId = "village_movie_${m.get("id")?.asString ?: return@forEach}"
                val duration = m.get("runtime")?.asInt ?: 120
                val movie = Movie(
                    id = movieId,
                    title = m.get("title")?.asString ?: return@forEach,
                    synopsis = m.get("synopsis")?.asString ?: "",
                    ratingCode = m.get("rating")?.asString ?: "",
                    durationMinutes = duration,
                    posterUrl = m.get("posterUrl")?.asString ?: ""
                )
                movies.add(movie)

                m.getAsJsonArray("sessions")?.forEach { sEl ->
                    val s = sEl.asJsonObject
                    val startMs = parseIso8601(s.get("startTime")?.asString ?: return@forEach) ?: return@forEach
                    sessions.add(
                        Session(
                            id = "village_session_${s.get("id")?.asString ?: UUID.randomUUID()}",
                            cinemaId = cinema.id,
                            movieId = movieId,
                            startTimeMs = startMs,
                            endTimeMs = startMs + duration * 60_000L,
                            screenType = s.get("screenType")?.asString ?: "Standard",
                            bookingUrl = s.get("bookingUrl")?.asString ?: ""
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return ScraperResult(cinema, movies, sessions)
    }

    private fun parseHtmlResponse(cinema: Cinema, html: String): ScraperResult {
        val movies = mutableListOf<Movie>()
        val sessions = mutableListOf<Session>()
        try {
            val doc = Jsoup.parse(html)
            doc.select(".movie-item, [data-movie]").forEach { movieEl ->
                val title = movieEl.select(".movie-title, h2, h3").firstOrNull()?.text() ?: return@forEach
                val movieId = "village_movie_${title.lowercase().replace(" ", "_")}"
                val synopsis = movieEl.select(".synopsis, .movie-synopsis").text()
                val rating = movieEl.select(".rating, .classification").text()

                val movie = Movie(
                    id = movieId,
                    title = title,
                    synopsis = synopsis,
                    ratingCode = rating,
                    posterUrl = movieEl.select("img").attr("src")
                )
                movies.add(movie)

                movieEl.select(".session-time, .showtime, [data-session-time]").forEach { timeEl ->
                    val timeText = timeEl.text().trim()
                    val startMs = parseTimeText(timeText) ?: return@forEach
                    sessions.add(
                        Session(
                            id = "village_session_${movieId}_$startMs",
                            cinemaId = cinema.id,
                            movieId = movieId,
                            startTimeMs = startMs,
                            endTimeMs = startMs + 120 * 60_000L,
                            screenType = timeEl.attr("data-screen-type").ifEmpty { "Standard" },
                            bookingUrl = timeEl.attr("href").ifEmpty {
                                timeEl.select("a").attr("href")
                            }
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return ScraperResult(cinema, movies, sessions)
    }

    private fun parseTimeText(text: String): Long? {
        val today = Calendar.getInstance()
        val formats = listOf("h:mm a", "HH:mm", "h:mma")
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.getDefault())
                val time = sdf.parse(text) ?: continue
                val cal = Calendar.getInstance()
                val timeCal = Calendar.getInstance().apply { this.time = time }
                cal.set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY))
                cal.set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE))
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseIso8601(dateStr: String): Long? {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(dateStr)?.time
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
