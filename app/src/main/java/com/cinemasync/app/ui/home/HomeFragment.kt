package com.cinemasync.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cinemasync.app.databinding.FragmentHomeBinding
import com.cinemasync.app.ui.sessions.SessionAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    private lateinit var upcomingAdapter: SessionAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUpcomingList()
        observeState()
    }

    private fun setupUpcomingList() {
        upcomingAdapter = SessionAdapter(
            onCalendarClick = { item -> viewModel.toggleCalendar(item.session) },
            onBookClick = { item ->
                if (item.session.bookingUrl.isNotBlank()) {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(item.session.bookingUrl)
                    )
                    startActivity(intent)
                }
            }
        )
        binding.recyclerUpcoming.adapter = upcomingAdapter
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.upcomingSynced.collect { items ->
                    binding.textEmptyCalendar.visibility =
                        if (items.isEmpty()) View.VISIBLE else View.GONE
                    upcomingAdapter.submitList(items)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
