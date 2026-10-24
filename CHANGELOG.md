# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security

- Encrypt stored Iwara session tokens (refresh/access) and the manually pasted access token with
  an AndroidKeyStore AES-256-GCM key before they reach SharedPreferences. Legacy plaintext values
  migrate to ciphertext on startup; when KeyStore is unavailable the app degrades to the old
  plaintext behaviour, and a lost key is treated as logged out rather than falling back.

### Changed

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

[Unreleased]: https://github.com/Amatsuki-Ichika/IwaraM/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Amatsuki-Ichika/IwaraM/releases/tag/v0.1.0
