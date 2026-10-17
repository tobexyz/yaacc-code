# Tasks: Browse card-grid view (content tab)

Plan: `specs/2026-10-17-browse-card-grid-view/requirements.md` and
`specs/2026-10-17-browse-card-grid-view/design.md`

## Group 1: Shared contract — resources, span-count utility, tests

- [x] Research and document the RecyclerView/CardView APIs this feature touches | `docs/tech.md`
  - **Accept**: `docs/tech.md` gets a new "Browse card-grid view" section
    confirming, by direct source/API inspection (not assumption):
    `GridLayoutManager.setSpanCount(int)` can be called on a live
    `LayoutManager` without recreating it; `GridLayoutManager` is a
    subclass of `LinearLayoutManager` (so the existing
    `(LinearLayoutManager) contentList.getLayoutManager()` cast in
    `BrowseContentItemAdapter.scrollToPositionId` continues to compile
    and work when a `GridLayoutManager` is assigned); `RecyclerView
    .addOnLayoutChangeListener` fires on width changes (rotation,
    multi-window resize); the exact signatures of
    `IconDownloadTask`'s constructors (`IconDownloadTask.java:43-72`),
    confirming the `(ImageView, int, int)` overload is unused today and
    safe to call from the adapter instead of the fixed-48
    `(ImageView, BrowseContentItemAdapter)` one.
  - **Verify**: `grep -n "Browse card-grid view" docs/tech.md`
  - **Constraints**: Group 2 tasks reference this section; do not let
    Group 2 write code against unverified AndroidX API behavior.

- [x] Shared resources + `CardGridSpanCount` utility with unit tests | `yaacc/src/main/res/values/setting_strings.xml`, `yaacc/src/main/res/values/strings.xml`, `yaacc/src/main/java/de/yaacc/browser/CardGridSpanCount.java`, `yaacc/src/test/java/de/yaacc/browser/CardGridSpanCountTest.java`
  - **Accept**: Adds, by exact name, the contract Group 2 tasks build
    against:
    - `setting_strings.xml`: new untranslatable string constant
      `settings_browse_view_mode_key` (value
      `"browse_view_mode_key"`), following the existing pattern next to
      `settings_sort_order_key`.
    - `strings.xml`: new content-description string
      `toggle_view_mode` (e.g. "Switch between list and card view"),
      following the existing pattern used by `sort_by_name`/
      `sort_by_date`.
    - New pure-JDK class `CardGridSpanCount` (no `android.*` imports,
      same zero-Android-import testability precedent as `SortSupport` —
      see `docs/tech.md`'s "2026-10-02" section) with one static method
      `computeSpanCount(int availableWidthPx, int targetCardWidthPx)` →
      `int`, returning `Math.max(2, availableWidthPx / targetCardWidthPx)`.
    - Test-first: `CardGridSpanCountTest` written with failing cases
      before the implementation, then passing: a narrow phone-portrait
      width yields exactly 2; a width just under 3×`targetCardWidthPx`
      still yields 2 (floor, not round); a wide/tablet width yields more
      than 2; a width smaller than `targetCardWidthPx` still returns 2
      (never less than the floor), using representative pixel values
      derived from a documented dp→px assumption (e.g. `targetCardWidthPx`
      for a 160dp target at a stated density) rather than magic numbers.
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests "de.yaacc.browser.CardGridSpanCountTest"` passes;
    `grep -n "^import android" yaacc/src/main/java/de/yaacc/browser/CardGridSpanCount.java || true` returns nothing.
  - **Constraints**: pure JDK types only in `CardGridSpanCount`. Do not
    add a Robolectric dependency. Do not touch
    `BrowseContentItemAdapter.java`, `ContentListFragment.java`, or any
    layout XML in this task — those are Group 2.

## Group 2: Implementation (parallel — no shared file writes)

- [x] `ViewMode` support in `BrowseContentItemAdapter` | `yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java`, `yaacc/src/main/java/de/yaacc/util/image/IconDownloadTask.java`, `yaacc/src/test/java/de/yaacc/browser/BrowseContentItemAdapterTest.java`
  - **Accept (leak fix, found during Group 1 research — see
    `docs/tech.md`'s "Browse card-grid view" section)**: do **not** call
    `IconDownloadTask`'s existing `(ImageView, int, int)` constructor
    from `onBindViewHolder` — that overload unconditionally sets its
    `browseContentItemAdapter` field to `null`
    (`IconDownloadTask.java:65-72`), so `onPostExecute`'s
    `browseContentItemAdapter.removeTask(this)` cleanup never runs and
    `asyncTasks` grows by one entry per bound row for the adapter's
    lifetime — a real unbounded-growth leak that doesn't exist today.
    Instead, add a **new** constructor overload to `IconDownloadTask`:
    `public IconDownloadTask(ImageView imageView, BrowseContentItemAdapter
    browseContentItemAdapter, int width, int height)`, setting all four
    fields (adapter reference preserved for cleanup, plus the custom
    width/height) — do not change any existing constructor's behavior.
    Call this new 4-arg constructor from `onBindViewHolder`, not the
    existing 3-arg one.
  - **Accept**: Add `public enum ViewMode { LIST, CARD }` (mirroring
    `SortMode`'s existing style). New field `viewMode`, initialized in
    the constructor from
    `sharedPreferences.getString(context.getString(R.string.settings_browse_view_mode_key), ViewMode.LIST.name())`
    (same `valueOf`-with-fallback pattern as `readPersistedSortMode()`).
    `public ViewMode getViewMode()`. `public void setViewMode(ViewMode
    mode)`: no-ops if unchanged; otherwise persists to the same pref key
    and calls `notifyDataSetChanged()` — **no** `clear()`/`loadMore()`
    reload, since the backing data doesn't change, only its
    presentation (unlike `setSortMode`, which does reload). Change
    `getItemViewType(int position)` from `return position;` to `return
    viewMode.ordinal();`. In `onCreateViewHolder`, inflate
    `R.layout.browse_content_item_card` when `viewType ==
    ViewMode.CARD.ordinal()`, else the existing
    `R.layout.browse_content_item` — same `ViewHolder` class/constructor
    for both (the Group 1 docs/tech.md research + the Group 2 card-layout
    task below guarantee both layouts expose the same view IDs, so
    `ViewHolder`'s own code needs zero changes). In `onBindViewHolder`,
    replace the thumbnail-loading call
    `new IconDownloadTask(holder.icon, this)` with
    `new IconDownloadTask(holder.icon, this, iconPixelSize, iconPixelSize)`
    (the new 4-arg constructor above — keeps the adapter reference for
    `removeTask` cleanup)
    where `iconPixelSize` is computed from the current `viewMode` (48dp→px
    for `LIST`, the card icon dp size decided in the Group 2 card-layout
    task below, converted to px via the context's display metrics, for
    `CARD`) — do not hardcode 48 for both modes, that's the bug this
    spec fixes per `design.md`'s "Thumbnail resolution" section.
  - **Accept**: test-first — `BrowseContentItemAdapterTest` (new file;
    check the project's existing adapter/Mockito test conventions before
    writing, do not invent a new test style) covers: `getItemViewType`
    returns `ViewMode.LIST.ordinal()`/`ViewMode.CARD.ordinal()`
    correctly per mode; `setViewMode` with a new mode persists the pref
    and triggers exactly one `notifyDataSetChanged()`-equivalent
    observable effect, with no adapter reload call; `setViewMode` with
    the *same* mode is a no-op (no pref write, no notify).
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests "de.yaacc.browser.BrowseContentItemAdapterTest"` passes;
    `./gradlew :yaacc:compileDebugJavaWithJavac` succeeds.
  - **Constraints**: do not change `BrowseContentItemAdapter`'s existing
    public methods' signatures (`BrowseItemLoadTask`,
    `ContentListClickListener`, `ContentItemPlayTask`,
    `ContentListFragment` all call into this class today — see
    `requirements.md`'s Constraints). Do not touch sort logic
    (`SortMode`, `sortObjects`, `compareByDate`/`compareByNameGrouped`) —
    out of scope. Reference `R.layout.browse_content_item_card` and
    `R.string.settings_browse_view_mode_key` by the exact names Group 1
    and the card-layout task below define — do not invent different
    names.

- [x] New card layout | `yaacc/src/main/res/layout/browse_content_item_card.xml`
  - **Accept**: A `CardView`-wrapped layout (reuse the existing
    `androidx.cardview.widget.CardView` wrapper pattern from
    `browse_content_item.xml` — same corner radius/elevation/padding
    attributes for visual consistency) containing a vertical
    `LinearLayout`, top to bottom: `ImageView
    android:id="@+id/browseContentItemIcon"` sized ~96dp×96dp (pick the
    exact value so the card's total height stays reasonable once title
    + buttons are added below — document the chosen value in a layout
    comment); `TextView android:id="@+id/browseContentItemName"` below
    it (reuse the same text appearance/ellipsize/maxLines attributes as
    the list layout's title); a horizontal row of the same four
    `ImageButton`s with the **exact same IDs** as
    `browse_content_item.xml` (`browseContentItemPlaylistAdd`,
    `browseContentItemDownload`, `browseContentItemPlay`,
    `browseContentItemPlayAll`) and the same drawables/tint/content
    descriptions, laid out left-to-right at the bottom of the card. The
    root view must keep `android:focusable="true"`,
    `android:clickable="true"`, and `android:foreground=
    "?attr/selectableItemBackground"` matching the list row's root, so
    `ContentListClickListener`'s existing click handling and the
    existing DPAD key-listener wiring (set up generically in
    `onCreateViewHolder`, not per-layout) continue to work unchanged.
  - **Accept**: reusing the exact same view IDs as the list layout means
    `BrowseContentItemAdapter.ViewHolder`'s `findViewById` calls and
    `onBindViewHolder`'s per-content-type visibility logic need **no**
    changes to support this layout — confirm this by inspection, do not
    duplicate or fork that binding logic.
  - **Verify**: `./gradlew :yaacc:lintDebug` passes with no new
    errors (warnings about hardcoded text etc. are pre-existing style,
    not introduced by this file, following the project's existing
    `lint { abortOnError false }` tolerance); manual visual check is
    deferred to on-device testing after this spec's build (not
    headlessly verifiable).
  - **Constraints**: do not add any `ImageButton`/control beyond the
    four that already exist on the list row — no new actions, per
    `requirements.md`'s Non-Goals.

- [x] View-mode toggle button + grid/list switching in `ContentListFragment` | `yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java`, `yaacc/src/main/res/layout/fragment_content_list.xml`
  - **Accept**: add a third `ImageButton
    android:id="@+id/contentListViewModeToggle"` into the existing
    `contentListSortToggle` `LinearLayout` in
    `fragment_content_list.xml` (same `Widget.MaterialComponents.Button
    .UnelevatedButton` style, `app:tint`, size as the sort buttons),
    using `@string/toggle_view_mode` (from Group 1) as its content
    description, with a suitable existing or new drawable for
    list/grid (check existing `ic_baseline_*` drawables for a
    grid/view-module icon before adding a new one; if none fits, add
    one new vector drawable following the existing `ic_baseline_*_32`
    naming convention — a single drawable addition, not a new icon
    family).
  - **Accept**: in `ContentListFragment`, add `initViewModeToggle()`
    (called from `init(...)` alongside the existing
    `initSortToggle(...)` call) that: reads the persisted mode from
    `R.string.settings_browse_view_mode_key` (same key Group 1 defined,
    same fallback-to-`LIST` pattern as `initSortToggle`'s sort-mode
    read) for the toggle's initial icon/state; on click, flips the mode,
    persists it to the same key, calls
    `bItemAdapter.setViewMode(newMode)`, and switches
    `contentList.setLayoutManager(...)` between the existing
    `LinearLayoutManager` (list) and a `GridLayoutManager(getActivity(),
    initialSpanCount)` (card), where `initialSpanCount` comes from
    `CardGridSpanCount.computeSpanCount(contentList.getWidth(),
    targetCardWidthPx)` (Group 1's utility; `targetCardWidthPx` is a
    constant derived from the ~160dp target documented in `design.md`).
  - **Accept**: add a width-change listener on `contentList`
    (`addOnLayoutChangeListener` or equivalent) that, **only while in
    CARD mode**, recomputes the span count via the same
    `CardGridSpanCount.computeSpanCount(...)` call and applies it via
    `((GridLayoutManager) contentList.getLayoutManager())
    .setSpanCount(...)` — covering rotation and multi-window resize per
    `design.md`'s decision 3, without recreating the `LayoutManager`.
  - **Verify**: `./gradlew :yaacc:compileDebugJavaWithJavac` succeeds;
    `./gradlew :yaacc:lintDebug` passes with no new errors.
  - **Constraints**: do not change `initSortToggle`/sort-toggle behavior.
    Do not hand-wire DPAD navigation between cards for v1, per
    `design.md`'s decision 4 — rely on Android's default focus-search
    for cross-card movement; only within-card button navigation (already
    implemented generically in the adapter's `onCreateViewHolder`, not
    per-layout) is in scope, and it needs no changes since both layouts
    share the same button IDs.

## Fix Group 1: Address security-review Cycle 1 (FAIL — Critical)

- [x] Fix httpcore5 CVE regression inherited from feat/issue252's Android-crash fix | `yaacc/build.gradle`
  - **Accept**: `httpclient5` stays at `5.2.1` (avoids the Android
    `jdk.net` crash — confirmed the crashing bytecode lives only in
    `httpclient5`'s own class, not `httpcore5`); `httpcore5` bumped back
    to `5.4.4` (fixes CVE-2026-54399); the transitive `httpcore5-h2`
    module explicitly pinned to `5.4.4` too (fixes CVE-2026-54428 —
    without an explicit declaration it silently stayed at the vulnerable
    `5.2` even after plain `httpcore5` was bumped). See `decisions.md`.
  - **Verify**: `./gradlew build` passes; `./gradlew :yaacc:dependencies --configuration releaseRuntimeClasspath | grep httpcomponents`
    shows `httpcore5`/`httpcore5-h2` both resolving to `5.4.4` everywhere;
    re-decompiled the resolved `httpclient5-5.2.1.jar`'s
    `DefaultHttpClientConnectionOperator.class` — zero `jdk/net`
    references.
  - Fixed directly by the orchestrator (build-config/dependency-version
    correction, not feature code) rather than delegated.

## Group 3: Documentation update

- [ ] Update documentation for the card-grid view | `docs/tech.md`
  - **Accept**: `docs/tech.md` gets an "as shipped" reconciliation note
    for this spec (matching the existing style for prior specs in that
    file — see the "As shipped: reconciliation with the design above"
    section from the 2026-10-02 spec) recording: the final chosen icon
    dp size, the final chosen `targetCardWidthPx` constant and where it
    lives, and explicitly noting that grid visual layout, thumbnail
    sharpness, and DPAD cross-card navigation were **not** verified
    headlessly and need on-device confirmation (same caveat pattern as
    the httpclient5/media3 runtime-only issues found earlier in this
    project) — flag this clearly for whoever reviews the PR.
  - **Verify**: `grep -n "browse-card-grid-view" docs/tech.md` (or
    equivalent heading match) returns the new section.
  - **Constraints**: do not document the "scale with width" column
    behavior as tablet-tested — it is implemented and unit-tested for
    the span-count math only, not visually confirmed on a tablet.
