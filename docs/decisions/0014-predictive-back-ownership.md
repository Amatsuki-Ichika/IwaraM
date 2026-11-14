# 0014 — One owner answers the predictive back gesture

Status: accepted

## Problem

Only the platform's default "swipe to go home" animation appeared; the pages' own back handling never animated with the finger. The app has one activity, and the platform asks only the focused window's OnBackInvokedDispatcher; a window with at least one enabled callback lets the app draw the animation, while a window with none falls through to finishing the activity. Pages decided their own backEnabled from local flags (overlays empty, page on screen, sequence numbers), and the flags disagreed: the overlay host returned early when the top overlay was an action sheet but the activity still reported the overlay stack as owning back, so the gesture saw no callback and finished the activity. The action sheets themselves are ComponentDialog windows, whose dispatcher was not driven by the gesture. Progress was also read during composition, so every gesture frame recomposed the whole main content.

## Decision

A pure function backOwner in IwaraOverlayStack chooses exactly one owner from the state the activity already tracks: onboarding, then an open sheet, then the visible page layer with the largest sequence number (overlay, settings, player), otherwise the root. The activity computes it once per frame and passes backEnabled = (owner == this layer) to the library, the overlay host, settings and the player, so at most one handler is enabled and the gesture always has an owner after the first frame.

The overlay host no longer suppresses its callback for action-sheet overlays; it reports the top interactive overlay and lets the sheet window handle its own gesture. AppBottomSheet registers an OnBackPressedCallback on the dialog window's dispatcher and drives its offset and scrim from BackEventCompat progress on API 34+, so the sheet slides with the finger; below API 34 the callback only handles the completed press. Gesture progress is passed to composables as lambdas read inside graphicsLayer and drawing code instead of being read during composition.

## Alternatives considered

**Register one root callback and implement a manual state machine for every page.** Guarantees a single handler, but it must replicate what each page does with progress (player slide, settings slide, sheet offset, overlay slide), turning five independent progress channels into one switch that is harder to test and easy to desynchronise.

**Leave backEnabled to each page and only fix the action-sheet early return.** Fixes the reported case but leaves the invariant unstated; the next page added with its own flag can reproduce the silent finish.

**Rely on BackHandler alone.** BackHandler reports only the completed press, so no page animates with the finger, which is the behavior the platform already showed for a window with no callback.

## Consequences

Back is answered by exactly one layer, and the root gets it only when nothing else is on screen, so a gesture that starts on an empty tab still exits to the home screen. The owner is recomputed from the same sequences that order drawing, so the visual top and the back owner cannot disagree. The cost is a single-frame window after a page change before the new handler is enabled; a gesture started in that frame falls back to the previous owner. API levels below 34 get the correct destination but no finger-following animation.
