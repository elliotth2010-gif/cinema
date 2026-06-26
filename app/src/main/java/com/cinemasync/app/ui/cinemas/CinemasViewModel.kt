package com.cinemasync.app.ui.cinemas

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.repository.CinemaRepository
import com.cinemasync.app.util.LocationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    fun refreshNearby() {
        viewModelScope.launch {
            _uiState.value = CinemasUiState.Loading
            try {
                val location = locationHelper.getCurrentLocation()
                    ?: locationHelper.getLastKnownLocation()

                if (location == null) {
                    _uiState.value = CinemasUiState.Error("Could not determine location. Please enable GPS.")
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
