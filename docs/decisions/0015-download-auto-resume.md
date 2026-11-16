# 0015 — Downloads resume themselves after a failure or a restart

Status: accepted

## Problem

A download that lost the network failed on the spot and waited for the user to press "re-download". A process restart left queued tasks in the pending cache but nothing started them, so after reopening the app a transfer that was running before stayed dead until found in the download sheet. The download card also repeated the same state twice and padded each entry with a full-size card, so a short queue filled the sheet.

## Decision

Automatic retry is a policy in DownloadPolicies: three attempts total, delays of 3, 10 and 30 seconds, retried for timeouts, IO failures, HTTP 408, 429 and 5xx, not retried for cancellation, storage-full or a definitive 4xx. The retry runs inside the same Job and the same foreground notification, so the task never re-enters the queue and the notification count does not change; the service watches pause and cancel flags during the delay and abandons the retry immediately. Only the final failure writes the FAILED pending record, with the attempt count, so the card can say how many automatic retries were spent.

Startup auto-resume is a plan, not a sweep: resumePlan selects only records that were queued or running when the process died, skips anything the live task list already has, and is gated by the new autoResumeDownloads setting. The activity restores the pending records into the task list first so the sheet shows them, then re-resolves each video's signed URL and starts it; paused and failed records are left for the user because those are explicit choices. The download card is a compact row: title and quality/percentage/status share two lines, redundant "received" and duplicate state text are gone, and spacing and buttons shrink.

## Alternatives considered

**Retry by re-enqueueing the task from scratch.** Simpler to reason about, but it resets the notification, can race the pending record write, and makes the attempt count live in two places.

**Resume every pending record on start.** Recovers more transfers, but a user who paused a download to save bandwidth would find it running on the next launch, and a permanently failed URL would retry on every start.

**Wait for the user to open the download sheet before restoring.** Keeps startup light, but a transfer that was running is exactly the one the user expects to continue, and requiring a visit to a sheet to discover a dead download is the bug being fixed.

**Keep the card as-is and only add the resume logic.** Less UI churn, but the card's duplicated state line and full-size padding are what makes the queue hard to scan, and the two changes ship from the same screen.

## Consequences

A failed transfer recovers from a transient network drop without user action, and a restart picks up the transfers that were actually running. The max automatic retry count is three; after that the card shows the failure with the retry count and waits for a tap. Automatic retry never applies to a paused download or to storage-full, and the startup sweep only issues a new request for records whose state says they were meant to run. The setting is on by default and can be turned off in Settings > Storage.
