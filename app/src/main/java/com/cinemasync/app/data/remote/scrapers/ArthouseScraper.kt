package com.cinemasync.app.data.remote.scrapers

import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.Movie
import com.cinemasync.app.data.model.Session
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Best-effort scraper for arthouse / independent cinemas listed in the
 * [ArthouseCinemaRegistry]. Nearby resolution is done from the curated registry
 * (these venues have no "find nearby" endpoint); session times are pulled by
 * parsing each venue's public session page with a set of common HTML patterns.
 */
class ArthouseScraper @Inject constructor(
    private val client: OkHttpClient
) : BaseCinemaScraper {

    override suspend fun findNearbyCinemas(lat: Double, lng: Double, radiusKm: Double): List<Cinema> {
        return ArthouseCinemaRegistry.venues.mapNotNull { venue ->
            val dist = haversineKm(lat, lng, venue.latitude, venue.longitude)
            if (dist <= radiusKm) ArthouseCinemaRegistry.toCinema(venue, dist) else null
        }.sortedBy { it.distanceKm }
    }

    override suspend fun fetchSessionsForDate(cinema: Cinema, dateMs: Long): ScraperResult {
        val venue = ArthouseCinemaRegistry.venueForCinema(cinema)
            ?: return ScraperResult(cinema, emptyList(), emptyList())

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(dateMs))
        val url = venue.sessionsUrlTemplate.replace("{date}", dateStr)

        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .addHeader("Accept", "text/html,application/xhtml+xml")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return ScraperResult(cinema, emptyList(), emptyList())

            val html = response.body?.string() ?: return ScraperResult(cinema, emptyList(), emptyList())
            parseHtml(cinema, html, dateMs)
        } catch (e: Exception) {
            ScraperResult(cinema, emptyList(), emptyList())
        }
    }

    private fun parseHtml(cinema: Cinema, html: String, dateMs: Long): ScraperResult {
        val movies = mutableListOf<Movie>()
        val sessions = mutableListOf<Session>()

        try {
            val doc = Jsoup.parse(html)

            // Common containers used across indie cinema CMS templates.
            val movieBlocks = doc.select(
                ".film, .movie, .session-movie, [class*=film-item], [class*=movie-item], article[class*=film]"
            )

            movieBlocks.forEach { block ->
                val title = block.select(
                    ".film-title, .movie-title, h1, h2, h3, [class*=title]"
                ).firstOrNull()?.text()?.trim().orEmpty()
                if (title.isBlank()) return@forEach

                val movieId = "${cinema.chain.name.lowercase()}_movie_${slug(title)}"
                val rating = block.select(".rating, .classification, [class*=rating]").firstOrNull()?.text()?.trim().orEmpty()
                val synopsis = block.select(".synopsis, .description, [class*=synopsis]").firstOrNull()?.text()?.trim().orEmpty()
                val duration = parseDuration(block.text())
                val poster = block.select("img").firstOrNull()?.let {
                    it.attr("src").ifBlank { it.attr("data-src") }
                }.orEmpty()

                if (movies.none { it.id == movieId }) {
                    movies.add(
                        Movie(
                            id = movieId,
                            title = title,
                            synopsis = synopsis,
                            ratingCode = rating,
                            durationMinutes = duration,
                            posterUrl = poster
                        )
                    )
                }

                val timeNodes = block.select(
                    ".session-time, .showtime, .session, [class*=session-time], [data-session-time], a[href*=session], a[href*=book]"
                )
                timeNodes.forEach { node ->
                    val timeText = node.attr("data-session-time").ifBlank { node.text() }.trim()
                    val startMs = parseTimeOnDate(timeText, dateMs) ?: return@forEach
                    val bookingUrl = node.absUrl("href").ifBlank {
                        node.select("a").firstOrNull()?.absUrl("href").orEmpty()
                    }
                    val screenType = node.attr("data-screen-type")
                        .ifBlank { node.select("[class*=format]").firstOrNull()?.text().orEmpty() }
                        .ifBlank { "Standard" }

                    val sessionId = "${cinema.id}_${movieId}_$startMs"
                    if (sessions.none { it.id == sessionId }) {
                        sessions.add(
                            Session(
                                id = sessionId,
                                cinemaId = cinema.id,
                                movieId = movieId,
                                startTimeMs = startMs,
                                endTimeMs = startMs + (if (duration > 0) duration else 120) * 60_000L,
                                screenType = screenType,
                                bookingUrl = bookingUrl.ifBlank { cinema.websiteUrl }
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        return ScraperResult(cinema, movies, sessions)
    }

    private fun parseDuration(text: String): Int {
        // Matches "120 min", "2h 5m", "125 mins"
        Regex("(\\d{2,3})\\s*min").find(text)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        Regex("(\\d)h\\s*(\\d{1,2})m").find(text)?.let {
            val h = it.groupValues[1].toIntOrNull() ?: 0
            val m = it.groupValues[2].toIntOrNull() ?: 0
            return h * 60 + m
        }
        return 0
    }

    private fun parseTimeOnDate(text: String, dateMs: Long): Long? {
        val match = Regex("(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?").find(text) ?: return null
        val hourRaw = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val meridiem = match.groupValues[3].uppercase()

        var hour = hourRaw
        if (meridiem == "PM" && hour < 12) hour += 12
        if (meridiem == "AM" && hour == 12) hour = 0

        if (hour !in 0..23 || minute !in 0..59) return null

        val cal = Calendar.getInstance().apply {
            timeInMillis = dateMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
