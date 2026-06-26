package com.cinemasync.app.ui.sessions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.cinemasync.app.databinding.FragmentSessionsBinding
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@AndroidEntryPoint
class SessionsFragment : Fragment() {

    private var _binding: FragmentSessionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SessionsViewModel by viewModels()
    private val args: SessionsFragmentArgs by navArgs()

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.WRITE_CALENDAR] == true) {
            pendingCalendarAction?.invoke()
        } else {
            Snackbar.make(binding.root, "Calendar permission required to sync sessions", Snackbar.LENGTH_LONG).show()
        }
        pendingCalendarAction = null
    }

    private var pendingCalendarAction: (() -> Unit)? = null
    private lateinit var adapter: SessionAdapter
    private val dateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSessionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupAdapter()
        setupWebView()
        setupDateChips()
        setupViewOnlineButton()
        observeState()
        observeWebView()
        viewModel.loadSessions(args.cinemaId)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    binding.webProgress.isVisible = false
                }
            }
            webChromeClient = WebChromeClient()
        }
    }

    private fun observeWebView() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.webViewUrl.collect { url ->
                    if (url != null) {
                        // Switch to WebView mode — hide the scraped list UI
                        binding.recyclerSessions.isVisible = false
                        binding.progressBar.isVisible = false
                        binding.layoutEmpty.isVisible = false
                        binding.webView.isVisible = true
                        binding.webProgress.isVisible = true
                        binding.webView.loadUrl(url)
                    }
                }
            }
        }
    }

    private fun setupViewOnlineButton() {
        binding.btnViewOnline.setOnClickListener {
            val url = viewModel.cinemaWebsiteUrl.value.ifBlank { return@setOnClickListener }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun setupAdapter() {
        adapter = SessionAdapter(
            onCalendarClick = { item ->
                if (item.session.isAddedToCalendar) {
                    viewModel.removeFromCalendar(item.session)
                    Snackbar.make(binding.root, "Removed from calendar", Snackbar.LENGTH_SHORT).show()
                } else {
                    withCalendarPermission {
                        viewModel.addToCalendar(item.session)
                        Snackbar.make(binding.root, "Added to calendar!", Snackbar.LENGTH_SHORT).show()
                    }
                }
            },
            onBookClick = { item ->
                if (item.session.bookingUrl.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.session.bookingUrl))
                    startActivity(intent)
                }
            }
        )
        binding.recyclerSessions.adapter = adapter
    }

    private fun setupDateChips() {
        val today = Calendar.getInstance()
        for (i in 0..6) {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, i) }
            val chip = Chip(requireContext()).apply {
                text = if (i == 0) "Today" else if (i == 1) "Tomorrow" else dateFormat.format(cal.time)
                isCheckable = true
                isChecked = i == 0
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        viewModel.selectDate(cal.timeInMillis)
                    }
                }
            }
            binding.chipGroupDates.addView(chip)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.isVisible = state is SessionsUiState.Loading
                    binding.layoutEmpty.isVisible = state is SessionsUiState.Success && state.items.isEmpty()

                    when (state) {
                        is SessionsUiState.Success -> adapter.submitList(state.items)
                        is SessionsUiState.Error -> {
                            binding.layoutEmpty.isVisible = true
                            Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun withCalendarPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingCalendarAction = action
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
