# 0003 — Evolve the Room schema with additive migrations, never a destructive rebuild

Status: accepted

## Problem

The Room database `iwara_manager.db` holds the local library index (videos, tags, tag cross-references) and the watch history; losing it costs a full folder rescan, metadata requests to the site, and the user's watch positions and history.
The file reached version 4 with two public releases: the v1 to v2 step that introduced watch history predates the public Git history (the initial commit already declares version 2 and 0.1.0 ships it), while v3 (secondary indexes) and v4 (`fileModifiedAt`) landed inside the 0.2.0 cycle.
Users install over an existing app, so every step had to open the old file and keep its rows; a version bump without a matching migration is a crash at startup, or a silent wipe if a destructive fallback is configured.
The Room rules in [`data/AGENTS.md`](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/AGENTS.md) state the boundary: no `fallbackToDestructiveMigration` on the production path, because the library index is user data, not a cache.

## Decision

Every schema change is additive and ships with a `Migration` in [`AppDatabase.kt`](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/AppDatabase.kt), an exported schema for the new version, and a migration test that opens the previous schema and asserts the rows survived.
v2 adds `watch_history` keyed by a unique `videoKey` (the iwara ID when known, otherwise `local:<uri>`), shared by local and online entries and carrying its own title, author and thumbnail snapshot, so a history row does not depend on a library row that may be gone.
v3 adds `index_videos_iwaraId`, `index_videos_metaFetched_iwaraId` and `index_video_tag_cross_ref_tagId`; names and column order must match Room's generated expectation exactly, and `RoomIndicesMigrationTest` also pins with `EXPLAIN QUERY PLAN` that the indexes are used rather than decorative.
v4 adds `videos.fileModifiedAt INTEGER NOT NULL DEFAULT 0` (`0` = unknown) and makes download-time ordering two-stage: known modification dates sort normally, unknowns fall back to `addedAt` within a pinned group.
The migration chain is always registered in full (`MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4`), because Room validates the migrated file against the current terminal entity shape, not against the version the test was named after.

## Alternatives considered

**Destructive rebuild: bump the version, drop the tables, rescan and refetch** — the cheapest implementation, and exactly what `data/AGENTS.md` forbids. It would lose watch history for good and charge every user a round of metadata requests after each schema release.

**Sort by `addedAt` and skip `fileModifiedAt` (the behavior before v4)** — no migration and no new column, but a scan imports a folder in one batch, so every file in that batch shares nearly the same `addedAt` and the unmatched ones pin to the top of a download-time sort. The commit that added the column describes that as the bug it fixed.

**Keep watch progress on the `videos` row** — natural for local files, but online videos have no `videos` row, and history merges the local and online copies of one video through a single `videoKey`; the shared table is what makes the merge possible.

**Rely on the single-column `iwaraId` index for the pending-metadata scan** — an index exists either way, and the migration test accepts either query plan. It was not chosen because that query filters `metaFetched = 0 AND iwaraId IS NOT NULL`, and the composite index puts the selective flag first for the hot path.

## Consequences

Every entity or DAO change now costs a version bump, a migration, a test edit and a schema export; the existing migration tests must be extended to the new terminal version even when they cover an older step, which the test comments already note.
The v1 schema was never exported (export starts at v2), so `WatchHistoryMigrationTest` hand-replicates the v1 DDL; if an entity changes, that literal DDL does not regenerate itself and is a copy that can rot.
The benefit is that no supported upgrade path can silently delete user data, and a schema change that forgets its migration fails a test rather than a user's install.
Revisit the shape of a migration as often as needed; the no-destructive-fallback rule is the part that should not be revisited, because a future migration that seems impossible is a reason for an explicit data migration or a recorded acceptance, not a fallback.
