package com.cinemasync.app.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.CinemaChain
import com.cinemasync.app.data.model.Movie
import com.cinemasync.app.data.model.Session
import com.cinemasync.app.data.remote.scrapers.ArthouseCinemaRegistry
import com.cinemasync.app.data.repository.CinemaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class SessionDisplayItem(
    val session: Session,
    val movie: Movie,
    val cinema: Cinema
)

sealed class SessionsUiState {
    object Loading : SessionsUiState()
    data class Success(val items: List<SessionDisplayItem>, val selectedDate: Long) : SessionsUiState()
    data class Error(val message: String) : SessionsUiState()
}

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: CinemaRepository
) : ViewModel() {

    private companion object {
        /** Chains with dedicated scrapers that render results in the list (no WebView). */
        val SCRAPED_CHAINS = setOf(CinemaChain.RITZ, CinemaChain.DENDY)
    }

    private val _uiState = MutableStateFlow<SessionsUiState>(SessionsUiState.Loading)
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val _cinemaWebsiteUrl = MutableStateFlow("")
    val cinemaWebsiteUrl: StateFlow<String> = _cinemaWebsiteUrl.asStateFlow()

    private val _webViewUrl = MutableStateFlow<String?>(null)
    /** Non-null when the cinema is an arthouse venue; the Fragment should show a WebView. */
    val webViewUrl: StateFlow<String?> = _webViewUrl.asStateFlow()

    private var currentCinemaId: String? = null
    private var selectedDateMs: Long = todayMs()
    private var arthouseVenue: com.cinemasync.app.data.remote.scrapers.ArthouseVenue? = null

    fun loadSessions(cinemaId: String) {
        currentCinemaId = cinemaId
        viewModelScope.launch {
            val cinema = repository.getCinemaById(cinemaId) ?: return@launch
            _cinemaWebsiteUrl.value = cinema.websiteUrl
            val venue = ArthouseCinemaRegistry.venueForCinema(cinema)
            // Ritz and Dendy have dedicated scrapers — show their results in the
            // list. Only venues without a working scraper fall back to the WebView.
            arthouseVenue = if (cinema.chain in SCRAPED_CHAINS) null else venue
            if (arthouseVenue != null) {
                _webViewUrl.value = urlForDate(arthouseVenue!!, selectedDateMs)
            }
        }
        viewModelScope.launch {
            repository.getSessionsForCinema(cinemaId).collect { sessions ->
                val items = sessions.mapNotNull { session ->
                    val movie = repository.getMovieById(session.movieId) ?: return@mapNotNull null
                    val cinema = repository.getCinemaById(session.cinemaId) ?: return@mapNotNull null
                    SessionDisplayItem(session, movie, cinema)
                }
                _uiState.value = SessionsUiState.Success(items, selectedDateMs)
            }
        }
        refreshSessions()
    }

    fun selectDate(dateMs: Long) {
        selectedDateMs = dateMs
        arthouseVenue?.let { _webViewUrl.value = urlForDate(it, dateMs) } ?: refreshSessions()
    }

    private fun urlForDate(venue: com.cinemasync.app.data.remote.scrapers.ArthouseVenue, dateMs: Long): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(dateMs))
        return venue.sessionsUrlTemplate.replace("{date}", date)
    }

    private fun refreshSessions() {
        val cinemaId = currentCinemaId ?: return
        viewModelScope.launch {
            try {
                val cinema = repository.getCinemaById(cinemaId) ?: return@launch
                repository.refreshSessionsForCinema(cinema, selectedDateMs)
            } catch (e: Exception) {
                _uiState.value = SessionsUiState.Error("Failed to load sessions: ${e.message}")
            }
        }
    }

    fun addToCalendar(session: Session) {
        viewModelScope.launch {
            repository.addSessionToCalendar(session)
        }
    }

    fun removeFromCalendar(session: Session) {
        viewModelScope.launch {
            repository.removeSessionFromCalendar(session)
        }
    }

    private fun todayMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
