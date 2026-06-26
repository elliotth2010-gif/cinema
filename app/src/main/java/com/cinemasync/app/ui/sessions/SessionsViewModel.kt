package com.cinemasync.app.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.Movie
import com.cinemasync.app.data.model.Session
import com.cinemasync.app.data.repository.CinemaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
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

    private val _uiState = MutableStateFlow<SessionsUiState>(SessionsUiState.Loading)
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val _cinemaWebsiteUrl = MutableStateFlow("")
    val cinemaWebsiteUrl: StateFlow<String> = _cinemaWebsiteUrl.asStateFlow()

    private var currentCinemaId: String? = null
    private var selectedDateMs: Long = todayMs()

    fun loadSessions(cinemaId: String) {
        currentCinemaId = cinemaId
        viewModelScope.launch {
            repository.getCinemaById(cinemaId)?.let { _cinemaWebsiteUrl.value = it.websiteUrl }
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
        refreshSessions()
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
