# 0005 — Keep the files.iwara.tv signature salt as a source constant

Status: accepted

## Problem

The file endpoint behind playback and downloads signs its URLs: a request must carry `X-Version`, the lowercase SHA-1 hex of `<last path segment>_<expires value>_<salt>`, and without the header the endpoint returns only the preview and 360p tiers.
The salt is therefore required by every install of every client that wants full quality, and [`IwaraSign.kt`](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/IwaraSign.kt) holds it as a `private const val` next to the comment naming the three implementations with the same algorithm: yt-dlp, LoveIwara and IwaraDownloadTool.
The repository's standing orders include "do not put secrets, tokens, API credentials or personal data in source", so a reader who finds a literal that looks like a key can reasonably conclude the rule is being violated, or try to move it and break playback.
That tension has to be answered in prose, because the code cannot express it: the constant is load-bearing, visible, and will be flagged by any secret scanner.

## Decision

The salt stays a compile-time constant in source.
It is a protocol constant shared by the server and third-party clients, not a user credential: the source comment states that yt-dlp, LoveIwara and IwaraDownloadTool all implement the same signing formula, so the value is published outside this project and knowing it authorises nothing beyond the public playback protocol.
The `AGENTS.md` rule protects per-user secrets and personal data, whose exposure differs per person; this value is the same for every user and grants nothing extra, so the rule does not apply to it.
The project operates no backend that could hand the value out, and the app has no runtime discovery path, so a hardcoded literal is the only mechanism that works on a clean clone and offline.
The cost is accepted explicitly: if the server rotates the salt, the constant changes in code and ships in a release; there is no remote update, and a stale client keeps working at the reduced quality tiers instead of crashing.

## Alternatives considered

**Drop the header and accept the preview/360p tiers** — removes the constant and the signing code entirely, at the cost of the feature the app exists for: the source comment states that without the header the endpoint returns only those two tiers, so full-quality playback and downloads would be gone.

**Treat it as a credential and move it out of tracked source — BuildConfig, a local properties file, or the Keystore** — the move a secret-scanning reviewer would request. It buys nothing: the value is already implemented by three open-source clients, so hiding it does not raise the cost of discovery, while it adds setup that a clean clone does not have and keeps a default build from playing full quality. It also cannot be a per-user secret, because the server expects the same salt from every client.

**Fetch or derive the salt at runtime from the site** — no such path exists in the app, and it would mean parsing or executing the site's JavaScript on every client to recover a value that has not changed. The repository contains no attempt at it, so this is recorded as the alternative a future maintainer may propose, not as something tried and measured.

## Consequences

Rotating the salt is a code change plus a release; users on older builds fall back to the reduced tiers (assuming the server treats a wrong value like a missing one), and the fix reaches them only when they update.
The literal will keep attracting secret scanners and well-meaning cleanup; the comment above it and this record are the answer, and deleting the comment would turn the next cleanup into a defect.
No test pins the literal — `IwaraSignTest` asserts determinism, length and hex shape — so rotation touches the constant, not the test suite.
If the value ever becomes per-user, time-bound, or sufficient to authorise more than a signed file URL, it is no longer a protocol constant and this record must be superseded.
