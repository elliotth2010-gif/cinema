package com.cinemasync.app.ui.cinemas

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cinemasync.app.R
import com.cinemasync.app.databinding.FragmentCinemasBinding
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CinemasFragment : Fragment() {

    private var _binding: FragmentCinemasBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CinemasViewModel by viewModels()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            viewModel.refreshNearby()
        } else {
            Snackbar.make(binding.root, "Location permission required to find nearby cinemas", Snackbar.LENGTH_LONG).show()
        }
    }

    private lateinit var adapter: CinemaAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCinemasBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupRadiusChips()
        setupSwipeRefresh()
        observeState()
        checkLocationAndLoad()
    }

    private fun setupRecyclerView() {
        adapter = CinemaAdapter(
            onCinemaClick = { cinema ->
                val action = CinemasFragmentDirections.actionCinemasToSessions(cinema.id)
                findNavController().navigate(action)
            },
            onFavouriteClick = { cinema ->
                viewModel.toggleFavourite(cinema.id)
            }
        )
        binding.recyclerCinemas.adapter = adapter
    }

    private fun setupRadiusChips() {
        val radii = listOf(5.0, 10.0, 20.0, 50.0)
        radii.forEachIndexed { index, radius ->
            val chip = Chip(requireContext()).apply {
                text = "${radius.toInt()} km"
                isCheckable = true
                isChecked = radius == 20.0
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) viewModel.setRadius(radius)
                }
            }
            binding.chipGroupRadius.addView(chip)
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            checkLocationAndLoad()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.swipeRefresh.isRefreshing = state is CinemasUiState.Loading
                    binding.progressBar.isVisible = state is CinemasUiState.Loading && adapter.itemCount == 0
                    binding.textEmpty.isVisible = state is CinemasUiState.Success && state.cinemas.isEmpty()

                    when (state) {
                        is CinemasUiState.Success -> adapter.submitList(state.cinemas)
                        is CinemasUiState.Error -> {
                            Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                        }
                        is CinemasUiState.LocationPermissionRequired -> requestLocationPermission()
                        else -> {}
                    }
                }
            }
        }
    }

    private fun checkLocationAndLoad() {
        if (hasLocationPermission()) {
            viewModel.refreshNearby()
        } else {
            requestLocationPermission()
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
