package com.cinemasync.app.data.local

import androidx.room.TypeConverter
import com.cinemasync.app.data.model.CinemaChain

/**
 * Room has no built-in support for enum columns, so [CinemaChain] is persisted
 * by name and resolved back (falling through to UNKNOWN for unrecognised values,
 * e.g. after an enum entry is renamed in a future version).
 */
class Converters {
    @TypeConverter
    fun fromCinemaChain(chain: CinemaChain): String = chain.name

    @TypeConverter
    fun toCinemaChain(value: String): CinemaChain =
        runCatching { CinemaChain.valueOf(value) }.getOrDefault(CinemaChain.UNKNOWN)
}
