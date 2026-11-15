# 0017 — In-app updates follow a chosen release channel

Status: accepted

## Problem

The app had no update check: users had to watch the GitHub releases page, download an APK next to the app and install it by hand, and there was no way to stay on stable builds while testers wanted the newest prerelease. The release workflow publishes both stable releases and prereleases (a tag containing "-"), and GitHub's "latest release" endpoint returns only stable ones, so a single endpoint cannot serve both audiences.

## Decision

The updater reads the release list (30 per page) and filters it by channel: the stable channel accepts releases without the prerelease flag, the beta channel accepts both, and each then takes the highest semantic version rather than the first listed, because draft and prerelease entries and edited releases make the list order unreliable. Versions compare by major, minor and patch with the pre-release suffix below any release, matching the versionCode rule already used by the build (major * 10000 + minor * 100 + patch), so a beta user on 0.3.0-beta.2 is offered 0.3.0 when it ships.

An update is offered only when the remote version is newer; the app never downgrades. The channel, automatic-check switch, interval (each start, daily, weekly, or never) and ignored version are persisted settings owned by IwaraRepository. "Never" is not a stored interval: it is the switch turned off, and the frequency chips show it as the selected option while the last used interval stays stored, so picking a frequency turns checking back on in one tap. Automatic checks are silent and respect updateLastCheck plus the interval; a manual check always runs and reports the result. Ignoring a version hides that version until a newer one appears, and switching channels clears the ignored version and last-check time so the other channel is evaluated immediately.

Packages are downloaded only after the user confirms, then verified against the release's paired SHA-256 asset before installation. Installation uses a FileProvider URI and ACTION_VIEW with the package-archive MIME type, so the system installer handles consent; the app does not try to install silently.

## Alternatives considered

**Track only GitHub's latest release.** No channel logic, but beta testers could never receive a prerelease, and the user explicitly asked for a choice between stable and beta.

**Let the user paste a release tag or APK URL.** Flexible, but it moves version comparison and asset selection to the user, and a wrong asset (wrong ABI) fails only at install time.

**Download and install without user confirmation.** Silent updates are out of scope for a sideloaded APK: the platform still requires the installer screen, and starting a download on mobile data without consent is hostile.

**Compare versionCode only.** Matches the build's arithmetic, but cannot order prereleases within the same code, so a beta channel would either never update between betas or offer a downgrade from a beta to its own final release incorrectly.

## Consequences

Users choose a channel once and then get in-app checks, a manual check and an ignore action; stable users never see a prerelease, and beta users see prereleases and the final release that ends them. The check is a public GitHub API call with no token, so it is rate-limited per IP and a failure is reported as a check failure rather than as "up to date". The downloaded APK is cached under the app cache and must match the published SHA-256; a missing or mismatched checksum aborts the install. The updater depends on the release workflow continuing to attach the arm64-v8a APK and its .sha256 asset with the expected names.
