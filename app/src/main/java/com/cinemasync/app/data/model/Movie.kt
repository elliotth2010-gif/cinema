package com.cinemasync.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "movies")
data class Movie(
    @PrimaryKey val id: String,
    val title: String,
    val synopsis: String = "",
    val ratingCode: String = "",
    val durationMinutes: Int = 0,
    val genres: String = "",
    val posterUrl: String = "",
    val trailerUrl: String = ""
)
