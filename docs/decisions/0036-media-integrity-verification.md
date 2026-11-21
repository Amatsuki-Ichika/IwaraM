# 0036 — Verify downloaded files structurally and repair a broken one once

Status: accepted

## Problem

A download is committed as soon as the byte counter agrees with the server's declared length, and a missing or short file is merely resumed later. That accounting cannot see what actually arrived: a captive-portal or 403 HTML page of the right length, a JSON error body, a body cut in the middle of an `mdat` whose length header lied, or two `moov` boxes from a bad merge all satisfy "count matches" and go straight into the library. The file then enters `DownloadHistoryEntry` and the video library as a finished download, and every retry path trusts the file length, so the same bad bytes are "already complete" forever. The download history also has no place to record that a file was checked, or which length was expected, so neither the UI nor a later repair pass can tell a checked file from an unchecked one.

## Decision

`data/MediaIntegrity.kt` walks the top-level MP4 box chain as pure logic, with no Android dependency: it takes the file length, the expected length and an `readAt(offset, size)` callback, so tests drive it with synthetic bytes and the repository drives it with SAF file descriptors. It rejects a file whose declared length disagrees with `Content-Length` (`Truncated`/`ExtraBytes`), whose first bytes look like HTML/JSON text, whose first box is not `ftyp` (filler boxes `free`/`skip`/`wide` allowed), whose `moov` is missing or repeated, which has no `mdat`, whose box sizes overrun the file, and it understands `size == 1` (64-bit `largesize`) and `size == 0` (extends to EOF). A read failure yields `Unknown` rather than a guess. `IwaraRepository.checkDownloadedIntegrity(videoId)` resolves the file from the download history (falling back to the library row) and returns `Broken(Missing)` when it is gone; `DownloadHistoryEntry` gains nullable `totalBytes`, `verifiedAt` and `verifyNote` fields with defaults, so old JSON stays readable. `finishDownload` runs the check after the library upsert and before `STATE_DONE`, stores the verdict through `markDownloadVerified`, and on `Broken` calls `DownloadService.requestRedownload`, which deletes the old file and enqueues the same video with `replaceExisting = true`; a `CopyOnWriteArraySet` allows exactly one such automatic repair per `videoId` per process, after which the user is just notified. `Unknown` never triggers a repair.

## Alternatives considered

**Use `MediaExtractor` and require a playable track** — this is the only way to prove a track exists, but it needs a `Context`, is slower than a few header reads, and its failure modes mix "file damaged" with "codec not supported on this device", which would delete files that play fine elsewhere. `NoTrack` stays in the reason enum for a later optional L3 pass instead.

**Compare a checksum with the server** — Iwara publishes no digest for media files; the only server-provided number is `Content-Length`, which is already checked. A self-computed checksum would detect bit rot inside `mdat` but cannot be compared with anything authoritative.

**Trust the download's own "complete" flag and never verify** — that is the current behaviour and it is exactly the failure mode: the flag is computed from the same byte count the corrupted file satisfies.

**Re-download on every `Unknown`** — a temporary SAF read failure would delete a healthy file and spend the user's data. Uncertainty must not be destructive.

**Repair indefinitely** — a server-side or storage-side cause makes every re-download identical, producing a download loop that eats data. One attempt per process is enough to fix transient corruption; a second failure is a user-visible notification.

**Keep the broken file and only flag it in the UI** — leaves an unplayable file in the library and forces the user to do the repair the app can do silently.

**Store the expected length only in the pending-download record** — pending records are deleted on completion, so a later verification (or a future library-wide scan) would lose the number it is comparing against.

## Consequences

A completed download now costs a handful of small SAF reads (one per top-level box) and cannot finish as `DONE` without a verdict; a bad file is repaired automatically once, at the price of one extra full download in the rare case. The old file is deleted before the repair, so the library briefly loses the entry until the replacement finishes, and a repair that cannot resolve a signed URL leaves a failure message instead of silently retrying. Verification is structural only: bit rot inside `mdat`, wrong codec and wrong content that happens to be a well-formed MP4 are not detected, and a file already in the library from an older version is only checked when `checkDownloadedIntegrity` is called for it. The history JSON gains three nullable fields; no migration is needed because absent keys deserialize to the defaults.
