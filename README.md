# RShop

**A game store for Android handhelds.** Browse a catalogue, download, install and manage games, with a console-style interface built for controllers.

RShop is **only a store and a download/install manager**. It does not launch games (that stays with your frontend or emulators) and it ships **no catalogue**: you add the sites whose content you have the right to download (homebrew, your own creations, freely licensed content, your own server…).

Made for Retroid, Ayn, Anbernic and similar devices (16:9, gamepad), and works on any Android 13+ phone or tablet.

| Home | Game page |
|---|---|
| ![Home](docs/screenshots/home.png) | ![Game page](docs/screenshots/details.png) |
| **Catalogue sources** | **Appearance** |
| ![Sources](docs/screenshots/sources.png) | ![Appearance](docs/screenshots/theme.png) |

*Screenshots use a fictional test site (`tools/testsite`).*

## Features

- **Store**: instant search (plus the source site's own search), filters by platform, genre and source, sorting, lazy loading for huge catalogues. Remembered filters: hide installed games, hide demos/betas. The filter rows fold away behind one *Filters* button.
- **Several sources** synced in the background into a local database, so the app works offline. Later syncs only look for new games. For sites organised by console, you choose which consoles to fetch.
- **Downloads** in the background: progress, speed, pause/resume/cancel (also from the notification), retry, SHA-256 check when the site publishes one. They wait while the battery is low and refuse to fill the device (free space to keep is a setting).
- **Installation** into the folder you pick (Storage Access Framework): zip, 7z, tar, tar.gz, tar.xz…, protected against path traversal and zip bombs. Games with several formats let you choose; games made of several files (discs, bin + cue) can be downloaded and installed together.
- **Built-in browser** (GeckoView) for sites with intermediate pages: the file you click is captured and installed by the app.
- **Favorites, custom lists, library** of installed games with sorting, update and delete.
- **Screenshots and descriptions without the source**: when a source gives none, screenshots (and the title screen) come from the free Libretro thumbnails and the description from the game's Wikipedia article (credited, CC BY-SA). Both can be switched off in *Settings → Covers*.
- **Complete game pages**: descriptions, files and download counters are read in the background for your favorites and the most popular games (Wi-Fi only if you chose so), and for the game you rest on with the controller.
- **Controller first**: visible focus, L1/R1 tabs, Y for a game menu, X for search, button hints.
- **Backup**: save and restore sources, favorites, lists and settings as one JSON file.
- **Customisation**: screen styles (dark, OLED, slate, twilight, **Glass** frosted surfaces, a **PlayStation Store** blue, a light **eShop** with red bands), accent colors, text size, flat covers or **3D game boxes** (turned on the shelf, facing you when selected), French / English.
- **In-app update**: *Settings → About → Check for updates* downloads the latest GitHub release (SHA-256 checked) and hands it to Android's installer. The app also looks once a day (can be turned off) and shows a dot on Settings when a version is available.

## Responsible use

The scraper is a standalone module (`:scraper`, pure JVM). It respects `robots.txt`, rate-limits its requests, **never bypasses** CAPTCHAs, anti-bot protection, DRM, paywalls or logins, never silently downloads executables, and validates URLs, sizes, file types, hashes and extraction paths.

Only add sources whose content you are allowed to use. You are responsible for what you download.

## Install

1. Download `app-release.apk` from [Releases](../../releases) and open it (allow installs from your file manager or browser if asked).
2. On first launch: *Settings → Games folder*, then *Settings → Catalogue sources → Add a source*.

With [Obtainium](https://github.com/ImranR98/Obtainium): [add RShop](obtainium://add/https://github.com/AnimatrixEX/RShop) for automatic updates.

Requires Android 13+ and an **arm64** CPU. About 200 MB, mostly GeckoView.

**Adding a source**: paste the address of a page listing games (or consoles). RShop analyses the site and proposes a configuration with a preview; you can also import/export the JSON config (`ScraperConfig`) for tricky sites.

**Covers** come from the free [Libretro thumbnails](https://thumbnails.libretro.com/) for the consoles it knows, and from [SteamGridDB](https://www.steamgriddb.com/) if you add your own free API key (*Settings → Covers*, stored encrypted on the device). Other games get a generated thumbnail.

## Build

JDK 17+ (tested with 21) and the Android SDK (API 37).

```bash
./gradlew installDebug                           # debug APK (com.rshop.debug)
./gradlew :scraper:test :app:testDebugUnitTest   # tests
./gradlew :app:assembleRelease                   # release APK (arm64)
```

The debug build includes a small fictional catalogue and allows cleartext HTTP to `localhost` only. To sign the release APK, set `RSHOP_KEYSTORE`, `RSHOP_KEYSTORE_PASSWORD`, `RSHOP_KEY_ALIAS` and `RSHOP_KEY_PASSWORD` in `~/.gradle/gradle.properties`.

A local fictional site for testing: `python tools/testsite/server.py --rate 4`, then `adb reverse tcp:8099 tcp:8099` and use `http://localhost:8099/consoles`.

## Architecture

```
app/       Kotlin · Compose · Material 3 · Hilt · Room · WorkManager · Coil · GeckoView
 ├─ data/ domain/ download/ installation/ ui/
scraper/   pure JVM (Jsoup, OkHttp): GameSource, WebsiteSource, SiteAnalyzer, ScraperConfig
tools/testsite/   local test site
```

MVVM, repositories, coroutines/Flow. HTML parsing never touches the UI: `Scraper → Website adapter → Parser → model → local database → UI`.

## Status

Version 0.1.7, personal project, tested on a Retroid Pocket 6. See [`CLAUDE.md`](CLAUDE.md) for the development plan (the game launcher is deliberately out of scope).
