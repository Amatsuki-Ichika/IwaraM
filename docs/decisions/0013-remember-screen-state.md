# 0013 — Tabs keep their scroll position while another tab is shown

Status: accepted

## Problem

The main tab strip switches content through AnimatedContent, which composes only the target tab. Each tab owned its lazy grid state and paging state in plain remember, so leaving a tab disposed that state and coming back rebuilt it from zero: the list returned to the top, and the paging feed could fire its first load again. The same happened between the Iwara page's segments.

## Decision

Both tab switches are wrapped in a rememberSaveableStateHolder: the main tabs are keyed by tab name and the Iwara page's segments by segment name, through SaveableStateProvider. rememberLazyGridState and the feed pager's state are saveable, so a tab that leaves composition has its scroll position and loaded page count restored when it returns. The paging loaders load only when the item list is empty, so a restored tab with items does not refetch its first page.

The state is deliberately not a global saved map: it lives as long as the activity, so a process restart still rebuilds from the network and the library scan, which is the intended cost of not persisting scroll offsets to disk.

## Alternatives considered

**Keep all tabs composed and hide them with graphicsLayer.** Preserves state without a holder, but every tab keeps its paging collectors, image loads and layout work alive, and the glass backdrop sees all of them; memory and idle work grow with the number of tabs.

**Hoist each tab's LazyGridState into the activity.** Works, but the activity would have to know every tab's data shape, and the feed pager (loaded pages, in-flight job) cannot be expressed as a simple state object.

**Persist scroll offsets in preferences.** Survives process death but stores a per-account, per-feed position that goes stale, and the saved position can point past the end of a feed whose first pages were re-fetched; the complexity is not worth surviving a cold start.

## Consequences

A tab keeps its scroll position, its loaded pages and its selection while the user switches tabs or opens and closes an overlay, and the Iwara page keeps each segment independently. The saveable state is tied to the activity instance, so rotating the device keeps positions only where Compose's saved instance state is retained, and a process death resets them. Restoration also means an old error or empty state is shown until the user retries, because the loader is not re-run for a non-empty feed.
