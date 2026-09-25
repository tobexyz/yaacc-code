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
