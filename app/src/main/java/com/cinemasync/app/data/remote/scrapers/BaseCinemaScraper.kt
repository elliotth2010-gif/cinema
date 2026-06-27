package com.cinemasync.app.data.remote.scrapers

import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.Movie
import com.cinemasync.app.data.model.Session

data class ScraperResult(
    val cinema: Cinema,
    val movies: List<Movie>,
    val sessions: List<Session>
)

interface BaseCinemaScraper {
    suspend fun fetchSessionsForDate(cinema: Cinema, dateMs: Long): ScraperResult
    suspend fun findNearbyCinemas(lat: Double, lng: Double, radiusKm: Double): List<Cinema>
}
