# 0030 — Saved file name length and byte budget

Status: accepted

## Problem

[FileNameSanitizer](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/FileNameSanitizer.kt) used to truncate the whole composed name at 120 code points, so a long Iwara title cut away the trailing `.mp4` extension, and it had no byte limit at all: 120 CJK code points are 360 UTF-8 bytes, above the 255-byte limit for a single file name component on ext4 and on Android shared storage, so MediaStore `createFile` can reject such a name after the download has already started.

## Decision

The sanitized name keeps at most 120 code points and at most 200 UTF-8 bytes, and the extension is protected: the stem is truncated first, the extension is detected as the suffix after the last dot when it is 1..10 ASCII alphanumerics with at least one letter, and the two are re-joined, so the saved name never loses its type; an empty or all-illegal title falls back to `video` and the existing illegal-character and control-character replacement is unchanged.
The composed download name is built from a fixed prefix `[iwaraId] `, the sanitized title and a fixed suffix ` (quality).mp4`, and the title alone is shrunk to the remaining code-point and byte budget, so the scanner's `[id]` marker, the quality marker and the `.mp4` extension survive any title length.
200 bytes is chosen because the common per-component limit is 255 bytes and the remaining headroom covers the suffix that a media store or a file manager may append to resolve a collision, while still allowing about 65 CJK characters of a readable title.

## Alternatives considered

**Truncate by UTF-8 bytes only** — one limit and no double bookkeeping, but it would drop the existing 120-code-point rule that keeps names readable in the UI and logs while still needing a byte check for the filesystem, so the two limits would just move into one place; rejected in favour of keeping both and testing both.
**Rely on the 255-byte filesystem limit and skip the byte cap** — the failure would only appear when `createFile` runs, far from the name composition, as a generic storage error for one specific title; rejected because a name that cannot be saved must be shortened before the download starts.
**Hash or encode an over-long title** — it always fits, but the file manager, the search-by-name workflow and the quality marker all stop being human-readable; rejected.
**Move the id and quality markers out of the file name** — it would remove the shape that the library scanner, resume matching and `VideoQuality.qualityLabelOfFileName` currently parse, breaking download resume and quality display for existing files; rejected, so the markers are treated as fixed and only the title shrinks.

## Consequences

Long titles now keep the extension and both markers: for example 200 CJK characters plus `.mp4` become 65 characters plus `.mp4` (199 bytes), where the old code produced a truncated name without an extension.
Every saved name stays within 200 bytes, leaving more than 50 bytes of headroom under the 255-byte component limit; a future budget change must keep the markers intact and update `FileNameSanitizerTest` and `DownloadTargetTest`.
The limits live in `FileNameSanitizer` as `MAX_CODE_POINTS` and `MAX_BYTES`, and `DownloadService` calls `sanitize` before `createFile`, while `DownloadTarget.downloadFileName` calls `compose`, so both the direct and the composed path keep the same budget.
