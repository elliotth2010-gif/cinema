# CinemaSync

An Android app that scrapes nearby cinemas, shows movie sessions, and lets you sync them to your device calendar.

## Features

- **Nearby Cinema Discovery** — Uses your GPS location to find Hoyts, Event Cinemas, Village Cinemas (and more) within a configurable radius (5–50 km)
- **Session Browser** — Browse sessions by date (today + 7 days) with movie details, screen type, rating and runtime
- **Calendar Sync** — Add any session to your Android calendar with one tap; get a 30-minute reminder automatically
- **Booking Link** — Tap to open the cinema's booking page directly in your browser
- **Favourites** — Star cinemas you visit often for quick access

## Supported Cinema Chains

### Major chains
| Chain | Country | Method |
|-------|---------|--------|
| Hoyts | Australia / NZ | JSON API |
| Event Cinemas | Australia | GraphQL |
| Village Cinemas | Australia | JSON / HTML scraper |

### Arthouse & independent
Curated registry of known venues (verified coordinates) with best-effort HTML
session scraping:

| Venue | Suburb |
|-------|--------|
| Ritz Cinemas | Randwick |
| Hayden Orpheum Picture Palace | Cremorne |
| Dendy Newtown | Newtown |
| Dendy Opera Quays | Circular Quay |
| Palace Norton Street | Leichhardt |
| Palace Verona | Paddington |
| Palace Central | Chippendale |
| Chauvel Cinema | Paddington |

New independent venues can be added in `ArthouseCinemaRegistry.kt`.

## Building

### GitHub Actions (recommended)
Push to any branch and the workflow at `.github/workflows/build-apk.yml` will automatically build a debug APK, available as a workflow artifact.

### Local build
Requirements: Android Studio or Android SDK (API 34), JDK 17

```bash
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

```
MVVM + Repository pattern
├── Hilt dependency injection
├── Room database (offline cache)
├── Retrofit / OkHttp (networking)
├── Jsoup (HTML scraping fallback)
├── Android CalendarContract (calendar sync)
└── Navigation Component with Safe Args
```

## Permissions

| Permission | Purpose |
|---|---|
| `ACCESS_FINE_LOCATION` | Find cinemas near you |
| `INTERNET` | Fetch cinema sessions |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Sync sessions to device calendar |

## Minimum Android Version

Android 8.0 (API 26) and above.
