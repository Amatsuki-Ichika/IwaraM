# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The app is bilingual. Every screen, dialog, status message, notification and error text is
  available in Simplified Chinese and English, and Settings > General > Language switches between
  following the system, Chinese and English. Switching takes effect immediately, including the
  library's month, root-folder and unknown-author labels, which are recomputed without rescanning.

- A first-run guide explains the language choice, the library folder, the download folder and the
  main preferences before the app opens for the first time. It can be replayed from
  Settings > About > Show the first-run guide again.

- The local library now indexes audio files (MP3, M4A, AAC, FLAC, OGG, Opus, WAV, AMR, MKA) and
  more video containers (TS, M2TS, FLV, OGV, AVI, 3GP, 3G2) in addition to the existing MP4, MKV,
  WebM and MOV support. Audio entries show a music-note placeholder on their card and play
  audio-only in the player instead of failing to render a video surface.

- The playback-error screen offers "Open in another app", which hands the file to an external
  player through the system chooser when the device cannot decode it.

### Changed

- The project is now licensed under the GNU Affero General Public License v3.0 instead of the MIT
  license. The README defaults to English with a Simplified Chinese translation, and shows the app
  icon and screenshots of the local library, the player and the first-run guide.

### Fixed

- Playback no longer stops at the first decoder that fails to initialise. The player now enables
  ExoPlayer's decoder fallback, so a file whose preferred audio decoder rejects it (for example an
  AAC Main track that `c2.android.aac.decoder` refuses with error 0x80000000) is retried on the
  next available decoder instead of failing outright.

## [0.2.1] - 2026-10-28

### Added

- The local library shows one entry per video instead of one per file. A video downloaded in
  several qualities used to appear as several cards; rows are now folded by Iwara id (files whose
  id could not be parsed always stay separate), and the highest quality is the one that plays by
  default.

- The player's quality chip lists the copy on disk, so a second quality can be played without
  leaving playback, and the same menu opens 「管理源文件…」. Removing sources asks for a selection
  first: the disk file is kept unless 「同时删除磁盘上的文件」 is switched on, deleting every source
  warns that the entry will leave the library, and a file the system refuses to delete is reported
  instead of silently ignored.

### Fixed

- Switching accounts no longer leaks the previous account's data. Logging out, a session expiring,
  or signing in as someone else now clears the account-scoped state held in memory —
  subscriptions, favorites, playlists, the comment pane, and the follow/like snapshots — instead of
  leaving it on screen while the new account's pages were appended onto the old account's lists.

- The offline playlists cache is stored per account instead of in one shared slot, so launching
  without a network can no longer show the playlists of whoever was signed in before.

- A manually pasted access token is decrypted when the repository starts. The startup path used to
  read the raw preference, which after token encryption is `v1:` ciphertext; had the manual-token
  entry point been reintroduced, every authenticated metadata request would have carried the
  ciphertext as its bearer token and failed.

- Player error recovery actually resumes playback. The rebuild path took the "should I keep
  playing" flag from `isPlaying`, which is already false by the time the error callback runs, so
  the 7001 (effect pipeline) and 1000 (frame queue) recoveries rebuilt the player paused — with the
  error panel cleared, that looked like an unexplained silent stop.

- The 1000 frame-queue recovery honours its two-attempt cap. The counter was reset inside the
  prepare effect, which the recovery path itself re-enters, so every rebuild refunded its own
  budget and a video that kept hitting the error could rebuild the player instance forever.

- The playback page shows the author's avatar. It built the URL with the video-thumbnail template
  instead of the avatar template, so the avatar slot rendered empty.

- Opening a streamed (not downloaded) video seeds the follow state for its author, so an already
  followed author no longer shows as 「关注」 and the first tap does not silently follow them again.

- Cancelling a queued download actually cancels it. The cancel button only removed the record,
  the list entry and any partial file; the job stayed in the service queue, so the worker started
  it once the previous download finished and re-registered it as running — the task came back.

- A download that waited in the queue no longer fails because its signed URL expired. The URL is
  minted when the task is created and carries an `expires` timestamp; the download service now
  checks it when the task reaches the front of the queue and re-resolves the address for the same
  video and quality instead of failing with an HTTP error.

- Download notifications keep the video title in the notification shade, but the lock-screen
  (`VISIBILITY_PRIVATE`) variant is fully redacted. Titles in a lock-screen preview disclose what
  the user is watching; the shade is only visible on an unlocked device.

- The "已加入下载队列" toast appears only after the task was actually queued; previously it was
  shown before the detail request and quality lookup ran, so a failure produced a success message
  followed by an error.

- Destroying the download service now stops the read loop instead of letting it finish the file.

- The library stops trusting rows whose file is gone. A scan only ever added or updated rows, so
  deleting a video in a file manager left its row behind: the online card still showed 「已下载」,
  opening it failed, and downloading the same video again inserted a second row for the same
  Iwara id — after which "find the local copy" could pick the dead one. A scan now prunes rows it
  did not find on disk, and only after confirming the file really is absent.

- Re-downloading a video whose local file was deleted replaces the old row instead of adding a
  second one for the same Iwara id.

- A cancelled scan no longer leaves the library page stuck on 「正在检索」. The progress state is
  cleared when the service is destroyed or hits the foreground-service time limit, and a scan that
  cannot open its folder now reports the failure instead of finishing with 「检索完成」.

- The player's quality and super-resolution menus no longer open off-screen. Placement used to be
  pinned to "always downward" with a hard 120 dp height floor, so a chip near the bottom edge (and
  any fullscreen window) pushed the menu past the screen border. The menu now opens into the side
  that actually fits (upward from the bottom bar), right-aligns with the chip, keeps itself inside
  the window and scrolls internally when the content is taller than the space. It also animates:
  fade plus a slight scale from the chip corner on open, and a quicker fade-out on close.

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

[Unreleased]: https://github.com/Amatsuki-Ichika/IwaraM/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/Amatsuki-Ichika/IwaraM/compare/v0.2.1-rc.2...v0.2.1
[0.2.1-rc.2]: https://github.com/Amatsuki-Ichika/IwaraM/compare/v0.2.0...v0.2.1-rc.2
[0.2.0]: https://github.com/Amatsuki-Ichika/IwaraM/compare/v0.2.0-rc.2...v0.2.0
[0.2.0-rc.2]: https://github.com/Amatsuki-Ichika/IwaraM/compare/v0.2.0-rc.1...v0.2.0-rc.2
[0.2.0-rc.1]: https://github.com/Amatsuki-Ichika/IwaraM/compare/v0.1.0...v0.2.0-rc.1
[0.1.0]: https://github.com/Amatsuki-Ichika/IwaraM/releases/tag/v0.1.0
