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

## Cycle 2 — 2026-10-02
Reviewing: follow-up commit `1ededc9` (on top of `aef648e`), addressing
the Cycle 1 Warning.

### Critical
(none)

### Warning
(none)

### Suggestion

- `yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java:422` —
  the theoretical residual race (main thread mutates `objects` at the
  exact instant `new ArrayList<>(objects)` is iterating it inside the
  copy constructor, still a live `LinkedList` with no `Collections
  .synchronizedList`/lock) is real but has shrunk from "the whole
  `contains()` + `stream().filter().collect()` read sequence in
  `playAllChildsOfParentFrom`" to "one list-copy loop inside `getObjects
  ()`" — see item 3 below for why this doesn't block. Worth a follow-up
  issue if `BrowseContentItemAdapter` ever gets a real synchronization
  pass (`getItem`/`getItemCount`/`addAll`/`clear`/`sortObjects` are
  equally unsynchronized and already read cross-thread elsewhere, e.g.
  `BrowseItemLoadTask.doInBackground()` calling `itemAdapter
  .getSortMode()`/`isServerSortRejected()` — see item 3), not something
  to fix piecemeal in this adapter method alone.

### Findings detail by review item

**1. Diff matches the described change** — `git show 1ededc9` confirms
exactly one production change: `BrowseContentItemAdapter.java:422`,
`return Collections.unmodifiableList(objects);` →
`return Collections.unmodifiableList(new ArrayList<>(objects));`, plus
an expanded Javadoc on `getObjects()` (see item 4) and a commit message
explicitly scoping out full synchronization as a documented follow-up.
No other production file touched; no test file touched (correctly —
this fix doesn't change any caller-visible behavior in the happy path,
only the thread-safety characteristics of the accessor, so no new test
is strictly necessary and the existing `ContentListFragmentPlayAllRotationTest`
suite + full `testDebugUnitTest` run is the right verification). Both
`ArrayList` and `Collections` were already imported
(`BrowseContentItemAdapter.java:51,53`), so this is a clean, minimal,
compiling change — confirmed by the green build below.

**2. Does the defensive copy meaningfully reduce the Cycle 1 risk, and
is this the fix that was actually asked for** — Yes to both. Re-reading
`playAllChildsOfParentFrom` (`ContentListFragment.java:533-553`):
`bItemAdapter.getObjects()` is called exactly **once**, bound to
`currentObjects`, and every subsequent operation
(`currentObjects.contains(item)`, `currentObjects.stream().filter(...)
.collect(...)`, and the `rotateToStart(sortedItems, target)` call built
from that filtered copy) operates on that one snapshot — not on `objects`
live. This was precisely the Cycle 1 complaint: "iterating the adapter's
`LinkedList` with no synchronization while the main thread can
concurrently call `addAll`/`clear`/`sortObjects` on the very same list"
across that whole read sequence (`contains()` possibly seeing one state,
then `stream().filter()` seeing a different, concurrently-mutated state
mid-collect — the "torn read" scenario Cycle 1 called out by name). With
a snapshot copy, that entire sequence now runs against an isolated
`ArrayList` the main thread has no reference to and cannot touch, so the
CME-during-iteration and torn-read hazards described in Cycle 1 are both
eliminated for every line after the copy is made. This is exactly Cycle
1's option (a) ("have `getObjects()` return a defensive copy... cheap...
removes the live-aliasing hazard entirely"), which Cycle 1's own verdict
called "the least invasive" and the one it explicitly recommended in its
Blocking reason. The implementing agent's scope call — fix the accessor
that was the actual subject of the Warning, and treat synchronizing the
adapter's mutators/other read points as a separate, broader concern — is
not a scope dodge; it's what was asked for. Options (b) (synchronize all
of `objects`) and (c) (force `playAllChildsOfParentFrom` onto the main
thread) were offered as alternatives in Cycle 1, not as additional
requirements on top of (a), and Cycle 1 itself said (a) alone was
sufficient to resolve the Warning.

**3. Residual risk: CME inside `new ArrayList<>(objects)` itself** —
Real but narrow, and does not warrant another FAIL. Reasoning:
  - *Window size*: the exposure is now a single `for`-loop over `objects`
    inside the `ArrayList` copy constructor (`ArrayList(Collection)`),
    versus the previous exposure across `contains()` **and** a full
    `stream().filter().collect()` pipeline **and** whatever the caller
    did with the live view afterward (unbounded, since it was a view).
    The constructor loop for a list of "a few hundred entries at most"
    (Cycle 1's own sizing estimate) is a handful of microseconds. This is
    a textbook "narrowed the race window by orders of magnitude" fix,
    which is the standard, accepted shape of a defensive-copy mitigation
    for benign data races — it does not claim to be a full fix and isn't
    represented as one anywhere (see item 4).
  - *Trigger conditions in practice*: for the window to be hit, a user
    would need to trigger a sort-mode/direction toggle or scroll-driven
    `loadMore()` (both explicit, deliberate UI actions) at the exact
    microsecond window while a previously-tapped `PLAY_ALL` background
    task happens to be inside this one constructor call. This is
    meaningfully narrower in practice than, say, a background sync timer
    racing a user action, because one side of the race is itself a
    direct, singular user tap (`PLAY_ALL`) that completes quickly end to
    end.
  - *Consistency with the existing codebase*: `BrowseContentItemAdapter`
    already has other unsynchronized cross-thread accessors that are
    *not* in scope for this issue and were never flagged by Cycle 1 —
    e.g. `BrowseItemLoadTask.doInBackground()`
    (`BrowseItemLoadTask.java:51-52`) reads `itemAdapter.getSortMode()`
    and `itemAdapter.isServerSortRejected()` from its own background
    thread while the main thread can concurrently call
    `setSortMode()`/mutate `serverSortRejected` via `clear()`/
    `markServerSortRejected()` — the same unsynchronized-field-read-
    across-threads pattern, pre-existing and untouched by either commit
    in this issue. That confirms this is an established, accepted risk
    class in this codebase's current architecture (`AsyncTask`
    background workers reading adapter state without synchronization),
    not a new category of problem this specific fix is uniquely
    responsible for closing. Holding this one `getObjects()` call to a
    stricter standard than every other adapter accessor already in
    production would be inconsistent, not more correct.
  - *Severity if it did fire*: unchanged from Cycle 1's analysis (a CME
    would crash the `PLAY_ALL` background task and, via `AsyncTask`'s
    uncaught-exception propagation, the app) — but the probability is now
    low enough, and consistent enough with already-accepted risk
    elsewhere in this file, that it's a **Suggestion**-level follow-up
    (logged above), not a blocking Warning. A full synchronization pass
    across `objects`'s read/write points is legitimately a separate,
    larger piece of work (touches `getItem`, `getItemCount`, `addAll`,
    `clear`, `sortObjects`, and every call site), and bundling it into
    this fix would violate the single-responsibility spirit of the
    original task (fix play-all's sort order; this follow-up fixes the
    thread-safety regression that introduced). Verdict: accept the
    tradeoff, track it as a follow-up.

**4. Javadoc accuracy** — Read the new Javadoc
(`BrowseContentItemAdapter.java:407-421`) in full against the actual
code and the real call graph. Every claim checks out:
  - "this method is also called from a background thread... invoked
    from `ContentItemPlayTask#doInBackground`" — confirmed;
    `ContentItemPlayTask.java:46` calls `parent.playAllChildsOfParentFrom`
    inside `doInBackground`, and `AsyncTask.execute()` runs
    `doInBackground` off the main thread by contract.
  - "`objects` is mutated in place from the main thread (`addAll`/
    `clear`/`sortObjects`, driven by `setSortMode`/`toggleDirection`/
    `loadMore`)" — confirmed by reading those methods
    (`BrowseContentItemAdapter.java:276-316`); all are reached from UI
    callbacks.
  - "A live `Collections.unmodifiableList(objects)` view does not
    protect against that: a concurrent mutation... can still throw a
    `ConcurrentModificationException` or yield a torn read" — accurate
    description of why the Cycle 1 fix was needed; matches Cycle 1's own
    analysis.
  - The `@return` line ("an unmodifiable snapshot copy... taken at the
    time of the call; never `null`") accurately describes the new
    behavior — it's genuinely a copy now, genuinely taken synchronously
    at call time, and `objects` is never reassigned to `null` anywhere in
    the class (only cleared in place via `clear()`), so `getObjects()`
    can't return `null` here either.
  - What the Javadoc does **not** claim is "this method is now fully
    thread-safe" or "this eliminates all concurrency risk" — it correctly
    scopes its claim to what the fix actually does (removes the
    live-view hazard) without overselling it. A future maintainer reading
    this Javadoc would correctly understand both that there's a known
    cross-thread access pattern here and that this accessor copies rather
    than aliases — they would not be misled into thinking the adapter as
    a whole is synchronized. No inaccuracy found.

### Tests
- [x] All tests passing — `./gradlew :yaacc:testDebugUnitTest --rerun-tasks` → `BUILD SUCCESSFUL`, all per-class JUnit XML reports show `failures="0" errors="0"` across the full suite (34 test classes), including the untouched 7-case `ContentListFragmentPlayAllRotationTest` (unaffected by this commit, which only changes `getObjects()`'s internals, not any method under direct test there).
- [x] Coverage adequate for this change — the change is a one-line accessor fix plus Javadoc; it has no new caller-visible behavior to unit-test (the single call site's observable contract — "returns the current children in on-screen order" — is unchanged, only the aliasing semantics of the returned reference changed, which isn't practically assertable from a single-threaded JVM unit test without a contrived concurrent-mutation harness that would be disproportionate to the residual risk accepted in item 3).

### Verdict: PASS

The Cycle 1 Warning is resolved: `getObjects()` no longer exposes a live
view over the adapter's mutable backing list, so the read sequence in
`playAllChildsOfParentFrom` (the actual site of the original hazard) now
operates on an isolated snapshot. The theoretical residual window inside
the copy constructor itself is real but narrow, consistent with an
already-accepted unsynchronized-cross-thread-read pattern elsewhere in
this same adapter (`BrowseItemLoadTask`), and explicitly documented as a
follow-up rather than silently dropped — the right shape of tradeoff for
a scoped bug fix, not a reason to block. No Critical or Warning findings
remain; full test suite green.
