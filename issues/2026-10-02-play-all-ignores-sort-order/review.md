# Review: Play-all-from-item now follows the active client-side sort order

Commit reviewed: `aef648e` on branch `feat/issue252`
Files: `yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java`,
`yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java`,
`yaacc/src/test/java/de/yaacc/browser/ContentListFragmentPlayAllRotationTest.java`

## Cycle 1 — 2026-10-02
Reviewing: the full fix (single commit, no task groups)

### Critical
(none)

### Warning

- `yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java:410` /
  `yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java:537-544` —
  **unsynchronized cross-thread read of the adapter's mutable backing
  list.** `getObjects()` returns `Collections.unmodifiableList(objects)`,
  which is a live *view* over the same `LinkedList<DIDLObject> objects`
  field the adapter mutates in place (`addAll()` at line 276-289,
  `clear()` at 291-300, `sortObjects()` at 307-316, all reached from
  `setSortMode`/`toggleDirection`/`loadMore` — all driven from UI
  callbacks on the main thread). `playAllChildsOfParentFrom`, however, is
  invoked from `ContentItemPlayTask.doInBackground()`
  (`yaacc/src/main/java/de/yaacc/browser/ContentItemPlayTask.java:46-47`),
  i.e. **a background thread** (`AsyncTask.execute()` never runs
  `doInBackground` on the main thread). The new fast path calls
  `currentObjects.contains(item)` and
  `currentObjects.stream().filter(...).collect(...)` on that live view,
  iterating the adapter's `LinkedList` with no synchronization while the
  main thread can concurrently call `addAll`/`clear`/`sortObjects` on the
  very same list (e.g. the user scrolls to trigger `loadMore()`, or taps
  a sort toggle, while a background `PLAY_ALL` task is mid-flight from an
  earlier tap). `LinkedList` is not thread-safe; this is a textbook setup
  for a `ConcurrentModificationException` (crashing the `PLAY_ALL`
  background task — and AsyncTask propagates an uncaught
  `doInBackground` exception, which crashes the app) or, more subtly, a
  torn/inconsistent read (e.g. `contains()` finds the item but the
  subsequent `stream().filter()` sees a list mutated mid-collect,
  producing a queue that doesn't match either the old or the new state).
  This risk did **not** exist before the fix — the pre-fix
  `playAllChildsOfParentFrom` never touched adapter state, only did an
  independent network fetch. The fix is correct in its happy-path logic
  but introduces this new hazard by reaching into adapter-owned mutable
  state from a different thread.
  **Fix options**: (a) have `getObjects()` return a defensive copy
  (`new ArrayList<>(objects)`, still wrapped unmodifiable) instead of a
  live view — cheap, since this list is only ever a few hundred entries
  at most, and removes the live-aliasing hazard entirely; or (b)
  synchronize access to `objects` (e.g. `Collections.synchronizedList`
  plus synchronizing the iteration block in
  `playAllChildsOfParentFrom`); or (c) ensure `playAllChildsOfParentFrom`
  only ever runs on the main thread (it currently doesn't, by design of
  `ContentItemPlayTask`). Option (a) is the least invasive given the
  existing architecture.

### Suggestion

- `yaacc/src/test/java/de/yaacc/browser/ContentListFragmentPlayAllRotationTest.java` —
  good coverage of the extracted `rotateToStart` helper (not found, found
  at index 0, found in the middle, found at the end, single-element list,
  `items == null`), but there's no case for a non-null **empty** input
  list (`rotateToStart(Collections.emptyList(), someItem)`). Low risk
  given the trivial `indexOf`-on-empty-list semantics, but cheap to add
  for completeness and it's the one edge case explicitly called out in
  the method's own null-list test sibling.
- `yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java:567` —
  `playAllChildsOfParentFromServer` is a reasonable name but reads oddly
  next to `playAllChildsOfParentFrom`; consider
  `playAllChildsOfParentFromServerBrowse` or similar if revisited, purely
  for clarity of why it's a *separate* method (no action needed now).

### Findings detail by review item

**1. Fast-path / fallback split correctness** — Traced the full method.
Order of checks: `bItemAdapter != null` → `currentObjects.contains(item)`
→ `!sortedItems.isEmpty()`, with a single terminal
`playAllChildsOfParentFromServer(item)` fallback call reached by falling
through any of the three negative branches. This is complete and
correctly ordered: no case exercises the fast path with an adapter that
is `null`, no case silently skips the fallback when the item isn't in
the adapter's list, and the containers-only-folder case (item found, but
`sortedItems` empty after filtering to `Item`) correctly falls through
to the fallback rather than returning an empty queue. Verified the
on-screen-order claim: `BrowseContentItemAdapter.objects` is mutated
in-place by `sortObjects()` (`compareByDate`/`compareByNameGrouped`)
before these are the objects on screen, and `getObjects()` exposes that
same already-sorted list; filtering it to `Item` instances preserves
relative order, so `rotateToStart` on that filtered list reproduces
exactly the on-screen sequence (minus containers, which is expected —
see item 4). Confirmed `DIDLObject.equals()`/`hashCode()` are
id-based (`DIDLObject.java:883-897`, with a strict `getClass()` check),
so `List.contains`/`indexOf` correctly match by content id rather than
reference identity in both the fast and fallback paths. No correctness
gap found here beyond the threading issue above.

**2. `getObjects()` unmodifiability and reference-safety** — It is a true
unmodifiable *view* (`Collections.unmodifiableList`), not just a
documentation convention: any attempted mutation via the returned
reference throws `UnsupportedOperationException` at runtime, so external
code cannot corrupt the adapter's list through this accessor directly.
However, because it's a *view* (not a copy) over the adapter's live,
mutable `objects` field, holding or iterating that reference while the
adapter mutates the backing list concurrently is a real risk — see the
Warning above. The single production call site uses it synchronously
and doesn't store it beyond the one method invocation, which limits (but
does not eliminate) the risk, because the adapter mutation and the
`playAllChildsOfParentFrom` read happen on *different threads*
(main vs. `AsyncTask` background), not just different points in the same
call stack. A same-thread, same-call-stack-only usage would indeed make
this a non-issue; that is not what's happening here.

**3. `rotateToStart` vs. pre-fix inline rotation** — Diffed against
`git show aef648e^:yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java`.
Old logic:
```java
int index = items.indexOf(item);
if (index > 0) {
    List<Item> tempItems = new ArrayList<>(items.subList(index, items.size()));
    tempItems.addAll(items.subList(0, index));
    items = tempItems;
}
```
New `rotateToStart`:
```java
int index = items.indexOf(item);
if (index <= 0) {
    return new ArrayList<>(items);
}
List<Item> rotated = new ArrayList<>(items.subList(index, items.size()));
rotated.addAll(items.subList(0, index));
return rotated;
```
Edge cases match exactly: not found (`index == -1`) → no rotation in
both; already first (`index == 0`) → no rotation in both; found
mid/end-of-list → identical subList-based rotation in both. The only
difference is the new version always returns a fresh `ArrayList` copy
even in the no-rotation case, whereas the old code reused the original
list reference in that case — a non-behavioral difference (no caller
depends on reference identity; `initializePlayers` only reads the
list). `item instanceof Item ? (Item) item : null` in both
`playAllChildsOfParentFrom`'s fast path and
`playAllChildsOfParentFromServer` reproduces the old code's de facto
behavior for non-`Item` (`Container`) inputs: the old code relied on
`DIDLObject.equals()`'s strict `getClass()` check to make
`items.indexOf(container)` return `-1` against a `List<Item>`; the new
code reaches the same `-1` explicitly by nulling the target first.
Confirmed equivalent by inspection — no test gap here since this is
exercised indirectly by the "target not found"/"target null" test
cases, which cover the same code path.

**4. Containers-only-folder / container-row fallback note** — Confirmed
pre-existing, not a regression. `BrowseContentItemAdapter` shows a
visible "play all" button for `Container` rows too
(`BrowseContentItemAdapter.java:502-507`), and `Container` is not an
`Item` subtype (`Item extends DIDLObject`, `AudioItem`/`VideoItem`/
`ImageItem`/`PlaylistItem`/`TextItem extends Item`; `Container` is a
sibling of `Item`). So tapping a container's "play all" always resulted
in `target == null` in `rotateToStart`, both before this fix (via the
`getClass()` mismatch route) and after (via the explicit
`instanceof Item` null-out) — no rotation occurs for a container target
in either version, and the container row itself is never included in
the played queue (it's filtered out of `sortedItems`/was never in
`List<Item>` server results). The containers-only-folder path (adapter
has items but zero are `Item`s, or the server fallback finds zero
`Items` but nonzero `Containers`) likewise matches old behavior via
`playAllChildsOfParentFromServer`'s unchanged
`upnpClient.toItemList(result.getResult())` branch. No new regression.

**5. Test coverage for `rotateToStart`** — The 7 cases are adequate for
the helper's actual branches (not-found, first, middle, last,
single-element, `items == null`, already-first-is-a-copy). Missing: a
non-null **empty** list case (logged as a Suggestion, not blocking —
the existing `null`-items test already proves the degenerate-input
branch works, and an empty non-null list exercises the exact same
`indexOf` → `-1` → copy-and-return path as "not found", so the risk of
a latent bug here is low). No test exists at the
`playAllChildsOfParentFrom`-level (fast path vs. fallback selection,
adapter-null, item-not-in-adapter, containers-only-folder) — that's a
reasonable scope cut given `ContentListFragment`'s other dependencies
(`upnpClient`, Android `Fragment` lifecycle) aren't readily testable
as plain JVM unit tests in this codebase's existing test setup, and the
dispatch logic itself is simple/visually-verifiable, but it does mean
the fast/fallback *selection* logic (items 1 above) is currently only
reviewed by inspection, not covered by an automated test — flagged as a
Suggestion, not a blocking gap, since the underlying rotation logic it
dispatches to is fully covered.

### Tests
- [x] All tests passing — `./gradlew :yaacc:testDebugUnitTest` → `BUILD SUCCESSFUL`, 0 failures/errors across the full suite, including all 7 new `ContentListFragmentPlayAllRotationTest` cases (`yaacc/build/test-results/testDebugUnitTest/TEST-de.yaacc.browser.ContentListFragmentPlayAllRotationTest.xml`: `tests="7" failures="0" errors="0"`).
- [x] Coverage adequate for the extracted `rotateToStart` helper itself; the fast-path/fallback dispatch logic in `playAllChildsOfParentFrom` is unit-tested only indirectly (see item 5 above) — acceptable as a Suggestion, not a blocker.

### Verdict: FAIL

Blocking reason: one Warning — the unsynchronized cross-thread read of
`BrowseContentItemAdapter.objects` via the new `getObjects()` live view,
from `playAllChildsOfParentFrom` running on an `AsyncTask` background
thread while the adapter is mutated on the main thread. This is a real
(if timing-dependent) crash/correctness risk introduced by this fix,
not present before it. Recommended fix: make `getObjects()` return a
defensive copy rather than a live `Collections.unmodifiableList` view
(see Warning for details) — this is a small, localized change that
doesn't require restructuring the fast/fallback dispatch logic, which is
otherwise correct.
