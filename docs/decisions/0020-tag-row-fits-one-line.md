# 0020 — Lay out card tags to fit one measured line

Status: accepted

## Problem

Library and feed cards showed a fixed number of tags (three) and let the last one wrap. Cards with
long tags grew taller than their neighbours, long tags ended in an ellipsis with no indication that
more existed, and two cards in the same row no longer lined up. The request was explicit: the tag
row must always be one line, adapt to the real width, ellipsize what does not fit, and always end
with a `+N` chip counting the tags after the visible ones (the ellipsized one counts as shown).

## Decision

The row measures its own available width, subcomposes real `TagChip` composables in a `SubcomposeLayout`, measures each chip's actual width, and asks `layoutTagRow` for a plan: how many chips fit whole, whether the last visible chip must be ellipsized with the remaining space, and how many tags are hidden. Composing stops once the next chip provably cannot fit beside the narrowest `+N` chip, so at most twelve tag chips are composed per row. The plan is computed in a pure function with unit tests; the composable only composes, measures and draws. A trailing `+N` chip is drawn whenever hidden tags remain. The ellipsized chip is the last visible one and is not included in N. A chip that could only show an ellipsis is folded into `+N` instead: the row also subcomposes the shortest "ellipsis plus one character" chip and requires that width before truncating.

## Alternatives considered

**Estimate each tag's width with `TextMeasurer` and sum the estimates** — cheaper than composing real chips, but the estimate differs from the real layout by 1-2 px; accumulated, the last chips overflow or collapse into an ellipsis-only pill, which is the defect this row exists to fix.

**Cap the tag text length in characters** — cheap, but character count says nothing about pixels:
wide CJK or emoji tags still overflow and narrow Latin tags waste the row.

**Let the row wrap to two lines and fix the card height** — consistent height, but wastes vertical
space, clips tags invisibly and still hides the count.

**Marquee or horizontal scroll inside the card** — keeps every tag reachable but makes a grid of
cards feel busy, and scroll inside a scrolling list steals gestures.

## Consequences

Every card with tags ends with an honest count, and cards in a row share the same height because the row is always one line. Measuring on every width change composes and measures up to twelve real tag chips, each `+N` width it needs and the truncation probe, which costs more than a `TextMeasurer` estimate but is exact, so no chip overflows and no ellipsis-only pill appears. A very long single tag is shown truncated with no `+N`, because it is the only tag and the ellipsis is the whole row.
