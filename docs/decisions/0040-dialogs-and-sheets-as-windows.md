# 0040 — Dialogs and action sheets are separate windows, not views in the page stack

Status: accepted

## Problem

[AppDialog] and the local-card action sheet used to draw inside the Activity's single root Box. Layer
order there is decided by each page's zIndex: the player, settings and overlay pages carry positive
zIndex values, so any dialog or sheet drawn as a plain sibling sat underneath them. Users saw the
integrity-check dialog *behind* the player and a long-press menu that was translucent, could be
tapped through and was covered by the floating navigation bar. Raising the dialog's zIndex fixes one
case and breaks the next: the number depends on which page is on screen and on the drawer scrim.

## Decision

[AppDialog] hosts its content in its own [ComponentDialog] window (full-screen, transparent system
dim, self-drawn scrim and card), the same hosting approach [AppBottomSheet] already used. Both
windows are full-screen with `decorFitsSystemWindows = false` and a self-drawn scrim, so they are
above every Activity page by construction. The Activity's drawer scrim gets a high zIndex only
because it is the one scrim still drawn inside the Activity.

## Alternatives considered

- Raise the dialog's zIndex above the page zIndex range. The scrim, the sheet and future full-screen
  effects each need their own number, and any new page can outrank them.
- Add every dialog to a single global Compose overlay host at the root. It still lives in the
  Activity window, so it stays below the player's zIndex unless the player is also demoted; it
  cannot draw over the navigation bar on edge-to-edge devices without extra inset work.
- Leave the sheet inside the page and only fix its background. It would still be non-modal and
  covered by the floating bar.

## Consequences

- Dialogs/sheets must manage their own window: system-bar appearance, back press, size and a
  slightly longer lifetime than `visible` so the exit animation finishes before the window is
  destroyed. The API keeps the old inline composable shape, so call sites did not change.
- One dialog-level bug can no longer be "fixed" by zIndex tweaks; the window remains the single
  authority for "above everything".
- Revisit if the app ever moves to a single-window Compose host (for example predictive back across
  dialogs in one window), where the trade-off changes.
