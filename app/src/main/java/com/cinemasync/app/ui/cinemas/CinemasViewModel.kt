package com.cinemasync.app.ui.cinemas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.remote.scrapers.ArthouseCinemaRegistry
import com.cinemasync.app.data.repository.CinemaRepository
import com.cinemasync.app.util.LocationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CinemasUiState {
    object Loading : CinemasUiState()
    data class Success(val cinemas: List<Cinema>) : CinemasUiState()
    data class Error(val message: String) : CinemasUiState()
    object LocationPermissionRequired : CinemasUiState()
}

@HiltViewModel
class CinemasViewModel @Inject constructor(
    private val repository: CinemaRepository,
    private val locationHelper: LocationHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow<CinemasUiState>(CinemasUiState.Loading)
    val uiState: StateFlow<CinemasUiState> = _uiState.asStateFlow()

    private val _radiusKm = MutableStateFlow(20.0)
    val radiusKm: StateFlow<Double> = _radiusKm.asStateFlow()

    /** Emits a cinema id when a venue is picked from the dropdown, so the
     *  fragment can navigate straight to its sessions/movies. */
    private val _navigateToCinema = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToCinema: SharedFlow<String> = _navigateToCinema.asSharedFlow()

    /** Known venues offered in the "pick a cinema" dropdown. These have fixed
     *  session pages, so they work regardless of GPS or where the user is. */
    val selectableCinemas: List<Cinema> =
        ArthouseCinemaRegistry.venues
            .map { ArthouseCinemaRegistry.toCinema(it, 0.0) }
            .sortedBy { it.name }

    init {
        observeCinemas()
    }

    private fun observeCinemas() {
        viewModelScope.launch {
            repository.getCinemas().collect { cinemas ->
                if (cinemas.isEmpty() && _uiState.value !is CinemasUiState.Error) {
                    _uiState.value = CinemasUiState.Loading
                } else {
                    _uiState.value = CinemasUiState.Success(cinemas)
                }
            }
        }
    }

    /** Persist the chosen venue, then ask the fragment to open its sessions. */
    fun selectCinema(cinema: Cinema) {
        viewModelScope.launch {
            repository.addKnownCinema(cinema)
            _navigateToCinema.emit(cinema.id)
        }
    }

    fun refreshNearby() {
        viewModelScope.launch {
            _uiState.value = CinemasUiState.Loading
            try {
                val location = locationHelper.getCurrentLocation()
                    ?: locationHelper.getLastKnownLocation()

                if (location == null) {
                    _uiState.value = CinemasUiState.Error("Could not determine location. Pick a cinema from the list above, or enable GPS.")
                    return@launch
                }

                val result = repository.refreshNearbyCinemas(
                    lat = location.latitude,
                    lng = location.longitude,
                    radiusKm = _radiusKm.value
                )

                result.onFailure {
                    _uiState.value = CinemasUiState.Error("Failed to load cinemas: ${it.message}")
                }
            } catch (e: SecurityException) {
                _uiState.value = CinemasUiState.LocationPermissionRequired
            } catch (e: Exception) {
                _uiState.value = CinemasUiState.Error("Error: ${e.message}")
            }
        }
    }

    fun setRadius(km: Double) {
        _radiusKm.value = km
        refreshNearby()
    }

    fun toggleFavourite(cinemaId: String) {
        viewModelScope.launch {
            repository.toggleFavourite(cinemaId)
        }
    }
}
