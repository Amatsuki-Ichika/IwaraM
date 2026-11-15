# 0021 — Compose the player's heavy content after the entry slide settles

Status: accepted

## Problem

Tapping a video slides the player page in over 320 ms. The whole page tree — the PlayerView
inflate/attach, details, comments, related list — composed in the first frame of that animation
together with the player setup, so the main thread missed frames exactly during the transition.
The user reported the animation itself as janky, not the content.

## Decision

While the entry slide runs, the player page composes a light shell only: theme background, black
video slot, cover image, and the top bar with the title and back button. The heavy content is
released on the first frame after the slide settles (tracked by `entryTransitionSettled` plus one
`withFrameNanos`). The shell is used once per page instance and never returns: a later exit slide
must not swap the real content out, or playback position and list state would be destroyed.
ExoPlayer creation, preparation, resume position, watch-history effects and predictive back stay in
the page and are not delayed; only the UI tree is. A 500 ms fallback releases the content if the
host never runs the entry animation.

## Alternatives considered

**Delay player preparation until the slide settles** — would remove the remaining work from the
animation, but changes when audio/video starts and adds a loading gap after landing; the reported
problem was the transition, not start-up.

**Shorten or disable the entry animation** — easy to ship and cheap, but removes the motion the app
is built around and does not help the same page open from a cold start.

**Compose the whole page off-screen before animating** — same total work, moved earlier; it would
make the tap feel late and can still miss the first animation frame.

## Consequences

During the ~320 ms slide the page shows a cover instead of the video's first frame and the details
are absent; on a fast local file audio can start before the surface appears. The shell must keep
matching the real layout (status bar strip, 16:9 slot, top bar) or landing will visibly jump, so
layout changes in the player need a matching change in `PlayerEntryShell`.
