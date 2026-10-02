# Issue: "Play all from here" ignores the active client-side sort order

## Summary
Tapping an audio item row (or its row-level "play all" button) starts
playback from the correct item, but the rest of the queued playlist
follows the server's unsorted default order instead of the Name/Date
sort order currently shown on screen. The per-item "play this one track
only" button is unaffected (trivially — it queues a single item, so
there's nothing to reorder).

## Impact
Any user who sorts a folder by Date (or Name descending) and then taps
a track to "play from here" gets a playlist whose *order* silently
reverts to the server's default, even though the item they tapped is
correctly first. Confusing and defeats the point of sorting before
playing through a folder. Affects every UPnP renderer target, not
specific to any one server.

## Reproduction
1. Browse a folder, switch sort mode to Date (or Name, descending).
2. Tap an audio item partway through the sorted list (or its row's
   "play all" icon).
3. Expected: playback queue follows the on-screen sorted order,
   starting at the tapped item.
4. Actual: the tapped item plays first, but subsequent queue order
   matches the server's unsorted default, not the visible sort order.

## Investigation

Root cause confirmed by direct code read — this is unrelated to either
of the two just-shipped sort specs' own code, but is newly *visible*
now that sorting exists at all:

`yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java:516-539`,
`playAllChildsOfParentFrom(DIDLObject item)`:

```java
ContentDirectoryBrowseResult result = upnpClient.browseSync(
    new Position(0, item.getParentID(), ..., item.getTitle()));
...
List<Item> items = result.getResult().getItems();
int index = items.indexOf(item);
// rotate items so `item` is first
```

This method re-browses the parent folder from scratch via the
no-`orderBy` `browseSync` overload — a fresh, independent fetch that
has no knowledge of the `BrowseContentItemAdapter`'s current sort mode
or direction. It then finds `item`'s index in that *freshly-fetched,
unsorted* list and rotates the list to start there. The rotation is
correct (the tapped item does end up first), but everything after it
follows the server's default order, not the adapter's sorted order.

This method is shared by two trigger paths that reach it identically:
- `ContentListClickListener.onClick` (tapping an audio item row) →
  `ContentItemPlayTask.PLAY_ALL` → `playAllChildsOfParentFrom`
- `BrowseContentItemAdapter`'s row-level `holder.playAll` button → same
  `PLAY_ALL` task → same method

The row-level `holder.play` button (`PLAY_CURRENT`) calls
`playItem(item)` → `upnpClient.initializePlayers(item)` directly — a
single-item queue, so there's no ordering to get wrong, which is
consistent with the reporter's observation that "the play button"
works correctly.

`BrowseContentItemAdapter` already holds the correct, currently-sorted
list in memory (`objects`, incrementally built/re-sorted by the
existing client-side sort feature from issue #252) but has no public
accessor for it — `playAllChildsOfParentFrom` has no way to reach it
today, which is presumably why it re-browses instead.
