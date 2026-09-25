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

## Post-ship bug report and feature request (2026-09-26)

After Groups 1-3 shipped and passed both review gates, the user reported
browsing a folder in Date mode where the server has no `dc:date` support
showed an **empty** folder, and asked for an ascending/descending toggle
with per-direction icons.

### Bug root cause

Confirmed by reading `ContentDirectoryBrowseActionCallback.failure(...)`
(`yaacc/src/main/java/de/yaacc/upnp/callback/contentdirectory/ContentDirectoryBrowseActionCallback.java:77-82`)
and `ContentDirectoryBrowseResult` (`.../ContentDirectoryBrowseResult.java`):
on a UPnP action **failure** (which a server can legitimately return for an
unsupported `SortCriteria` value per the ContentDirectory spec — not every
server silently ignores it), `browsingResult.setResult(didl)` is never
called, so `ContentDirectoryBrowseResult.getResult()` stays `null` (its
constructor default). `BrowseItemLoadTask.onPostExecute` treats a `null`
`content` as "nothing here" and calls `itemAdapter.clear()` — emptying the
folder instead of falling back to unsorted results. This was an unstated
assumption in the original design (`design.md`'s "Two independent levers"
section only considered servers that *ignore* `SortCriteria`, not ones
that error on it).

### Bug fix decision

`BrowseItemLoadTask.doInBackground` retries the same chunk request without
`orderBy` when the sorted attempt's `ContentDirectoryBrowseResult` reports
a failure (`getUpnpFailure() != null`) or is `null`. On that first
rejection, `BrowseContentItemAdapter` remembers (a per-adapter-instance
flag, reset in `clear()`) that this folder's server rejected date sort, so
subsequent chunk requests within the same folder load skip the doomed
sorted attempt entirely rather than retrying-and-failing on every page.
Existing `isDateSortAvailable()` disabling and the client-side fallback
sort both continue to work unchanged off the (now successfully fetched)
unsorted results.

### Ascending/descending feature decisions

1. **Scope**: applies to both Name (A-Z ↔ Z-A) and Date (newest-first ↔
   oldest-first) — not Date-only, per user's explicit choice over the
   narrower option.
2. **Interaction model**: tapping the *already-selected* button flips
   that mode's direction and re-sorts. Tapping the *other* button
   switches mode, using that mode's own last-remembered direction (not a
   shared single direction flag) — so switching back and forth between
   Name and Date doesn't clobber each mode's separately-chosen direction.
3. **Icons**: each button's icon fully swaps to reflect its own current
   direction (not a fixed base icon with a separate direction indicator
   next to it), per user's explicit choice. Four drawables needed:
   ascending/descending variants of both the existing
   `ic_baseline_sort_by_alpha_32` and `ic_baseline_date_range_32`.
4. **Persistence**: two additional SharedPreferences keys (name-ascending,
   date-ascending) alongside the existing sort-mode key, following the
   same `setting_strings.xml` pattern, not exposed in the Settings screen.
5. **Server-side direction**: `SortCriterion`'s `ascending` boolean
   (`docs/tech.md`: `new SortCriterion(ascending, "dc:date")`,
   `toString()` prefixes `-` when descending) is already direction-aware —
   Date mode's server-side attempt just needs to pass the persisted
   direction instead of always `false`.
