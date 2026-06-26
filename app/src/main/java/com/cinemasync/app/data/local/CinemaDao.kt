package com.cinemasync.app.data.local

import androidx.room.*
import com.cinemasync.app.data.model.Cinema
import kotlinx.coroutines.flow.Flow

@Dao
interface CinemaDao {
    @Query("SELECT * FROM cinemas ORDER BY distanceKm ASC")
    fun getAllCinemas(): Flow<List<Cinema>>

    @Query("SELECT * FROM cinemas WHERE isFavourite = 1 ORDER BY name ASC")
    fun getFavouriteCinemas(): Flow<List<Cinema>>

    @Query("SELECT * FROM cinemas WHERE id = :id")
    suspend fun getCinemaById(id: String): Cinema?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCinemas(cinemas: List<Cinema>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCinema(cinema: Cinema)

    @Update
    suspend fun updateCinema(cinema: Cinema)

    @Query("UPDATE cinemas SET isFavourite = :isFavourite WHERE id = :id")
    suspend fun setFavourite(id: String, isFavourite: Boolean)

    @Query("DELETE FROM cinemas")
    suspend fun deleteAll()
}
