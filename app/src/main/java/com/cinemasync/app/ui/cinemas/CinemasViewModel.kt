package com.cinemasync.app.ui.cinemas

import android.content.Context
import android.location.Geocoder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.repository.CinemaRepository
import com.cinemasync.app.util.LocationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

sealed class CinemasUiState {
    object Loading : CinemasUiState()
    data class Success(val cinemas: List<Cinema>) : CinemasUiState()
    data class Error(val message: String) : CinemasUiState()
    object LocationPermissionRequired : CinemasUiState()
}

@HiltViewModel
class CinemasViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
        // Re-search using whatever source was last used
        if (_manualAddress.value.isNotBlank()) searchBySuburb(_manualAddress.value) else refreshNearby()
    }

    private val _manualAddress = MutableStateFlow("")

    fun searchBySuburb(query: String) {
        _manualAddress.value = query.trim()
        if (query.isBlank()) {
            refreshNearby()
            return
        }
        viewModelScope.launch {
            _uiState.value = CinemasUiState.Loading
            try {
                val results = withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.getDefault()).getFromLocationName(query, 1)
                }
                if (results.isNullOrEmpty()) {
                    _uiState.value = CinemasUiState.Error("Could not find \"$query\". Try a more specific suburb or city.")
                    return@launch
                }
                val addr = results[0]
                val result = repository.refreshNearbyCinemas(
                    lat = addr.latitude,
                    lng = addr.longitude,
                    radiusKm = _radiusKm.value
                )
                result.onFailure {
                    _uiState.value = CinemasUiState.Error("Failed to load cinemas: ${it.message}")
                }
            } catch (e: Exception) {
                _uiState.value = CinemasUiState.Error("Location lookup failed: ${e.message}")
            }
        }
    }

    fun toggleFavourite(cinemaId: String) {
        viewModelScope.launch {
            repository.toggleFavourite(cinemaId)
        }
    }
}
