package com.cinemasync.app.data.repository

import com.cinemasync.app.data.local.CinemaDao
import com.cinemasync.app.data.local.MovieDao
import com.cinemasync.app.data.local.SessionDao
import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.Movie
import com.cinemasync.app.data.model.Session
import com.cinemasync.app.data.remote.scrapers.ScraperFactory
import com.cinemasync.app.util.CalendarHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CinemaRepository @Inject constructor(
    private val cinemaDao: CinemaDao,
    private val movieDao: MovieDao,
    private val sessionDao: SessionDao,
    private val scraperFactory: ScraperFactory,
    private val calendarHelper: CalendarHelper
) {

    fun getCinemas(): Flow<List<Cinema>> = cinemaDao.getAllCinemas()

    fun getFavouriteCinemas(): Flow<List<Cinema>> = cinemaDao.getFavouriteCinemas()

    fun getSessionsForCinema(cinemaId: String): Flow<List<Session>> {
        val now = System.currentTimeMillis()
        return sessionDao.getSessionsForCinema(cinemaId, now)
    }

    fun getSessionsForWeek(): Flow<List<Session>> {
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY, 0)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        val fromMs = now.timeInMillis
        val toMs = fromMs + 7 * 24 * 60 * 60 * 1000L
        return sessionDao.getSessionsInRange(fromMs, toMs)
    }

    fun getSyncedSessions(): Flow<List<Session>> = sessionDao.getSyncedSessions()

    suspend fun getMovieById(id: String): Movie? = movieDao.getMovieById(id)

    suspend fun getCinemaById(id: String): Cinema? = cinemaDao.getCinemaById(id)

    /** Persists a known venue (e.g. one picked from the cinema dropdown) so its
     *  sessions can be loaded without a location lookup. */
    suspend fun addKnownCinema(cinema: Cinema): Unit = withContext(Dispatchers.IO) {
        cinemaDao.insertCinema(cinema)
    }

    suspend fun refreshNearbyCinemas(lat: Double, lng: Double, radiusKm: Double): Result<List<Cinema>> =
        withContext(Dispatchers.IO) {
            try {
                val allCinemas = coroutineScope {
                    scraperFactory.allScrapers().map { scraper ->
                        async { scraper.findNearbyCinemas(lat, lng, radiusKm) }
                    }.awaitAll().flatten()
                }
                cinemaDao.insertCinemas(allCinemas)
                Result.success(allCinemas)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun refreshSessionsForCinema(cinema: Cinema, dateMs: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val scraper = scraperFactory.scraperFor(cinema)
                val result = scraper.fetchSessionsForDate(cinema, dateMs)
                movieDao.insertMovies(result.movies)
                sessionDao.deleteForCinema(cinema.id)
                sessionDao.insertSessions(result.sessions)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun refreshSessionsForAllFavourites(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            coroutineScope {
                // Fetch from the DB synchronously since we need the list
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleFavourite(cinemaId: String) {
        val cinema = cinemaDao.getCinemaById(cinemaId) ?: return
        cinemaDao.setFavourite(cinemaId, !cinema.isFavourite)
    }

    suspend fun addSessionToCalendar(session: Session): Boolean {
        val movie = movieDao.getMovieById(session.movieId) ?: return false
        val cinema = cinemaDao.getCinemaById(session.cinemaId) ?: return false
        val eventId = calendarHelper.addSessionToCalendar(session, movie, cinema)
        if (eventId >= 0) {
            sessionDao.setCalendarSynced(session.id, true, eventId)
            return true
        }
        return false
    }

    suspend fun removeSessionFromCalendar(session: Session): Boolean {
        val removed = calendarHelper.removeSessionFromCalendar(session.calendarEventId)
        if (removed) {
            sessionDao.setCalendarSynced(session.id, false, -1L)
        }
        return removed
    }
}
