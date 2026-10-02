# Technical notes: verified APIs

Internal implementation notes for spec `2026-09-24-issue252-sort-by-date`.
Verified by direct source inspection (file:line citations below), not
external docs — all APIs involved are already vendored in this repo
(Cling UPnP stack under `org.fourthline.cling`, and yaacc's own classes).

## Cling `SortCriterion` / `Browse` action

`yaacc/src/main/java/de/yaacc/upnp/callback/contentdirectory/Browse.java`

- L56-58: no-orderBy convenience constructor — delegates with empty
  `orderBy` varargs:
  ```java
  public Browse(Service service, String objectID, BrowseFlag flag, HttpRequestSender httpRequestSender) {
      this(service, objectID, flag, CAPS_WILDCARD, 0, null, httpRequestSender);
  }
  ```
- L60-75: full constructor already accepts and forwards `SortCriterion...
  orderBy`:
  ```java
  public Browse(Service service, String objectID, BrowseFlag flag,
                String filter, long firstResult, Long maxResults, HttpRequestSender httpRequestSender, SortCriterion... orderBy) {
      super(new ActionInvocation(service.getAction("Browse")), httpRequestSender);
      ...
      getActionInvocation().setInput("SortCriteria", SortCriterion.toString(orderBy));
  }
  ```

**Confirmed (Group 1, `yaacc/src/main/java/org/fourthline/cling/support/model/SortCriterion.java:29-32`):**
```java
public class SortCriterion {
    final protected boolean ascending;
    final protected String propertyName;

    public SortCriterion(boolean ascending, String propertyName) {   // L29-32
        this.ascending = ascending;
        this.propertyName = propertyName;
    }
    ...
    @Override
    public String toString() {   // L68-74
        StringBuilder sb = new StringBuilder();
        sb.append(ascending ? "+" : "-");
        sb.append(propertyName);
        return sb.toString();
    }
}
```
- Two-arg constructor is the only one that takes a boolean/property-name
  pair; there's also a single-`String` constructor (L34-38) that parses a
  `+`/`-`-prefixed criterion string, and static `valueOf(String)` /
  `toString(SortCriterion[])` helpers (L48-66) used by `Browse.java` to
  serialize the array into the `SortCriteria` UPnP input.
- **Exact construction call for "descending by dc:date" (newest first)**:
  ```java
  new SortCriterion(false, "dc:date")
  ```
  `ascending=false` → `toString()` emits `-dc:date`, matching the
  ContentDirectory `SortCriteria` syntax the requester's server (and the
  DLNA spec) expects. `ascending=true` would emit `+dc:date` (oldest
  first) — not used in this feature (no oldest-first mode per
  requirements.md Non-Goals).

## `UpnpClient.browseSync` — the orderBy gap

`yaacc/src/main/java/de/yaacc/upnp/UpnpClient.java`

- L577-579 — `Position`-based overload, no orderBy, no firstResult/maxResult:
  ```java
  public ContentDirectoryBrowseResult browseSync(Position pos) {
      return browseSync(pos, 0L, null);
  }
  ```
- L581-596 — `Position`-based overload **that `BrowseItemLoadTask` actually
  calls**; still no orderBy parameter:
  ```java
  public ContentDirectoryBrowseResult browseSync(Position pos, Long firstResult, Long maxResult) {
      ...
      return browseSync(getDevice(pos.getDeviceId()), pos.getObjectId(), BrowseFlag.DIRECT_CHILDREN, "*", firstResult, maxResult);
  }
  ```
- L610-639 — low-level device-based overload that **does** accept and
  forward `orderBy` down to `ContentDirectoryBrowseActionCallback`
  (invoked L620):
  ```java
  public ContentDirectoryBrowseResult browseSync(Device<?, ?, ?> device, String objectID, BrowseFlag flag, String filter, long firstResult,
                                                 Long maxResults, SortCriterion... orderBy) {
  ```

**Gap**: the `Position`-based overloads (L577, L581) never accept or pass
`orderBy` through to the L610 overload that already supports it. Fix is to
add `orderBy` (varargs, defaulting to none) to the L581 overload's
signature and forward it — no changes needed below L610.

`BrowseItemLoadTask.java:50` is the only call site for the L581 overload:
`upnpClient.browseSync(itemAdapter.getNavigator().getCurrentPosition(), from, this.chunkSize)`.

## `BrowseItemLoadTask` — chunk assembly order

`yaacc/src/main/java/de/yaacc/browser/BrowseItemLoadTask.java` (87 lines)

- L43-52 `doInBackground`: calls the L581 `browseSync` overload above.
- L54-85 `onPostExecute`: containers added before items, per chunk:
  ```java
  protected void onPostExecute(ContentDirectoryBrowseResult result) {
      ...
      int previousItemCount = itemAdapter.getItemCount();
      DIDLContent content = result.getResult();
      if (content != null) {
          itemAdapter.addAll(content.getContainers());
          itemAdapter.addAll(content.getItems());
          boolean allItemsFetched = chunkSize != (itemAdapter.getItemCount() - previousItemCount);
          itemAdapter.setAllItemsFetched(allItemsFetched);
      } else { ... itemAdapter.clear(); }
      itemAdapter.removeTask(this);
      itemAdapter.setLoading(false);
      itemAdapter.scrollToPositionId(scrollToPositionId);
  }
  ```
  `allItemsFetched` is inferred from "this chunk returned fewer items than
  requested" — no separate total-count field from the server.

## `BrowseContentItemAdapter` — storage and pagination

`yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java` (400 lines)

- L64: `extends RecyclerView.Adapter<BrowseContentItemAdapter.ViewHolder>`.
- L68: `private List<DIDLObject> objects = new LinkedList<>();` — plain
  list, safe to re-sort in place once fully loaded.
- L71 + L108-110: `private boolean allItemsFetched;` with
  `setAllItemsFetched(boolean)` setter — this is the existing gate to hook
  a client-side full re-sort onto.
- L135-142 `addAll`: filters duplicates, appends, `notifyItemRangeInserted`.
- L337-355 `loadMore(...)`: chunk size from
  `settings_browse_chunk_size_key` pref, default `"50"`; guards on
  `loading || allItemsFetched`.
- L144-151 `clear()`: resets `objects`, `loading`, `allItemsFetched`.

## `ContentListFragment` — header wiring

`yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java` (445 lines)

- L253-276 `initBrowsItemAdapter`: constructs the adapter, sets it on the
  RecyclerView, adds an infinite-scroll `OnScrollListener` calling
  `bItemAdapter.loadMore()`.
- L283-299 `populateItemList(boolean clear)`.
- L95-142 `init()`: wires `contentListBackButton`,
  `contentListCurrentFolderName`, `currentReceivers`, `currentProvider`,
  `contentListTopSeperator` from the layout.
- Layout: `R.layout.fragment_content_list`
  (`yaacc/src/main/res/layout/fragment_content_list.xml`, plus a
  `layout-land` variant with the same header IDs — both must be updated
  together).

Header layout excerpt (L24-51 of `fragment_content_list.xml`):
```xml
<ImageButton
    android:id="@+id/contentListBackButton"
    style="@style/Widget.MaterialComponents.Button.UnelevatedButton"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:contentDescription="@string/icon"
    app:srcCompat="@drawable/ic_baseline_arrow_back_32"
    app:tint="?attr/colorControlNormal" />
<TextView
    android:id="@+id/contentListCurrentFolderName"
    android:layout_alignTop="@id/contentListBackButton"
    android:layout_alignParentEnd="true"
    android:layout_toEndOf="@id/contentListBackButton"
    .../>
<View android:id="@+id/contentListTopSeperator" android:layout_below="@+id/contentListCurrentFolderName" .../>
<RecyclerView android:id="@+id/contentList" android:layout_below="@+id/contentListTopSeperator" .../>
```
A new sort toggle belongs in this header row (between/near the back
button and the folder name, above `contentListTopSeperator`).

## SharedPreferences pattern

- Key constants live as untranslatable string resources in
  `yaacc/src/main/res/values/setting_strings.xml`, e.g.:
  ```xml
  <string name="settings_thumbnails_chkbx" translatable="false">thumbnails_chkbx</string>
  <string name="settings_browse_chunk_size_key" translatable="false">browse_chunk_size_key</string>
  <string name="settings_browse_max_results_key" translatable="false">settings_browse_max_results_key</string>
  ```
- Read/write via `PreferenceManager.getDefaultSharedPreferences(context)`,
  e.g. `UpnpClient.java` L159, L925/934, L973/984, L1109, L1120:
  `.getString(context.getString(R.string.KEY), "default")` /
  `.getBoolean(...)`; writes via `preferences.edit().put...().apply()`.
- `BrowseContentItemAdapter` caches its own `SharedPreferences` field
  (L76, set L89) and reads a preference in its constructor (L90) — same
  pattern to follow for reading the persisted sort order on adapter
  construction.
- Per the design decision, the new sort-order preference is **not**
  exposed in `preference.xml` (no global Settings entry) — it's written
  directly from the header toggle's click handler — but its key constant
  should still live in `setting_strings.xml` following the existing
  naming convention (e.g. `settings_sort_order_key`).

## `DIDLObject.Property.DC.DATE`

- Definition:
  `org/fourthline/cling/support/model/DIDLObject.java:172-179`:
  ```java
  static public class DATE extends Property<String> implements NAMESPACE {
      public DATE() {}
      public DATE(String value) { super(value, null); }
  }
  ```
  Value type is a raw `String` (ISO-ish date, exact format server-dependent
  — needs defensive parsing, not a strict `LocalDate.parse`).
- Read via `DIDLObject.getFirstPropertyValue(Class<? extends
  Property<V>>)` (`DIDLObject.java:856-859`), returns `null` if absent:
  `item.getFirstPropertyValue(DIDLObject.Property.DC.DATE.class)`.
  Pattern precedent (different property):
  `de/yaacc/upnp/model/YaaccMusicTrack.java:157`.
- Only populated if the server actually sends `<dc:date>` — parser:
  `org/fourthline/cling/support/contentdirectory/DIDLParser.java:662`:
  `getInstance().addProperty(new DIDLObject.Property.DC.DATE(getCharacters()));`.

## Icon/toggle conventions

- Existing icon buttons use `ic_baseline_*` drawables tinted via
  `ThemeHelper.tintDrawable(...)` and `app:tint="?attr/colorControlNormal"`
  (see `fragment_content_list.xml` L24-33 and
  `BrowseContentItemAdapter.java` L246-306 for per-row icon buttons).
- No existing segmented/multi-state toggle widget found in the layouts
  inspected — build the new "Name"/"Date" control as either two adjacent
  `ImageButton`/`ToggleButton` views or a
  `MaterialButtonToggleGroup` (Material Components is already a
  dependency, used via `Widget.MaterialComponents.Button.*` styles).

---

# Technical notes: server-side sort (spec `2026-10-02-issue252-server-side-sort`)

Covers `YaaccContentDirectory` (yaacc acting as a UPnP/DLNA **server**),
a separate code path from the client-side sort above (which only affects
yaacc *browsing* other servers).

## `SortCriterion.valueOf(String)` / `.toString()` — already in use server-side

`yaacc/src/main/java/org/fourthline/cling/support/model/SortCriterion.java`
(full file read, L1-75):

```java
public SortCriterion(boolean ascending, String propertyName) { ... }   // L29-32
public boolean isAscending() { return ascending; }                     // L40-42
public String getPropertyName() { return propertyName; }               // L44-46
public static SortCriterion[] valueOf(String s) {                      // L48-56
    if (s == null || s.length() == 0) return new SortCriterion[0];
    // splits on ",", each token re-parsed via SortCriterion(String):
    //   requires a leading '+' or '-' or throws IllegalArgumentException
}
```

- Empty/`null` input → empty array (no criteria). This is the "no sort
  requested" case and must keep producing each browser's existing
  hardcoded default order — do not change default-order behavior.
- A malformed criterion (missing `+`/`-` prefix) throws
  `IllegalArgumentException`, already caught in
  `YaaccContentDirectory.browse()` (L314-321) and converted to a
  `ContentDirectoryException(UNSUPPORTED_SORT_CRITERIA, ...)` — this
  existing validate-and-reject behavior is correct per spec and must be
  preserved unchanged.
- `getPropertyName()` returns the raw string after the sign, e.g.
  `"dc:date"` or `"dc:title"` — no normalization/whitespace-trim beyond
  what `SortCriterion(String)`'s constructor already does
  (`criterion.trim()` happens in `valueOf`, L53, before the per-criterion
  constructor runs).

## `CSV<String>` / `CSVString` — populating real `SortCaps`

`yaacc/src/main/java/org/fourthline/cling/model/types/csv/CSV.java` (full
file read): `CSV<T> extends ArrayList<T>`. `YaaccContentDirectory`'s
`sortCapabilities` field is already typed `CSV<String>` and constructed
as `new CSVString()` (empty) in the constructor (L116). Because `CSV`
*is* an `ArrayList`, populating it is just:

```java
this.sortCapabilities = new CSVString();
this.sortCapabilities.add("dc:title");
this.sortCapabilities.add("dc:date");
```

No need for the `CSVString(String)` comma-parsing constructor — direct
`.add()` calls are simpler and match how the field is already
constructed. `getSortCapabilities()` (L262-265) needs no change; it
already just returns the field.

## Where `dc:date` is (and isn't) already populated today

Confirmed by grep across all 20 files in
`de/yaacc/upnp/server/contentdirectory/`:

- **Music** (`MusicAllTitleItemBrowser`, `MusicAlbumItemBrowser`,
  `MusicArtistItemBrowser`, `MusicGenreItemBrowser`, and their
  `*FolderBrowser` `browseMeta` counterparts): already read
  `MediaStore.Audio.Media.YEAR` and call
  `ContentBrowser.createMusicTrack(..., date, ...)` →
  `YaaccMusicTrack.setDate(date)`. This is a **4-digit year string**
  (coarse granularity, e.g. `"2020"`), not a full timestamp — sufficient
  for lexicographic date-string sorting but coarser than the images path.
- **Images** (`ImageByBucketNameItemBrowser`,
  `ImagesByBucketNameFolderBrowser`'s analogous read): already read
  `MediaStore.Images.Media.DATE_TAKEN` (a `long` millisecond Unix
  timestamp) but — confirmed by grep — **never actually pass it into
  `createPhoto(...)`**; `ContentBrowser.createPhoto()` (L320-344) has no
  `date` parameter at all today, so photo items currently emit **no**
  `dc:date` property despite the cursor already reading
  `DATE_TAKEN`. This is a pre-existing gap this spec should close (the
  value is already queried, just not wired to `dc:date`).
- **Video** (`VideoItemBrowser`, `VideosFolderBrowser`): projections
  confirmed via grep — **no date column selected at all** (no
  `DATE_ADDED`/`DATE_TAKEN` in the projection array), and
  `ContentBrowser` has no `createVideo(...)`-with-date helper. Adding
  `dc:date` here means adding `MediaStore.Video.Media.DATE_ADDED` to the
  existing projection and threading it through.
- **SAF-backed files** (`SafFolderBrowser`): no MediaStore cursor at all
  (uses `DocumentFile`/`DocumentsContract` listing) — confirmed no date
  read. `DocumentFile.lastModified()` is the available equivalent if
  `dc:date` support is added here (standard `androidx.documentfile`
  API, already a transitive dependency since `SafFolderBrowser` already
  uses `DocumentFile`).
- **Synthetic/folder-only objects** (root, music/images/videos/SAF
  top-level folders, artist/album/genre container listings): these are
  containers with no backing media file: title-only sort applies; no
  `dc:date` is meaningful for them (matches how the client-side feature
  already treats containers — see Group 2 `compareByNameGrouped` in the
  `2026-09-24-issue252-sort-by-date` spec, containers group separately
  from dated items).

## `MediaStore` sort-column reference (standard Android API, platform docs)

Already used (hardcoded, unconditional) by existing browsers today —
confirmed by grep in each `*FolderBrowser`/`*ItemBrowser`'s `.query(...)`
call:

- `MusicAllTitlesFolderBrowser.java:120`:
  `sortOrder = MediaStore.Audio.Media.DISPLAY_NAME + " ASC"` (hardcoded,
  ignores any client-requested order today — this is the bug this spec
  fixes).
- Equivalent title columns for other content types, same constant
  pattern (`android.provider.MediaStore`, API level already targeted by
  this project — no new dependency):
  `MediaStore.Audio.Media.TITLE`, `MediaStore.Images.Media.DISPLAY_NAME`,
  `MediaStore.Video.Media.DISPLAY_NAME`.
- Cursor `query(uri, projection, selection, selectionArgs, sortOrder)`
  accepts a raw SQL `ORDER BY`-clause fragment as `sortOrder` (e.g.
  `"<column> ASC"` / `"<column> DESC"`) — standard
  `ContentResolver.query` contract, already relied on by every browser
  in this package. Pushing the requested sort column + direction into
  this existing `sortOrder` argument (instead of an in-memory sort after
  the cursor is read) is the natural fit for all MediaStore-cursor-backed
  browsers and avoids reading the whole cursor into memory just to sort
  it.

## As shipped: reconciliation with the design above

This section was written during planning, before implementation. The
feature is now built, reviewed, and security-reviewed (all gates PASS
— see `.claude/specs/2026-10-02-issue252-server-side-sort/review.md`
and `security-review.md`). The design above held up well overall; the
notes below record the points worth a future reader knowing about,
rather than re-deriving from the diff history.

**`MAX_SORT_CRITERIA` cap (added after the original design, Fix Group
2).** The security review (Group 2, Cycle 1) flagged the original
design as an unbounded resource-exhaustion vector: nothing capped how
many comma-separated criteria a single `SortCriteria` string could
contain, so an attacker-chosen, arbitrarily long duplicate-criteria
chain could turn an `O(n)` sort into `O(n * criteria count)` (or, for
`SafFolderBrowser`'s whole-folder in-memory sort,
`O(m*log(m)*criteria count)`). The fix adds one constant,
`SortSupport.MAX_SORT_CRITERIA = 8`
(`yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/SortSupport.java:76`),
enforced inside `SortSupport.validateSupported(SortCriterion[])`: a
request with more than 8 criteria is rejected with the same
`UNSUPPORTED_SORT_CRITERIA` error code used for an unsupported
property (no new error code). Because `validateSupported` is the one
central guard called from `YaaccContentDirectory.browse()`, this cap
closes the issue for all 20 browsers at once — no per-browser change
was needed.

**Per-content-type column mapping table — confirmed as shipped.** Spot
checked against the actual source (`MusicAllTitlesFolderBrowser`,
`ImagesAllFolderBrowser`, `VideosFolderBrowser`, plus the other
MediaStore-backed browsers via a repo-wide grep for `columnMap.put`):
every `columnMap` matches the design table above exactly — Music maps
`dc:title`→`Audio.Media.DISPLAY_NAME` / `dc:date`→`Audio.Media.YEAR`;
Images map `dc:title`→`Images.Media.DISPLAY_NAME` /
`dc:date`→`Images.Media.DATE_TAKEN`; Video maps
`dc:title`→`Video.Media.DISPLAY_NAME` /
`dc:date`→`Video.Media.DATE_ADDED` (with the seconds→millis conversion
called out in the design, done at the read site before formatting).
No column choice changed during implementation.

**Deviation 1 — `HashMap` → `LinkedHashMap` in four container-listing
browsers (Group 2 review Cycle 1, Critical; fixed in Fix Group 1).**
The design didn't anticipate this: `MusicAlbumsFolderBrowser`,
`MusicArtistsFolderBrowser`, `MusicGenresFolderBrowser`, and
`ImagesByBucketNamesFolderBrowser` each read their (now correctly
SQL-`ORDER BY`-sorted) cursor rows into a `Map` keyed by id to
de-duplicate, then built the returned container list from the map's
`entrySet()`. With a plain `HashMap`, iteration order is unrelated to
insertion order, so the SQL sort was silently discarded one step
later — these four browsers would ignore `SortCriteria` in practice
despite doing the sorted query correctly. Fixed by switching the map
type to `LinkedHashMap` (insertion order == cursor order == SQL-sorted
order); each site now carries a `// LinkedHashMap preserves
cursor/SQL order - do not change to HashMap.` comment. A future
browser that follows this "cursor rows into a map, then
`entrySet()`-build the result" pattern needs the same `LinkedHashMap`
choice to actually honor a pushed-down sort order — plain `HashMap` is
safe only for simple key→value lookups (e.g. the `columnMap`s
themselves, which are read via `.get()` only and never iterated for
output order).

**Deviation 2 — `SafFolderBrowser` pagination restructuring (Group 2
review Cycle 1, Warning; fixed in Fix Group 1).** The original design
(see "Container-only / no-cursor browsers" above) implied sorting
`SafFolderBrowser`'s full item list and then slicing the page window,
without flagging the cost of doing so. Review caught that "full item
list" meant running the *entire* `createItem(...)` pipeline (SAF
metadata-cache lookup, MIME-type sniffing, `ProtocolInfo`/URI
construction) for every file in the folder on every `Browse` call,
not just the requested page — exactly the "materializing entire large
collections" risk the design's own Risks section had called out in
the abstract but not caught concretely for this browser. The shipped
fix splits sorting from item construction: a lightweight
`SafFileEntry` (URI + `DocumentFile`, no `createItem` call) is built
and sorted using cheap accessors read straight off `DocumentFile`
(`getName()`/`lastModified()`) via a local `buildAccessorComparator`
helper (functionally identical to `SortSupport.toComparator`, but
generic over `SafFileEntry` instead of hard-typed to `DIDLObject`,
since `SortSupport`'s own signature is fixed to `DIDLObject` for its
other callers) — only the page slice then runs through the full
`createItem(...)` pipeline. Folder/container listing in the same file
was left as unconditional full-enumeration-then-sort, confirmed cheap
on review (container construction there is just a `StorageFolder`
object plus a `canRead()` check — no metadata-cache, MIME, or
`ProtocolInfo`/URI work). One accepted, non-blocking trade-off from
this restructuring: if `createItem` returns `null` for an item inside
the already-sorted page slice (a SAF metadata-cache miss or
unresolvable MIME type at a page boundary), that slot is dropped
without backfilling from the next candidate, so `NumberReturned` can
come back under `maxResults` even though more valid items exist
further down the list — allowed by the UPnP `Browse` contract
(`NumberReturned < RequestedCount` is expected to be followed by a
continuation request) and not a new regression (the pre-spec baseline
had the same raw-index-vs-valid-item-count characteristic). See
`review.md` Cycle 2 for the full trace; a cheap follow-up (backfill
from `candidates` past the slice until `maxResults` valid items are
collected) was suggested but not required.

**Everything else matches the design as written** — `SortSupport`'s
public API (`validateSupported`, `toMediaStoreSortOrder`,
`toComparator`, `formatEpochMillisAsDate`), its zero-`android.*`-import
testability property, the `YaaccContentDirectory` constructor/`browse()`
wiring, and the "default order unchanged when `SortCriteria` is
absent" regression guarantee all shipped exactly as designed above,
confirmed by the review cycles' line-by-line comparisons against this
document.
