# 0016 — An author page is addressed by handle or UUID, never by an empty handle

Status: accepted

## Problem

Opening an author from the following list sometimes failed with a network error, while opening the same author from a video page worked. The profile entry point carried username and user id as two independent strings; a following edge or a cached account could have the id but an empty username, and the page built its request from the username alone, producing a profile URL with an empty path segment. The request retried on every recomposition of the page, and the page also fired a follow-state seed for the same user, so a single navigation could issue several failing requests and surface whichever error arrived first.

## Decision

IwaraProfileTarget collapses the pair into one target: a handle, a uid, and the content id to use, with empty or blank strings treated as absent. A target with only a uid skips the profile request entirely and renders from the cached follow state and the user id; a target with only a handle asks for the profile; a target with neither is unavailable and shows an error instead of issuing a request. All three places that construct the profile overlay in MainActivity go through iwaraProfileTargetOf.

The author page client also stops multiplying requests: profile requests retry once for 429 and 5xx, follow-state seeding is single-flight with a five-minute cooldown after a failure, user counts load sequentially and swallow individual failures, and author-page requests share a two-permit semaphore.

## Alternatives considered

**Make the profile request accept a missing handle and derive the handle from the UUID.** Requires an extra lookup with no better data than the cached follow state already holds, and a UUID-only page can render its header from the follow state immediately.

**Let the page retry the profile request until it succeeds.** Turns a permanent empty-handle bug into a retry storm and still cannot succeed, because the URL is wrong on every attempt.

**Guard only the following-list entry point.** Fixes the reported path but leaves the other two constructors able to produce the same target; the overlay is one type and should be built one way.

## Consequences

An author opened from any entry point now has one normalized target, and a UUID-only target renders without a failing request. The failure modes are distinct: fatal only when the target has neither a handle nor a uid, retryable only for 429 and 5xx. Retry and cooldown state is per process, so a cold start allows one fresh attempt; the follow-state cooldown means a failed seed does not retry for five minutes, during which a UUID-only page shows its cached state.
