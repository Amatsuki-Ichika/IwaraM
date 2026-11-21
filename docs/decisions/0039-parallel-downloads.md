# 0039 — Download two files at a time and back off on rate limiting

Status: accepted

## Problem

The service keeps one long-lived worker draining a `Channel.UNLIMITED`, so queued downloads run strictly one after another no matter how fast the connection is: the user who queues five videos waits for four full serial transfers, and a slow file blocks a fast one behind it. The HTTP stack already has a connection pool and a per-host request cap that allow more, and the download buffer was 64 KB, which makes even a single transfer spend a noticeable share of its time in `ContentResolver` read/write round trips rather than in the network. Simply raising the buffer helps one file but does not shorten a queue, and raising concurrency without a control loop risks a burst that Iwara's CDN answers with 429/403, which is also what the existing per-request retry path would then hammer.

## Decision

`onCreate` starts `MAX_PARALLEL_DOWNLOADS = 2` workers, each running the existing `drainDownloadQueue` loop with the same pause/cancel/startId protocol; a single file is still written by exactly one worker, so Range resume, pause, cancel and the `onIdle` `stopSelf(lastStartId)` contract are unchanged (the idle check also waits for `activeJobs` to drain, so one worker going idle cannot stop the service while the other is downloading). `DownloadConcurrency` gates each worker with a slot count: it starts at two, `onRateLimited` drops the limit to one and opens a process-wide backoff window of at least three seconds (or the existing retry delay, whichever is longer), and `onSucceeded` clears the window and raises the limit by one per successful download, so concurrency recovers gradually instead of slamming back to two. A 429 or 403 response therefore throttles the whole download queue, not just the failing file, while the failing file still goes through the existing re-sign/retry loop. The read buffer grows from 64 KB to 256 KB; progress updates remain throttled by `PROGRESS_THROTTLE_MS` and are independent of the buffer size. There is no user-facing concurrency setting.

## Alternatives considered

**Split one file into concurrent range requests** — would give the biggest speed-up for a single large file, but it breaks the existing resume/pause/cancel model (one worker owns one `DocumentFile` and appends to it), needs multipart range support from the CDN, and turns a 2-file queue into a much larger state machine. Out of scope for a first parallel step.

**A fixed higher concurrency (three or more)** — SAF writes are serialized by the document provider and each open file holds a descriptor and a notification slot; more workers mostly add contention and make the app look like a burst to the CDN, which is the behaviour that triggers the rate limiting this change must survive.

**Expose a concurrency or buffer setting** — a setting most users cannot reason about, and the failure it prevents (rate limiting) is not visible to them. Adaptive behaviour with no switch is the smaller product surface; it can be revisited if users report slow queues on fast connections.

**Only increase the buffer, keep one worker** — improves per-file throughput slightly but leaves queue latency untouched, which is what the request is about.

**Spawn a separate service or worker per download** — multiple foreground services fight over the single progress notification and the startId/stopSelf contract that keeps the process alive exactly as long as the queue needs it.

**Retry a rate-limited request without lowering global concurrency** — two workers keep hitting the same throttle window and each retry adds load, so the backoff gets longer in practice. The gate makes the whole queue wait and then recover.

## Consequences

Up to two files transfer at once, so a queue of several videos finishes in roughly half the wall time when the network allows, and per-file throughput improves from the larger buffer. While both workers write through SAF, each file's IO can interleave with the other's and the device sees two concurrent large writes; on a slow storage provider a single download can therefore be a little slower than before. A 429/403 pauses all download dispatch for at least three seconds, which delays unrelated files even when only one was rate-limited. The gate is process memory: after a process restart concurrency starts at two again, and the backoff window is not persisted. Queue order of completion is no longer the enqueue order because two workers race, although each worker still takes jobs FIFO; per-video pause/cancel still works because those intents are keyed by `videoId`. Revisit the limit if devices with slow SAF providers show worse per-file throughput, and the recovery step if rate limiting becomes frequent.
