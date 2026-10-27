# 0006 — Account-scoped state is cleared on every identity change

Status: accepted

## Problem

The browse screen holds state that only makes sense for the account that produced it: the
subscription feed and its trust flag, both favourites pagers, the playlists list, the comment pane,
and the follow/like snapshots. Nothing cleared that state when the identity changed, so signing in
as someone else showed the previous account's playlists, and the favourites pager appended the new
account's page onto the old account's list. Session expiry was worse: it rewrote the session
message and cleared nothing.

## Decision

One clearing point, `IwaraClientViewModel.resetAccountScopedState()`, is called from three places:
logout, the session-expired listener, and a successful login. It resets the subscription pager and
trust flag, both favourites pagers, the playlists state, the comment pane, the follow/like
snapshots and the follow-seeding flag.

Search query and filter pills are deliberately **not** part of the account scope: they belong to
the public feed, and dropping them on logout would lose work the user is in the middle of.

The offline playlists cache is keyed by user id (`playlists_cache_<userId>`) and the legacy global
key is dropped on startup; an empty user id produces no key at all, so an unknown account neither
reads nor writes a cache. The expired-session callback may arrive on an OkHttp thread, so the reset
is dispatched through `viewModelScope` before touching Compose snapshot state.

## Alternatives considered

**Clear everything in `logout()` only.** Cheaper, and it fixes the visible logout case — but token
expiry is the path that actually happens in the field (401 during a refresh), and it never calls
logout. Leaving it uncovered would have fixed the symptom users report least.

**Reset lazily: on the first request after a session change.** Avoids clearing state that is about
to be replaced anyway, but it moves the correctness argument into every call site and leaves the
window where the previous account's data is on screen.

**Version the cache instead of partitioning it.** One slot plus an owner id would also work, but
reading it still requires knowing who the caller is, so the blank-id rule would have to be
reinvented at the read site.

## Consequences

Any future account-scoped slot must be added to `resetAccountScopedState()`; forgetting one is a
new leak, and nothing mechanical catches it. The reset runs on the main thread by design, which
means a session expiry is reflected one dispatch later than the session flag itself — visible only
as a single frame of stale data, and the flag already hides the account UI.
