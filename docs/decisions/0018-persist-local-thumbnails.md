# 0018 — Local thumbnails live in private storage and repair themselves

Status: accepted

## Problem

A local video's cover was generated into the app cache (`cacheDir/thumbs`) and only its absolute path was stored in the library row. Android deletes that directory when the user taps "clear cache" or when the system runs short of space, and the library keeps the stale path: the card then falls back to the remote thumbnail URL, which a local-only video does not have, so the cover turns blank. A rescan did not help either, because it only refreshed metadata and file dates for rows it already knew; the only way back was to remove and re-add the folder, which also lost tags and watch history.

## Decision

Generated covers are stored in `filesDir/thumbs`, which only an explicit "clear storage" removes, and the directory is created before every write (`localThumbsDir`). Existing covers are moved out of the cache on startup by `migrateLegacyLocalThumbs`: for every row whose path points into the legacy cache directory and whose file still exists, the file is renamed into the new directory (copied when rename fails) and all moved paths are written back in one transaction; the legacy directory is removed once empty. The migration is best-effort — a row whose file is already gone is left for the scan to repair.

A stored path is considered usable only when it is non-blank and the file exists (`needsThumbnailRepair`). The scanner checks that predicate for every already-known row and regenerates the cover when it fails, batching the writes the same way it batches file dates; downloads do the same when they adopt an existing row whose cover was lost. Rows that are pruned or deleted also delete their cover file, but only when the path is inside the managed directory (`isInsideDir`), so a hand-edited path cannot make the app delete an unrelated picture.

## Alternatives considered

**Keep `cacheDir` and rely on scan repair alone.** The repair predicate alone would fix the blank cards, but every cache clear would force a full regeneration pass: `MediaMetadataRetriever` decodes several frames per video, so a large library would pay seconds of decoding after each cleanup. Private storage makes the loss rare instead of routine, and repair still covers the case where it does happen.

**Write covers next to the videos in the user's chosen folder.** The covers would survive even "clear storage", but on scoped storage that needs write access to the user's tree and would leave a visible `thumb_*.jpg` beside every video, which users do not expect from a video library.

**Store the JPEG bytes in Room.** One source of truth and transactional deletes, but it inflates the database and its backups, and every list row would load a blob through a cursor instead of displaying a file path.

## Consequences

Private storage now grows with the library at roughly one JPEG per video (quality 80, 480x270), which is small compared with the videos but no longer reclaimable by "clear cache"; a row carrying a cover outside the managed directory is never cleaned up by the app. The startup migration touches the database once per install and is best-effort: an interrupted move leaves a row pointing at a missing file, which the next scan repairs by regenerating the cover. Covers still disappear after "clear storage" (data wipe), and the recovery path for that is rescanning the folder.
