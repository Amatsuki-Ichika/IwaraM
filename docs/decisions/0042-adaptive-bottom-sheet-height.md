# 0042 — Single-column sheets wrap their content up to the filter sheet's height

Status: accepted

## Problem

`AppBottomSheet` sized itself once, when it opened: 88% of the screen height minus 112 dp, shared by
every sheet. That is right for the filter sheet, whose two-column grid needs the room, but the
local-card action sheet only has four rows. Opening it reserved almost the whole screen and left a
large empty area under the rows, which the user reported as wasted space.

## Decision

- A sheet's height is its content height, capped at the previously fixed value
  (`(screenHeight * 0.88) - 112 dp`). The cap is the filter sheet's height, so sheets that need the
  room (filter, diagnostics) look exactly as before and short action sheets shrink to their rows.
- The cap stays viewport-derived and is not affected by the keyboard or by switching tabs.
- Drag-to-dismiss, the closing animation and the scrim are driven by the measured height, not by the
  hypothetical maximum: a short sheet travels a short distance, and dragging it a quarter of its own
  height closes it. Before the first measurement the maximum is used as a fallback.
- Content taller than the cap keeps scrolling inside the sheet, as before.
- The player-details sheet is not affected: it is not an `AppBottomSheet` and keeps its own layout.

## Alternatives considered

- Give each call site an explicit height. It works, but every new sheet has to guess a number and the
  four sheets drift apart; the content already knows how tall it is.
- Always use 88% (the previous behaviour). That is the reported bug.
- Cap at half the screen. The filter sheet needs more than half, so it would have to opt out.
- Size the sheet with the keyboard open so it grows while typing. The sheet would jump whenever the
  IME appears and disappears; the safe-area padding already keeps content above the keyboard.

## Consequences

- Short sheets open with a shorter slide, which looks faster; the scrim and predictive back are
  unchanged.
- Height is measured after the first layout, so the open animation's first frame uses the cap. Both
  values put the sheet fully below the screen at progress 1, so no jump is visible.
- Content that changes height while the sheet is open resizes it without animation. Today all sheet
  bodies are static; a future sheet with async content should animate the height instead.
