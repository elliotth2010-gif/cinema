package com.cinemasync.app.ui.sessions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cinemasync.app.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(
    private val onCalendarClick: (SessionDisplayItem) -> Unit,
    private val onBookClick: (SessionDisplayItem) -> Unit
) : ListAdapter<SessionDisplayItem, SessionAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSessionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SessionDisplayItem) {
            binding.apply {
                textMovieTitle.text = item.movie.title
                textSessionTime.text = timeFormat.format(Date(item.session.startTimeMs))
                textScreenType.text = item.session.screenType
                textRating.text = item.movie.ratingCode
                textDuration.text = if (item.movie.durationMinutes > 0)
                    "${item.movie.durationMinutes} min" else ""

                val calIcon = if (item.session.isAddedToCalendar)
                    android.R.drawable.ic_menu_my_calendar
                else
                    android.R.drawable.ic_menu_agenda

                btnCalendar.setImageResource(calIcon)
                btnCalendar.isSelected = item.session.isAddedToCalendar
                btnCalendar.setOnClickListener { onCalendarClick(item) }
                btnBook.setOnClickListener { onBookClick(item) }
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SessionDisplayItem>() {
            override fun areItemsTheSame(old: SessionDisplayItem, new: SessionDisplayItem) =
                old.session.id == new.session.id
            override fun areContentsTheSame(old: SessionDisplayItem, new: SessionDisplayItem) =
                old == new
        }
    }
}
