# 0031 — Architecture boundaries

Status: accepted

## Problem

The review found code crossing the layers that [architecture.md](../architecture.md) describes: a second HTTP stack in `data/IwaraApi.kt` with its own OkHttp client and token field, used only to backfill metadata; `DownloadService` opening `VideoDao` directly to read and write library rows; and the player screen constructing an `OkHttpDataSource.Factory` in the UI. The same review found a conflict between the two written rules: [ui/AGENTS.md](../../app/src/main/java/io/github/amatsukiichika/iwaram/ui/AGENTS.md) said a screen does not reach into a repository, while [architecture.md](../architecture.md) names composition locals as the documented path by which screens consume repository `StateFlow`s, which six screens already do.

## Decision

There is one HTTP client. [IwaraClient](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/IwaraClient.kt) owns signing, base URLs, token injection and 401 refresh; the metadata backfill calls it through `IwaraClient.call { it.video(id) }`. `IwaraApi.kt` is deleted, and the two pieces that were only defined there move to the DTO file: `IwaraTag` to [IwaraDtos.kt](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/IwaraDtos.kt) and `parseIwaraDate` to [IwaraDates.kt](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/IwaraDates.kt).

`DownloadService` reads and writes library rows only through repository extensions in [IwaraRepositoryDownloads.kt](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/IwaraRepositoryDownloads.kt): `videoRowByUri`, `videoRowByIwaraId`, `upsertDownloadedVideo` and `deleteReplacedVideoRow`. A schema or query change now breaks the repository, not an unrelated service.

The media source factory is built in [PlayerDataSources.kt](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/PlayerDataSources.kt); the player screen passes its `Context` and keeps creating the `ExoPlayer` instance. Network stack details stay in `data/`, while player lifecycle stays in the UI where the composition owns it.

Reading repository-owned `StateFlow`s through `LocalAppRepository` remains the sanctioned UI access path, and [ui/AGENTS.md](../../app/src/main/java/io/github/amatsukiichika/iwaram/ui/AGENTS.md) now says so: screen code may read repository state, but must not open the database, construct a network client, or obtain and start a service itself. Remote API calls from `ui/` stay in `IwaraClientViewModel` and its `IwaraClient*.kt` extension files; those are the UI edge of the shared client, not a second stack.

## Alternatives considered

**Refactor every `LocalAppRepository` read into ViewModel state** — it would make the "no repository in a screen" sentence literally true, but it duplicates settings, download and session state per screen, adds a migration risk to player and settings code that already works, and contradicts the composition-local path that `architecture.md` documents as the design.

**Keep `IwaraApi.kt` beside `IwaraClient`** — metadata backfill is a repository job and the second client had drifted already: it had no 401 refresh and a token field written at construction time, which is exactly the behavior [0026](0026-session-token-storage-policy.md) removed.

**Give `DownloadService` a narrow DAO interface of its own** — it would still put a database handle in a service and copy the four queries, so the repository keeps one owner of the table regardless.

**Move the media source factory into the player screen behind an interface** — the UI would still pick transport details, and a test would still need OkHttp; building it in `data/` keeps the seam where the network stack already lives.

## Consequences

The boundary is about construction and writes, not reads: a screen may observe repository state, and that is what the six existing screens do. New UI code must not open the database, build a network client, or start a service from a composable.

`DownloadService` no longer depends on `AppDatabase`; its remaining coupling to library rows is the four named repository functions. A future download-related query belongs in that file rather than in the service.

A later API surface must extend `IwaraClient`; adding another Retrofit interface or client is the drift this record removes. The `IwaraTag` and `parseIwaraDate` moves keep their Kotlin package, so tests and call sites did not need import changes.
