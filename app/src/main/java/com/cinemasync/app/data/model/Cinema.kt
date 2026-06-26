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

enum class CinemaChain(val displayName: String, val baseUrl: String, val isArthouse: Boolean = false) {
    HOYTS("Hoyts", "https://www.hoyts.com.au"),
    EVENT("Event Cinemas", "https://www.eventcinemas.com.au"),
    VILLAGE("Village Cinemas", "https://www.villagecinemas.com.au"),
    PALACE("Palace Cinemas", "https://www.palacecinemas.com.au", isArthouse = true),
    READING("Reading Cinemas", "https://www.readingcinemas.com.au"),
    DENDY("Dendy Cinemas", "https://www.dendy.com.au", isArthouse = true),
    RITZ("Ritz Cinemas", "https://www.ritzcinemas.com.au", isArthouse = true),
    ORPHEUM("Hayden Orpheum Picture Palace", "https://www.orpheum.com.au", isArthouse = true),
    INDEPENDENT("Independent Cinema", "", isArthouse = true),
    UNKNOWN("Unknown", "")
}
