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
