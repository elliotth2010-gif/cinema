package com.cinemasync.app.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.Movie
import com.cinemasync.app.data.model.Session
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun addSessionToCalendar(
        session: Session,
        movie: Movie,
        cinema: Cinema
    ): Long = withContext(Dispatchers.IO) {
        val calendarId = getPrimaryCalendarId() ?: return@withContext -1L

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, "🎬 ${movie.title}")
            put(CalendarContract.Events.DESCRIPTION, buildDescription(movie, session, cinema))
            put(CalendarContract.Events.EVENT_LOCATION, "${cinema.name}, ${cinema.address}")
            put(CalendarContract.Events.DTSTART, session.startTimeMs)
            put(CalendarContract.Events.DTEND, session.endTimeMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }

        val uri: Uri? = try {
            resolver.insert(CalendarContract.Events.CONTENT_URI, values)
        } catch (e: Exception) {
            null
        }

        val eventId = uri?.lastPathSegment?.toLongOrNull() ?: return@withContext -1L

        // Add reminder 30 minutes before
        addReminder(eventId, 30)

        eventId
    }

    suspend fun removeSessionFromCalendar(calendarEventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (calendarEventId < 0) return@withContext false
        try {
            val uri = CalendarContract.Events.CONTENT_URI
                .buildUpon()
                .appendPath(calendarEventId.toString())
                .build()
            resolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getSyncedSessions(): List<Long> = withContext(Dispatchers.IO) {
        val eventIds = mutableListOf<Long>()
        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = "${CalendarContract.Events.TITLE} LIKE ?"
        val selectionArgs = arrayOf("🎬 %")

        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection, selection, selectionArgs, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    eventIds.add(it.getLong(0))
                }
            }
        } catch (_: Exception) {}
        finally {
            cursor?.close()
        }
        eventIds
    }

    private fun getPrimaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
                "${CalendarContract.Calendars.IS_PRIMARY} = 1"

        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, selection, null, null
            )
            if (cursor?.moveToFirst() == true) cursor.getLong(0)
            else getAnyCalendarId()
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun getAnyCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1"
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, selection, null, null
            )
            if (cursor?.moveToFirst() == true) cursor.getLong(0) else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun addReminder(eventId: Long, minutesBefore: Int) {
        val reminderValues = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
        }
        try {
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
        } catch (_: Exception) {}
    }

    private fun buildDescription(movie: Movie, session: Session, cinema: Cinema): String {
        return buildString {
            if (movie.ratingCode.isNotBlank()) append("Rating: ${movie.ratingCode}\n")
            if (movie.durationMinutes > 0) append("Duration: ${movie.durationMinutes} min\n")
            if (session.screenType.isNotBlank()) append("Screen: ${session.screenType}\n")
            if (movie.synopsis.isNotBlank()) append("\n${movie.synopsis}\n")
            if (session.bookingUrl.isNotBlank()) append("\nBook tickets: ${session.bookingUrl}")
        }
    }
}
