# Iwara Manager

[![CI](https://github.com/Amatsuki-Ichika/IwaraM/actions/workflows/ci.yml/badge.svg)](https://github.com/Amatsuki-Ichika/IwaraM/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Amatsuki-Ichika/IwaraM?sort=semver)](https://github.com/Amatsuki-Ichika/IwaraM/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-yellow.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-26-blue.svg)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)

An unofficial Android client for [iwara.tv](https://iwara.tv), written in Kotlin and Jetpack
Compose: browse the site, keep a searchable local library, and play videos with optional
GPU-accelerated super resolution.

> **Not affiliated with iwara.tv.** This is a community project and an independent client.
> The service may contain adult material — see [DISCLAIMER.md](DISCLAIMER.md) before using it.

## Features

- **Online browsing** — trending and latest feeds, tag browsing, search and author pages.
- **Local library** — import and scan media folders, fetch metadata, then browse by folder,
  author, date or tag with search and sorting.
- **Playback** — swipe to seek, double-tap to seek, fullscreen, quality selection and
  resume from where you left off.
- **Super resolution** — optional Anime4K shader models running on the GPU.
- **Downloads** — background downloads with resume.
- **Watch history** — automatic history with one-tap resume.
- **Favourites and playlists** — browse and manage your account's collections.
- **Content filter** — global include/exclude rules for titles and tags, plus an R18 toggle.
- **Material 3 UI** — light and dark themes with a choice of accent colours, and an optional
  frosted-glass treatment for the bars.

## Install

Download the latest APK from the [releases page](https://github.com/Amatsuki-Ichika/IwaraM/releases/latest)
and install it. The build targets **arm64-v8a** devices running **Android 8.0 (API 26)** or newer.

Because each release is signed with the same certificate, newer versions can be installed over
older ones. Builds signed by anyone else cannot — uninstall first if you are coming from a
different build. Verify a download as described in [SECURITY.md](SECURITY.md).

## Build from source

Requirements:

- JDK 17
- Android SDK with platform `android-37.2` and build-tools `36.0.0`

```bash
# Point Gradle at your SDK (local.properties is git-ignored).
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:test            # unit tests
./gradlew :app:assembleDebug   # debug APK
./gradlew :app:assembleRelease # signed release APK; needs keystore.properties, see CONTRIBUTING.md
```

The app version has a single source of truth in `gradle.properties` (`app.versionCode`,
`app.versionName`) and can be overridden per build: `./gradlew assembleRelease -Papp.versionName=0.2.0`.

## Architecture

A single-activity Compose app with a hand-written overlay stack, a repository layer over Room
and Retrofit, and per-screen ViewModels — deliberately without a DI framework or a navigation
library. See [docs/architecture.md](docs/architecture.md) for the module map and the
conventions used in this codebase.

## Contributing

Bug reports and pull requests are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md)
first. This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).

## License

[MIT](LICENSE) © Iwara Manager contributors.

Bundled Anime4K shaders are MIT-licensed by their authors; see [NOTICE.md](NOTICE.md) for the
full list of third-party components.

## Acknowledgements

- [Anime4K](https://github.com/bloc97/Anime4K) by bloc97 for the super-resolution shaders.
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) by Kyant0 for the
  backdrop effect used by the frosted-glass bars.
