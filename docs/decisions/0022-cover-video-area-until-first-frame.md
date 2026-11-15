# 0022 — Cover the video area until the player's first frame arrives

Status: accepted

## Problem

Tapping a video slides the player page in over 320 ms. The transition should move a real page,
not a placeholder, and should not spend the animation's frame budget on the heaviest setup in the
page: inflating the PlayerView, attaching its SurfaceView and rendering the first video frame.
The previous fix composed the whole page before starting the slide and gated the slide on a
readiness callback, which bought smooth frames at the cost of a small delay before any motion and a
host-to-page readiness contract. The user asked to roll that back in favour of a mask over the video
area that defers rendering of the video itself.

## Decision

The host starts the 320 ms entry slide immediately when the player page is mounted and tells the
page when the slide has settled (`entrySettled`). While the slide is running the page draws the
video's cover over the video area and does not create the PlayerView, so no part of the video
surface is set up during the transition. After settling the page creates the PlayerView, keeps the
cover over the video area until the player reports its first rendered frame, and then fades the
cover out over 220 ms. Errors take the mask away immediately: a resolve or playback error removes
the cover so the error panel is readable. The cover is the local thumbnail when the video has one
and the online thumbnail otherwise, darkened by a 12% black scrim, with a small spinner only during
the short phase after settling and before the first frame.

## Alternatives considered

**Keep the readiness gate from 0021 and compose the player before the slide.** This is what the
change rolls back: the host had to wait for the page's first frame before moving, so tap-to-motion
grew by one or two frames and the page readiness contract stayed, while the video setup still had to
finish before the transition could be considered ready. The user rejected this approach.

**Slide a light skeleton shell and compose the real page after settling.** Rejected earlier as ugly
next to a real page: the shell turns into a different layout on landing.

**Mount the PlayerView and hide it behind an opaque overlay.** The SurfaceView is a separate window
layer, so a Compose alpha on its parent does not fade it, and it would still be created and attached
during the slide; the expensive setup stays inside the animation.

**Show only a black overlay during the slide.** It avoids flashing the loading state, but a black
rectangle is worse than the video's own cover and gives no hint of what is opening.

## Consequences

The slide starts on the same frame as the tap again, so the delay accepted in 0021 is gone. The
player is created after the slide instead of before it, so playback starts a little later, but the
cover hides that gap and the first frame fades in instead of popping. The page now needs a cover
source (local thumbnail or thumbnail URL) and a host signal for the settle moment; the first-frame
flag is keyed by `entryToken` so an interrupted exit and re-entry cannot keep a stale mask. Because
the PlayerView is only created when `entrySettled` is true, a missed reset would leave the page
without video, so the host must reset it on every fresh open.
