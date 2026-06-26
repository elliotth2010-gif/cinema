package com.cinemasync.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cinemas")
data class Cinema(
    @PrimaryKey val id: String,
    val name: String,
    val chain: CinemaChain,
    val address: String,
    val suburb: String,
    val latitude: Double,
    val longitude: Double,
    val websiteUrl: String,
    val distanceKm: Double = 0.0,
    val isFavourite: Boolean = false
)

enum class CinemaChain(val displayName: String, val baseUrl: String) {
    HOYTS("Hoyts", "https://www.hoyts.com.au"),
    EVENT("Event Cinemas", "https://www.eventcinemas.com.au"),
    VILLAGE("Village Cinemas", "https://www.villagecinemas.com.au"),
    PALACE("Palace Cinemas", "https://www.palacecinemas.com.au"),
    READING("Reading Cinemas", "https://www.readingcinemas.com.au"),
    DENDY("Dendy Cinemas", "https://www.dendy.com.au"),
    UNKNOWN("Unknown", "")
}
