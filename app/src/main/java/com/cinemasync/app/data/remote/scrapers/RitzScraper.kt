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

class RitzScraper @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) : BaseCinemaScraper {

    private companion object {
        const val BASE = "https://www.ritzcinemas.com.au"
        const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override suspend fun findNearbyCinemas(lat: Double, lng: Double, radiusKm: Double): List<Cinema> =
        ArthouseCinemaRegistry.venues
            .filter { it.chain == CinemaChain.RITZ }
            .mapNotNull { v ->
                val d = haversine(lat, lng, v.latitude, v.longitude)
                if (d <= radiusKm) ArthouseCinemaRegistry.toCinema(v, d) else null
            }.sortedBy { it.distanceKm }

    override suspend fun fetchSessionsForDate(cinema: Cinema, dateMs: Long): ScraperResult {
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dateMs))
        val au  = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(dateMs))
        val urls = listOf(
            "$BASE/now-showing?date=$iso",
            "$BASE/now-showing?date=$au",
            "$BASE/now-showing",
            "$BASE/sessions?date=$iso",
            BASE
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
                .header("Referer", BASE)
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Cache-Control", "max-age=0")
                .build()
        ).execute()
        return if (resp.isSuccessful) resp.body?.string() else null
    }

    private fun parseDoc(cinema: Cinema, doc: Document, html: String, dateMs: Long): ScraperResult {
        jsonLd(cinema, doc, dateMs).takeIf { it.sessions.isNotEmpty() }?.let { return it }
        nextData(cinema, doc, dateMs).takeIf { it.sessions.isNotEmpty() }?.let { return it }
        scriptJson(cinema, html, dateMs).takeIf { it.sessions.isNotEmpty() }?.let { return it }
        veeziHtml(cinema, doc, dateMs).takeIf { it.sessions.isNotEmpty() }?.let { return it }
        cmsHtml(cinema, doc, dateMs).takeIf { it.sessions.isNotEmpty() }?.let { return it }
        return timeScan(cinema, doc, dateMs)
    }

    // Strategy 1: JSON-LD ScreeningEvent schema
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
                    val startStr = obj.optStr("startDate") ?: return@forEach
                    val startMs  = parseIsoDateTime(startStr) ?: return@forEach
                    if (!sameDay(startMs, dateMs)) return@forEach
                    val bookUrl  = obj.optStr("url").orEmpty()
                    val duration = obj.optStr("duration")?.let { parseDurationISO(it) } ?: 120
                    val movieId  = movieId(cinema, title)
                    if (movies.none { it.id == movieId }) movies += Movie(movieId, title, "", "", duration, "")
                    val sid = "${cinema.id}_${movieId}_$startMs"
                    if (sessions.none { it.id == sid })
                        sessions += Session(sid, cinema.id, movieId, startMs, startMs + duration * 60_000L, "Standard", bookUrl.ifBlank { cinema.websiteUrl })
                }
            }
        }
        return ScraperResult(cinema, movies, sessions)
    }

    // Strategy 2: Next.js __NEXT_DATA__ embedded JSON
    private fun nextData(cinema: Cinema, doc: Document, dateMs: Long): ScraperResult {
        val tag = doc.getElementById("__NEXT_DATA__") ?: return ScraperResult(cinema, emptyList(), emptyList())
        return runCatching {
            val root = gson.fromJson(tag.data(), JsonObject::class.java)
            val movies   = mutableListOf<Movie>()
            val sessions = mutableListOf<Session>()
            walkJson(cinema, root, dateMs, movies, sessions)
            ScraperResult(cinema, movies, sessions)
        }.getOrElse { ScraperResult(cinema, emptyList(), emptyList()) }
    }

    // Strategy 3: Inline JS variables containing film/session arrays
    private fun scriptJson(cinema: Cinema, html: String, dateMs: Long): ScraperResult {
        val patterns = listOf(
            Regex("""(?:var|let|const|window\.)\w+\s*=\s*(\{[\s\S]{50,5000}?\});"""),
            Regex("""(?:var|let|const|window\.)\w+\s*=\s*(\[[\s\S]{20,5000}?\]);""")
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(html)) {
                val jsonStr = match.groupValues[1]
                if (("title" !in jsonStr && "name" !in jsonStr) ||
                    ("time" !in jsonStr && "session" !in jsonStr && "start" !in jsonStr)) continue
                val movies   = mutableListOf<Movie>()
                val sessions = mutableListOf<Session>()
                runCatching {
                    when {
                        jsonStr.startsWith("{") -> walkJson(cinema, gson.fromJson(jsonStr, JsonObject::class.java), dateMs, movies, sessions)
                        jsonStr.startsWith("[") -> {
                            val arr = gson.fromJson(jsonStr, JsonArray::class.java)
                            arr.forEach { el -> if (el.isJsonObject) walkJson(cinema, el.asJsonObject, dateMs, movies, sessions) }
                        }
                    }
                }
                if (sessions.isNotEmpty()) return ScraperResult(cinema, movies, sessions)
            }
        }
        return ScraperResult(cinema, emptyList(), emptyList())
    }

    // Strategy 4: Veezi CMS HTML (common in Australian/NZ arthouse cinemas)
    private fun veeziHtml(cinema: Cinema, doc: Document, dateMs: Long): ScraperResult {
        val movies   = mutableListOf<Movie>()
        val sessions = mutableListOf<Session>()
        val blocks = doc.select(
            ".filmListing, .film-listing, [class*=FilmListing], [class*=film-listing-item], " +
            ".veezi-film, [class*=veezi], .schedule-film"
        )
        blocks.forEach { block ->
            val title = block.select(".filmTitle, .film-title, [class*=FilmTitle], h2, h3").firstOrNull()?.text()?.trim().orEmpty()
            if (title.isBlank()) return@forEach
            val movieId  = movieId(cinema, title)
            val duration = parseDuration(block.text())
            val poster   = block.select("img").firstOrNull()?.absUrl("src").orEmpty()
            val synopsis = block.select(".synopsis, .filmSynopsis, .description").firstOrNull()?.text().orEmpty()
            val rating   = block.select(".classification, .rating, [class*=Classification]").firstOrNull()?.text().orEmpty()
            if (movies.none { it.id == movieId }) movies += Movie(movieId, title, synopsis, rating, duration, posterUrl = poster)
            block.select(".sessionTime, .session-time, a[href*=book], a[href*=ticket], [class*=SessionTime], [data-time]").forEach { el ->
                val timeText = el.attr("data-time").ifBlank { el.text() }.trim()
                val startMs  = parseTimeOnDate(timeText, dateMs) ?: return@forEach
                val bookUrl  = el.absUrl("href").orEmpty()
                val sid = "${cinema.id}_${movieId}_$startMs"
                if (sessions.none { it.id == sid })
                    sessions += Session(sid, cinema.id, movieId, startMs, startMs + maxOf(duration, 60) * 60_000L, "Standard", bookUrl.ifBlank { cinema.websiteUrl })
            }
        }
        return ScraperResult(cinema, movies, sessions)
    }

    // Strategy 5: Generic cinema CMS selectors with broad fallback
    private fun cmsHtml(cinema: Cinema, doc: Document, dateMs: Long): ScraperResult {
        val movies   = mutableListOf<Movie>()
        val sessions = mutableListOf<Session>()
        var blocks = doc.select(
            ".film, .movie, .now-showing-item, .whats-on-item, article[class*=film], article[class*=movie], " +
            "[class*=film-block], [class*=movie-block], .event-item, .screening"
        )
        if (blocks.isEmpty()) {
            // Broader: any article/card that has a booking/session link inside
            blocks = doc.select("article, .card, .item, .entry, li[class*=film], li[class*=movie]")
                .filter { it.select("a[href*=book], a[href*=ticket], a[href*=session]").isNotEmpty() }
        }
        blocks.forEach { block ->
            val title = block.select("h1, h2, h3, h4, .title, [class*=title], [class*=name]")
                .firstOrNull { it.text().length in 2..100 }?.text()?.trim().orEmpty()
            if (title.isBlank()) return@forEach
            val movieId  = movieId(cinema, title)
            val duration = parseDuration(block.text())
            val poster   = block.select("img[src], img[data-src]").firstOrNull()?.let {
                it.absUrl("src").ifBlank { it.absUrl("data-src") }
            }.orEmpty()
            if (movies.none { it.id == movieId }) movies += Movie(movieId, title, "", "", duration, posterUrl = poster)
            block.select("a, button, span[class*=time], div[class*=time]").forEach { el ->
                val startMs = parseTimeOnDate(el.text().trim(), dateMs) ?: return@forEach
                val bookUrl = el.absUrl("href").orEmpty()
                val sid = "${cinema.id}_${movieId}_$startMs"
                if (sessions.none { it.id == sid })
                    sessions += Session(sid, cinema.id, movieId, startMs, startMs + maxOf(duration, 60) * 60_000L, "Standard", bookUrl.ifBlank { cinema.websiteUrl })
            }
        }
        return ScraperResult(cinema, movies, sessions)
    }

    // Strategy 6: Scan ALL text for HH:MM patterns, walk DOM to find nearby title
    private fun timeScan(cinema: Cinema, doc: Document, dateMs: Long): ScraperResult {
        val movies   = mutableListOf<Movie>()
        val sessions = mutableListOf<Session>()
        val timeRe   = Regex("""(\d{1,2}:\d{2}\s*[AaPp][Mm])""")
        doc.body()?.allElements?.forEach { el ->
            if (el.children().size > 8) return@forEach
            val text = el.ownText().trim()
            if (!timeRe.containsMatchIn(text)) return@forEach
            // Allowed session hour range: 8am–midnight
            val hour = text.substringBefore(":").trimStart().toIntOrNull() ?: return@forEach
            if (hour !in 8..23 && !text.contains("pm", ignoreCase = true)) return@forEach

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
            cursor!!.select("h1, h2, h3, h4, h5, .title, [class*=film-title], [class*=movie-title]")
                .firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            cursor = cursor!!.parent()
        }
        return el.parent()?.previousElementSiblings()
            ?.firstOrNull { it.tagName() in listOf("h1","h2","h3","h4","h5") }
            ?.text()?.trim().orEmpty()
    }

    // ── JSON tree walker ─────────────────────────────────────────────────────

    private fun walkJson(cinema: Cinema, obj: JsonObject, dateMs: Long, movies: MutableList<Movie>, sessions: MutableList<Session>) {
        val title    = obj.optStr("title") ?: obj.optStr("name") ?: obj.optStr("movieTitle") ?: obj.optStr("filmTitle")
        val startStr = obj.optStr("startTime") ?: obj.optStr("startDate") ?: obj.optStr("time") ?: obj.optStr("sessionTime") ?: obj.optStr("datetime")
        if (title != null && startStr != null) {
            val startMs = parseIsoDateTime(startStr) ?: parseTimeOnDate(startStr, dateMs)
            if (startMs != null && sameDay(startMs, dateMs)) {
                val movieId  = movieId(cinema, title)
                val duration = obj.optInt("duration") ?: obj.optInt("durationMinutes") ?: 120
                val bookUrl  = obj.optStr("bookingUrl") ?: obj.optStr("url") ?: obj.optStr("link").orEmpty()
                if (movies.none { it.id == movieId })
                    movies += Movie(movieId, title, obj.optStr("synopsis").orEmpty(), obj.optStr("rating").orEmpty(), duration, posterUrl = obj.optStr("poster").orEmpty())
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private fun parseDurationISO(iso: String): Int {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""").find(iso) ?: return 120
        return (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
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
