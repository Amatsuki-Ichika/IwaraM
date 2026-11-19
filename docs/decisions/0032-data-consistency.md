# 0032 — Data consistency between the scanner, downloads and metadata

Status: accepted

## Problem

Three paths could disagree with the files on disk. The scan prune asked `DocumentFile.exists()`, which swallows query exceptions and returns `false`, so a provider error looked like "file deleted" and the row was removed. The download's "already in the library" branch treated any file with `length > 0` as complete, so a half-written file that the scanner had already indexed was finished instead of resumed. Metadata backfill used a REPLACE-style upsert that could reinsert a row after the scanner had deleted it and overwrite fields written concurrently (file name, thumbnail path, modification time). Pause and cancel were two one-shot sets consumed by four code paths in an order that was not specified.

## Decision

The scanner asks a three-valued question: [documentExists](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/IwaraRepositoryScan.kt) returns `Boolean?`, where `null` means the provider could not answer. `rowsToPrune` removes a row only when the answer is `false`; an unknown answer keeps the row and the next scan retries.

Completeness is explicit: `libraryFileIsComplete(fileLength, pendingTotal)` in [DownloadPolicies.kt](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/DownloadPolicies.kt) requires a positive file length and, when the pending record knows the total, a length of at least that total. When the check fails, `runDownload` falls through to the resume path instead of calling `finishDownload`, and an unknown total keeps the old permissive behavior because there is nothing to compare against.

Metadata is applied, never resurrected: `VideoDao.applyFetchedMetadata` runs an `UPDATE ... WHERE localUri = ?` for the metadata columns and returns `false` when no row matches, in which case tags and the cross-reference table are left alone. A row deleted while the request was in flight therefore stays deleted, and fields that downloads write are not part of the update.

Download control is a small state machine: [DownloadControl](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/DownloadControl.kt) maps a video id to one intent (`CANCEL` or `PAUSE`). Cancel wins over pause, a later pause does not downgrade a pending cancel, and each intent is consumed once by the code path that acts on it; the persisted task state remains in `PendingDownload` and `DownloadState`.

## Alternatives considered

**Recreate the row when metadata arrives** — this is the resurrection the review found; the scanner's deletion is the authority, and a metadata response can arrive minutes late.

**Treat a provider exception as "file exists" without distinguishing it** — that is what the old code intended but not what it did: it called an API that hides the exception behind `false`, so the intent could not survive the call. A three-valued result makes the distinction explicit and testable.

**Reconcile rows and files periodically instead of fixing the prune** — a periodic sweep would still need the same existence answer, and would extend the window in which a wrong deletion is visible.

**Track pause and cancel as two sets with a documented consumption order** — the order was implicit in four call sites and easy to break; a single value with a defined priority has one place to read.

## Consequences

A provider that keeps failing the existence query keeps rows that point at missing files: the library can show a stale entry until a scan resolves the question, which is the safe direction compared with deleting playable rows.

Metadata for a video deleted mid-request is dropped, so a deleted video is not brought back by a late fetch, and the metadata request cannot overwrite `fileName`, `localThumbnailPath` or `fileModifiedAt`.

A half-written library file is downloaded to the recorded total before it is reported complete. When the total is unknown, completeness still falls back to "non-empty", the same protocol limit that [DownloadPolicies](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/DownloadPolicies.kt) documents for `isDownloadComplete`.

`DownloadControl` is in-memory only; a process death loses pending intents, while persisted pause/cancel outcomes are already recorded by the download paths that act on them.
