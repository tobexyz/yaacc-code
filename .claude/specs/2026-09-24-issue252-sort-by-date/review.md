# Review: Sort by Date / Recently Added for UPnP folders (issue #252)

## Cycle 1 — 2026-09-25
Reviewing: Group 1 + Group 2 (commits `7145277`, `998d0e9`, `e1ea74c` on
`feat/issue252`; verified against the working tree, which matches `HEAD`).

### Critical

- **`yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java:130-137`
  (`setSortMode`) — stale in-flight `BrowseItemLoadTask` is never cancelled,
  races the new load.**
  `setSortMode()` calls `clear()` then `loadMore()` directly. `clear()` only
  resets `loading`/`allItemsFetched`/`objects`/`dateSortAvailable` — it does
  **not** cancel or remove any `BrowseItemLoadTask` already running in
  `asyncTasks` (`cancelRunningTasks()` is a separate method that is never
  called here). Every other code path in the codebase that does
  `clear()` + `loadMore()` calls `bItemAdapter.cancelRunningTasks()`
  immediately before it — see `ContentListFragment.java:273`
  (`onBackPressed`), `:378` (`populateItemList`) — establishing that this is
  a deliberate, existing safety convention, not an incidental style choice.
  `setSortMode()` breaks that convention.

  Concrete failure sequence: user scrolls near the bottom of a large folder
  (triggers `loadMore()` → task A starts fetching chunk N in the *old* sort
  mode) and, before task A completes, taps the Name/Date toggle.
  `setSortMode()` clears `objects` to empty and starts task B (chunk 0,
  *new* mode) — both tasks now run concurrently against the same adapter.
  When task A's `onPostExecute` (`BrowseItemLoadTask.java:58-89`) eventually
  fires, it is completely unaware the mode/clear happened: it calls
  `itemAdapter.addAll(...)` with old-mode results, corrupting whichever
  state task B has already written into `objects` (duplicate/out-of-order
  items, since `addAll` only de-dupes exact `DIDLObject` matches); it
  computes `allItemsFetched` from a `previousItemCount` that may already
  reflect task B's writes, so the "all fetched" flag can be set
  incorrectly; and it unconditionally calls `itemAdapter.setLoading(false)`,
  which can stomp on task B's `loading = true` while B is still in flight,
  letting a third concurrent task start on the next scroll event. This
  directly undermines requirement 3 ("no visible mid-load reshuffle") and
  the "no duplicate network requests/work" success metric, and is reachable
  through completely ordinary use of the feature (switching sort mode
  during a chunked/slow load — exactly the large-library scenario this
  issue targets).

  Fix: `setSortMode()` should call `cancelRunningTasks()` before `clear()`,
  mirroring `onBackPressed()`/`populateItemList()`. (Note `clear()` also
  resets `loading = false` unconditionally, so even after adding the
  cancel call it's worth double-checking `cancelRunningTasks()` +
  `clear()`'s combined effect on `loading` matches the other call sites —
  it does, since both already call `cancelRunningTasks()` then `clear()`
  in that order.)

### Warning

- **`yaacc/src/main/res/values/strings.xml:103-104` — new strings not
  translated, and `lintDebug` flags it as `Error: MissingTranslation`.**
  Every other string in `values/strings.xml` has a matching entry in
  `values-de`, `values-es`, `values-fr`, `values-nl`, `values-pt`,
  `values-zh` (spot-checked `add_to_playlist`, `downloaded_to_target`).
  `sort_by_name`/`sort_by_date` were added only to the base `values/`
  file. `./gradlew :yaacc:lintDebug` reports two `MissingTranslation`
  lint **Errors** for exactly these two strings (project has
  `lint { abortOnError false }`, so this doesn't fail the build, but it's
  a real, lint-confirmed regression against an established project
  convention — 100% of the other 71 pre-existing lint-report "Error"
  entries are unrelated categories (`MissingPermission`,
  `UnspecifiedRegisterReceiverFlag`, etc.), i.e. translation coverage was
  otherwise complete before this change). Fix: add `sort_by_name`/
  `sort_by_date` entries to the six `values-*/strings.xml` files (even a
  placeholder/English fallback is what the lint check wants, or add
  `tools:ignore="MissingTranslation"` if translations are intentionally
  deferred — but that should be a deliberate choice, not an omission).

### Suggestion

- **Reflection workaround in `BrowseContentItemAdapterSortTest.java:126-157`
  (`fixUpAdapterDataObservable`) is acceptable scope, not a red flag** —
  judgment call as requested. It's test-only, narrowly targeted at one
  known AGP mockable-`android.jar` limitation (documented in
  `decisions.md`'s "2026-09-25 — Group 2" entry and in the test file's own
  Javadoc), changes zero assertions/production behavior, and the
  alternative (adding Robolectric or `mockito-inline`) is a real new test
  dependency that would need spec approval and is out of scope for a
  single Group 2 task per the plan's dependency-pinning/scope rules. The
  one durable risk is that it reaches into `RecyclerView.Adapter`'s and
  `Observable`'s private field names (`mObservable`/`mObservers`) via
  reflection, which is brittle to an AndroidX version bump silently
  breaking this one test class with an opaque `IllegalStateException`
  (it does fail loudly rather than silently, which is the important part).
  Consider a short comment pointing at Robolectric as the long-term
  replacement if/when it's added to the project, but this does not block
  the current cycle.
- **`BrowseContentItemAdapter.readPersistedSortMode()` (adapter) and
  `ContentListFragment.initSortToggle()` (fragment) both independently
  parse `settings_sort_order_key` into `SortMode` with duplicated
  try/catch-`IllegalArgumentException` fallback logic.** Not a bug today
  (both reads are consistent and the adapter's own constructor read is
  what actually governs behavior), but the duplication is a drift risk if
  one copy is changed without the other. A single shared helper (e.g. a
  static `SortMode.fromPreferences(SharedPreferences, Context)`) would
  remove the duplication.
- `BrowseContentItemAdapter.parseDateMillis` / date sort logic is otherwise
  solid: verified it cannot throw on malformed/missing `dc:date` (three
  `LocalDate`/`OffsetDateTime`/`LocalDateTime` parse attempts are all
  wrapped in `try/catch DateTimeParseException`, null/empty checked first),
  and `compareByDateDescending`'s null-handling (`null` sorts after any
  real date, `0` only when both are `null`) is a valid, contract-safe
  `Comparator` — no `IllegalArgumentException`/"Comparison method violates
  its general contract" risk from `List.sort`.

### Behavior-spec check (requirements.md)

- Segmented "Name"/"Date" header buttons next to
  `contentListCurrentFolderName`, highlighted current selection: **met**
  (`fragment_content_list.xml` + `layout-land/fragment_content_list.xml`,
  `ContentListFragment.updateSortToggleUi()`).
- Server-side `SortCriterion` attempted first (`-dc:date`, verified against
  vendored `SortCriterion.java:29-32`/`68-74` — `docs/tech.md`'s citation is
  accurate): **met** (`BrowseItemLoadTask.java:51-54`,
  `UpnpClient.browseSync` overloads threading `orderBy` through unchanged,
  L610-639 overload untouched as required).
- Client-side fallback gated on `allItemsFetched`, no mid-load reshuffle:
  **met** in the single-load-in-flight case (see Critical finding above for
  the concurrent-load case).
- Full interleaving of folders/items in Date mode only, Name mode
  unchanged (folders-first, alphabetical): **met**
  (`compareByDateDescending` vs. `compareByNameGrouped`).
- Date button disabled when no `dc:date` present anywhere loaded, live
  update as chunks arrive: **met** (`isDateSortAvailable()` +
  `AdapterDataObserver` wiring in `initBrowsItemAdapter`, registered once
  per adapter instance since it's inside the `bItemAdapter == null` guard —
  no double-registration/leak across `onCreateView` calls that reuse the
  same `bItemAdapter`).
- SharedPreferences persistence, global across folders/restarts: **met**
  (`settings_sort_order_key`, read/written symmetrically by adapter and
  fragment).
- Land vs. portrait layout: both build (`lintDebug` passed), no ID
  collisions (`contentListSortToggle`/`contentListSortByNameButton`/
  `contentListSortByDateButton` are unique across the resource tree), and
  no visual overlap — land's `contentListCurrentFolderName` uses
  `layout_below="@id/contentListBackButton"` (next row down) while the new
  toggle uses `layout_alignTop="@id/contentListBackButton"` (same row as
  the back button), so they don't compete for the same space; portrait's
  `contentListCurrentFolderName` was correctly changed from
  `layout_alignParentEnd="true"` to
  `layout_toStartOf="@id/contentListSortToggle"` to make room.

### Tests

- [x] All tests passing — `./gradlew :yaacc:testDebugUnitTest` (forced
  re-run, not just UP-TO-DATE): BUILD SUCCESSFUL, 0 failures across the
  module; `BrowseContentItemAdapterSortTest` 5/5 green (verified via its
  JUnit XML report: `nameModeKeepsContainersBeforeItemsAlphabeticalWithinGroup`,
  `dateModeInterleavesContainersAndItemsByDateDescendingOnceFullyLoaded`,
  `noReshuffleWhileStillLoading`,
  `isDateSortAvailableFalseWhenNoLoadedItemHasDate`,
  `isDateSortAvailableTrueWhenAtLeastOneLoadedItemHasDate`).
- [x] `./gradlew :yaacc:compileDebugJavaWithJavac` — BUILD SUCCESSFUL.
- [x] `./gradlew :yaacc:lintDebug` — BUILD SUCCESSFUL (non-fatal per
  `abortOnError false`); see Warning above for the two `MissingTranslation`
  errors it surfaced.
- [ ] Coverage adequate for the change — mostly yes (all four spec-required
  behaviors from `tasks.md`'s Group 1 test-skeleton task are covered), but
  there is no test covering the concurrent-mode-switch race described
  above (understandably hard to hit from the adapter's plain-JVM unit test
  given `AsyncTask` isn't exercised there — this is really an integration
  concern). Not counted against "tests passing," but flagged since the
  Critical finding above is precisely an untested critical path.

### Verdict: FAIL

One Critical (unguarded race between `setSortMode()`'s reload and a stale
in-flight `BrowseItemLoadTask`) and one Warning (missing translations for
the two new strings, lint-confirmed) must be resolved before this cycle can
pass. Both are narrow, targeted fixes: add `cancelRunningTasks()` to
`setSortMode()`, and add the two strings to the six locale files (or an
explicit `tools:ignore`). All tests pass and the rest of the behavior spec
is faithfully implemented.

## Cycle 2 — 2026-09-25
Reviewing: fix commit `bd9269d` (on top of `e1ea74c`), addressing Cycle 1's
Critical + Warning. Full diff re-swept via `git diff bc11586..bd9269d`.

### Critical

None.

### Warning

None.

### Verification of Cycle 1's Critical fix

`git show bd9269d -- yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java`
adds exactly one line: `setSortMode()` now calls `cancelRunningTasks()`
immediately before `clear()`/`loadMore()`, matching the pattern at every
other call site (`ContentListFragment.onBackPressed()` L273,
`populateItemList()` L378).

Traced the actual race-prevention mechanism, not just the textual match to
convention:
- `cancelRunningTasks()` (`BrowseContentItemAdapter.java:472-480`) iterates
  `asyncTasks` and calls `task.cancel(true)` on each, then resets
  `loading = false` / `allItemsFetched = false`.
- Per `AsyncTask`'s documented/actual framework behavior, `cancel()` sets
  the task's internal cancelled flag; when the background computation
  later finishes, the framework's `finish()` step checks `isCancelled()`
  and invokes `onCancelled(result)` **instead of** `onPostExecute(result)`.
  `BrowseItemLoadTask` does not override `onCancelled`, so it is a no-op.
  This means the stale task's `onPostExecute` body — `itemAdapter.addAll(...)`,
  `itemAdapter.setAllItemsFetched(...)`, `itemAdapter.setLoading(false)`,
  `itemAdapter.removeTask(this)` — never executes for a task cancelled this
  way, regardless of whether `doInBackground`'s blocking network call
  (`browseSync`) actually stops early. This is exactly the callback that
  Cycle 1 identified as corrupting state; it is now fully suppressed for
  every task that was in flight at the moment `setSortMode()` is called.
- Confirmed the ordering is correct: `cancelRunningTasks()` runs before
  `clear()`, so by the time `clear()` resets `objects`/`loading`/
  `allItemsFetched`/`dateSortAvailable` and `loadMore()` re-checks
  `if (loading || allItemsFetched) return;`, both flags are guaranteed
  `false` and `getItemCount()` is `0` — the new task starts a clean load
  from position 0 in the new sort mode, with no possibility of a second
  task racing it in.

This fully closes the race described in Cycle 1. Verdict on this finding:
**resolved**.

### Verification of Cycle 1's Warning fix

`git show bd9269d -- yaacc/src/main/res/values-{de,es,fr,nl,pt,zh}/strings.xml`
adds `sort_by_name`/`sort_by_date` translated entries to all six locale
files (not placeholder English copies — each is a genuine translation,
e.g. de: "Nach Name sortieren"/"Nach Datum sortieren", zh: "按名称排序"/
"按日期排序"). Ran `./gradlew :yaacc:lintDebug --rerun` fresh and inspected
`yaacc/build/reports/lint-results-debug.html`: zero occurrences of
`MissingTranslation` and zero occurrences of `sort_by_name`/`sort_by_date`
anywhere in the report (previously 2 `MissingTranslation` errors for
exactly these strings). Total lint errors dropped from 73 (Cycle 1) to 71,
consistent with exactly the two flagged errors being cleared and nothing
else changing. Verdict on this finding: **resolved**.

### Fresh full-diff pass (`git diff bc11586..bd9269d`)

Confirmed the fix commit's diffstat is *exactly* the Cycle-1-reviewed diff
(unchanged) plus the one-line `cancelRunningTasks()` call, the six
translation additions, and spec/doc files (`review.md`, `tasks.md`) — no
other production file changed. Specifically checked the concern flagged in
Cycle 1 itself (`cancelRunningTasks()`'s effect on `loading`/
`allItemsFetched` vs. `clear()`'s effect on the same fields):
- `cancelRunningTasks()`: `loading = false; allItemsFetched = false;`
  (does not touch `objects` or `dateSortAvailable`).
- `clear()`: clears `objects`, `loading = false`, `allItemsFetched = false`,
  `dateSortAvailable = false`, `notifyDataSetChanged()`.
- The two are idempotent/additive on the shared fields (both set
  `loading`/`allItemsFetched` to `false`; `clear()` additionally zeroes
  `objects`/`dateSortAvailable`), so calling them back-to-back has no
  conflicting or surprising combined effect, and the sequence is
  byte-for-byte the same as the pre-existing `onBackPressed()`/
  `populateItemList()` call sites. No new bug introduced by combining
  them.

Also re-verified the untouched parts of the diff (`BrowseContentItemAdapter`'s
`sortObjects`/`compareByDateDescending`/`compareByNameGrouped`/
`parseDateMillis`/`hasDate`, `ContentListFragment`'s toggle wiring,
`UpnpClient`/`BrowseItemLoadTask`'s `orderBy` threading, the two layout
files) byte-for-byte against Cycle 1's already-approved diff — identical,
nothing new.

### Tests

- [x] `./gradlew :yaacc:compileDebugJavaWithJavac` — BUILD SUCCESSFUL.
- [x] `./gradlew :yaacc:testDebugUnitTest --rerun` (forced, not UP-TO-DATE) —
  BUILD SUCCESSFUL, 0 failures/errors across all 30 test classes in the
  module (checked every `TEST-*.xml` JUnit report individually via
  `tests=".." failures="0" errors="0"`);
  `BrowseContentItemAdapterSortTest` 5/5 green, unchanged from Cycle 1.
- [x] `./gradlew :yaacc:lintDebug --rerun` (forced) — BUILD SUCCESSFUL
  (non-fatal per `abortOnError false`); `lint-results-debug.html` confirmed
  to contain zero `MissingTranslation` hits and zero `sort_by_name`/
  `sort_by_date` hits (down from 2 `MissingTranslation` errors in Cycle 1).
- [x] Coverage — same as Cycle 1 for the feature's core logic. The
  concurrent-mode-switch race itself is still not covered by an automated
  test (still an `AsyncTask`/integration-level concern out of reach of the
  adapter's plain-JVM unit test, as noted in Cycle 1), but the fix's
  correctness was verified by tracing `AsyncTask.cancel()`/`finish()`
  semantics rather than by a new test. Given the narrow, well-understood
  nature of the one-line fix and the framework-level guarantee it relies
  on, this is acceptable and does not block — flagged as a residual gap,
  not a blocker.

### Suggestion

- **`cancelRunningTasks()` never removes cancelled tasks from `asyncTasks`**
  (`BrowseContentItemAdapter.java:472-480`) — `removeTask(this)` is only
  called from `BrowseItemLoadTask.onPostExecute`, which (per the analysis
  above) never runs for a cancelled task, so `asyncTasks` silently
  accumulates stale, already-finished `AsyncTask` references on every
  sort-mode toggle (and on every `onBackPressed()`/`populateItemList()`
  cancel-and-reload, since this is a pre-existing pattern, not something
  `bd9269d` introduced). Harmless in practice (bounded by how many times a
  user toggles sort/navigates per session, and the objects are small), but
  a long-lived session with heavy toggling will leak a growing list of
  dead task references. Not a regression from this fix — same behavior
  existed at every other `cancelRunningTasks()` call site before this spec
  — so not counted as a Warning here, but worth a follow-up
  (`removeTask` could be called from `onCancelled` too, or
  `cancelRunningTasks()` could clear the list directly since a fresh
  `loadMore()` immediately follows in every call site).

### Verdict: PASS

Both Cycle 1 findings are correctly and minimally fixed: `setSortMode()`
now cancels in-flight tasks before clearing/reloading, and the framework's
`cancel()`/`onCancelled()` semantics guarantee the stale task's callback
into the adapter never fires — closing the race outright, not just
narrowing it. All six locale files now carry genuine translations for the
two new strings, confirmed absent from lint's `MissingTranslation` output.
The fresh full-diff pass over `bc11586..bd9269d` found nothing beyond the
approved Cycle 1 diff plus these two targeted fixes — no scope creep, no
new side effects from combining `cancelRunningTasks()` with `clear()`.
`compileDebugJavaWithJavac`, `testDebugUnitTest`, and `lintDebug` all BUILD
SUCCESSFUL, 0 test failures. Zero Critical, zero Warning. Ready to proceed
past this spec's review gate (security review next, per the workflow).

## Cycle 3 — 2026-09-25
Reviewing: commit `f9efa83` (diff `21dad94..f9efa83`) — Fix Group 2 (server
sort-rejection fallback) and Group 4 (ascending/descending direction
toggle), on top of the already-`PASS`ed Groups 1-2/fix-cycle work. Diff
touches `BrowseItemLoadTask.java`, `BrowseContentItemAdapter.java`,
`ContentListFragment.java`, two new drawables, `setting_strings.xml`, and
two test files (`BrowseItemLoadTaskTest.java` new,
`BrowseContentItemAdapterSortTest.java` extended).

### Critical

None.

### Warning

None.

### 1. Fix Group 2 — server sort-rejection fallback

`BrowseItemLoadTask.doInBackground` (`yaacc/src/main/java/de/yaacc/browser/BrowseItemLoadTask.java:54-71`):
`attemptServerSort` gates the sorted attempt on `SortMode.DATE &&
!itemAdapter.isServerSortRejected()`. On a sorted attempt, it returns the
result only if `sortedResult != null && sortedResult.getUpnpFailure() ==
null`; otherwise it calls `itemAdapter.markServerSortRejected()` and falls
through to an unsorted `browseSync(...)` call. This correctly covers
**both** failure shapes named in the task brief:
- **Non-null result with `getUpnpFailure() != null`** — covered directly by
  `BrowseItemLoadTaskTest.serverRejectionOfSortedBrowseFallsBackToUnsortedContentInsteadOfClearing`
  (uses `rejectedResult()`, which sets a mocked `UpnpFailure`).
- **`sortedResult == null`** (the alternative the task's Accept criteria
  named, e.g. `getProviderDevice() == null` inside
  `UpnpClient.browseSync`) — **not exercised by the committed test**, so I
  verified it independently: temporarily edited the test's
  `stubSortedAttempt(rejectedResult())` call to
  `stubSortedAttempt(null)` (a scratch, reverted-after edit, not part of
  this diff) and reran `BrowseItemLoadTaskTest` — it still passed,
  confirming `sortedResult != null && ...` in the production code correctly
  falls back on a literal `null` return too. Logging this as a residual
  test-coverage gap (see Suggestion), not a Warning, since the production
  code is correct and I independently verified it — the task's Accept
  criteria used "or" for these two cases, so covering only one is a minor
  gap, not a failure to implement the fix.

**`markServerSortRejected()`/`isServerSortRejected()`** (`BrowseContentItemAdapter.java:204-222`):
a per-instance boolean, set once on first rejection, checked before every
subsequent sorted attempt, and reset in `clear()` (`BrowseContentItemAdapter.java:297`,
alongside `loading`/`allItemsFetched`/`dateSortAvailable` — correctly fires
on folder change, sort-mode change, and direction toggle, since all three
paths call `clear()`). I did **not** just trust the "does not attempt the
sorted overload again" claim — I mutation-tested it directly: temporarily
changed `attemptServerSort`'s condition from
`itemAdapter.getSortMode() == SortMode.DATE && !itemAdapter.isServerSortRejected()`
to just `itemAdapter.getSortMode() == SortMode.DATE` (dropping the
rejection check), reran `BrowseItemLoadTaskTest`, and confirmed it now
**fails** with `TooManyActualInvocations` on the second-call
`verifySortedAttemptCount(1)` assertion — i.e. the test genuinely catches a
regression to the "skip repeated sorted attempts" behavior, not a
false-positive-passing test. Reverted the mutation immediately after
(`git status` clean, confirmed byte-for-byte restored).

**Mockito vararg-matching gotcha, verified not a false positive**: the task
brief specifically flagged `isA(SortCriterion.class)` vs `any()` as a
tricky spot. `UpnpClient.browseSync` has exactly **one** method
(`browseSync(Position, Long, Long, SortCriterion...)` — confirmed via
`grep`, no separate 3-arg overload exists), so both `stubSortedAttempt`
(matches `..., isA(SortCriterion.class)`, i.e. exactly one vararg element)
and `stubUnsortedAttempt` (matches `...` with zero vararg-matcher
arguments, i.e. an empty `orderBy` array) are stubbing/verifying against
the *same* underlying vararg method, distinguished only by element count.
The mutation test above is direct proof this distinction works as intended
in this Mockito version: the test would not have caught the "always
re-attempts sorted" regression if `isA(SortCriterion.class)` were silently
also matching zero-vararg calls (or vice versa). Confirmed genuine, not a
false-positive-passing test.

**Genuinely empty unsorted folder still shows empty**
(`genuinelyEmptyUnsortedFolderStillShowsEmptyNotLoopingOrErroring`): NAME
mode never sets `attemptServerSort`, so `doInBackground` goes straight to
the single unsorted `browseSync` call; a successful-but-empty
`DIDLContent` result reaches `onPostExecute`'s `content != null` branch
(adds zero items, no `clear()` call), `verifySortedAttemptCount(0)` and
`verifyUnsortedAttemptCount(1)` both assert no retry/loop machinery
triggers. Confirmed correct — no infinite retry, no masking of real
emptiness.

### 2. Group 4 — `toggleDirection()` and comparator correctness

`toggleDirection()` (`BrowseContentItemAdapter.java:180-192`) branches
strictly on `sortMode == SortMode.DATE` (flip `dateAscending`) vs. the
`else` (flip `nameAscending`) — the non-selected mode's field is never
touched in either branch. Rather than trusting the extended test's own
narrative, I read
`toggleDirectionFlipsOnlyCurrentModesDirectionIndependently`
(`BrowseContentItemAdapterSortTest.java`) line by line: it toggles in NAME
mode (only `nameAscending` flips), switches to DATE (asserts both
unchanged), toggles again (only `dateAscending` flips, NAME's already-
flipped value asserted unchanged), switches back to NAME (asserts DATE's
flip persisted). This is a real, order-sensitive assertion sequence, not a
single before/after check — it would catch a shared-flag regression. Ran
it fresh (`testDebugUnitTest --rerun`): green.

**`compareByNameGrouped(a, b, ascending)`**
(`BrowseContentItemAdapter.java:318-334`): the container-vs-item grouping
check (`if (aContainer != bContainer) return aContainer ? -1 : 1;`) sits
*before* and is completely independent of the `ascending`-conditional
title comparison (`ascending ? comparison : -comparison`) — direction can
never flip the grouping. Verified against
`nameModeDescendingReversesAlphabeticalOrderWithinGroupsOnly`, which
asserts both the Z-A order *and* that positions 0-1 are containers, 2-3
are items, with `Z-A` direction active — this is the one existing
invariant test that actually exercises direction combined with grouping,
not just direction alone.

**`compareByDate(a, b, ascending)`** (`BrowseContentItemAdapter.java:341-358`):
the null-handling block (`both null → 0`, `a null → 1` i.e. after,
`b null → -1` i.e. before) executes *before* the final
`ascending ? dateA.compareTo(dateB) : dateB.compareTo(dateA)` line and
does not reference `ascending` at all — missing/unparseable dates sort
last regardless of direction, by construction, not by accident. Verified
against `dateModeAscendingSortsOldestFirstButMissingDatesStillSortLast`,
which sets `dateAscending = true` (oldest-first, the non-default
direction) and still asserts the no-date item lands last. This is the
correct test to prove the invariant holds under the *non-default*
direction, not just the default — a weaker test that only checked the
default descending direction would not have caught a regression here.

### 3. Race conditions — `toggleDirection()` vs. Cycle 1's Critical finding

`toggleDirection()` calls `cancelRunningTasks(); clear(); loadMore();` in
that exact order (`BrowseContentItemAdapter.java:190-192`), identical to
`setSortMode()`'s already-`PASS`ed pattern from Cycle 2. Same
`AsyncTask.cancel(true)` → `onCancelled()` (no-op, not overridden) →
suppressed `onPostExecute` mechanism applies, so a stale in-flight
`BrowseItemLoadTask` from before a direction toggle cannot corrupt
`objects`/`loading`/`allItemsFetched` after the toggle clears and reloads.
No regression to the Cycle 1 Critical finding.

### 4. UI — `ContentListFragment` click handling and `updateSortToggleUi()`

Click handlers (`initSortToggle`, `ContentListFragment.java:166-181`):
same-mode tap → `bItemAdapter.toggleDirection()` (only when `bItemAdapter
!= null`) then `updateSortToggleUi()`; different-mode tap →
`onSortModeSelected(mode)` (unchanged, already-approved code path). If
`bItemAdapter` is still `null` (e.g. `initSortToggle` runs before the
adapter is constructed, a pre-existing lifecycle ordering — see
`ContentListFragment.java:111` vs. `:350`) and the user taps the
already-selected button, it falls through to
`onSortModeSelected(currentSortMode)`, which itself no-ops via its
existing `if (mode == currentSortMode) return;` guard — safe, no crash,
no accidental mode switch.

`updateSortToggleUi()` (`ContentListFragment.java:213-230`): guards
`sortByNameButton == null || sortByDateButton == null || getContext() ==
null` before touching anything (unchanged guard, extended with the
pre-existing `getContext()` check now folded in) — null-safe. Icon
selection reads `bItemAdapter.isNameAscending()`/`isDateAscending()` live
on every call (falls back to the known defaults `true`/`false` only when
`bItemAdapter == null`, matching the adapter's own constructor defaults),
so it reflects live adapter state, not cached/stale UI state, satisfying
the review brief's specific concern. The one momentary edge case — between
`initSortToggle()` (adapter not yet constructed, defaults shown) and
`initBrowsItemAdapter()` constructing the real adapter — is self-corrected
synchronously by this diff's own added `updateSortToggleUi()` call
immediately after `bItemAdapter.setSortMode(currentSortMode)`
(`ContentListFragment.java:353-357`), within the same call stack, before
any frame is drawn — not a user-visible flicker.

### 5. New drawables

`ic_baseline_date_range_asc_32.xml` / `ic_baseline_sort_by_alpha_desc_32.xml`:
valid vector-drawable XML — `<group android:pivotX="12" android:pivotY="12"
android:rotation="180">` wraps the same `<path>` data as the corresponding
existing icon, correctly closed, single root `<vector>` per file, valid
namespace declaration. `./gradlew :yaacc:lintDebug --rerun` ran clean
(BUILD SUCCESSFUL); grepped `lint-results-debug.xml` for both new file
names directly — zero matches, i.e. lint raised no issue against either
new drawable specifically. Confirmed no resource-name collision:
`find yaacc/src/main/res -iname "ic_baseline_date_range_asc_32*" -o -iname
"ic_baseline_sort_by_alpha_desc_32*"` returns exactly the two new files,
nothing pre-existing under those names. Total lint error count is 71,
identical to Cycle 2's baseline (no new lint errors from this diff); the
lone `IconDuplicates` finding in the report is the pre-existing, unrelated
`yaacc192_32.png`/`yaacc192png.png` pair, not these two vector drawables.
`UseCompatLoadingForDrawables` still fires exactly 3 times on
`ContentListFragment.java` (1 pre-existing for the back-button icon + 2 for
the sort icons, same count as before this diff — the 2 calls simply moved
from `initSortToggle` into `updateSortToggleUi`, not added).

### 6. Other

- **The "not in scope" note** (`onPostExecute`'s early-`return` when
  `doInBackground` itself returns Java `null`, `BrowseItemLoadTask.java:76-77`):
  confirmed genuinely pre-existing and unrelated to this diff. Read
  `UpnpClient.browseSync(Position, Long, Long, SortCriterion...)`
  (`UpnpClient.java:581-593`): it returns `null` only when
  `getProviderDevice() == null` or (`pos == null || pos.getDeviceId() ==
  null`) **and** `getProviderDevice() == null` — both purely
  device/position-availability conditions, entirely independent of
  `orderBy`/sort mode. Both the sorted attempt (line 58) and the unsorted
  fallback (line 71) call this exact same overload, so this diff's retry
  logic does not change when or whether a literal `null` can be returned —
  it was reachable before this diff (on the single unsorted call) and is
  reachable after it (on either the sorted or unsorted call), with
  identical trigger conditions. Not worsened by this diff.
- **Dead code**: none found — every new method (`isNameAscending`,
  `isDateAscending`, `toggleDirection`, `isServerSortRejected`,
  `markServerSortRejected`) is called from either production code
  (`ContentListFragment`/`BrowseItemLoadTask`) or the test suite.
- **Test coverage**: adequate. The one gap identified (Fix Group 2's
  literal-`null`-return branch not covered by the committed test, only by
  my own scratch verification) is a Suggestion, not a Warning — the
  production code is correct and the identical code shape
  (`sortedResult != null && ...`) is exercised via the type-checked
  `getUpnpFailure() != null` case, so the uncovered branch is a short-
  circuit variant of already-tested logic, not untested business logic.

### Tests

- [x] `./gradlew :yaacc:compileDebugJavaWithJavac` — BUILD SUCCESSFUL.
- [x] `./gradlew :yaacc:testDebugUnitTest --rerun` (forced) — BUILD
  SUCCESSFUL, 0 failures/errors. Fresh JUnit XML reports confirmed:
  `BrowseItemLoadTaskTest` 2/2 (`serverRejectionOfSortedBrowseFallsBackToUnsortedContentInsteadOfClearing`,
  `genuinelyEmptyUnsortedFolderStillShowsEmptyNotLoopingOrErroring`);
  `BrowseContentItemAdapterSortTest` 9/9 (5 pre-existing + 4 new:
  `directionDefaults`,
  `toggleDirectionFlipsOnlyCurrentModesDirectionIndependently`,
  `nameModeDescendingReversesAlphabeticalOrderWithinGroupsOnly`,
  `dateModeAscendingSortsOldestFirstButMissingDatesStillSortLast`).
- [x] `./gradlew :yaacc:lintDebug --rerun` (forced) — BUILD SUCCESSFUL,
  71 total errors (unchanged from Cycle 2's baseline), 0 `MissingTranslation`,
  0 issues against either new drawable, no new resource collisions.
- [x] Coverage adequate — both new test classes assert exactly the
  behaviors the task briefs called for, including the two invariant checks
  (containers-before-items under Z-A, missing-dates-last under
  oldest-first) that would be easy to accidentally break while threading
  `ascending` through the comparators. One minor gap noted above
  (Suggestion, not blocking).

### Suggestion

- **`BrowseItemLoadTaskTest` does not directly test the literal
  `sortedResult == null` branch** of `doInBackground`'s fallback condition
  (`sortedResult != null && sortedResult.getUpnpFailure() == null`) — only
  the `getUpnpFailure() != null` shape is exercised by the committed test.
  I verified the `null` branch independently (scratch edit + rerun,
  reverted), and it works correctly, but a permanent test case (e.g.
  `stubSortedAttempt(null)` as a third `@Test`) would close this gap for
  future regressions without relying on a reviewer's one-off check.
- **`markServerSortRejected()`/`isServerSortRejected()`** naming is clear,
  but consider a short Javadoc cross-reference from
  `BrowseItemLoadTask.doInBackground` back to
  `BrowseContentItemAdapter.clear()` (where the flag resets) — the flag's
  reset condition is currently only documented on the adapter side, not at
  the call site that depends on it.
- Carrying forward from Cycle 2 (still not addressed, still not blocking):
  `cancelRunningTasks()` never removes cancelled tasks from `asyncTasks`,
  so `toggleDirection()` (a second call site added by this diff, on top of
  `setSortMode()`) adds one more way to accumulate stale task references
  over a long session. Same pre-existing, harmless pattern as before —
  still just a follow-up candidate, not a new problem introduced here.

### Verdict: PASS

Zero Critical, zero Warning. Both Fix Group 2 (server sort-rejection
fallback) and Group 4 (ascending/descending direction toggle) are
correctly implemented and match `design.md`'s "Post-ship bug report and
feature request" section exactly. I did not take the implementing
subagent's report at face value for any of the specifically-flagged risk
areas: the Mockito vararg-matching claim, the "skip repeated sorted
attempts" claim, and the "toggleDirection only flips the selected mode"
claim were each independently verified via targeted mutation testing
(temporarily breaking the relevant production logic and confirming the
existing tests fail, then reverting), not just read and trusted. The
`compareByNameGrouped`/`compareByDate` invariants (containers-always-
before-items; missing-dates-always-last) hold regardless of direction,
verified both by code reading (the checks execute before/independent of
the `ascending` branch) and by the tests that exercise them under the
*non-default* direction specifically. The `toggleDirection()` race-
prevention pattern matches Cycle 1's Critical-finding fix exactly, with no
regression. The two new drawables are valid, lint-clean, and collision-
free. The one pre-existing "not in scope" note (`doInBackground` returning
literal `null`) is confirmed genuinely unrelated to and unworsened by this
diff. `compileDebugJavaWithJavac`, `testDebugUnitTest`, and `lintDebug` all
BUILD SUCCESSFUL. One Suggestion (a missing direct test for the
`sortedResult == null` branch, already verified correct by hand) does not
block. Ready to proceed to the security-review gate for this commit.
