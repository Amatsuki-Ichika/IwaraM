# Repository guidelines for AI agents

This file is context for AI coding agents working in this repository. For human-facing
documentation, start with [README.md](README.md); for the module map, see
[docs/architecture.md](docs/architecture.md).

## Commands

| Task | Command |
| --- | --- |
| Unit tests | `./gradlew :app:test` |
| Debug build | `./gradlew :app:assembleDebug` |
| Release build | `./gradlew :app:assembleRelease` (requires `keystore.properties`) |
| Single test class | `./gradlew :app:testDebugUnitTest --tests '*VideoSorterTest'` |

Always go through the Gradle wrapper (`./gradlew`). Do not add absolute paths, machine-specific
SDK locations or locally installed toolchains to build files or documentation.

## Layout

```
app/src/main/java/io/github/amatsukiichika/iwaramanager/
├── MainActivity.kt        single Activity: the three main tabs and the overlay stack
├── IwaraApp.kt            Application: lazy database/repository, crash logger
├── CrashLogger.kt         uncaught-exception handler that writes crash logs to private storage
├── ScanService.kt         foreground service that scans media folders and fetches metadata
├── DownloadService.kt     foreground service that runs downloads
├── data/                  Room entities and DAOs, Retrofit API, session handling, repository
└── ui/                    Compose screens, shared components, player, theme
app/src/test/java/...      JVM unit tests (JUnit 4 + Robolectric where Android APIs are needed)
```

## Conventions

- Kotlin + Jetpack Compose, Material 3. Official Kotlin code style (4-space indent); sources are
  UTF-8. Comments in Kotlin sources are written in Simplified Chinese, matching the UI language;
  build scripts (`*.gradle.kts`, `gradle.properties`, `proguard-rules.pro`) keep English comments.
- **No DI framework, no Navigation library, no MVI.** Screens are composed directly and
  navigation is a hand-written overlay stack on the single Activity. Do not introduce those
  dependencies.
- Settings live as `StateFlow`s in `IwaraRepository` (the single source of truth) and are
  exposed to the UI through composition locals or ViewModels.
- The app version is defined once, in `gradle.properties`.
- Anything that is not a build input (build outputs, IDE files, SDK paths, signing material)
  stays out of git — see `.gitignore`.

## Do not

- Do not commit `keystore.properties`, `*.jks`, `local.properties`, APKs or build output.
- Do not change code while editing comments, and do not reformat unrelated code.
- Do not add a dependency without checking the version catalog (`gradle/libs.versions.toml`).
- Do not put secrets, API credentials or personal data in source or tests.

## Testing expectations

Add a JVM unit test under `app/src/test/...` for any new pure logic (geometry maths, parsers,
filtering, sorting, migrations). Robolectric is available when Android APIs are required.
`./gradlew :app:test` must pass before a change is considered complete.
