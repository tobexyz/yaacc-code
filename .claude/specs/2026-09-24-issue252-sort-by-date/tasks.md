# Tasks: Sort by Date / Recently Added for UPnP folders (issue #252)

Plan: `specs/2026-09-24-issue252-sort-by-date/requirements.md` and
`specs/2026-09-24-issue252-sort-by-date/design.md`

## Group 1: SDK verification, plumbing, and test skeletons

- [x] Verify `SortCriterion` constructor/format and document it | `docs/tech.md`
  - **Accept**: `docs/tech.md` "Cling `SortCriterion`" section states the
    exact constructor signature of
    `org.fourthline.cling.support.model.SortCriterion` and confirms how to
    build a "descending by dc:date" instance (e.g.
    `new SortCriterion(false, "dc:date")` or equivalent — verify against
    the actual vendored source, do not assume).
  - **Verify**: cite the exact file:line of the constructor read, e.g. via
    `grep -n "class SortCriterion" -A 20 yaacc/src/main/java/org/fourthline/cling/support/model/SortCriterion.java`
  - **Constraints**: this is the one unresolved item flagged in
    `docs/tech.md` — do not write the `-dc:date` `SortCriterion` call in
    Group 2 tasks until this is confirmed.

- [x] Thread `orderBy` through `UpnpClient.browseSync(Position, Long, Long)` | `yaacc/src/main/java/de/yaacc/upnp/UpnpClient.java`
  - **Verified**: `./gradlew :yaacc:compileDebugJavaWithJavac` → BUILD
    SUCCESSFUL, once the environment's network policy was opened to
    `dl.google.com` and `jitpack.io` and a local Android SDK was installed
    for this session (`ANDROID_HOME=/home/user/android-sdk`,
    `local.properties` created).
  - **Accept**: `browseSync(Position pos, Long firstResult, Long maxResult,
    SortCriterion... orderBy)` overload added (or the existing L581-596
    overload extended with a varargs `orderBy` parameter, default empty),
    forwarding to the existing L610-639 device-based overload that already
    accepts and sends `SortCriteria`. The existing no-arg call sites
    (`browseSync(pos, firstResult, maxResult)` with no orderBy) must keep
    compiling and behaving identically (empty `SortCriteria`, same as
    today).
  - **Verify**: `./gradlew :yaacc:compileDebugJavaWithJavac`
  - **Constraints**: do not change the L610-639 overload's behavior — it
    already forwards `orderBy` correctly per `docs/tech.md`. Do not touch
    `Browse.java` — it already accepts and sends `orderBy` correctly.

- [x] Add persisted sort-order preference key | `yaacc/src/main/res/values/setting_strings.xml`
  - **Accept**: a new untranslatable string resource
    `settings_sort_order_key` (value e.g. `sort_order_key`), following the
    existing pattern of `settings_thumbnails_chkbx` /
    `settings_browse_chunk_size_key` in the same file. Do NOT add an entry
    to `yaacc/src/main/res/xml/preference.xml` — per design.md this is not
    a global Settings preference.
  - **Verify**: `grep -n "settings_sort_order_key" yaacc/src/main/res/values/setting_strings.xml`

- [x] Write test skeletons for sort behavior | `yaacc/src/test/java/de/yaacc/browser/BrowseContentItemAdapterSortTest.java`
  - **Verified**: `./gradlew :yaacc:testDebugUnitTest --tests
    "de.yaacc.browser.BrowseContentItemAdapterSortTest"` → confirmed red
    phase, fails with `cannot find symbol` for `SortMode`, `getSortMode()`,
    `setSortMode()`, and `isDateSortAvailable()` on
    `BrowseContentItemAdapter`, exactly as expected — those four symbols
    are Group 2's job to implement.
  - **Accept**: new JUnit 4 test class (follow existing convention, e.g.
    `yaacc/src/test/java/de/yaacc/upnp/model/YaaccMusicTrackTest.java` for
    license header/package/import style) with failing (red-phase) test
    methods covering, at minimum:
    1. Name mode keeps containers before items, alphabetical within each
       group (existing behavior, regression guard).
    2. Date mode fully interleaves containers and items sorted by
       `dc:date` descending (newest first), once `allItemsFetched` is
       true.
    3. No re-sort/reshuffle happens while `allItemsFetched` is false (list
       order matches arrival order mid-load).
    4. When no item in the adapter's current contents has a non-null
       `dc:date`, the adapter reports date-sort-unavailable (exact method
       name to be defined by the Group 2 implementation task; stub the
       test against the interface this task designs, e.g.
       `adapter.isDateSortAvailable()`).
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests "de.yaacc.browser.BrowseContentItemAdapterSortTest"`
    (expected to fail/not compile against current `BrowseContentItemAdapter`
    — that's the point of the red phase; Group 2 makes it pass).
  - **Constraints**: mock/construct minimal `DIDLObject`/`Container`/`Item`
    instances directly (Cling model classes are plain POJOs, no Android
    framework dependency needed for this unit test) — do not require
    Robolectric or instrumentation for this test class.

## Group 2: Implementation (depends on Group 1)

- [x] Pass sort mode through `BrowseItemLoadTask` and apply server-side `SortCriterion` | `yaacc/src/main/java/de/yaacc/browser/BrowseItemLoadTask.java`, `yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java`
  - **Accept**: when the adapter's current sort mode is "Date",
    `BrowseItemLoadTask.doInBackground` calls the new orderBy-accepting
    `browseSync` overload (Group 1) with the verified `-dc:date`
    `SortCriterion` from `docs/tech.md`. When sort mode is "Name", no
    `orderBy` is passed (identical to current behavior). Chunk assembly in
    `onPostExecute` is unchanged for Name mode (containers, then items).
  - **Accept**: `BrowseContentItemAdapter` gets a sort-mode field
    (Name/Date enum or equivalent), a setter that triggers `clear()` +
    reload when changed, and re-sorts `objects` once `allItemsFetched`
    becomes true in Date mode — fully interleaving containers and items by
    `dc:date` descending, per requirements.md item 4. Name mode is
    unaffected (existing folders-first alphabetical order).
  - **Accept**: adapter exposes date-sort availability (e.g.
    `isDateSortAvailable()`) reflecting whether any loaded item currently
    has a non-null `dc:date`; recompute as chunks arrive so the toggle can
    enable/disable live rather than only after full load.
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests "de.yaacc.browser.BrowseContentItemAdapterSortTest"` passes.
  - **Constraints**: do not fetch the whole folder up front to sort — the
    client-side sort only ever reorders what's already loaded, gated on
    `allItemsFetched`, per design.md's "no mid-load reshuffle" decision.
    Reuse `docs/tech.md`'s `getFirstPropertyValue(DIDLObject.Property.DC.DATE.class)`
    read pattern; treat unparseable/missing date strings as "no date" (do
    not crash on malformed `dc:date`).

- [x] Add sort-order header toggle UI | `yaacc/src/main/res/layout/fragment_content_list.xml`, `yaacc/src/main/res/layout-land/fragment_content_list.xml`
  - **Accept**: a segmented "Name" / "Date" control added to the header
    row near `contentListCurrentFolderName` (above
    `contentListTopSeperator`), in both the portrait and land layout
    variants, following the existing `ic_baseline_*` icon +
    `?attr/colorControlNormal` tint convention (see `docs/tech.md`
    "Icon/toggle conventions"). New drawable(s) added under
    `yaacc/src/main/res/drawable/` if no suitable existing icon exists
    (e.g. sort/alphabetical and date/calendar icons).
  - **Verify**: `./gradlew :yaacc:lintDebug` (layout inflation/resource
    validity) and manual visual check (see final Verify step in Group 3).
  - **Constraints**: match existing `48dp`/`32dp` touch-target sizing used
    by `contentListBackButton`; both layout variants must expose the same
    view IDs so `ContentListFragment` wiring works identically in either
    orientation.

- [x] Wire the toggle to sort mode, persistence, and enable/disable state | `yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java`
  - **Accept**: on fragment init, read the persisted sort order
    (`settings_sort_order_key`, default "Name") via
    `PreferenceManager.getDefaultSharedPreferences`, set it on the adapter,
    and reflect it in the toggle's visual selection state. Tapping a
    button: (a) writes the new mode to SharedPreferences, (b) calls the
    adapter's sort-mode setter (triggers reload per the Group 2 adapter
    task), (c) updates highlighted/selected state. The "Date" button is
    disabled whenever `adapter.isDateSortAvailable()` is false, and its
    enabled state updates live as chunks load (per the adapter task above)
    rather than only once at full load.
  - **Verify**: `./gradlew :yaacc:compileDebugJavaWithJavac` and
    `./gradlew :yaacc:testDebugUnitTest`
  - **Constraints**: switching sort mode must not issue more network
    requests than a normal fresh folder load (i.e. it's equivalent to
    re-entering the folder with the new mode, not an additional
    incremental fetch on top of what's already loaded).

## Group 3: Manual verification and documentation update

- [ ] Manually verify the feature against a running server | (no file changes)
  - **Accept**: using the `run` skill/build, browse a folder on a server
    that returns `dc:date` (e.g. confirm against a UAPP-compatible test
    server or any DLNA server with dated content) — confirm: Name mode
    unchanged, Date mode shows newest-first, switching modes doesn't
    reshuffle mid-load, restarting the app preserves the last-chosen mode,
    and a folder/server with no `dc:date` shows the Date button disabled.
  - **Verify**: manual pass/fail notes recorded in `decisions.md` or this
    task's checkbox comment — this task cannot be verified by an automated
    command alone; note explicitly if a live dc:date-populated server was
    unavailable to test against and what was substituted (e.g. a synthetic
    DIDL fixture).

- [ ] Update documentation for the new sort control | `README.md`, `CHANGELOG.md`
  - **Accept**: `README.md` mentions the Content-tab sort toggle if it
    documents other browsing UI features (skip if `README.md` doesn't
    describe UI features at this level of detail — check before editing).
    `CHANGELOG.md` gets an entry for issue #252 following its existing
    entry format/style.
  - **Verify**: `grep -n "252" CHANGELOG.md` shows the new entry;
    `grep -r 'TODO\|FIXME\|PLACEHOLDER' README.md CHANGELOG.md || true`
    returns no plan-related placeholders.
  - **Constraints**: do not invent a changelog format — match whatever
    style the most recent entries already use.
