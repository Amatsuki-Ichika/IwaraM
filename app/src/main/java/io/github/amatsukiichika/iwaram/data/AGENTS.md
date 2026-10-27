# Data layer rules

Applies to `app/src/main/java/io/github/amatsukiichika/iwaram/data/` and to anything it owns.
Standing orders and the definition of done are in the root [AGENTS.md](../../../../../../../../../AGENTS.md);
this file only adds what is specific to this directory.

## Room

- `app/schemas/*.json` are compiler output. Never hand-edit them; a schema change is a build
  artifact of an entity or DAO change.
- Any change to an entity, DAO query, index or column requires: a version bump in `AppDatabase`,
  a migration, and a migration test that opens the previous schema and asserts the result.
- No destructive fallback (`fallbackToDestructiveMigration`) on the production path. Losing the
  user's library index is not an acceptable "migration".
- Export the schema for every version introduced after schema export was enabled; v1 predates it
  and is recreated by hand in its migration test.

## Tokens, sessions and crypto

- Never log, print, or include a token in an exception message, a crash report or a test name.
- Tokens are stored through the existing encrypted storage; do not add a second storage path.
- Keep the Robolectric crypto workaround in `app/build.gradle.kts` intact. It makes the suite
  order-independent; deleting it reintroduces a flaky failure that is hard to attribute.

## Network

- One client, one API surface: extend `IwaraClient` and its DTOs rather than opening a second
  Retrofit instance.
- Map failures to the existing typed error text; the UI renders those, so a new raw exception
  string is a behavior change, not a detail.
- No real network in unit tests. Stub the response; do not call the live site.

## Repository

- `IwaraRepository` is the single source of truth for persisted data and settings. UI code never
  opens the database: add a repository function instead (see `observeVideos` / `deleteVideo`).
- Remote API calls are allowed from the two shared ViewModels (`VideoViewModel`,
  `IwaraClientViewModel`); screens go through them. A third place calling `IwaraClient` is a defect.
- Settings are exposed as `StateFlow`s. A new setting needs a default, a persistence path and a
  place in the settings screen; keep those in one change.
