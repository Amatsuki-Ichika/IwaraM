# 0038 — Lower online playback latency with explicit buffering, a warmer pool and signed-URL prefetch

Status: accepted

## Problem

Opening an online video costs three serial network round trips before the first frame: the video detail (it carries the signed `fileUrl`), the quality list (`IwaraSign.fetchQualities`, an extra request with a computed `X-Version` header), then the first media segment. Every entry pays the full chain because the signatures expire in minutes, so nothing is reused across plays. The player ran on media3's conservative defaults (50 s minimum buffer, 50 s maximum, 2.5 s before start, 5 s before resuming after a stall), which waits on healthy networks, and a zero back buffer makes every backward seek refetch. All HTTP clients shared a dispatcher with the default 64/5 limits and a five-connection idle pool, so the playback segment competes with thumbnails and with the JSON calls that are still resolving on the same screen. The related list already names the likely next video, but its signed URLs are resolved only after the user taps it.

## Decision

`PlayerScreen` builds its `ExoPlayer` with an explicit `DefaultLoadControl`: 15 s minimum buffer, 60 s maximum, 1 s before start, 2 s after a stall and a 15 s back buffer. The values sit in `PLAYBACK_*` constants next to the screen with their rationale; the super-resolution rebuild path is untouched.

`IwaraClient` keeps one shared `ConnectionPool(16, 5 min)` and one shared `Dispatcher` with `maxRequests = 32` and `maxRequestsPerHost = 8`. Every HTTP client in the app still reuses that pool and dispatcher; clients differ only in interceptors and timeouts.

`data/SignedUrlCache.kt` caches resolved qualities per `(videoId, quality)`, with the URL's own `expires` minus a 30 s safety margin as TTL (10 min fallback when no `expires` is present, 256-entry cap). `loadQualities` serves a hit without touching the network and coalesces concurrent loads for one video into a single request, so a prefetch and a resolve share one call. `resolveOnlineUrl` consults the cache before fetching. `loadRelatedVideos` prefetches exactly one candidate (the first related video with a different id that is not cached yet) in the background after delivering the list; a prefetch failure is silent and never blocks the list callback or playback. The player's media client evicts a URL from the cache when the CDN answers 403/404/410, so the existing auto-retry re-resolves instead of retrying a dead signature.

## Alternatives considered

**Only raise the default buffer durations** — the defaults are already tuned for weak networks; raising them shortens nothing on the critical path and adds data, memory and time to discard on a quality switch. The wait comes from the start threshold and the serial resolve chain, which buffering does not touch.

**Cache the whole `video(id)` detail response** — the detail carries account state (liked, author follow) and a signed `fileUrl`; both must stay current. Only the quality list is safe to reuse for minutes.

**Persist signed URLs to disk** — signatures are valid for minutes, so a disk cache is stale by the next app launch while adding cleanup and migration surface. A process-memory cache matches the data's lifetime.

**Prefetch several related videos or the whole related list** — multiplies CDN traffic for videos the user may never open. One candidate covers the common "next episode" tap at bounded cost.

**Raise the pool and dispatcher further (e.g. 64/16)** — playback needs two to four connections; past roughly 32 the app competes with itself and looks like a burst to the CDN, and the per-host cap is what protects the origin. Eight per host already lets one screen's JSON and media requests overlap.

**Keep the 2.5 s start threshold and preload the first segment elsewhere** — media3 already loads ahead; the start threshold is the wait the user feels. Preloading outside the player would duplicate the range request and the URL-signing path.

## Consequences

A cache hit removes one network round trip from the open path, and playback starts after 1 s of buffer instead of 2.5 s. It now keeps at most 60 s buffered (more memory and mobile data per minute than 50 s) and may rebuffer sooner on very weak networks because the safety margin is 15 s instead of 50 s, while recovery takes 2 s instead of 5 s. The cache is process memory only, bounded to 256 entries and empty on process death; signed URLs are not account-bound, so it is intentionally not cleared on sign-out. Prefetch can be wasted when the user picks another video, which is accepted for one candidate. Revisit the buffer levels if rebuffer reports rise on mobile data, and the prefetch rule if the CDN starts rate-limiting burst requests.
