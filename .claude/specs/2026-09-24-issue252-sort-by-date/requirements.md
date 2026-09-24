# Issue #252: Sort by Date / Recently Added for UPnP folders

## Problem Statement

When yaacc browses a remote DLNA/UPnP content server (the "Content" tab —
yaacc acting as client, not its own embedded server), folder listings are
always strictly alphabetical. Users with large or frequently-updated
libraries have no way to see newly added files without scrolling the full
alphabetical list.

## Goals

- Let the user switch a folder listing between "Name" (current alphabetical
  behavior) and "Date" (newest-first) ordering via a visible control at the
  top of the content list.
- Use server-side `SortCriteria` (`-dc:date`) when the server supports it,
  avoiding extra requests or full-folder prefetch.
- Fall back to a client-side sort once all items are loaded, for servers
  that ignore `SortCriteria`.
- Persist the user's last-chosen sort order (Name/Date) across app restarts
  and across folders.

## Non-Goals

- No ascending/descending toggle beyond "Name" (A-Z) and "Date" (newest
  first) — no oldest-first mode in this iteration.
- No changes to yaacc's own embedded UPnP/DLNA server or its content
  ordering.
- No per-folder sort memory (the chosen order is global, not saved
  per-folder-path).
- No sorting by other metadata (size, type, etc.).

## Behavior Spec

1. **Sort control**: segmented buttons ("Name" / "Date") in the content
   list header row, next to `contentListCurrentFolderName`. Current
   selection is visually highlighted. Tapping the other button re-sorts
   immediately.
2. **Server-side attempt first**: when "Date" is selected, `SortCriterion`
   for `-dc:date` is threaded through `UpnpClient.browseSync(...)` →
   `Browse` action's `orderBy` parameter, so a compliant server returns
   items pre-sorted newest-first without any client-side work.
3. **Client-side fallback**: because pagination loads chunks
   incrementally, a full client-side re-sort by date is only applied once
   the entire folder has been fetched (`allItemsFetched`-equivalent state)
   — no visible mid-load reshuffle. Until then, chunks render in arrival
   order.
4. **Folder/item grouping in Date mode**: containers and items are fully
   interleaved by date — Date mode does NOT keep folders pinned above
   files. Name mode is unchanged (folders before items, alphabetical
   within each group).
5. **Missing `dc:date`**: if a folder's items entirely lack `dc:date`
   metadata (neither server-sorted nor client-sortable), the "Date" button
   is disabled for that folder so the user isn't offered a no-op sort.
6. **Persistence**: the last-chosen sort mode (Name/Date) is saved to
   SharedPreferences and applied as the default for all folders on next
   app launch, until the user changes it again.

## Success Metrics

- A user browsing a `dc:date`-populated server can switch to "Date" mode
  and see newest items first, matching server-reported dates.
- Switching sort mode does not trigger duplicate network requests beyond
  what pagination already requires.
- No visible list reshuffle while a folder is still loading chunks.
- App restart preserves the last-selected sort mode.

## Constraints

- Must not regress existing alphabetical (Name) browsing behavior or
  pagination/chunking performance for large folders.
- Must work against servers that do NOT support `SortCriteria` or
  `dc:date` (graceful degradation per items 3 and 5 above).
- Android/Java, existing Cling UPnP stack — no new UPnP libraries.
- Follow existing SharedPreferences/settings patterns already used in the
  codebase (see `design.md` for exact call sites once identified).
