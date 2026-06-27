package com.cinemasync.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = Cinema::class,
            parentColumns = ["id"],
            childColumns = ["cinemaId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Movie::class,
            parentColumns = ["id"],
            childColumns = ["movieId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("cinemaId"), Index("movieId")]
)
data class Session(
    @PrimaryKey val id: String,
    val cinemaId: String,
    val movieId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val screenType: String = "",
    val bookingUrl: String = "",
    val isAddedToCalendar: Boolean = false,
    val calendarEventId: Long = -1L
)

data class SessionWithDetails(
    val session: Session,
    val cinema: Cinema,
    val movie: Movie
)
