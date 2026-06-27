package com.cinemasync.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemasync.app.data.model.Session
import com.cinemasync.app.data.repository.CinemaRepository
import com.cinemasync.app.ui.sessions.SessionDisplayItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: CinemaRepository
) : ViewModel() {

    private val _upcomingSynced = MutableStateFlow<List<SessionDisplayItem>>(emptyList())
    val upcomingSynced: StateFlow<List<SessionDisplayItem>> = _upcomingSynced.asStateFlow()

    init {
        loadSyncedSessions()
    }

    private fun loadSyncedSessions() {
        viewModelScope.launch {
            repository.getSyncedSessions().collect { sessions ->
                val items = sessions.mapNotNull { session ->
                    val movie = repository.getMovieById(session.movieId) ?: return@mapNotNull null
                    val cinema = repository.getCinemaById(session.cinemaId) ?: return@mapNotNull null
                    SessionDisplayItem(session, movie, cinema)
                }.sortedBy { it.session.startTimeMs }
                _upcomingSynced.value = items
            }
        }
    }

    fun toggleCalendar(session: Session) {
        viewModelScope.launch {
            if (session.isAddedToCalendar) {
                repository.removeSessionFromCalendar(session)
            } else {
                repository.addSessionToCalendar(session)
            }
        }
    }
}
