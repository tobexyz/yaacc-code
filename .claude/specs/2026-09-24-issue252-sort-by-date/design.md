# Issue #252: "Sort by Date" / "Recently Added" for UPnP folders

Source: https://github.com/tobexyz/yaacc-code/issues/252
Branch: `feat/issue252` (off `develop`)

## What's requested

Folder browsing in the Content tab is strictly alphabetical. Requester wants a
client-side "Sort by Date / Date Modified" toggle, or a "Recently Added" view,
so newly added files don't require scrolling the whole alphabetical list.

## Where this lives

This is yaacc acting as a **client**, browsing someone else's DLNA server (the
requester uses UAPP) — the "Content" tab, not yaacc's own embedded server
(`de.yaacc.upnp.server.*`, a separate code path/role).

Call chain: `ContentListFragment` → `BrowseContentItemAdapter.loadMore()` →
`BrowseItemLoadTask` → `UpnpClient.browseSync(pos, from, chunkSize)` → Cling's
`Browse` UPnP action.

## Two independent levers, both currently unused

**1. Server-side sort is already half-wired, just not exposed.**
Cling's `Browse` action (`de/yaacc/upnp/callback/contentdirectory/Browse.java:61,74`)
accepts `SortCriterion... orderBy` and sends it as the UPnP `SortCriteria`
input — part of the ContentDirectory spec (e.g. `-dc:date` for newest-first).
But `UpnpClient.browseSync(Position, firstResult, maxResult)` (the overload
`BrowseItemLoadTask` actually calls) never forwards any `orderBy`, so
`SortCriteria` is always sent empty and the server falls back to its own
default order. Threading `SortCriterion` through costs nothing extra over the
network — the parameter is already plumbed, just unused at the call site.

**2. Client-side re-sort has no seam yet, and it collides with pagination.**
- `BrowseContentItemAdapter.loadMore()` is infinite-scroll pagination (default
  chunk size 50, configurable in settings). `BrowseItemLoadTask.onPostExecute`
  always adds **containers first, then items**
  (`itemAdapter.addAll(content.getContainers()); itemAdapter.addAll(content.getItems());`),
  each chunk appended in server order.
- A correct client-side sort needs the whole folder's contents before
  sorting. Conflicts with lazy pagination: either fetch the whole folder
  up front when sort-by-date is active (defeats chunking, worse for
  large folders/slow connections), or re-sort the list on every incoming
  chunk (list visibly reorders while still loading).
- Date metadata is optional per DIDL: `DIDLObject.Property.DC.DATE` exists in
  the model, but only appears if the *source server* populates `<dc:date>`
  on its items — not guaranteed for every server (the requester's own server,
  UAPP, does return it though).
- Current rendering always groups folders before files. A straight
  "newest first" sort spanning both would break that grouping unless folders
  stay pinned to the top regardless of sort mode.

## Recommended approach

Layer both:
1. Thread `SortCriterion` through the unused overload down to
   `BrowseItemLoadTask`/`loadMore`, defaulting to `-dc:date` when the user
   picks "recently added". Servers that support it just work — no client
   sorting needed, no extra requests.
2. Add a client-side fallback sort applied to what's already loaded, for
   servers that ignore `SortCriteria` (per the issue: "for local media
   servers that do not natively provide this sorting node"). Gate it so it
   only fully reorders once `allItemsFetched` is true, avoiding the
   mid-load reshuffle.

## Decisions (2026-09-24)

1. **Toggle placement**: segmented buttons in the list header row, next to
   `contentListCurrentFolderName` in `fragment_content_list.xml` — always
   visible (e.g. "Name" / "Date"), current selection highlighted, one tap to
   switch. No global Settings preference.
2. **Persistence**: sort order is saved to SharedPreferences and applied
   globally across all folders until changed again (does not reset on app
   restart or folder navigation).
3. **Folder grouping**: date sort **fully interleaves** containers and items
   by date — this breaks the current folders-before-items convention when
   "Date" is selected. (Only applies in Date mode; "Name" mode keeps the
   existing folders-first alphabetical behavior.)
4. **Missing `dc:date`**: if items in a folder lack `dc:date` entirely, the
   Date button is disabled/hidden for that folder/server rather than being
   offered as a no-op.

## Not yet decided / not started

No code changes have been made on this branch yet. Requirements and task
breakdown are next; implementation starts once `tasks.md` is written.
