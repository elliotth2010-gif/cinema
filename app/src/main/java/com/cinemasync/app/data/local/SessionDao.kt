package com.cinemasync.app.data.local

import androidx.room.*
import com.cinemasync.app.data.model.Session
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("""
        SELECT * FROM sessions
        WHERE cinemaId = :cinemaId
        AND startTimeMs >= :fromMs
        ORDER BY startTimeMs ASC
    """)
    fun getSessionsForCinema(cinemaId: String, fromMs: Long): Flow<List<Session>>

    @Query("""
        SELECT * FROM sessions
        WHERE startTimeMs >= :fromMs AND startTimeMs < :toMs
        ORDER BY startTimeMs ASC
    """)
    fun getSessionsInRange(fromMs: Long, toMs: Long): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: String): Session?

    @Query("SELECT * FROM sessions WHERE isAddedToCalendar = 1")
    fun getSyncedSessions(): Flow<List<Session>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<Session>)

    @Update
    suspend fun updateSession(session: Session)

    @Query("UPDATE sessions SET isAddedToCalendar = :added, calendarEventId = :eventId WHERE id = :id")
    suspend fun setCalendarSynced(id: String, added: Boolean, eventId: Long)

    @Query("DELETE FROM sessions WHERE cinemaId = :cinemaId")
    suspend fun deleteForCinema(cinemaId: String)
}
