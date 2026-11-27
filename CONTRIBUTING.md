# Contributing

Thanks for taking the time to contribute. Bug reports, feature suggestions and pull requests
are all welcome.

## Getting started

1. Fork the repository and clone your fork.
2. Install **JDK 17**, the **Android SDK** (platform `android-37.2`, build-tools `36.0.0`) and
   **Node.js 18+** (only for the documentation and repository gates; the app does not need it).
3. Point Gradle at your SDK:

   ```bash
   echo "sdk.dir=$ANDROID_HOME" > local.properties
   ```

4. Build and run the tests:

   ```bash
   ./gradlew :app:test
   ./gradlew :app:assembleDebug
   node scripts/check.mjs
   ```

## Definition of done

A change is ready when the conditions in [AGENTS.md](AGENTS.md#definition-of-done) hold, not when it compiles.

## Reporting bugs

Open an issue using the bug report template. Useful reports include the app version, your
device and Android version, and the steps that reproduce the problem. If the app crashed,
attach the log from the file described in `CrashLogger.kt` if you can retrieve it.

## Pull requests

- Direct pushes to `main` are blocked by branch rules — always open a pull request;
  CI must pass and a human reviewer must approve before merging.
- Squash-merge is the default: one pull request becomes one commit on `main`, which keeps the
  history linear and rollback simple. Use a merge commit only when the pull request's own commit
  sequence is worth keeping (for example a multi-step change reviewed commit by commit); never
  rebase-merge. Merging and deleting the branch remain manual steps for a maintainer, not for
  automation.
- Keep changes focused; one topic per pull request, and delete the branch when it merges.
- Follow the existing code style (official Kotlin conventions, 4-space indentation) and keep
  comments in Kotlin sources in Simplified Chinese, consistent with the rest of the codebase
  (build scripts keep English comments).
- Add or update JVM unit tests for any pure logic you change. `./gradlew :app:test` must pass.
- Update `CHANGELOG.md` under `[Unreleased]` when the change is user-visible: one sentence per
  entry, describing what the user sees rather than how it works.
- Run the checks locally before requesting review:
  `./gradlew verify && node scripts/check.mjs`.
- Fill in the pull request template. When an agent did part of the work, the AI usage line names
  the level and who made the decisions.

## Licensing

IwaraM is released under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0). By
opening a pull request you agree that your contribution is licensed under the same terms.
Third-party components keep their own licences; see [NOTICE.md](NOTICE.md) for the list.

## Decision records

Choices that the code cannot explain — a rejected alternative, a deliberately accepted cost, a
change to persisted data — get a short record in [docs/decisions](docs/decisions/README.md).
Records are reviewed for accuracy, not for length. Agent scratch plans do not belong in the
repository; keep them in the tool's own git-ignored directory.

## Documentation

Documentation rules, including the word-budget policy, live in [docs/AGENTS.md](docs/AGENTS.md#budgets-and-the-ratchet).

## Dependencies

Dependency versions are centralised in `gradle/libs.versions.toml`. Add new entries there
rather than hard-coding versions in `build.gradle.kts`. Dependency-update bots are disabled;
updates are applied manually and verified with a debug build.

## Signing

Release builds must be signed, but **signing material must never be committed**. The build
reads it from a git-ignored `keystore.properties` in the repository root:

```properties
storeFile=keystore/release.jks
storePassword=<your store password>
keyAlias=<your key alias>
keyPassword=<your key password>
```

To create your own key (contributors do not need one — only maintainers publishing releases do):

```bash
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -storetype PKCS12 \
  -alias iwara-m \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=IwaraM, O=IwaraM"
```

`keystore.properties` and `keystore/` are ignored by git; `keystore.properties.example` shows
the expected format. Keep a backup of the keystore and its passwords — losing them means
existing installations can no longer be updated in place.

## Releasing

Releases go out in two stages: a **pre-release (RC)** for testing, then the **stable**
release. Both are published by pushing a tag; the workflow picks the stage from the tag
itself — a tag with a semver pre-release suffix (e.g. `v0.2.0-rc.1`) publishes as a GitHub
pre-release, while a plain `vMAJOR.MINOR.PATCH` tag publishes as stable.

**1. Pre-release (RC).** Bump `app.versionName` in `gradle.properties` to the suffixed
version through a normal PR, then tag:

```bash
git tag v0.2.0-rc.1
git push origin v0.2.0-rc.1
```

The workflow runs the tests, builds the signed APK and publishes it as a GitHub
**pre-release**. Pre-releases are excluded from the "Latest" badge and the `/releases/latest`
install link, and the app shows the suffixed version name (`0.2.0-rc.1`) so testers can see what
they run. If the RC has problems, fix them on `main` and tag `v0.2.0-rc.2`; repeat until testing
passes.

**2. Stable.** Once an RC passes testing, bump `app.versionName` to the final version, move the
CHANGELOG entries out of `[Unreleased]` into `## [<version>] - <date>`, and open the section with
one sentence that says what the release is about, then:

```bash
git tag v0.2.0
git push origin v0.2.0
```

The stable release is published and automatically becomes the latest release — there is no
manual promotion step. Testers can upgrade in place from the RC: a pre-release shares the
stable release's `versionCode` (derived from the numeric base, ignoring the suffix) and the
same signing key, so the stable APK installs straight over the RC.

`gradle.properties` is the single source of truth for the version: the tag must match
`app.versionName` there (the workflow fails the build on a mismatch), and `versionCode` is
derived from `versionName` automatically (major\*1,000,000 + minor\*1,000 + patch, any
pre-release suffix ignored). The workflow runs the unit tests, builds the signed APK on the tag, verifies
the signature with `apksigner` and uploads the APK plus its `.sha256` checksum to a GitHub
Release. The release body is the version's CHANGELOG section (the `[Unreleased]` section for an
RC), followed by the APK name, its `.sha256` file, and a compare link to the previous stable tag;
it does not list commits, and an RC body carries a one-line pre-release note. It needs these
repository secrets: `KEYSTORE_BASE64` (the keystore file, base64-encoded), `STORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`.

**Before tagging a stable release**, check that the repository gates pass on the tagged commit
(`node scripts/check.mjs`), and that a persisted-data change has both a decision record and a
`### Changed` CHANGELOG entry that tells existing users what will happen to their data.

