package com.cinemasync.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.Movie
import com.cinemasync.app.data.model.Session

@Database(
    entities = [Cinema::class, Movie::class, Session::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cinemaDao(): CinemaDao
    abstract fun movieDao(): MovieDao
    abstract fun sessionDao(): SessionDao
}
