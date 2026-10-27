# 0004 — Target Android 16 (API 36) and handle the dataSync foreground-service limit

Status: accepted

## Problem

Both long-running jobs — the download queue in `DownloadService` and the folder scan in `ScanService` — run as `dataSync` foreground services, declared with that type in `AndroidManifest.xml` and holding `FOREGROUND_SERVICE_DATA_SYNC`.
Starting with Android 15, an app targeting that release or later gets a six-hour-per-24-hour cap on `dataSync` foreground service runtime; when the cap is reached the system calls `Service.onTimeout()` and then allows only seconds before the process is treated as misbehaving.
At `targetSdk = 34` the app was exempt, so a long queue or a slow folder scan had no system-imposed ceiling; raising the target makes the ceiling real, and ignoring it turns a large download into an ANR or an apparently random interruption.
Nothing in the app can lift the cap, so the only choice is what the app does when it is reached.

## Decision

The build targets API 36, moving from 34 in one step, so Android 15 and 16 behavior changes arrive together; the comment above `targetSdk` in `app/build.gradle.kts` enumerates the three the change had to handle: edge-to-edge enforcement, predictive back defaults, and the dataSync limit.
`DownloadService.onTimeout` marks every in-flight job paused, keeps queued jobs in the download list, posts a notification saying the system limit was reached and that "continue" resumes, then removes the foreground notification and stops itself immediately.
The pause path is the existing one, so partial files and queue records survive and the existing resume flow restarts from the partial file; a process killed without `onTimeout` still recovers through the older interrupted/queued path.
`ScanService.onTimeout` does the minimum: drop the foreground notification and stop, which cancels the scan coroutine; a scan is not resumable, and the user restarts it with the existing scan action.
Both overrides use `onTimeout(startId: Int)` from API 35 and are inert on older devices.

## Alternatives considered

**Stay on `targetSdk = 34` and keep the exemption** — genuinely available, because the app is distributed as a sideloaded APK from GitHub Releases and no store deadline forces the bump. It is rejected because the newer behaviors do not disappear by waiting: edge-to-edge and predictive back were already implemented, the remaining work was the timeout handler, and deferring only makes the eventual jump larger and later.

**Raise `targetSdk` and skip `onTimeout`, relying on the existing interrupted-download recovery** — that recovery already exists for process death, so this would mostly work. It is rejected because a hard stop throws away the orderly write close and the explanation: the user would see downloads become "interrupted" instead of "paused" with a notification naming the system limit. The code comment is also explicit that `onTimeout` leaves only seconds, so the graceful path has to stay cheap.

## Consequences

Downloads stop after six hours of dataSync time in a 24-hour window even on a charger; resuming is manual by design and costs the user one tap.
A scan that hits the cap is lost and restarts from the beginning; scanning is short enough that this is accepted rather than engineered around.
`onTimeout` must stay free of slow work — no database compaction, no network — and must call `stopSelf()` promptly; adding work there is a regression against the platform's grace period.
If the platform cap or callback contract changes, this record is the place to revisit, and the app still has no way to ask for more time.
