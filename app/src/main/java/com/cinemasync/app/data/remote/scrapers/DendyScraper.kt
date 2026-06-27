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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class DendyScraper @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) : BaseCinemaScraper {

    private companion object {
        const val BASE = "https://www.dendy.com.au"
        const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override suspend fun findNearbyCinemas(lat: Double, lng: Double, radiusKm: Double): List<Cinema> =
        ArthouseCinemaRegistry.venues
            .filter { it.chain == CinemaChain.DENDY }
            .mapNotNull { v ->
                val d = haversine(lat, lng, v.latitude, v.longitude)
                if (d <= radiusKm) ArthouseCinemaRegistry.toCinema(v, d) else null
            }.sortedBy { it.distanceKm }

    override suspend fun fetchSessionsForDate(cinema: Cinema, dateMs: Long): ScraperResult {
        val venue = ArthouseCinemaRegistry.venueForCinema(cinema)
        // Derive the cinema slug (e.g. "newtown", "opera-quays") from the venue URL.
        val slug = venue?.websiteUrl?.substringAfterLast("/cinemas/")?.substringBefore("/")?.substringBefore("?")
            ?: "newtown"
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dateMs))
        val au  = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(dateMs))
        val urls = listOf(
            "$BASE/cinemas/$slug/sessions?date=$iso",
            "$BASE/cinemas/$slug/sessions?date=$au",
            "$BASE/cinemas/$slug/sessions",
            "$BASE/cinemas/$slug?date=$iso",
            "$BASE/cinemas/$slug",
            "$BASE/sessions?cinema=$slug&date=$iso"
        )
        for (url in urls) {
            val r = runCatching {
                val html = fetchHtml(url) ?: return@runCatching null
                val doc  = Jsoup.parse(html, url)
                parseDoc(cinema, doc, html, dateMs).takeIf { it.sessions.isNotEmpty() }
            }.getOrNull()
            if (r != null) return r
        }
        return ScraperResult(cinema, emptyList(), emptyList())
    }

    private fun fetchHtml(url: String): String? {
        val resp = client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .header("Accept-Language", "en-AU,en;q=0.9")
                .header("Referer", "$BASE/cinemas")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Cache-Control", "max-age=0")
                .build()
        ).execute()
        return if (resp.isSuccessful) resp.body?.string() else null
    }

    private fun parseDoc(cinema: Cinema, doc: Document, html: String, dateMs: Long): ScraperResult {
        nextData(cinema, doc, dateMs).takeIf { it.sessions.isNotEmpty() }?.let { return it }
        jsonLd(cinema, doc, dateMs).takeIf { it.sessions.isNotEmpty() }?.let { return it }
        scriptJson(cinema, html, dateMs).takeIf { it.sessions.isNotEmpty() }?.let { return it }
        sessionHtml(cinema, doc, dateMs).takeIf { it.sessions.isNotEmpty() }?.let { return it }
        return timeScan(cinema, doc, dateMs)
    }

    // Strategy 1: Next.js / Nuxt embedded JSON state (Dendy is a JS SPA)
    private fun nextData(cinema: Cinema, doc: Document, dateMs: Long): ScraperResult {
        val tag = doc.getElementById("__NEXT_DATA__")
            ?: doc.select("script[type='application/json']").firstOrNull { it.data().contains("\"sessions\"") || it.data().contains("\"films\"") }
            ?: return ScraperResult(cinema, emptyList(), emptyList())
        return runCatching {
            val root = gson.fromJson(tag.data(), JsonObject::class.java)
            val movies   = mutableListOf<Movie>()
            val sessions = mutableListOf<Session>()
            walkJson(cinema, root, dateMs, movies, sessions)
            ScraperResult(cinema, movies, sessions)
        }.getOrElse { ScraperResult(cinema, emptyList(), emptyList()) }
    }

    // Strategy 2: JSON-LD ScreeningEvent schema
    private fun jsonLd(cinema: Cinema, doc: Document, dateMs: Long): ScraperResult {
        val movies   = mutableListOf<Movie>()
        val sessions = mutableListOf<Session>()
        doc.select("script[type='application/ld+json']").forEach { tag ->
            runCatching {
                val text = tag.data().trim()
                val items = when {
                    text.startsWith("[") -> gson.fromJson(text, JsonArray::class.java).mapNotNull { it.asJsonObject }
                    text.startsWith("{") -> listOf(gson.fromJson(text, JsonObject::class.java))
                    else -> emptyList()
                }
                items.forEach { obj ->
                    val type = obj.optStr("@type") ?: return@forEach
                    if (type != "ScreeningEvent" && type != "Event") return@forEach
                    val title    = obj.getAsJsonObject("workPresented")?.optStr("name") ?: obj.optStr("name") ?: return@forEach
                    val startMs  = obj.optStr("startDate")?.let { parseIsoDateTime(it) } ?: return@forEach
                    if (!sameDay(startMs, dateMs)) return@forEach
                    val bookUrl  = obj.optStr("url").orEmpty()
                    val movieId  = movieId(cinema, title)
                    if (movies.none { it.id == movieId }) movies += Movie(movieId, title, "", "", 120, "")
                    val sid = "${cinema.id}_${movieId}_$startMs"
                    if (sessions.none { it.id == sid })
                        sessions += Session(sid, cinema.id, movieId, startMs, startMs + 120 * 60_000L, "Standard", bookUrl.ifBlank { cinema.websiteUrl })
                }
            }
        }
        return ScraperResult(cinema, movies, sessions)
    }

    // Strategy 3: Inline JS state assignments (window.__INITIAL_STATE__, etc.)
    private fun scriptJson(cinema: Cinema, html: String, dateMs: Long): ScraperResult {
        val patterns = listOf(
            Regex("""window\.__INITIAL_STATE__\s*=\s*(\{[\s\S]*?\});"""),
            Regex("""window\.__NUXT__\s*=\s*(\{[\s\S]*?\});"""),
            Regex("""__APOLLO_STATE__\s*=\s*(\{[\s\S]*?\});"""),
            Regex("""(?:var|let|const)\s+\w+\s*=\s*(\{[\s\S]{50,8000}?\});""")
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(html)) {
                val jsonStr = match.groupValues[1]
                if ("session" !in jsonStr && "film" !in jsonStr && "showtime" !in jsonStr) continue
                val movies   = mutableListOf<Movie>()
                val sessions = mutableListOf<Session>()
                runCatching { walkJson(cinema, gson.fromJson(jsonStr, JsonObject::class.java), dateMs, movies, sessions) }
                if (sessions.isNotEmpty()) return ScraperResult(cinema, movies, sessions)
            }
        }
        return ScraperResult(cinema, emptyList(), emptyList())
    }

    // Strategy 4: Server-rendered session listing HTML
    private fun sessionHtml(cinema: Cinema, doc: Document, dateMs: Long): ScraperResult {
        val movies   = mutableListOf<Movie>()
        val sessions = mutableListOf<Session>()
        var blocks = doc.select(
            "[class*=MovieCard], [class*=movie-card], [class*=FilmCard], [class*=film-card], " +
            "[class*=session-group], [class*=SessionGroup], .movie, .film, article[class*=film]"
        )
        if (blocks.isEmpty()) {
            blocks = doc.select("article, .card, [class*=card]")
                .filter { it.select("a[href*=session], a[href*=book], a[href*=ticket], [class*=time]").isNotEmpty() }
        }
        blocks.forEach { block ->
            val title = block.select("h1, h2, h3, h4, [class*=title], [class*=Title], [class*=name]")
                .firstOrNull { it.text().length in 2..100 }?.text()?.trim().orEmpty()
            if (title.isBlank()) return@forEach
            val movieId  = movieId(cinema, title)
            val duration = parseDuration(block.text())
            val poster   = block.select("img[src], img[data-src]").firstOrNull()?.let {
                it.absUrl("src").ifBlank { it.absUrl("data-src") }
            }.orEmpty()
            val rating   = block.select("[class*=classification], [class*=rating], .rating").firstOrNull()?.text().orEmpty()
            if (movies.none { it.id == movieId }) movies += Movie(movieId, title, "", rating, duration, posterUrl = poster)
            block.select("a[href*=book], a[href*=ticket], a[href*=session], [class*=session-time], [class*=SessionTime], [data-time], time, button").forEach { el ->
                val timeText = el.attr("data-time").ifBlank { el.attr("datetime") }.ifBlank { el.text() }.trim()
                val startMs  = parseIsoDateTime(timeText) ?: parseTimeOnDate(timeText, dateMs) ?: return@forEach
                if (!sameDay(startMs, dateMs)) return@forEach
                val bookUrl  = el.absUrl("href").orEmpty()
                val sid = "${cinema.id}_${movieId}_$startMs"
                if (sessions.none { it.id == sid })
                    sessions += Session(sid, cinema.id, movieId, startMs, startMs + maxOf(duration, 60) * 60_000L, "Standard", bookUrl.ifBlank { cinema.websiteUrl })
            }
        }
        return ScraperResult(cinema, movies, sessions)
    }

    // Strategy 5: Scan all text for HH:MM patterns and find nearest title
    private fun timeScan(cinema: Cinema, doc: Document, dateMs: Long): ScraperResult {
        val movies   = mutableListOf<Movie>()
        val sessions = mutableListOf<Session>()
        val timeRe   = Regex("""(\d{1,2}:\d{2}\s*[AaPp][Mm])""")
        doc.body()?.allElements?.forEach { el ->
            if (el.children().size > 8) return@forEach
            val text = el.ownText().trim()
            if (!timeRe.containsMatchIn(text)) return@forEach
            val title = nearestTitle(el)
            if (title.isBlank() || title.length > 100) return@forEach
            val movieId = movieId(cinema, title)
            if (movies.none { it.id == movieId }) movies += Movie(movieId, title, "", "", 0, "")
            timeRe.findAll(text).forEach { m ->
                val startMs = parseTimeOnDate(m.value, dateMs) ?: return@forEach
                val bookUrl = el.absUrl("href").ifBlank { el.select("a").firstOrNull()?.absUrl("href").orEmpty() }
                val sid = "${cinema.id}_${movieId}_$startMs"
                if (sessions.none { it.id == sid })
                    sessions += Session(sid, cinema.id, movieId, startMs, startMs + 120 * 60_000L, "Standard", bookUrl.ifBlank { cinema.websiteUrl })
            }
        }
        return ScraperResult(cinema, movies, sessions)
    }

    private fun nearestTitle(el: Element): String {
        var cursor: Element? = el.parent()
        repeat(6) {
            cursor ?: return ""
            cursor!!.select("h1, h2, h3, h4, h5, [class*=title], [class*=Title]")
                .firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            cursor = cursor!!.parent()
        }
        return ""
    }

    private fun walkJson(cinema: Cinema, obj: JsonObject, dateMs: Long, movies: MutableList<Movie>, sessions: MutableList<Session>) {
        val title    = obj.optStr("title") ?: obj.optStr("name") ?: obj.optStr("movieTitle") ?: obj.optStr("filmTitle") ?: obj.optStr("filmName")
        val startStr = obj.optStr("startTime") ?: obj.optStr("startDate") ?: obj.optStr("time") ?: obj.optStr("sessionTime") ?: obj.optStr("showtime") ?: obj.optStr("datetime")
        if (title != null && startStr != null) {
            val startMs = parseIsoDateTime(startStr) ?: parseTimeOnDate(startStr, dateMs)
            if (startMs != null && sameDay(startMs, dateMs)) {
                val movieId  = movieId(cinema, title)
                val duration = obj.optInt("duration") ?: obj.optInt("runtime") ?: obj.optInt("durationMinutes") ?: 120
                val bookUrl  = obj.optStr("bookingUrl") ?: obj.optStr("url") ?: obj.optStr("link").orEmpty()
                if (movies.none { it.id == movieId })
                    movies += Movie(movieId, title, obj.optStr("synopsis").orEmpty(), obj.optStr("rating").orEmpty(), duration, posterUrl = (obj.optStr("poster") ?: obj.optStr("image").orEmpty()))
                val sid = "${cinema.id}_${movieId}_$startMs"
                if (sessions.none { it.id == sid })
                    sessions += Session(sid, cinema.id, movieId, startMs, startMs + duration * 60_000L, "Standard", bookUrl.ifBlank { cinema.websiteUrl })
            }
        }
        obj.entrySet().forEach { (_, v) ->
            when {
                v.isJsonObject -> walkJson(cinema, v.asJsonObject, dateMs, movies, sessions)
                v.isJsonArray  -> v.asJsonArray.forEach { el -> if (el.isJsonObject) walkJson(cinema, el.asJsonObject, dateMs, movies, sessions) }
            }
        }
    }

    private fun movieId(cinema: Cinema, title: String) =
        "${cinema.chain.name.lowercase()}_movie_${title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"

    private fun JsonObject.optStr(key: String): String? =
        runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun JsonObject.optInt(key: String): Int? =
        runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()

    private fun sameDay(msA: Long, msB: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = msA }
        val b = Calendar.getInstance().apply { timeInMillis = msB }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    private fun parseIsoDateTime(s: String): Long? {
        if (!s.contains("-") && !s.contains("/")) return null
        listOf("yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm").forEach { fmt ->
            runCatching { return SimpleDateFormat(fmt, Locale.US).parse(s)?.time }.getOrNull()
        }
        return null
    }

    private fun parseTimeOnDate(text: String, dateMs: Long): Long? {
        val m = Regex("""(\d{1,2}):(\d{2})\s*([AaPp][Mm])?""").find(text) ?: return null
        val h   = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val ap  = m.groupValues[3].uppercase()
        var hour = h
        if (ap == "PM" && hour < 12) hour += 12
        if (ap == "AM" && hour == 12) hour = 0
        if (hour !in 0..23 || min !in 0..59) return null
        return Calendar.getInstance().apply {
            timeInMillis = dateMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun parseDuration(text: String): Int {
        Regex("""(\d{2,3})\s*min""").find(text)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        Regex("""(\d)h\s*(\d{1,2})m""").find(text)?.let {
            return (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0)
        }
        return 0
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinLat = Math.sin(dLat / 2)
        val sinLon = Math.sin(dLon / 2)
        val a = sinLat * sinLat + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLon * sinLon
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
