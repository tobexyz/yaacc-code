# Issue #252: Sort by Date / Recently Added for UPnP folders

## Problem Statement

When yaacc browses a remote DLNA/UPnP content server (the "Content" tab —
yaacc acting as client, not its own embedded server), folder listings are
always strictly alphabetical. Users with large or frequently-updated
libraries have no way to see newly added files without scrolling the full
alphabetical list.

## Goals

- Let the user switch a folder listing between "Name" (current alphabetical
  behavior) and "Date" ordering via a visible control at the top of the
  content list.
- Let the user toggle sort direction (ascending/descending) independently
  for each of Name and Date, with the button's icon reflecting the current
  direction.
- Use server-side `SortCriteria` (`-dc:date`/`dc:date`) when the server
  supports it, avoiding extra requests or full-folder prefetch.
- Fall back to a client-side sort once all items are loaded, for servers
  that ignore `SortCriteria`.
- Gracefully recover when a server actively **rejects** an unsupported
  `SortCriteria` (a UPnP action failure), rather than showing an empty
  folder.
- Persist the user's last-chosen sort mode and per-mode direction across
  app restarts and across folders.

## Non-Goals

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
6. **Persistence**: the last-chosen sort mode (Name/Date) and each mode's
   own last-chosen direction are saved to SharedPreferences and applied as
   the default for all folders on next app launch, until the user changes
   them again.
7. **Direction toggle**: tapping the *already-selected* mode's button
   flips its direction (Name: A-Z ↔ Z-A; Date: newest-first ↔
   oldest-first) and re-sorts immediately (server-side retry when
   applicable, otherwise a client-side re-sort of what's already loaded —
   no new network request when everything is already fetched). Tapping
   the *other* (not-currently-selected) button switches mode using that
   mode's own last-remembered direction. Each button's icon changes to
   reflect its own current direction, independent of which mode is
   currently selected.
8. **Server rejects `SortCriteria`**: if a server-side sorted Browse
   request fails (UPnP action failure — e.g. the server doesn't declare
   `dc:date` in its `GetSortCapabilities` and errors rather than ignoring
   it), the app automatically retries the same request without `orderBy`
   instead of showing an empty/cleared folder. Once a folder's server has
   been observed rejecting sort criteria, subsequent chunk requests for
   that same folder load skip the doomed sorted attempt (client-side
   fallback and `isDateSortAvailable()` disabling still apply normally to
   the unsorted results).

## Success Metrics

- A user browsing a `dc:date`-populated server can switch to "Date" mode
  and see newest items first, matching server-reported dates.
- A user browsing a folder/server that rejects `dc:date` sort criteria
  still sees the folder's contents (never an empty list), with the Date
  button correctly disabled.
- Switching sort mode or direction does not trigger duplicate network
  requests beyond what pagination (and, on first rejection, the one
  fallback retry) already requires.
- No visible list reshuffle while a folder is still loading chunks.
- App restart preserves the last-selected sort mode and each mode's
  direction.

## Constraints

- Must not regress existing alphabetical (Name) browsing behavior or
  pagination/chunking performance for large folders.
- Must work against servers that do NOT support `SortCriteria` or
  `dc:date`, including servers that actively reject it rather than
  silently ignoring it (graceful degradation per items 3, 5, and 8 above).
- Android/Java, existing Cling UPnP stack — no new UPnP libraries.
- Follow existing SharedPreferences/settings patterns already used in the
  codebase (see `design.md` for exact call sites once identified).
