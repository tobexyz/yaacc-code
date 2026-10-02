# Server-side sort support for yaacc's own UPnP ContentDirectory

## Context

Issue #252 originally only covered yaacc as a UPnP *client*. While
investigating the server-visible symptoms of that work, a parallel gap
was found on the *server* side: `YaaccContentDirectory` (yaacc acting as
a DLNA server) accepts and syntax-validates a `Browse` request's
`SortCriteria` but never applies it — see `docs/tech.md`'s "server-side
sort" section for full file:line citations. This spec closes that gap.

## Decision

Add one small, Android-framework-free utility class
(`SortSupport`) that both (a) turns a `SortCriterion[]` plus a
caller-supplied property→column map into a MediaStore `sortOrder`
string, and (b) turns the same input plus a `Comparator<DIDLObject>`
into a comparator usable for in-memory sorting. Every browser applies
whichever of the two fits its own data source, via a small, uniform
change to its existing query/listing code — no restructuring of
`ContentBrowser` or the browser class hierarchy.

**Alternatives considered:**
- *Push SQL `ORDER BY` building into `ContentBrowser` itself* (shared
  base-class method) — rejected: `ContentBrowser` has no per-type
  knowledge of which MediaStore column maps to `dc:date` (YEAR vs
  DATE_TAKEN vs DATE_ADDED), so the mapping has to live with each
  concrete browser regardless; a shared stateless utility avoids forcing
  that per-type knowledge onto the abstract base.
- *Always sort in-memory* (read full cursor, sort with `Comparator`,
  regardless of data source) — rejected: throws away the existing,
  already-working `sortOrder`-pushdown pattern every MediaStore-backed
  browser already uses for its current hardcoded order (see
  `docs/tech.md`), and would read full result sets into memory
  needlessly for large libraries.
- *One sort property per request, reject multi-criteria* — rejected as
  unnecessary extra restriction: `SortCriterion[]` is already an array;
  supporting it (apply criteria in array order, first wins, matching
  normal multi-key sort semantics) costs nothing extra in the design
  below and matches what `SortCriterion.valueOf` already parses from a
  comma-separated string.

## Technical Approach

### `SortSupport` (new class, `de.yaacc.upnp.server.contentdirectory`)

```java
public final class SortSupport {

    // Service-wide supported sort properties, used both to populate
    // SortCaps and to validate incoming requests.
    public static final String PROPERTY_TITLE = "dc:title";
    public static final String PROPERTY_DATE = "dc:date";
    public static final Set<String> SUPPORTED_PROPERTIES =
        new HashSet<>(Arrays.asList(PROPERTY_TITLE, PROPERTY_DATE));

    private SortSupport() {}

    /** Throws ContentDirectoryException(UNSUPPORTED_SORT_CRITERIA, ...)
     *  if any criterion's property isn't in SUPPORTED_PROPERTIES.
     *  No-op for an empty/null array. */
    public static void validateSupported(SortCriterion[] orderby)
            throws ContentDirectoryException;

    /** Builds a MediaStore sortOrder string ("<col> ASC, <col> DESC, ...")
     *  from orderby, mapping dc:title/dc:date through propertyToColumn.
     *  A criterion for a property not present in propertyToColumn for
     *  this call is skipped (not an error here — validateSupported
     *  already rejected anything outside dc:title/dc:date at the top of
     *  browse(); a container-only browser simply has no date column to
     *  map dc:date to, and that's a legitimate per-type gap, not a
     *  client error).
     *  Returns defaultSortOrder unchanged when orderby is null/empty or
     *  every criterion was skipped. */
    public static String toMediaStoreSortOrder(
        SortCriterion[] orderby,
        Map<String, String> propertyToColumn,
        String defaultSortOrder);

    /** Same idea for in-memory lists: returns defaultComparator
     *  unchanged when orderby is null/empty or no criterion maps;
     *  otherwise builds a Comparator chaining the mapped
     *  property-accessor Comparators in orderby's array order
     *  (stable multi-key sort), each direction-aware per
     *  SortCriterion.isAscending(). */
    public static Comparator<DIDLObject> toComparator(
        SortCriterion[] orderby,
        Map<String, Function<DIDLObject, String>> propertyToAccessor,
        Comparator<DIDLObject> defaultComparator);
}
```

Both methods are pure functions over plain Java types (`SortCriterion`
is already Android-framework-free; `Map`/`Comparator`/`Function` are
JDK) — no `android.*` or MediaStore imports in `SortSupport` itself, so
it is fully unit-testable on the plain JVM without the
mockable-android.jar pitfalls already documented in
`2026-09-24-issue252-sort-by-date/decisions.md`. Callers pass raw
MediaStore column-name strings (e.g. `MediaStore.Audio.Media.TITLE`) as
map values from their own Android-dependent code — `SortSupport` never
references `MediaStore` directly.

### `YaaccContentDirectory` changes

1. Constructor: populate `sortCapabilities` —
   `.add(SortSupport.PROPERTY_TITLE); .add(SortSupport.PROPERTY_DATE);`
   instead of leaving it empty.
2. `browse(...)` (the `@UpnpAction` entry point): immediately after the
   existing `SortCriterion.valueOf(orderBy)` try/catch (which already
   handles syntactically malformed criteria), call
   `SortSupport.validateSupported(orderByCriteria)` — propagates as the
   same `UNSUPPORTED_SORT_CRITERIA` error code the existing catch block
   already uses, so callers see one consistent rejection path for both
   "malformed" and "unsupported property" cases.

### Per-browser application

**MediaStore-cursor-backed browsers** (music/image/video — all their
`*ItemBrowser`/`*FolderBrowser` listing queries, not their `getSize()`
count-only queries): replace the hardcoded `sortOrder` argument in the
existing `.query(uri, projection, selection, selectionArgs, sortOrder)`
call with
`SortSupport.toMediaStoreSortOrder(orderby, columnMap, "<today's hardcoded default>")`,
where `columnMap` is a small per-browser `Map.of(PROPERTY_TITLE,
MediaStore.X.Media.DISPLAY_NAME, PROPERTY_DATE, <date column>)` literal.
Date column per type (per `docs/tech.md` research):

| Content type | Title column | Date column | Notes |
|---|---|---|---|
| Music | `Audio.Media.DISPLAY_NAME` | `Audio.Media.YEAR` | Unchanged value/format — already wired to `dc:date` today, just newly honored as a sort key. |
| Images | `Images.Media.DISPLAY_NAME` | `Images.Media.DATE_TAKEN` | Already queried, never attached to the DIDL item — `ContentBrowser.createPhoto(...)` gets a new optional `dateTaken` (`Long`, millis) parameter; formatted via a new small `SortSupport`-adjacent helper (see below) into the `dc:date` string. |
| Video | `Video.Media.DISPLAY_NAME` | `Video.Media.DATE_ADDED` | Not previously queried — add to each `VideoItemBrowser`/`VideosFolderBrowser` projection array. `DATE_ADDED` is seconds-since-epoch (not millis, per Android's `MediaStore` contract — multiply by 1000 before formatting) and feeds a new optional date param on the existing video item-creation path, mirroring the photo change. |

A new tiny formatting helper (`SortSupport.formatEpochMillisAsDate(long
millis)` → `"yyyy-MM-dd"` via `java.time.Instant`/`LocalDate`, consistent
with the defensive-parsing precedent already established client-side in
`BrowseContentItemAdapter.parseDateMillis()`) is used for the two newly
wired paths (images, video). Music's existing bare-year `dc:date` format
(`"2020"`) is left exactly as-is — changing it would be a visible format
change to an already-shipped value with no spec requirement forcing it,
and lexicographic sort on a 4-digit year string already sorts correctly
regardless of format.

**Container-only / no-cursor browsers** (`SafFolderBrowser`, and the
synthetic fixed-list browsers: `RootFolderBrowser`, `MusicFolderBrowser`,
`ImagesFolderBrowser`, `LiveStreamFolderBrowser`, plus the
`DISTINCT`-query browsers `MusicAlbumsFolderBrowser`,
`MusicArtistsFolderBrowser`, `MusicGenresFolderBrowser`,
`ImagesByBucketNamesFolderBrowser` where listing is still cursor-backed
and uses the MediaStore path above, but the *containers themselves* have
no per-row date — these already fall under "title-only" per the table
below): build the `List<DIDLObject>` exactly as today, then sort it with
`SortSupport.toComparator(orderby, accessorMap, defaultComparator)`
before returning, where `defaultComparator` reproduces each browser's
current fixed order (so behavior is unchanged when no criteria is
requested) and `accessorMap` only has an entry for `PROPERTY_TITLE`
(e.g. `DIDLObject::getTitle`) for pure containers — a `dc:date` request
against a title-only container listing simply has no mapped accessor,
so `toComparator` falls through to `defaultComparator` for that
criterion, per the "skip unmapped property" rule above (not an error —
see Risks).

`SafFolderBrowser` is the one case with real per-item dates available:
its items (not its folder/container entries) get a `dc:date` sourced
from `DocumentFile.lastModified()` (millis, same formatting helper as
images/video), and its existing unconditional
`Collections.sort(sortedPathes)` (alphabetical path sort) becomes the
`defaultComparator` passed into `toComparator` rather than an
unconditional step — so a `dc:date` or descending `dc:title` request is
now honored there too, and "no criteria" reproduces today's exact
alphabetical-ascending behavior.

### Interfaces & Contracts

- `SortSupport` is the only new public type. Everything else is a
  behavior change inside existing method bodies — no signature changes
  on `UpnpAction`-annotated methods, no change to `ContentBrowser`'s
  abstract method signatures (they already accept `SortCriterion[]
  orderby`, per the original research — it was just unused).
- `ContentBrowser.createPhoto(...)` gains one new overload (existing
  call sites without a date keep compiling; call sites that now have a
  `DATE_TAKEN`/`DATE_ADDED` value available use the new overload) rather
  than changing the existing signature, to avoid a disruptive ripple
  through every call site that has no date value to pass (synthetic test
  content, any future caller).

## Diagrams

Request flow for a `Browse` call with `SortCriteria="-dc:date"` against
an image folder:

```
Control point --Browse(SortCriteria="-dc:date")--> YaaccContentDirectory.browse()
    -> SortCriterion.valueOf("-dc:date")              [existing, unchanged]
    -> SortSupport.validateSupported([...])           [new: dc:date is supported, passes]
    -> findBrowserFor(objectId).browseChildren(...)
         -> browseContainer(...)  [e.g. ImagesByBucketNamesFolderBrowser:
                                    title-only accessorMap, dc:date has no
                                    mapped accessor -> falls back to
                                    defaultComparator, unchanged order]
         -> browseItem(...)       [e.g. ImageByBucketNameItemBrowser:
                                    columnMap has dc:date -> DATE_TAKEN,
                                    pushed into Cursor query's sortOrder
                                    as "datetaken DESC"]
    -> DIDL response: containers in default order, items newest-first
```

## Risks

- **"Unmapped property silently falls back to default" could mask a
  bug** if a browser is accidentally left without an accessor/column
  entry it should have had. Mitigated by: the per-content-type table
  above is explicit about which browsers get which entries, and the
  reviewer gate checks each browser's `columnMap`/`accessorMap`
  construction against that table — this is a one-time review item, not
  a runtime ambiguity for well-formed requests (a client asking for
  `dc:date` on a folder listing with no dated content is legitimately
  asking for something that doesn't apply to containers, which is
  normal UPnP behavior, not a bug).
- **Photo/video `createPhoto`/item-creation overload churn** touches
  several call sites. Mitigated by additive overloads (old signature
  kept, not removed) rather than changing existing parameter lists.
- **`DATE_ADDED` unit mismatch** (seconds vs the millis
  `DATE_TAKEN`/`DocumentFile.lastModified()` use) is an easy off-by-1000
  bug — called out explicitly in the table above and should get a
  direct unit-test case in `SortSupportTest` for the formatting helper
  alone (independent of MediaStore) plus a reviewer checklist item.
- **No live multi-client interop test** — same limitation already
  accepted and documented for issue #252's client-side work (no
  emulator/real UPnP network in this sandbox); covered by `SortSupport`
  unit tests (pure JVM, fully testable) plus code review of each
  browser's query/sort wiring, not a live DLNA control-point test. Flag
  this as a `[!]` deferred-to-user manual verification task, same
  pattern as the original spec.
