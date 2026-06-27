package com.cinemasync.app.data.remote.scrapers

import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.CinemaChain
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScraperFactory @Inject constructor(
    private val hoytsScraper: HoytsScraper,
    private val eventCinemasScraper: EventCinemasScraper,
    private val villageScraper: VillageScraper,
    private val arthouseScraper: ArthouseScraper,
    private val ritzScraper: RitzScraper,
    private val dendyScraper: DendyScraper
) {
    fun scraperFor(cinema: Cinema): BaseCinemaScraper = when (cinema.chain) {
        CinemaChain.HOYTS -> hoytsScraper
        CinemaChain.EVENT -> eventCinemasScraper
        CinemaChain.VILLAGE -> villageScraper
        CinemaChain.RITZ -> ritzScraper
        CinemaChain.DENDY -> dendyScraper
        CinemaChain.PALACE,
        CinemaChain.ORPHEUM,
        CinemaChain.INDEPENDENT -> arthouseScraper
        else -> eventCinemasScraper
    }

    fun allScrapers(): List<BaseCinemaScraper> = listOf(
        hoytsScraper,
        eventCinemasScraper,
        villageScraper,
        arthouseScraper,
        ritzScraper,
        dendyScraper
    )
}
