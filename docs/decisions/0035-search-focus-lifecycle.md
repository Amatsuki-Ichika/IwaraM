# 0035 — Search focus is a one-shot event, not a property of the expanded bar

Status: accepted

## Problem

The home tabs swap their top bar through an `AnimatedContent` keyed by the selected tab, so every
tab switch destroys and recomposes the search bar. The bar requested focus from a
`LaunchedEffect(open)`: each recomposition of the expanded bar did it again, and a `BasicTextField`
that gains focus opens the IME. A user who typed half a query on the Iwara tab, switched to the
local library and came back got the keyboard back unasked; rotating the screen did the same.
Collapsing was the only way to stop it, and collapsing also cleared the query and the search
results, so results could not be kept while the keyboard was closed. Focus and expansion were one
state, so "expanded, keyboard closed, results still on screen" was not representable.

## Decision

Expansion and focus are two independent facts: `SearchPhase` in `ui/components/SearchInteraction.kt`
has `COLLAPSED` (the round pill), `EDITING` (expanded, focused, IME up) and `EXPANDED_IDLE`
(expanded, no focus, query and results kept), and `reduceSearchPhase(phase, event)` is the only
transition table. Focus is a one-shot event: `CompactSearchSlot` requests focus only when a tap sets
a local `pendingFocus` flag (after one `withFrameNanos`), never when `open` becomes true, so a
recomposed, restored or re-entered bar never reopens the IME. Losing focus, the IME-height falling
edge (`WindowInsets.isImeVisible`), the keyboard Search key, picking a tag suggestion and switching
tabs all converge on `EXPANDED_IDLE`; only the clear button and a feed-segment switch collapse the
bar. The query is committed when editing ends only if the input is non-empty and differs from the
committed query, so closing the keyboard runs at most one search and empty input never flashes an
empty result state. The Iwara state lives in `IwaraClientViewModel`; the library state is hoisted to
`MainContent` and saved with `rememberSaveable` because its top bar is recomposed away on every tab
switch. A "Results for: X ×" chip keeps the result state visible after the keyboard is gone.

## Alternatives considered

**Keep one expanded boolean and request focus whenever it flips true.** That is exactly the bug: any
recreation of the bar looks like a flip and steals focus; separating the two facts is what lets the
bar stay expanded without owning focus.

**Move the whole search bar back into the tab content and keep its state in `remember`.** The bar
must stay in the static top-bar material layer (a full-width material box inside the tab's sliding
content drew its edge into the screen), so its state has to outlive that layer instead.

**Collapse the bar and clear the query when the keyboard closes.** The opposite of what was asked:
closing the keyboard should reveal the results, not throw them away. Clearing stays available through
the clear button and through Back.

## Consequences

`EXPANDED_IDLE` is a resting state every search surface must handle, including the local library,
where the live filter keeps the text after the keyboard is closed. Focus is now an explicit tap
action; future code that expands the bar programmatically must not expect the keyboard. Back is
layered: with the IME up the system closes it first, and the next Back clears the search before the
screen's own Back behavior. The transition table is JVM-tested (`SearchInteractionTest`); changing it
without changing the test is the failure mode to watch for.
