# Architecture

This document describes how the app is put together. It is meant to be read alongside
[AGENTS.md](../AGENTS.md), which lists the conventions and constraints for working in the code.

## Overview

IwaraM is a **single-activity Jetpack Compose app**. There is deliberately no dependency
injection framework and no navigation library: screens are composed directly, and navigation is a
small hand-written overlay stack. State is held in `StateFlow`s, most of which live in
`IwaraRepository`, which acts as the single source of truth for both data and settings.

```
                     ┌──────────────────────── MainActivity ─────────────────────────┐
                     │  three main tabs  +  overlay stack (author page, favourites,  │
                     │  playlists, comments, tag pages, settings, video player)      │
                     └───────────────┬──────────────────────────────┬───────────────┘
                                     │                              │
                        ViewModels + Compose screens          overlay-stack state
                                     │
                     ┌───────────────┴──────────────────────────────┐
                     │              IwaraRepository                 │
                     │  database · network · local files · settings   │
                     └───────┬───────────────┬──────────────┬───────┘
                             │               │              │
                        Room (SQLite)    Retrofit/OkHttp   SharedPreferences
                                             │
                                     api.iwara.tv
```

## Module map

`app/src/main/java/io/github/amatsukiichika/iwaram/`

| Path | Responsibility |
| --- | --- |
| `MainActivity.kt` | The only Activity: tab bar, overlay stack, settings entry, player entry. |
| `IwaraApp.kt` | `Application`: lazily creates the database and repository, installs the crash logger. |
| `ScanService.kt` | Foreground service that scans imported folders and fetches metadata; progress is published through a global `ScanState`. |
| `DownloadService.kt` | Foreground service that runs the download queue with resume support. |
| `CrashLogger.kt` | Writes uncaught exceptions to a file under the app's private storage. |
| `data/` | Room entities, DAOs and migrations; the Retrofit API and DTOs; session/token handling; the repository; and pure helpers such as the ID parser and the global filter rules. |
| `ui/` | Screens and shared components: the local library, the player, settings, the video card, sorting, scroll geometry, the theme, and the browse/ subtree with the online screens. |
| `ui/components/` | Reusable Compose building blocks (top bar, glass bars, sheets, dialogs, sliders, glass material and backdrop helpers). The floating bottom bar itself lives in `ui/browse/IwaraBottomBar.kt`. |

Tests live in `app/src/test/java/...` and run on the JVM. Pure logic — geometry maths, parsers,
filtering, sorting, database migrations — is covered by JUnit 4 tests; Robolectric is used where
Android APIs are unavoidable.

## Data flow

- **Local library.** `ScanService` walks the imported directories, derives a video's iwara ID from
  its file name (`IwaraIdParser`), fetches any missing metadata, and writes Room entities in
  batches. `VideoViewModel` derives the library's views (all / folders / authors / dates / tags),
  search results and sort orders from the DAO streams.
- **Online.** `IwaraClient` performs paginated requests against the site's API. Feeds use a paging
  helper that de-duplicates entries across pages, because the API pages by offset and the lists
  shift while the user scrolls.
- **Playback.** The player is built on ExoPlayer. Optional GPU shader models (Anime4K) run as a
  video effect; switching models rebuilds the player instance rather than re-configuring the live
  effect pipeline.
- **Settings.** Preferences are stored in `SharedPreferences` and exposed as `StateFlow`s from the
  repository, then consumed by the UI through composition locals or ViewModels.

## Conventions worth knowing

- Comments in Kotlin sources are written in Simplified Chinese, matching the UI language; build
  scripts keep English comments.
- The app version is defined once, in `gradle.properties`; dependency versions live in
  `gradle/libs.versions.toml`.
- No absolute paths, SDK locations or machine-specific configuration are checked in.
- Everything that is a build output, an IDE file, SDK path or signing material is git-ignored.
