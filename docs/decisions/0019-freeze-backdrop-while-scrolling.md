# 0019 — Freeze the frosted backdrop while a list is scrolling

Status: accepted

## Problem

On a long local library or author page, scrolling drops frames even though the list itself is
cheap. The frosted background is recorded for real: a full-screen layer of the page is drawn into
an offscreen texture on every frame so the bars and the back-to-top button can sample it. During a
fling that recording is a second full-screen render pass per frame, competing with the list's own
draw for the main thread.

## Decision

Scrolling lists now own a content-level backdrop (`MainContentBackdrop`) instead of a view-level
one. The list source passes `record = !isScrollInProgress`: while the finger or the fling moves,
the layer is not re-recorded and the last recorded frame stays as the sample. When the list stops,
recording resumes on the next frame. The back-to-top button keeps sampling the frozen frame during
the drag, so it does not flash or fall back to a plain fill.

Every list that shows the button (library, infinite grids, paged author grids, profile and playlist overlays) uses the same pair `rememberGlassScrollBackdrop()` + `glassScrollBackdropSource(source) { scrolling }`. The predicate also covers the scrollbar thumb: dragging it moves the list through `requestScrollToItem`, which never sets `isScrollInProgress`, so each page feeds the scrollbar's `onDraggingChange` into the same scrolling predicate and the freeze applies while the thumb is held too. A list with no backdrop (blur disabled) gets `null` and behaves as before.

The same gate exists one level up: the host records the whole tab content into the main backdrop that the floating bottom bar and the static top bar sample, and that source had no scroll condition at all. Each main tab page now reports its own scrolling predicate (list scroll or scrollbar drag) to a shared `MainContentScrollActivity` channel, and the host source stops recording while any page reports scrolling. Without this second gate the list stopped re-recording its own header backdrop but the window still re-recorded the whole content area every frame, which is exactly the second full-screen pass this decision removes. The channel keeps one token per page rather than a single flag, because a page that leaves during a tab or mode switch could otherwise clear the incoming page's state.

## Alternatives considered

**Detach the view-level `LayerBackdrop` while scrolling** — the obviously cheaper edit, but a
detached view backdrop clears its layer coordinates and every consumer exits early without drawing,
so bars and the button would lose their material mid-drag.

**Record every other frame / on a timer** — halves the cost and keeps the backdrop moving, but the
sample visibly lags and steps; the user complaint was stutter, and a stepping backdrop reads as
more stutter.

**Remove the backdrop from list screens entirely** — fastest, but throws away the frosted material
the user asked to keep and makes the page look different while idle.

## Consequences

During a scroll the frosted material shows a still image of the frame where the scroll started; a
fast fling over very different content can look slightly stale for its duration. In exchange the
scroll path no longer renders the page twice per frame. If per-frame backdrop recording ever gets
cheap enough, the gate can be removed without touching consumers; if the stale look is reported,
the first option to revisit is recording once per scroll settle rather than per frame.
