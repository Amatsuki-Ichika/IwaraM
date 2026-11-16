# 0023 — Draw placeholder cards while a scrollbar thumb is dragged

Status: accepted

## Problem

Dragging the scrollbar thumb through a large library (1700 local videos) still stuttered slightly after the drag was changed to land the list at the position under the finger. Each drag frame has to compose and measure the screen of rows that the landing position reveals, and every card in that screen was built in full: the cover request and decode, the local/R18/date badges, the author row and the tag row, whose SubcomposeLayout measures every chip plus the +N and ellipsis chips. requestScrollToItem also replaces the scroll position without running a scroll gesture, so the lazy list's prefetch does not warm the rows ahead of the finger. The cost is not a long freeze but a consistent sub-frame jank on every drag frame.

## Decision

While a scrollbar thumb is held, the library grid draws each card as a placeholder: the cover box (surfaceVariant, 16:9), the title line and the fixed row placeholders stay, while the cover image, the badges, the author name and the tag row are skipped. The grid reads this through `LocalScrubbingCards`, provided by the library page from its existing `scrollbarDragging` flag, so releasing the thumb brings the full cards back on the next frame. The placeholder keeps every `VideoCardSpec` height and padding used by a real card, so card height, row alignment and the grid's measured position are identical and nothing shifts when the cards return. The scope is the local library grid, the only page whose library is large enough to show the cost; the Iwara feeds compose far fewer cards per screen and keep their cards while a drag is in progress.

## Alternatives considered

**Leave the cards unchanged and accept the residual jank.** The backdrop work already removed the full-screen re-recording from every drag frame; this was the remaining cost and the user still felt it, so leaving it does not reach the bar.

**Blank the whole grid, including the cards already visible, while the thumb is held.** It would also blank the cards under the finger, which makes the drag harder to follow, and redraw the current screen for no gain; only the rows the drag reveals need the cheap path.

**Prefetch the rows the drag will land on.** The thumb can land on any of 1700 rows, so a prefetch window cannot cover it, and loading covers and tag layouts ahead of the finger costs more than it saves.

**Make the tag row cheaper instead of skipping the rest of the card.** The tag row's subcompose work is only part of the card; the cover request and the badge composition remain, and the tag row keeps its measured-height contract, so the saving is smaller and the layout logic has to be re-tuned.

## Consequences

Dragging the thumb through a large library now shows plain cards with titles instead of cover art, which is a visible downgrade for as long as the thumb is held; the thumb position, the section bubble and the backdrop freeze are unaffected. The placeholder path depends on the card spec staying shared: a future card row added outside `VideoCardSpec` would also have to be skipped in the scrubbing branch, or the placeholder would grow taller than a real card and the grid would shift when the drag ends. The flag is a composition local rather than an item parameter, so any host that does not provide it keeps the current full-card rendering.
