# 0021 — Compose the player page before starting its entry slide

Status: superseded

Superseded by: 0022

## Problem

Tapping a video slides the player page in over 320 ms. The whole page tree — the PlayerView
inflate/attach, details, comments, related list — composed in the first frame of that animation
together with the player setup, so the main thread missed frames exactly during the transition.
The user reported the animation itself as janky, not the content.

## Decision

The player page is composed as the real page, not a placeholder. The host does not start the entry
slide until the page reports that its first frame has been drawn (`PlayerScreen.onEntryReady`, sent
after two `withFrameNanos` ticks so the first composition's measure/layout has finished). The page
reports again whenever `entryToken` changes; the host resets readiness whenever it mounts the page
anew. An 800 ms timeout starts the slide anyway so a missed report cannot leave the page parked
off-screen.

Because the slide starts after the first frame, the 320 ms transition itself only moves the page:
it no longer shares a frame with the first composition or with PlayerView inflation. ExoPlayer
creation, preparation, resume position, watch-history effects and predictive back stay in the page
and are not delayed by the gate.

The earlier light shell (`PlayerEntryShell`) and its gate (`entryTransitionSettled`) were removed.

## Alternatives considered

**Compose a light shell during the slide and the heavy content after it settles** — the previous
implementation. It removes the jank, but the user rejected it: the slide shows a cover and a
skeleton layout that changes into the real page on landing, which reads as "ugly" next to a real
page sliding in.

**Keep the light shell, but compose the real page underneath it during the slide** — both trees
would exist for the whole transition; that pays the composition cost inside the animation again and
doubles the visible work, so it fixes the appearance without fixing the frame budget.

**Shorten or disable the entry animation** — easy to ship and cheap, but removes the motion the app
is built around and does not help the same page open from a cold start.

**Delay player preparation until the slide settles** — would move the remaining work off the
animation, but changes when audio/video starts and adds a loading gap after landing; the reported
problem was the transition, not start-up.

## Consequences

A tap now takes one to two frames (about 16–33 ms) longer before the slide starts, and the first
composition runs while the page is still off-screen instead of during the animation. After that the
transition is a pure translation and does not drop frames. The readiness contract is the only thing
keeping the page off-screen, so removing the callback or its host reset must be done together with
the timeout; the page must keep reporting on `entryToken` changes or a re-opened player could stay
parked. Layout of the real page is now the only thing that has to be right — there is no second
layout to keep in sync.
