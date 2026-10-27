# 0007 — Player menus open on the side that fits

Status: accepted

## Problem

The quality and super-resolution chips sit in the player's bottom bar, a few dp above the window edge. A menu pinned below its chip can only be as tall as the room beneath it, and the floor on that room was a fixed 120 dp, so anything taller ran past the screen border unclipped: the last items were unreachable, and in fullscreen the menu sat mostly off-screen. The placement has to hold on any window size without the caller guessing a height.

## Decision

`AnchorMenu` positions itself through pure functions in `AnchorMenuGeometry`: the side is the one that fits the whole menu (downward when it does, otherwise the side with more room), the right edge aligns with the chip, both axes are clamped inside the window minus the margin, and the height cap is the room on the chosen side so overflow scrolls inside the menu instead of leaving the window. A chip in the bottom bar therefore opens its menu upward and overlaps the bottom of the video while the menu is open; that overlap is the accepted cost. The menu fades in and scales from the chip corner, and closes with a shorter fade-out.

## Alternatives considered

**Keep the menu downward-only and clamp it inside the window.** Cheapest, but a tall menu then covers its own chip and the bottom-bar controls it belongs to, and the clamp hides the overflow with no way to scroll to it.

**Let `DropdownMenu` choose the position.** Material's provider picks its own side and animation, cannot be told to right-align with the chip, and brings its own dismissal and focus handling; matching this screen would mean reimplementing the provider anyway.

**Always open upward from the bottom bar.** Saves the geometry, but hard-codes the bar's position into the caller and breaks the first frame, where the chip has not been measured yet.

**Move the menu out of the video surface, as a sheet or a docked panel.** Never covers the video, but turns a two-item choice into a full-screen interruption and changes how dismissal interacts with playback.

## Consequences

While a menu is open it covers the lower part of the video, and only dismissing it reveals that region; the trade is accepted because the menu is transient and the alternative was items the user could not reach. Placement reads the measured anchor, so the first frame guesses the side and corrects once the anchor is known. A future rule that the video must never be covered forces the upward branch out and the menu into a sheet or a docked panel; nothing mechanical prevents that except the geometry tests, which pin the window-clamping and chip-spacing invariants.
