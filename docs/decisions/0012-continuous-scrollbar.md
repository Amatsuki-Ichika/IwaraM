# 0012 — The scrollbar moves continuously and sits under the search row

Status: accepted

## Problem

The fast-scrollbar thumb was drawn on a track that started at the top of the content box, which is the window's top edge, while the floating search and segment row covers the first 64 dp plus its own height. The track therefore ran behind the search field, and the thumb's zero position was a lie: the first visible list row was already hidden under the search row. On sectioned lists the drag only changed a highlighted section marker and the list jumped to the section start on release, so the content never moved with the finger; on continuous lists every seek was rounded to a whole row and applied with scrollToItem, which made the thumb trail the finger in visible steps and jump back when the release recomputed the fraction from the snapped row.

## Decision

Track geometry comes from the measured header: the caller passes headerItemCount and a header height provider, ScrollGeometry converts the header into content-row units (headerScrollGeometry) and returns null when the header is taller than the viewport or the row height cannot be measured, in which case the bar is not drawn. Both list and grid call sites pad the track by the same top inset as the content, so position zero is the first row below the search row.

Dragging is continuous instead of row-snapped: the drag handler converts the fraction change between two pointer positions into a pixel delta, and the lazy state applies it synchronously with dispatchRawDelta, so the list follows the finger frame by frame without recomputing an integer target. All pixel deltas that arrive within one frame are merged and applied once at the next frame, and the first drag frame claims the scroll mutex with a zero-distance suspend scroll, so an in-flight fling or track-tap jump cannot fight the drag. The content-row scale (spanPx) is sampled once when the drag starts and held for the whole gesture, so layout changes mid-drag cannot rescale the movement. On release the bar pins the last drag fraction as its displayed progress until the list's real scroll fraction catches up, then returns to drawing the real progress; the thumb-length dead band shrinks from 2% to 0.4% so the bar no longer freezes near the ends.

## Alternatives considered

**Keep Integer seekTarget and only fix the track offset.** Solves the visual placement but not the stutter: a row-snapped target cannot express the position between two rows, so the thumb still moves in row-sized steps and the release still has to guess a fraction.

**Draw the bar with an offset animation towards the real fraction.** Hides the discontinuity behind interpolation, but during the drag the thumb would lag the finger and the list would still jump, and a fast drag opens a visible gap between finger and thumb.

**Queue row seeks from the drag in a coroutine collector.** Each pointer event recomputed a seek target and a collector applied it, but a new event cancelled the previous scroll, so fast drags dropped most of the movement and the list advanced in bursts. Applying a pixel delta synchronously removes the cancellation point.

**Move the search row into the content list and let it scroll away.** The track could then start at the window top without overlap, but the search field must stay reachable at any scroll position, and making it a sticky item changes the list structure and the measured header arithmetic.

## Consequences

The bar now needs a measured header height and a content-row height, so before the first layout it is hidden rather than drawn at a wrong offset; a header taller than the viewport keeps it hidden instead of inverting the mapping. Continuous dragging merges one frame's pointer events into a single pixel delta applied at the next frame, so a high-sampling touchscreen cannot trigger several scroll layouts per frame, and the first drag frame cancels an in-flight fling instead of adding to it; the caller must still stop applying seekTarget directly. Tap-to-seek stays a whole-row jump, so the two paths have different precision. The drag-end pinning is a timed visual state, so a seek that is rejected by the list end is corrected after the next frame instead of at release.
