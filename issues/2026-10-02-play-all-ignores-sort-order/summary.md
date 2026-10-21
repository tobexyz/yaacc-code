# Resolution: "Play all from here" ignores the active sort order

## Root Cause
`ContentListFragment.playAllChildsOfParentFrom(DIDLObject item)` always
re-browsed the parent folder fresh from the server via the no-`orderBy`
`browseSync` overload, independent of whatever Name/Date sort mode and
direction the `BrowseContentItemAdapter` currently had active. It then
rotated that freshly-fetched, unsorted list to start at the tapped
item — so the tapped item correctly played first, but every subsequent
item in the queue followed the server's default order instead of the
sorted order visible on screen. Both of this method's trigger paths
(tapping an audio item row, and its row's dedicated "play all" button)
hit the same code, so both were affected identically; the single-track
"play" button was unaffected since it queues only one item.

## Fix Applied
- `BrowseContentItemAdapter` gained a `getObjects()` accessor exposing
  the adapter's current (already client-side-sorted) item list.
- `playAllChildsOfParentFrom` now builds its playlist from that list —
  filtering to playable `Item`s and rotating to start at the tapped
  item via an extracted `rotateToStart()` helper — instead of
  re-browsing the server. It falls back to the old server re-browse
  (kept as `playAllChildsOfParentFromServer`) only when the adapter
  doesn't have the item (stale reference, or a containers-only folder
  needing a recursive fetch).
- Review cycle 1 caught a follow-on risk the fix itself introduced:
  the first version of `getObjects()` returned a live unmodifiable
  *view* over the adapter's mutable list, read from a background
  thread (`ContentItemPlayTask.doInBackground`) while the UI mutates
  it on the main thread (sort toggle, scroll-triggered `loadMore`) —
  an unsynchronized cross-thread read. Fixed by returning a defensive
  snapshot copy instead.

## Prevention
- `ContentListFragmentPlayAllRotationTest` (7 cases) covers the
  extracted rotation helper's edge cases (not found, already first,
  mid-list, last, empty, null) on the plain JVM.
- Two review cycles (general review only — this fix touches no
  attacker-reachable surface, so the security-reviewer gate wasn't
  invoked, consistent with its mandate covering vulnerabilities/
  misconfigurations rather than general correctness) caught both the
  original ordering bug class and the thread-safety regression the
  fix's first draft introduced.
- A residual, much narrower race (a `ConcurrentModificationException`
  theoretically possible during the defensive copy's single iteration
  if the main thread mutates at that exact instant) was accepted
  rather than fully closed — full closure would need synchronizing
  all of `BrowseContentItemAdapter`'s read/write points on its
  backing list, which is an existing, pre-dating-this-fix pattern
  elsewhere in the same class (e.g. `BrowseItemLoadTask` already reads
  adapter state cross-thread with no synchronization at all). Noted as
  a follow-up for a future broader thread-safety pass on the adapter,
  not blocking for this fix.

## Status
RESOLVED
