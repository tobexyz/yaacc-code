# Review: Browse card-grid view (content tab)

## Cycle 1 — 2026-10-17
Reviewing: Group 1 + Group 2 (commits `b163c35`..`ad5d7e1`, diff `57caa8a..HEAD`)

### Critical
None.

### Warning
None.

### Suggestion

- [`yaacc/src/test/java/de/yaacc/browser/BrowseContentItemAdapterTest.java:120-133`] The
  `registerObserverDirectly` reflection helper (reaching into
  `RecyclerView.Adapter`'s private `mObservable`/`mObservers` fields to work
  around `android.database.Observable`'s platform stub under plain-JVM unit
  tests) is a legitimate technique, not a new smell: it mirrors the exact
  same reflection pattern `BrowseContentItemAdapterSortTest
  .fixUpAdapterDataObservable` already established in this package, for the
  same documented root cause. It is still somewhat fragile (private-field
  reflection breaks silently on an AndroidX internal rename), so consider
  eventually factoring both call sites into one shared test helper rather
  than two near-duplicate copies, or moving this package's adapter tests to
  Robolectric if that dependency is ever added for other reasons. Not a
  blocker — it is consistent with existing project convention and is well
  documented inline.

- [`yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java:273` and
  `:131`] `init()` sets `contentList.setLayoutManager(new
  LinearLayoutManager(...))` unconditionally at line 127, and then
  `initViewModeToggle()` (called right after, line 131) immediately
  overwrites it again via `applyLayoutManagerForViewMode(currentViewMode)`
  when the persisted mode is `CARD`. Harmless (no visible flicker, no
  double layout pass visible to the user) but worth a one-line comment or a
  follow-up cleanup to drop the now-redundant line 127 assignment, since
  `initViewModeToggle` already fully owns setting the initial
  `LayoutManager` for both modes.

### Tests

- [x] All tests passing — ran `./gradlew :yaacc:testDebugUnitTest
  --tests "de.yaacc.browser.CardGridSpanCountTest" --tests
  "de.yaacc.browser.BrowseContentItemAdapterTest"` directly (not trusting
  prior reports): `CardGridSpanCountTest` 4/4 green, `BrowseContentItemAdapterTest`
  3/3 green. Also ran the full `./gradlew build` (compile, full unit test
  suite, `lintDebug`, assembleDebug/Release) — `BUILD SUCCESSFUL`, zero new
  lint **errors** (`lintDebug` tolerates warnings per the project's existing
  `lint { abortOnError false }` config). The one new lint finding
  attributable to this diff is the `InconsistentLayout` **warning** on
  `contentListViewModeToggle` missing from `layout-land` — this is exactly
  the gap `decisions.md` documents as an accepted v1 limitation, not an
  undocumented regression.
- [x] Coverage adequate for the change — `CardGridSpanCountTest` covers the
  floor-not-round behavior, the 2-column minimum, and a wide/tablet case,
  matching design.md's stated test list. `BrowseContentItemAdapterTest`
  covers `getItemViewType` per mode, `setViewMode`'s persist+notify path,
  and the same-mode no-op path (no pref write, no notify, no reload). No
  headless test exists for the grid's visual layout, thumbnail sharpness,
  or DPAD cross-card navigation — correctly called out in design.md/tasks.md
  as deferred to on-device/manual verification, consistent with this
  project's existing treatment of UI-only/runtime-only concerns (e.g. the
  httpclient5/media3 precedent cited in prior specs).

### Findings detail (per the review brief's specific checks)

1. **`IconDownloadTask` leak fix — correctly implemented.** The new 4-arg
   constructor (`IconDownloadTask.java:85-92`) assigns
   `this.browseContentItemAdapter = browseContentItemAdapter` (the passed
   reference, not hardcoded `null` the way the pre-existing 3-arg
   `(ImageView, int, int)` overload does at line 68). `onPostExecute`
   (`IconDownloadTask.java:137-139`) still gates `removeTask(this)` on
   `browseContentItemAdapter != null`, so cleanup fires correctly for tasks
   created via the new constructor. `onBindViewHolder`
   (`BrowseContentItemAdapter.java:551-552`) calls exactly this new overload
   — not the leaking 3-arg one. Confirmed no existing constructor's
   behavior changed (the other four constructors are untouched). The fix
   is real and matches `decisions.md`'s account exactly.

2. **Zero-change claim for `onCreateViewHolder`/`ViewHolder`/`onBindViewHolder`
   — verified true.** Diffed `browse_content_item_card.xml` against
   `browse_content_item.xml` view-ID-by-view-ID: both define
   `browseContentItemIcon`, `browseContentItemName`,
   `browseContentItemPlaylistAdd`, `browseContentItemDownload`,
   `browseContentItemPlay`, `browseContentItemPlayAll` with identical IDs,
   the same four drawables/content-descriptions/tints on the buttons, and
   the same root-view `focusable`/`clickable`/`foreground` attributes the
   DPAD key listener and `ContentListClickListener` depend on. `ViewHolder`'s
   constructor and `onBindViewHolder`'s per-content-type visibility logic in
   the actual diff are untouched except for the `IconDownloadTask`
   constructor-overload swap and the `iconPixelSize` computation — exactly
   as designed.

3. **Width-change listener — safe, correctly scoped.** The
   `addOnLayoutChangeListener` callback in `initViewModeToggle`
   (`ContentListFragment.java:283-296`) returns immediately when
   `currentViewMode != CARD`, so it never touches the `LayoutManager` while
   in LIST mode (where it would be a plain `LinearLayoutManager`, not a
   `GridLayoutManager`). It also guards the cast with `instanceof
   GridLayoutManager` before casting, so there's no unsafe-cast risk even
   in an edge case where mode/`LayoutManager` briefly disagree. Only a true
   width change (`newWidth > 0 && newWidth != oldWidth`) triggers
   `setSpanCount(...)`. This matches `docs/tech.md`'s confirmed
   `setSpanCount` semantics (mutates the live instance, no
   `setLayoutManager` re-call needed). The separate `scrollToPositionId`
   cast to `LinearLayoutManager` (`BrowseContentItemAdapter.java:690`)
   remains valid for both modes since `GridLayoutManager extends
   LinearLayoutManager`, as design.md anticipated.

4. **`registerObserverDirectly` test technique — reasonable, not a smell.**
   See Suggestion above: it is a direct continuation of an existing,
   documented convention in this same test package
   (`BrowseContentItemAdapterSortTest.fixUpAdapterDataObservable`), not a
   new ad hoc workaround. Both exist because this project runs plain-JVM
   unit tests against `RecyclerView.Adapter` with
   `unitTests.returnDefaultValues = true`, which stubs
   `android.database.Observable`'s register/notify methods to no-ops. Using
   reflection to reach the real, already-`androidx`-initialized
   `mObservers` list (rather than mocking the whole adapter or pulling in
   Robolectric) is a pragmatic, narrowly-scoped choice consistent with how
   this codebase already tests `RecyclerView.Adapter` subclasses elsewhere.

5. **Landscape toggle gap — accurately documented, not broader than
   stated.** Confirmed `yaacc/src/main/res/layout-land/fragment_content_list.xml`
   was not touched by this diff (not in the file list, not in `git diff
   --stat`), so it has no `contentListViewModeToggle` button, exactly as
   `decisions.md` says. `initViewModeToggle` null-checks
   `viewModeToggleButton` and returns early (`ContentListFragment.java:262-264`),
   the same defensive pattern `initSortToggle` already uses, so nothing
   crashes in landscape. `applyLayoutManagerForViewMode` is called from
   `initViewModeToggle` itself based on the *persisted* mode regardless of
   whether the button exists, so a mode chosen in portrait is correctly
   applied to the `LayoutManager` after rotating to landscape — matching
   `decisions.md`'s claim that landscape "respects" the choice, it just
   can't change it there. Lint's own `InconsistentLayout` warning (see
   Tests section) independently confirms the gap is real and exactly this
   one ID, not something wider.

6. **Build/tests re-run independently, not trusted from prior reports.**
   `./gradlew :yaacc:testDebugUnitTest --tests
   "de.yaacc.browser.CardGridSpanCountTest" --tests
   "de.yaacc.browser.BrowseContentItemAdapterTest"` → both green (verified
   via the generated `TEST-*.xml` result files: 4 tests/0 failures and 3
   tests/0 failures respectively). Full `./gradlew build` (compile, all
   unit tests, `lintDebug`, `assembleDebug`, `assembleRelease`) →
   `BUILD SUCCESSFUL`. Parsed `lint-results-debug.xml` directly: 73 total
   `Error`-severity issues in the report, none of them attributable to any
   file touched by this diff; the diff's own findings are all
   `Warning`-severity (`UseCompatLoadingForDrawables`,
   `NotifyDataSetChanged` — both pre-existing lint categories already
   present elsewhere in this adapter/fragment — and the one new, already-
   documented `InconsistentLayout` warning discussed above).

### Design-decision conformance check

- Icon/thumbnail size ~96dp in CARD mode: implemented exactly
  (`CARD_ICON_SIZE_DP = 96` in the adapter, `96dp` in the card layout's
  `ImageView`, with a layout comment explaining why 96 was chosen over
  120). Matches.
- Toggle persisted globally via `SharedPreferences`
  (`settings_browse_view_mode_key`), read the same way
  `sortMode`/`showThumbnails` are: implemented exactly, same
  `valueOf`-with-fallback pattern as `readPersistedSortMode`. Matches.
- Span count scales with width via `CardGridSpanCount.computeSpanCount`
  (`max(2, availableWidthPx / targetCardWidthPx)`, 160dp target,
  recomputed on initial CARD switch and on width change while in CARD
  mode via `setSpanCount`, no `LayoutManager` recreation): implemented
  exactly. Matches.
- No hand-wired DPAD grid navigation for v1, relying on Android's default
  focus-search across card boundaries, with only the existing
  within-card button navigation preserved: implemented exactly — no new
  key-listener code was added for cross-card movement; the existing
  `onCreateViewHolder`/`ViewHolder` key listeners are untouched except for
  the unrelated `IconDownloadTask`/`iconPixelSize` edit. Matches.

### Verdict: PASS
