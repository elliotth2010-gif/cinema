package com.cinemasync.app.ui.cinemas

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.databinding.ItemCinemaBinding
import java.util.Locale

class CinemaAdapter(
    private val onCinemaClick: (Cinema) -> Unit,
    private val onFavouriteClick: (Cinema) -> Unit
) : ListAdapter<Cinema, CinemaAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCinemaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemCinemaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(cinema: Cinema) {
            binding.apply {
                textCinemaName.text = cinema.name
                textCinemaChain.text = cinema.chain.displayName
                textCinemaAddress.text = "${cinema.suburb} · ${String.format(Locale.getDefault(), "%.1f km", cinema.distanceKm)}"
                btnFavourite.isSelected = cinema.isFavourite
                btnFavourite.setImageResource(
                    if (cinema.isFavourite) android.R.drawable.btn_star_big_on
                    else android.R.drawable.btn_star_big_off
                )
                root.setOnClickListener { onCinemaClick(cinema) }
                btnFavourite.setOnClickListener { onFavouriteClick(cinema) }
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Cinema>() {
            override fun areItemsTheSame(old: Cinema, new: Cinema) = old.id == new.id
            override fun areContentsTheSame(old: Cinema, new: Cinema) = old == new
        }
    }
}
