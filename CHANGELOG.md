# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-10-25

### Changed

- Settings pages tightened vertically: list edge padding and card/section gaps 12 dp → 8 dp,
  in-card vertical padding 14 dp → 10 dp, title-to-control gap 12 dp → 8 dp, and the opacity
  slider row min height 44 dp → 32 dp. Top bar, switch rows and chips keep their sizes (touch
  targets preserved). The material preset row now sits above the opacity slider (pick the mode
  first, then fine-tune). Note: `AppEntryCard` is shared, so the 「我的」 page tightens with it.

- Defaults for **new installs** (existing installations keep their saved values): material
  opacity 78% → 80%, material preset 半透明 (translucent) → 磨砂 (frosted liquid glass), and
  fullscreen orientation 自动 (follow system) → 智能识别 (pick landscape/portrait from the
  video's aspect ratio).

- **Breaking:** the application ID changed from `io.github.amatsukiichika.iwaramanager` to
  `io.github.amatsukiichika.iwaram` (Kotlin package renamed to match). Android treats this as a
  different app: existing installations cannot update in place and must uninstall first, and
  local data (library index, watch history, settings) does not carry over. Downloaded media in
  user-chosen folders is unaffected.

### Added

- Settings → General → 「本地库」 gains a "隐藏未匹配的视频" (hide unmatched videos) switch:
  when on, the library grid hides local videos that never resolved to an Iwara id (filename
  could not be parsed and none was assigned manually). Videos that have an id but whose metadata
  has not been fetched yet still show - that state is transient (right after a download or while
  retrying on a bad network). Hidden entries are counted into the "N hidden" empty-state line.
  Author buckets and author pages are unaffected.

### Fixed

- Secondary pages (overlay stack, player, settings and its category pages) stuttered when a
  swipe-back gesture was released: the exit animation used a fixed 320 ms tween with a
  zero-initial-slope easing and inherited none of the finger's momentum. Release velocity is now
  estimated from the back-gesture progress stream, and the exit tween covers only the remaining
  distance at a duration derived from that velocity with a fast-out easing, so the page keeps
  gliding at the speed the finger left it at.

- The detail pane's scroll-back-to-top animation when switching to a recommended video never
  actually played: clearing the previous video's recommendations collapsed the list below the
  viewport, so LazyColumn clamped the scroll position straight to 0. The previous video's
  recommendations are now kept until the new list arrives (guarded against stale responses),
  keeping the list tall and the animation visible.

## [0.2.0-rc.1] - 2026-10-24

### Security

- Encrypt stored Iwara session tokens (refresh/access) and the manually pasted access token with
  an AndroidKeyStore AES-256-GCM key before they reach SharedPreferences. Legacy plaintext values
  migrate to ciphertext on startup; when KeyStore is unavailable the app degrades to the old
  plaintext behaviour, and a lost key is treated as logged out rather than falling back.

### Changed

- Target Android 16 (`targetSdk` 36). Edge-to-edge and predictive back were already in place;
  the dataSync foreground services now implement `onTimeout()` so downloads pause gracefully
  when Android 15+ enforces the 6-hour/24h limit, resuming via the existing 「继续」 flow.

- Upgrade the network stack: Retrofit 2.11 -> 3.0 and OkHttp 4.12 -> 5.5.1.

- Add Room secondary indexes (schema v3): `videos.iwaraId` for download dedup lookups,
  `(videos.metaFetched, videos.iwaraId)` for the pending-metadata scan, and
  `video_tag_cross_ref.tagId` for the tag JOIN - all previously full table scans.

- Housekeeping: ignore `.zcode/`, drop `validateDistributionUrl=false` from the Gradle wrapper
  properties (restoring default distribution URL validation), log SAF persistable-permission
  failures, stop the download service via the framework startId protocol once the queue drains
  (instead of leaving an idle service resident), and reuse thread-safe `DateTimeFormatter`s for
  the download-list and relative-time date labels.

### Fixed

- Rethrow `CancellationException` in the online feed pager and view-model catch blocks instead of
  surfacing it as a load error / diagnostic log noise when a screen is left mid-request.

## [0.1.0] - 2026-10-24

First public release.

### Added

- **Online browsing** — trending and latest feeds, tag browsing, search, author pages.
- **Local library** — import and scan media folders, fetch metadata from the site, then
  browse the result grouped by folder, author, date or tag, with search and sorting.
- **Playback** — ExoPlayer-based player with fullscreen, swipe-to-seek, double-tap to seek,
  quality selection and resume from the last watched position.
- **Super resolution** — optional Anime4K shader models running on the GPU.
- **Downloads** — background download service with resume support.
- **Watch history** — automatic history entries with resume playback.
- **Favourites and playlists** — browse and manage the account's favourites and playlists.
- **Content filter** — global include/exclude rules over titles and tags, plus an R18
  visibility toggle.
- **Material 3 UI** — light and dark themes with a choice of accent colours, and an optional
  frosted-glass treatment for the bars.
- **Crash log capture** — uncaught exceptions are written to a file for troubleshooting.

[Unreleased]: https://github.com/Amatsuki-Ichika/IwaraM/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Amatsuki-Ichika/IwaraM/compare/v0.2.0-rc.2...v0.2.0
[0.2.0-rc.2]: https://github.com/Amatsuki-Ichika/IwaraM/compare/v0.2.0-rc.1...v0.2.0-rc.2
[0.2.0-rc.1]: https://github.com/Amatsuki-Ichika/IwaraM/compare/v0.1.0...v0.2.0-rc.1
[0.1.0]: https://github.com/Amatsuki-Ichika/IwaraM/releases/tag/v0.1.0
