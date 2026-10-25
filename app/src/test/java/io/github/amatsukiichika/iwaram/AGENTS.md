# Test rules

Applies to `app/src/test/java/io/github/amatsukiichika/iwaram/`. Standing orders and the
definition of done are in the root AGENTS.md; this file only adds what is specific to tests.

- JUnit 4. A test name states the behavior, not the method: `videoSorterPutsUnmatchedVideosLast`,
  not `testSort`. The suite is documentation of behavior.
- A test asserts observable state (return value, DAO result, emitted state), not that a private
  helper was called.
- Robolectric only when an Android API is unavoidable. The build already disables Conscrypt so the
  suite stays order-independent; do not add JVM-wide state that makes it order-dependent again.
- No network, no real clock, no sleeping for assertions. Inject a value or use a fixed clock.
- Migration tests open the exported schema of the previous version and assert the migrated shape.
- Each new pure-logic file gets its test in the mirrored package in the same change.
- When behavior changes, change the test and say why in the pull request; do not silently delete
  the assertion that caught it.
