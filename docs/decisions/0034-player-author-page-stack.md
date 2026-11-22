# 0034 — The player and author pages share one navigation stack

Status: accepted

## Problem

The player is drawn above every overlay through a boolean (`showingPlayer`), not through the
overlay list. Tapping the author name inside the player therefore had to call `closePlayer()`
before pushing the author page: the only way to get the author page drawn on top was to remove the
player. Returning from that author page then lost the video, its playback position and the whole
session, so a user who just wanted to look at the author had to find the video again. The overlay
list already avoids a second page for the same author (`profileRevertIndex`), but only among
overlays, so the player never took part in the rule.

## Decision

The player participates in stack ordering through its entry sequence (`playerSeq`) although it
is not an overlay entry. `authorNavPlan` in `ui/browse/IwaraPageStack.kt` decides where an author
page goes: with no page for that profile in the stack the new page is pushed on top of the player
and the player keeps playing underneath; with an existing page the overlays above it are popped,
and the player is closed only when it is the layer being popped, that is when `playerSeq` is
greater than that page's sequence. `MainActivity.openAuthorProfile` is the only entry point for
author pages; the player's author tap no longer closes the player. An existing author page is
reused and never rewritten, and one author never gets two pages. When any page covers the player,
the player pauses; it does not resume on its own. Player visibility remains a separate flag and
the player is still a single slot: opening another video replaces the current one.

## Alternatives considered

**Always push a new author page above the player.** This draws the same author twice in the stack,
so Back visits the same page twice and the page state (filters, scroll) is lost between the two
copies.

**Keep closing the player and restore it on return.** Restoring means remembering the video id and
the position somewhere, which is the same stack structure with an extra playback restart and a
visible reload; a short tap of the author name would still lose the frame.

**Put the player into the overlay list itself.** The player is one slot holding one ExoPlayer and
one current video; making it an entry would let two player entries coexist, and both the
z-order and Back would have to resolve duplicates the overlay stack was never designed for.

## Consequences

The ordering rule is one function with a JVM test (`AuthorPageStackTest`), and the stack is no
longer play history: only one player entry can exist, so opening a second video from an author
page replaces the first without a way back to it. Audio stops when the player is covered, so a user
reading an author page needs one tap to resume; that is the chosen trade-off over sound continuing
behind a text page. Predictive Back and the cover animation keep reading the same
`playerCovered` flag, which now means "covered by a page" rather than "a page opened instead of
the player".
