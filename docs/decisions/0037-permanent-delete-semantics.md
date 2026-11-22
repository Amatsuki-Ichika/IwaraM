# 0037 — Library removal and permanent deletion are separate operations

Status: accepted

## Problem

The local library offered no way to remove an entry without guessing what the long press would do: the card opened the manual-iwara-ID dialog, and that dialog's "remove from library" action called [VideoViewModel.removeLocalSources](../../app/src/main/java/io/github/amatsukiichika/iwaram/ui/VideoViewModel.kt), which deleted the Room row even when deleting the file had failed. A user who chose "delete the file too" saw the entry disappear, believed the disk was clean, and the next scan brought the video back; the SAF failure was returned to the caller but the row was already gone.

Permanent deletion also has to coexist with three other owners of the same video: the player may be reading the file, a downloader task may hold a pending record for the video id and re-register it after deletion, and one logical video can have several local source files, each its own row.

## Decision

The library exposes two distinct operations from the same long-press action sheet in [LibraryScreen.kt](../../app/src/main/java/io/github/amatsukiichika/iwaram/ui/LibraryScreen.kt): "remove from library" deletes the row only and keeps the file (a rescan restores it), and "delete permanently" deletes the file, its cover and the row and cannot be undone. Each opens a second confirmation; only permanent deletion is styled as destructive.

The execution order is an invariant encoded in [LocalDeletion.kt](../../app/src/main/java/io/github/amatsukiichika/iwaram/ui/LocalDeletion.kt): the file is deleted first, and the row is deleted only after that succeeds. A file failure aborts the whole operation, keeps the row and returns the user-facing reason. `planLocalDeletion` computes `DeleteRowOnly`, `DeleteRowAndFiles` or `Abort`; `executeLocalDeletion` and `executeLocalDeletionBatch` perform it through injected suspend callbacks, so the JVM tests cover every branch without a SAF provider.

Permanent deletion of the source currently open in the player aborts with "exit the player first". Playback has no repository-owned state, so [LocalPlayback.kt](../../app/src/main/java/io/github/amatsukiichika/iwaram/ui/LocalPlayback.kt) holds a process-level `currentUri` registered by the player details section while it is composed; the guard is best-effort by construction (see Consequences).

Before deleting rows for a video id, the view model cancels its downloader task through `DownloadService.cancel`, deletes the partial file recorded in the pending download, removes the persisted pending record and finally removes the in-memory task. Only then does the deletion plan run. The row itself is removed by the repository's existing `deleteVideo`, which also deletes tag associations and the local thumbnail inside the app's thumbnail directory.

Only the long-pressed version is deleted. When the same video has other source files, the confirmation names how many remain, and those rows are untouched.

## Alternatives considered

**Keep deleting the row when the file deletion fails** — this is the behavior being fixed. It makes a failed permanent deletion silent and lets the next scan resurrect the entry, so the user cannot tell whether the disk was cleaned.

**Delete the row first and retry the file later** — a retry queue for orphaned files would need its own persistence and a background owner, and until it runs the disk is not what the UI claims. File-first failure leaves at worst one stale row, which the next scan or a manual retry resolves.

**Reuse the player's source sheet with its "also delete the file" switch** — that sheet manages versions of the playing video, not library entries: it has no cover, pending-download or other-version context, and it is reachable only from the player. The long press on the library card is where the user thinks about a library entry.

**Introduce repository-owned "now playing" state** — the data layer owning playback would let the guard be exact, but playback currently lives in the Compose player screen and the repository is not a player owner; adding that state is a larger change than the deletion feature and would need its own decision.

**Use a single confirmation dialog per card instead of an action sheet** — one dialog cannot express three operations with different consequences; the sheet carries the subtitle that tells the user whether the file is kept or destroyed.

## Consequences

A permanent deletion whose file deletion fails keeps both the file and the row and reports why; the user can retry, and no failure path loses data silently.

The playback guard is best-effort: [LocalPlayback](../../app/src/main/java/io/github/amatsukiichika/iwaram/ui/LocalPlayback.kt) is a volatile process-level value, and the details section that registers it is not composed while the player is fullscreen, so a deletion that somehow ran concurrently with fullscreen playback would not be blocked by it. The library is covered by the player overlay, so permanent deletion cannot be started from the library while the player page is open; the guard protects the paths that do share the process. A future decision that gives the player a repository-owned current-media state should replace `LocalPlayback` rather than extend it.

Deleting a video whose download task is queued or running cancels that task first; the downloader's own cancel path is idempotent, so either side cleaning up first leaves no partial file or pending record behind.

Deleting one version leaves the other source files of the same video in the library. The confirmation states this so the user does not read "delete permanently" as "delete every quality".

The cover is deleted through the repository's existing thumbnail cleanup and only inside the app's thumbnail directory; a failure there is ignored by that helper, so a stale thumbnail file is possible but a library row is never kept solely because its cover could not be deleted.
