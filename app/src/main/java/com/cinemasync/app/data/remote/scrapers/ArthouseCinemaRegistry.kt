package com.cinemasync.app.data.remote.scrapers

import com.cinemasync.app.data.model.Cinema
import com.cinemasync.app.data.model.CinemaChain

/**
 * Arthouse and independent cinemas rarely expose the structured session APIs
 * that the large chains do, and several are single-venue operators with no
 * "find a cinema near me" endpoint at all. We therefore keep a curated registry
 * of known venues (with verified coordinates and their public session pages),
 * and the [ArthouseScraper] resolves nearby ones from this list before
 * attempting a best-effort HTML scrape of each venue's session times.
 */
data class ArthouseVenue(
    val id: String,
    val name: String,
    val chain: CinemaChain,
    val address: String,
    val suburb: String,
    val latitude: Double,
    val longitude: Double,
    val websiteUrl: String,
    /** Page listing session times. {date} is replaced with yyyy-MM-dd. */
    val sessionsUrlTemplate: String
)

object ArthouseCinemaRegistry {

    val venues: List<ArthouseVenue> = listOf(
        ArthouseVenue(
            id = "ritz_randwick",
            name = "Ritz Cinemas",
            chain = CinemaChain.RITZ,
            address = "43-47 St Pauls St, Randwick NSW 2031",
            suburb = "Randwick",
            latitude = -33.9145,
            longitude = 151.2419,
            websiteUrl = "https://www.ritzcinemas.com.au",
            sessionsUrlTemplate = "https://www.ritzcinemas.com.au/now-showing?date={date}"
        ),
        ArthouseVenue(
            id = "orpheum_cremorne",
            name = "Hayden Orpheum Picture Palace",
            chain = CinemaChain.ORPHEUM,
            address = "380 Military Rd, Cremorne NSW 2090",
            suburb = "Cremorne",
            latitude = -33.8285,
            longitude = 151.2245,
            websiteUrl = "https://www.orpheum.com.au",
            sessionsUrlTemplate = "https://www.orpheum.com.au/now-showing?date={date}"
        ),
        ArthouseVenue(
            id = "dendy_newtown",
            name = "Dendy Newtown",
            chain = CinemaChain.DENDY,
            address = "261-263 King St, Newtown NSW 2042",
            suburb = "Newtown",
            latitude = -33.8967,
            longitude = 151.1793,
            websiteUrl = "https://www.dendy.com.au/cinemas/newtown",
            sessionsUrlTemplate = "https://www.dendy.com.au/cinemas/newtown/sessions?date={date}"
        ),
        ArthouseVenue(
            id = "dendy_opera_quays",
            name = "Dendy Opera Quays",
            chain = CinemaChain.DENDY,
            address = "2 East Circular Quay, Sydney NSW 2000",
            suburb = "Circular Quay",
            latitude = -33.8606,
            longitude = 151.2127,
            websiteUrl = "https://www.dendy.com.au/cinemas/opera-quays",
            sessionsUrlTemplate = "https://www.dendy.com.au/cinemas/opera-quays/sessions?date={date}"
        ),
        ArthouseVenue(
            id = "palace_norton_st",
            name = "Palace Norton Street",
            chain = CinemaChain.PALACE,
            address = "99 Norton St, Leichhardt NSW 2040",
            suburb = "Leichhardt",
            latitude = -33.8836,
            longitude = 151.1567,
            websiteUrl = "https://www.palacecinemas.com.au/cinemas/norton-street",
            sessionsUrlTemplate = "https://www.palacecinemas.com.au/cinemas/norton-street/?date={date}"
        ),
        ArthouseVenue(
            id = "palace_verona",
            name = "Palace Verona",
            chain = CinemaChain.PALACE,
            address = "17 Oxford St, Paddington NSW 2021",
            suburb = "Paddington",
            latitude = -33.8847,
            longitude = 151.2207,
            websiteUrl = "https://www.palacecinemas.com.au/cinemas/verona",
            sessionsUrlTemplate = "https://www.palacecinemas.com.au/cinemas/verona/?date={date}"
        ),
        ArthouseVenue(
            id = "palace_central",
            name = "Palace Central",
            chain = CinemaChain.PALACE,
            address = "28 Broadway, Chippendale NSW 2008",
            suburb = "Chippendale",
            latitude = -33.8836,
            longitude = 151.1972,
            websiteUrl = "https://www.palacecinemas.com.au/cinemas/central",
            sessionsUrlTemplate = "https://www.palacecinemas.com.au/cinemas/central/?date={date}"
        ),
        ArthouseVenue(
            id = "palace_chauvel",
            name = "Chauvel Cinema",
            chain = CinemaChain.PALACE,
            address = "249 Oxford St, Paddington NSW 2021",
            suburb = "Paddington",
            latitude = -33.8857,
            longitude = 151.2289,
            websiteUrl = "https://www.palacecinemas.com.au/cinemas/chauvel",
            sessionsUrlTemplate = "https://www.palacecinemas.com.au/cinemas/chauvel/?date={date}"
        )
    )

    fun toCinema(venue: ArthouseVenue, distanceKm: Double): Cinema = Cinema(
        id = "arthouse_${venue.id}",
        name = venue.name,
        chain = venue.chain,
        address = venue.address,
        suburb = venue.suburb,
        latitude = venue.latitude,
        longitude = venue.longitude,
        websiteUrl = venue.websiteUrl,
        distanceKm = distanceKm
    )

    fun venueForCinema(cinema: Cinema): ArthouseVenue? {
        val rawId = cinema.id.removePrefix("arthouse_")
        return venues.firstOrNull { it.id == rawId }
    }
}
