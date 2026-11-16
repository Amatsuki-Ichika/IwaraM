# 0020 — Lay out card tags to fit one measured line

Status: accepted

## Problem

Library and feed cards showed a fixed number of tags (three) and let the last one wrap. Cards with
long tags grew taller than their neighbours, long tags ended in an ellipsis with no indication that
more existed, and two cards in the same row no longer lined up. The request was explicit: the tag
row must always be one line, adapt to the real width, ellipsize what does not fit, and always end
with a `+N` chip counting the tags after the visible ones (the ellipsized one counts as shown).

## Decision

The row measures its own available width, measures each tag with `TextMeasurer` using the chip's
text style and padding, and asks `layoutTagRow` for a plan: how many chips fit whole, whether the
last visible chip must be ellipsized with the remaining space, and how many tags are hidden. The
plan is computed in a pure function with unit tests; the composable only measures and draws.
A trailing `+N` chip is drawn whenever hidden tags remain. The ellipsized chip is the last visible
one and is not included in N.

## Alternatives considered

**Cap the tag text length in characters** — cheap, but character count says nothing about pixels:
wide CJK or emoji tags still overflow and narrow Latin tags waste the row.

**Let the row wrap to two lines and fix the card height** — consistent height, but wastes vertical
space, clips tags invisibly and still hides the count.

**Marquee or horizontal scroll inside the card** — keeps every tag reachable but makes a grid of
cards feel busy, and scroll inside a scrolling list steals gestures.

## Consequences

Every card with tags ends with an honest count, and cards in a row share the same height because
the row is always one line. Measuring tags on every width change costs a `TextMeasurer` call per
tag; results are remembered per tag list and width. A very long single tag is shown truncated with
no `+N`, because it is the only tag and the ellipsis is the whole row.
