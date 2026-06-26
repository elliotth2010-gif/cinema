package com.cinemasync.app.data.remote.scrapers

import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.CinemaChain
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScraperFactory @Inject constructor(
    private val hoytsScraper: HoytsScraper,
    private val eventCinemasScraper: EventCinemasScraper,
    private val villageScraper: VillageScraper
) {
    fun scraperFor(cinema: Cinema): BaseCinemaScraper = when (cinema.chain) {
        CinemaChain.HOYTS -> hoytsScraper
        CinemaChain.EVENT -> eventCinemasScraper
        CinemaChain.VILLAGE -> villageScraper
        else -> eventCinemasScraper
    }

    fun allScrapers(): List<BaseCinemaScraper> = listOf(
        hoytsScraper,
        eventCinemasScraper,
        villageScraper
    )
}
