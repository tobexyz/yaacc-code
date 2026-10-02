# Tasks: Server-side sort support for yaacc's own UPnP ContentDirectory

Plan: `specs/2026-10-02-issue252-server-side-sort/requirements.md` and
`specs/2026-10-02-issue252-server-side-sort/design.md`

## Group 1: Core sort infrastructure

- [x] Research and document the server-side sort APIs | `docs/tech.md`
  - **Accept**: `docs/tech.md` "server-side sort" section documents
    `SortCriterion.valueOf`/`.isAscending()`/`.getPropertyName()`
    (file:line cited), `CSV<String>`'s `ArrayList`-based `.add()`
    population pattern, and the exact MediaStore column + which content
    types already/don't-yet expose `dc:date` (music `YEAR`, images
    `DATE_TAKEN` unused-today, video no column queried, SAF no date
    read) — confirmed by direct source grep, not assumption.
  - **Verify**: `grep -n "server-side sort" docs/tech.md`
  - **Constraints**: later tasks reference this section — do not write
    code against unverified MediaStore column names.

- [x] `SortSupport` utility class with unit tests | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/SortSupport.java`, `yaacc/src/test/java/de/yaacc/upnp/server/contentdirectory/SortSupportTest.java`
  - **Accept**: new class per `design.md`'s "`SortSupport`" section —
    `PROPERTY_TITLE`/`PROPERTY_DATE` constants, `SUPPORTED_PROPERTIES`,
    `validateSupported(SortCriterion[])` (throws
    `ContentDirectoryException` with
    `ContentDirectoryErrorCode.UNSUPPORTED_SORT_CRITERIA` for any
    criterion whose property isn't `dc:title`/`dc:date`; no-op for
    null/empty array), `toMediaStoreSortOrder(SortCriterion[], Map<String,String>, String defaultSortOrder)`,
    `toComparator(SortCriterion[], Map<String, Function<DIDLObject,String>>, Comparator<DIDLObject> defaultComparator)`,
    and `formatEpochMillisAsDate(long millis)` (→ `"yyyy-MM-dd"` via
    `java.time`). Class has **zero** `android.*`/MediaStore imports —
    verified by the Verify command below.
  - **Accept**: test-first — write `SortSupportTest.java` with failing
    (red-phase) cases before implementing, then make them pass. Cover
    at minimum: empty/null `orderby` returns `defaultSortOrder`/
    `defaultComparator` unchanged; a single mapped `dc:title ASC`/`DESC`
    criterion produces the right column + direction string; an
    unmapped property (e.g. `dc:date` against a title-only map) falls
    back to default rather than erroring; `validateSupported` throws
    for `upnp:artist` and passes for `dc:title`/`dc:date`;
    `formatEpochMillisAsDate` produces the right `yyyy-MM-dd` for a
    known epoch-millis value (and a documented case showing the
    DATE_ADDED-is-seconds vs DATE_TAKEN-is-millis distinction is the
    *caller's* responsibility — this helper only formats millis, per
    `design.md`'s Risks section).
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests "de.yaacc.upnp.server.contentdirectory.SortSupportTest"` passes;
    `grep -n "^import android" yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/SortSupport.java || true` returns nothing.
  - **Constraints**: pure JDK types only (`java.util.*`, `java.time.*`)
    — this is what makes the class unit-testable without Robolectric,
    per the precedent in `2026-09-24-issue252-sort-by-date/decisions.md`
    about AGP's mockable-android.jar stripping real `android.*`
    behavior in plain-JVM unit tests. Do not add a Robolectric
    dependency.

- [x] Wire real `SortCaps` and request validation | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/YaaccContentDirectory.java`
  - **Accept**: constructor populates `sortCapabilities` with
    `SortSupport.PROPERTY_TITLE`/`PROPERTY_DATE` instead of leaving it
    empty. `browse(...)`'s existing `SortCriterion.valueOf(orderBy)`
    try/catch is followed immediately by
    `SortSupport.validateSupported(orderByCriteria)` so an unsupported
    property (not `dc:title`/`dc:date`) is rejected with
    `UNSUPPORTED_SORT_CRITERIA`, same as the existing malformed-syntax
    case. No other behavior in `browse(...)` changes in this task
    (per-browser sort application is Group 2).
  - **Verify**: `./gradlew :yaacc:compileDebugJavaWithJavac`
  - **Constraints**: depends on the `SortSupport` task above being
    complete first (same group, sequenced — do not start until that
    class exists and compiles).

## Group 2: Apply sorting to each content-type browser (depends on Group 1)

- [x] Music browsers | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/MusicAllTitlesFolderBrowser.java`, `MusicAllTitleItemBrowser.java`, `MusicAlbumFolderBrowser.java`, `MusicAlbumItemBrowser.java`, `MusicArtistFolderBrowser.java`, `MusicArtistItemBrowser.java`, `MusicGenreFolderBrowser.java`, `MusicGenreItemBrowser.java`, `MusicAlbumsFolderBrowser.java`, `MusicArtistsFolderBrowser.java`, `MusicGenresFolderBrowser.java`, `MusicFolderBrowser.java`
  - **Accept**: every MediaStore `.query(...)` listing call (not
    `getSize()`/count-only queries) in these files replaces its
    hardcoded `sortOrder` argument with
    `SortSupport.toMediaStoreSortOrder(orderby, columnMap, "<existing hardcoded default, unchanged>")`
    using `columnMap = {dc:title -> Audio.Media.DISPLAY_NAME, dc:date -> Audio.Media.YEAR}`
    per `design.md`'s table. `MusicFolderBrowser` (synthetic top-level,
    builds a fixed small `List<Container>`) sorts that list via
    `SortSupport.toComparator(orderby, {dc:title -> DIDLObject::getTitle}, <today's fixed order>)`
    before returning. When `orderby` is empty/null, every query's
    effective `sortOrder` string and every synthetic list's order is
    byte-for-byte identical to today's hardcoded behavior.
  - **Verify**: `./gradlew :yaacc:compileDebugJavaWithJavac :yaacc:testDebugUnitTest`
  - **Constraints**: do not change `getSize()`/count-only query calls.
    Do not change the `dc:date` *value* music already emits (`YEAR`,
    unchanged format) — only make it an honored sort key.

- [x] Image browsers + wire `DATE_TAKEN` into `dc:date` | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/ImageAllItemBrowser.java`, `ImageByBucketNameItemBrowser.java`, `ImagesByBucketNameFolderBrowser.java`, `ImagesByBucketNamesFolderBrowser.java`, `ImagesAllFolderBrowser.java`, `ImagesFolderBrowser.java`, `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/ContentBrowser.java`
  - **Accept**: `ContentBrowser.createPhoto(...)` gets a new additive
    overload taking an optional `Long dateTaken` (millis) that, when
    non-null, sets `dc:date` via
    `SortSupport.formatEpochMillisAsDate(dateTaken)` on the created
    `Photo`/`YaaccPhoto` (existing no-date overload/call sites keep
    compiling unchanged). `ImageByBucketNameItemBrowser`/analogous item
    browsers pass the already-queried `DATE_TAKEN` cursor value through
    to the new overload instead of discarding it. Listing queries use
    `SortSupport.toMediaStoreSortOrder(orderby, {dc:title -> Images.Media.DISPLAY_NAME, dc:date -> Images.Media.DATE_TAKEN}, <existing default>)`.
    `ImagesFolderBrowser` (synthetic top-level) sorts its fixed list via
    `SortSupport.toComparator(...)` title-only, same pattern as
    `MusicFolderBrowser` above.
  - **Verify**: `./gradlew :yaacc:compileDebugJavaWithJavac :yaacc:testDebugUnitTest :yaacc:lintDebug`
  - **Constraints**: `DATE_TAKEN` is already millis (per `docs/tech.md`)
    — do not apply any unit conversion here (that's specifically the
    video task's concern, not this one).

- [x] Video browser + add `DATE_ADDED` | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/VideoItemBrowser.java`, `VideosFolderBrowser.java`, `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/ContentBrowser.java`
  - **Accept**: `MediaStore.Video.Media.DATE_ADDED` added to the
    existing projection array(s) in both files (currently absent —
    confirmed by grep in `docs/tech.md`). A new additive
    `ContentBrowser.createItem(...)`-style overload (or reuse the photo
    overload's pattern) accepts the queried `DATE_ADDED` value and sets
    `dc:date` via `SortSupport.formatEpochMillisAsDate(dateAddedSeconds * 1000L)`
    — the `* 1000L` conversion is required and must have a code comment
    or be obviously named (e.g. a local `dateAddedMillis` variable)
    since `DATE_ADDED` is seconds-since-epoch, unlike `DATE_TAKEN`.
    Listing queries use
    `SortSupport.toMediaStoreSortOrder(orderby, {dc:title -> Video.Media.DISPLAY_NAME, dc:date -> Video.Media.DATE_ADDED}, <existing default>)`
    (the SQL `ORDER BY` sorts correctly on raw seconds regardless of
    the millis conversion needed for the DIDL string value).
  - **Verify**: `./gradlew :yaacc:compileDebugJavaWithJavac :yaacc:testDebugUnitTest`
  - **Constraints**: the seconds→millis conversion is the specific risk
    called out in `design.md` — get it right and make it visually
    obvious in the diff (named variable, not an inline magic multiply
    buried in a larger expression).

- [x] SAF browser + remaining synthetic folders | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/SafFolderBrowser.java`, `RootFolderBrowser.java`, `LiveStreamFolderBrowser.java`
  - **Accept**: `SafFolderBrowser`'s current unconditional
    `Collections.sort(sortedPathes)` becomes the `defaultComparator`
    passed into `SortSupport.toComparator(orderby, accessorMap, defaultComparator)`
    instead of an unconditional step; items get `dc:date` sourced from
    `DocumentFile.lastModified()` (millis) via
    `SortSupport.formatEpochMillisAsDate(...)`, and `accessorMap` maps
    both `dc:title` (existing title/name accessor) and `dc:date` (the
    newly-added date) for SAF items specifically — SAF *folders*
    (directories) only get the `dc:title` mapping, same "containers are
    title-only" rule as elsewhere. `RootFolderBrowser` and
    `LiveStreamFolderBrowser` (small fixed/synthetic lists) sort via the
    same `toComparator` title-only pattern as `MusicFolderBrowser`/
    `ImagesFolderBrowser` in the prior two tasks, reproducing today's
    order exactly when `orderby` is empty.
  - **Verify**: `./gradlew :yaacc:compileDebugJavaWithJavac :yaacc:testDebugUnitTest :yaacc:lintDebug`
  - **Constraints**: when `orderby` is empty/null, SAF folder listing
    order must be byte-for-byte identical to today's
    `Collections.sort(sortedPathes)` alphabetical result — this is a
    regression-sensitive default, verify by inspection/manual diff of
    the comparator logic, not just compilation.

## Fix Group 1: Address review cycle 1 (Group 2)

Per `review.md`'s Group 2 Cycle 1 (FAIL — 1 Critical, 2 Warnings).

- [x] Fix HashMap iteration order discarding the SQL sort order (Critical) | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/MusicAlbumsFolderBrowser.java`, `MusicArtistsFolderBrowser.java`, `MusicGenresFolderBrowser.java`, `ImagesByBucketNamesFolderBrowser.java`
  - **Accept**: each file's `HashMap<String, X>` used to dedupe/collect
    cursor rows before iterating into the result list becomes a
    `LinkedHashMap<String, X>` (same put/get usage, just
    insertion-order-preserving), so the SQL `ORDER BY` that
    `SortSupport.toMediaStoreSortOrder` already pushes into the query
    is actually reflected in the returned DIDL order. No other logic
    changes. Add a one-line comment on the `LinkedHashMap` declaration
    in each file noting why the map type matters here (per the
    reviewer's Suggestion), e.g. "LinkedHashMap preserves cursor/SQL
    order — do not change to HashMap."
  - **Verify**: `./gradlew :yaacc:compileDebugJavaWithJavac :yaacc:testDebugUnitTest`
  - **Constraints**: when `orderby` is empty/null, behavior must remain
    exactly as it already is post-Group-2 (the review confirmed the
    HashMap bug doesn't affect the no-criteria default case, only the
    sorted case — don't change default-order behavior while fixing this).

- [x] Avoid materializing/fully-processing the whole SAF folder before pagination (Warning) | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/SafFolderBrowser.java`
  - **Accept**: the expensive per-item `createItem(...)` pipeline (SAF
    metadata cache lookups, MIME sniffing, `ProtocolInfo`/URI building)
    only runs for the `firstResult`..`firstResult+maxResults` page
    slice actually being returned, not for every file in the folder on
    every `Browse` call. Sorting itself still necessarily needs the
    full listing (per `design.md`, you can't correctly sort a slice),
    but that full-listing pass should only compute the lightweight sort
    key (file name / `DocumentFile.lastModified()`), not build full
    DIDL items — build full items only for the page slice, after
    sorting. Folder/container listing in the same file has the same
    fix applied if it has an equivalent per-container cost; if container
    construction is already cheap (just a name/title, no MIME/URI
    work), note that in your report rather than over-engineering it.
  - **Verify**: `./gradlew :yaacc:compileDebugJavaWithJavac :yaacc:testDebugUnitTest :yaacc:lintDebug`
  - **Constraints**: must not change the regression-sensitive default
    order (no-`SortCriteria` case) established in Group 2 — re-verify
    it after this change, don't just trust it carried over.

- [x] Wire `DATE_TAKEN` into the two image browsers Group 2 missed (Warning) | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/ImagesAllFolderBrowser.java`, `ImageAllItemBrowser.java`
  - **Accept**: both files were named in Group 2's own task scope but
    left untouched. `ImagesAllFolderBrowser.java`'s listing query adds
    `MediaStore.Images.Media.DATE_TAKEN` to its projection (if not
    already present — re-check, the prior task's report says it wasn't)
    and wires the queried value through to the existing additive
    `createPhoto(..., Long dateTaken)` overload (added in Group 2,
    already in `ContentBrowser.java` — do not add another overload).
    `ImageAllItemBrowser.java` (single-row `_ID=?` lookup) gets the same
    treatment if it independently builds a `Photo`/calls `createPhoto`
    — if it actually delegates to another already-fixed browser instead
    of building its own, note that and skip the redundant change. Once
    wired, `ImagesAllFolderBrowser`'s existing `columnMap` (which
    already claims a `dc:title`/`dc:date` mapping per Group 2's report)
    is now accurate — no columnMap change needed, just make the date
    actually available.
  - **Verify**: `./gradlew :yaacc:compileDebugJavaWithJavac :yaacc:testDebugUnitTest :yaacc:lintDebug`
  - **Constraints**: reuse the existing `createPhoto` overload and
    `SortSupport.formatEpochMillisAsDate` helper — do not introduce a
    third date-formatting path. Match the `0L`→`null` fallback fix
    Group 2 already applied in the sibling "by bucket name" browser
    (a photo with no `DATE_TAKEN` should get `null`, not a bogus
    1970-01-01 date).

## Fix Group 2: Address security review cycle 1 (Group 2)

Per `security-review.md`'s Group 2 Cycle 1 (FAIL — 1 Warning, resource
exhaustion via unbounded `SortCriteria` count).

- [x] Cap the number of sort criteria a single request may specify | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/SortSupport.java`, `yaacc/src/test/java/de/yaacc/upnp/server/contentdirectory/SortSupportTest.java`
  - **Accept**: `SortSupport.validateSupported(SortCriterion[])` rejects
    (with the same `UNSUPPORTED_SORT_CRITERIA` error code it already
    uses for an unsupported property — no new error code) a request
    whose `orderby` array length exceeds a small fixed cap (8, per the
    security review's recommendation — adjust only with a documented
    reason if a different number is chosen). This is a single
    centrally-located guard, so it closes the issue for all 20 browsers
    at once (`SafFolderBrowser`'s whole-folder comparator chain and
    every MediaStore `toMediaStoreSortOrder` caller) without touching
    any browser file individually. New test case(s) in
    `SortSupportTest.java` cover: exactly-at-the-cap passes, one-over
    the cap throws, and confirm the existing unsupported-property
    rejection still works unchanged (regression guard).
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests "de.yaacc.upnp.server.contentdirectory.SortSupportTest"` passes;
    `./gradlew :yaacc:testDebugUnitTest` (full suite) still green.
  - **Constraints**: do not touch any `*Browser.java` file — the whole
    point of this fix is that one guard in `SortSupport`/
    `YaaccContentDirectory.browse()` (where `validateSupported` is
    already called, per Group 1) covers every call site.

## Group 3: Manual verification and documentation update (depends on Group 2)

- [!] Manually verify against a real or loopback UPnP control point | (no file changes)
  - **Blocked**: same limitation as `2026-09-24-issue252-sort-by-date`'s
    Group 3 — this cloud sandbox has no Android emulator/device and no
    real UPnP network, so a live control-point-to-yaacc-server smoke
    test isn't possible here. Covered indirectly by `SortSupportTest`
    (unit-level, pure JVM) and the review/security-review gates, but
    neither substitutes for a real client sending `SortCriteria` against
    yaacc's running server.
  - **Accept**: using a UPnP control point (or yaacc's own client,
    pointed at yaacc's local server) against yaacc's server with local
    media present, confirm: `GetSortCapabilities` returns
    `dc:title,dc:date`; a `Browse` with `SortCriteria="-dc:date"`
    returns images/music/video newest-first; `SortCriteria="+dc:title"`
    returns alphabetical; an unsupported property (e.g.
    `SortCriteria="-upnp:artist"`) is rejected with
    `UNSUPPORTED_SORT_CRITERIA`; no `SortCriteria` reproduces today's
    existing fixed order exactly (regression check).
  - **Verify**: manual pass/fail notes recorded in `decisions.md` — this
    task cannot be verified by an automated command alone.

- [ ] Documentation update | `CHANGELOG.md`, `docs/tech.md`
  - **Accept**: `CHANGELOG.md` gets an entry for this follow-up under
    issue #252, matching the existing entry format/style (see the prior
    client-side entry already there). `docs/tech.md`'s "server-side
    sort" section is reconciled with whatever actually shipped if
    implementation deviated from the design during build (e.g. a
    different final column choice). `README.md` is left unchanged
    unless it already documents server-side UPnP behavior at this level
    of detail (check before editing, per the mandatory docs-group rule
    — do not invent a section that doesn't fit the existing doc's
    scope).
  - **Verify**: `grep -n "252" CHANGELOG.md` shows the new entry;
    `grep -r 'TODO\|FIXME\|PLACEHOLDER' README.md CHANGELOG.md docs/tech.md || true`
    returns no plan-related placeholders.
  - **Constraints**: do not re-describe the already-documented
    client-side feature — this entry is specifically about the server
    now honoring `SortCriteria` from other clients.
