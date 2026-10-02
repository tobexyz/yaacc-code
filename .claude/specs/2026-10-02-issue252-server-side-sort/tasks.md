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

- [ ] Image browsers + wire `DATE_TAKEN` into `dc:date` | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/ImageAllItemBrowser.java`, `ImageByBucketNameItemBrowser.java`, `ImagesByBucketNameFolderBrowser.java`, `ImagesByBucketNamesFolderBrowser.java`, `ImagesAllFolderBrowser.java`, `ImagesFolderBrowser.java`, `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/ContentBrowser.java`
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

- [ ] SAF browser + remaining synthetic folders | `yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/SafFolderBrowser.java`, `RootFolderBrowser.java`, `LiveStreamFolderBrowser.java`
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
