# 0012 — The scrollbar moves continuously and sits under the search row

Status: accepted

## Problem

The fast-scrollbar thumb was drawn on a track that started at the top of the content box, which is the window's top edge, while the floating search and segment row covers the first 64 dp plus its own height. The track therefore ran behind the search field, and the thumb's zero position was a lie: the first visible list row was already hidden under the search row. On sectioned lists the drag only changed a highlighted section marker and the list jumped to the section start on release, so the content never moved with the finger; on continuous lists every seek was rounded to a whole row and applied with scrollToItem, which made the thumb trail the finger in visible steps and jump back when the release recomputed the fraction from the snapped row.

## Decision

Track geometry comes from the measured header: the caller passes headerItemCount and a header height provider, ScrollGeometry converts the header into content-row units (headerScrollGeometry) and returns null when the header is taller than the viewport or the row height cannot be measured, in which case the bar is not drawn. Both list and grid call sites pad the track by the same top inset as the content, so position zero is the first row below the search row.

Dragging lands the list at the position under the finger instead of accumulating pixel deltas: the drag handler turns the pointer position into a fraction, ScrollGeometry turns the fraction into an item index and an in-item pixel offset with seekTarget, and the state version hands that target to LazyGridState.requestScrollToItem or LazyListState.requestScrollToItem. That request only invalidates the measurement, so the position is applied by the next measure rather than inside the pointer callback, several requests within one frame collapse into the last one, and a fast drag across a large library no longer builds a screen of new cards per input event; a request also replaces the scroll position, so an in-flight fling or track-tap jump is stopped instead of fighting the drag. On release the bar pins the last drag fraction as its displayed progress until the list's real scroll fraction matches it, then returns to drawing the real progress; the thumb-length dead band shrinks from 2% to 0.4% so the bar no longer freezes near the ends.

## Alternatives considered

**Keep the pixel-delta drag and only cap its cost.** Limiting the delta to one row, merging events or hiding card work during the drag keeps the immediate follow, but the list still has to lay out new items for every event and the bar would have to decide how far behind the finger the content may fall.

**Draw the bar with an offset animation towards the real fraction.** Hides the discontinuity behind interpolation, but during the drag the thumb would lag the finger and the list would still jump, and a fast drag opens a visible gap between finger and thumb.

**Queue row seeks from the drag in a coroutine collector.** Each pointer event recomputed a seek target and a collector applied it; a new event cancelled the previous scroll, so fast drags dropped most of the movement. requestScrollToItem collapses same-frame requests and replaces the position without a coroutine.

**Move the search row into the content list and let it scroll away.** The track could then start at the window top without overlap, but the search field must stay reachable at any scroll position, and making it a sticky item changes the list structure and the measured header arithmetic.

## Consequences

The bar now needs a measured header height and a content-row height, so before the first layout it is hidden rather than drawn at a wrong offset; a header taller than the viewport keeps it hidden instead of inverting the mapping. A drag changes the scroll position rather than running a scroll gesture, so isScrollInProgress stays false and each page has to report the drag to the backdrop gate itself. The bar reads its progress from the list's own fraction after the next measure, so the thumb lands on the finger position within one frame instead of being redrawn from the finger. requestScrollToItem applies the requested position only to the next remeasure, so a data-set change in the same frame can keep a different index and a drag during loading ends on the last requested item; a drag is also not a consumed scroll, so the overscroll gates do not see it. Tap-to-seek shares the same seekTarget conversion, so the two paths no longer differ in precision, and the drag-end pinning still corrects a seek clamped at the list end after the next frame.
