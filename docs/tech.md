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

## Browse card-grid view

Research notes for spec `2026-10-17-browse-card-grid-view` (Group 1,
research task). Verified by direct inspection of the actual vendored
AndroidX bytecode (`javap` disassembly of the `classes.jar` extracted
from the exact `.aar` Gradle resolves for this project) and, for
`View.addOnLayoutChangeListener`, the official platform doc fetched
live — not assumption, not training-data recall. Exact artifacts
inspected:

- `androidx.recyclerview:recyclerview:1.3.1` — the version this
  project's dependency graph resolves to (confirmed present under
  `~/.gradle/caches/modules-2/files-2.1/androidx.recyclerview/recyclerview/1.3.1/`;
  no explicit `implementation 'androidx.recyclerview:...'` line exists
  in `yaacc/build.gradle` today — it's pulled in transitively, e.g. via
  `androidx.appcompat`/`com.google.android.material`).
- `androidx.cardview:cardview:1.0.0` — already the version in actual
  use (`browse_content_item.xml:31` wraps the list row in
  `<androidx.cardview.widget.CardView>`, confirmed by grep).

### 1. `GridLayoutManager.setSpanCount(int)` mutates a live instance — confirmed

Disassembled `androidx.recyclerview.widget.GridLayoutManager.setSpanCount(int)`
from `recyclerview-1.3.1.aar`'s `classes.jar`
(`javap -c -p -classpath classes.jar androidx.recyclerview.widget.GridLayoutManager`).
Decompiled bytecode logic:

```java
public void setSpanCount(int spanCount) {
    if (spanCount == mSpanCount) {
        return;                       // no-op if unchanged
    }
    mPendingSpanCountChange = true;
    if (spanCount < 1) {
        throw new IllegalArgumentException("Span count should be at least 1. Provided " + spanCount);
    }
    mSpanCount = spanCount;           // mutates the field on THIS instance
    mSpanSizeLookup.invalidateSpanIndexCache();
    requestLayout();                  // re-layout, no new LayoutManager object
}
```

No constructor call, no `RecyclerView.setLayoutManager(...)` call —
the method only writes `mSpanCount` on the receiver and calls
`requestLayout()`. This confirms `((GridLayoutManager)
contentList.getLayoutManager()).setSpanCount(newCount)` (as `design.md`
decision 3 calls for, from a width-change listener) can be called
repeatedly on the one `GridLayoutManager` the `RecyclerView` already
holds, with no need to call `setLayoutManager` again.

### 2. `GridLayoutManager extends LinearLayoutManager` — confirmed, and the inherited method the existing code calls is not overridden

`javap -public -classpath classes.jar androidx.recyclerview.widget.GridLayoutManager`
prints:

```
public class androidx.recyclerview.widget.GridLayoutManager extends androidx.recyclerview.widget.LinearLayoutManager {
  ...
  public int getSpanCount();
  public void setSpanCount(int);
  ...
}
```

i.e. `GridLayoutManager`'s own class file declares `setSpanCount`,
`getSpanCount`, `onLayoutChildren`, etc., but **not**
`scrollToPositionWithOffset` — that method is inherited unmodified
from `LinearLayoutManager`. Consequences for
`BrowseContentItemAdapter.scrollToPositionId`
(`yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java:626-629`):

```java
public void scrollToPositionId(Integer id) {
    if (id == null) return;
    ((LinearLayoutManager) contentList.getLayoutManager()).scrollToPositionWithOffset(id, 0);
}
```

- The cast compiles today regardless of the declared type of
  `contentList.getLayoutManager()` (`RecyclerView.LayoutManager`) — a
  downcast to any named class always compiles; the question is only
  whether it succeeds at runtime.
- Since `GridLayoutManager extends LinearLayoutManager`, every
  `GridLayoutManager` instance **is** a `LinearLayoutManager` at
  runtime too (standard Java subtype rule) — the cast never throws
  `ClassCastException` when `contentList`'s current layout manager is
  a `GridLayoutManager`.
- Because `scrollToPositionWithOffset` is inherited, not overridden,
  calling it on a `GridLayoutManager` instance runs the exact same
  `LinearLayoutManager` implementation it runs for a plain
  `LinearLayoutManager` — behavior is unchanged, not just "compiles".
- Net: this method needs **zero code changes** when the adapter's
  `RecyclerView` is switched to a `GridLayoutManager` for `CARD` mode,
  confirming `design.md`'s claim ("only the cast target type needs
  loosening to the common base" — and in fact it doesn't even need
  loosening, since `LinearLayoutManager` already is that common base).

### 3. `RecyclerView.addOnLayoutChangeListener` fires on width changes (rotation, multi-window resize) — confirmed

`RecyclerView` (`recyclerview-1.3.1.aar`) extends `android.view.ViewGroup`
extends `android.view.View`
(`javap -p -classpath classes.jar androidx.recyclerview.widget.RecyclerView`
→ `public class androidx.recyclerview.widget.RecyclerView extends android.view.ViewGroup implements ...`).
`addOnLayoutChangeListener`/`removeOnLayoutChangeListener` do not
appear in `RecyclerView`'s own member list (`javap -p`) or in
`ViewGroup`'s (checked against this project's
`compileSdk 36` `android.jar`,
`/home/user/android-sdk/platforms/android-36/android.jar`,
`javap -p -classpath android.jar android.view.ViewGroup`) — so neither
class overrides it, and `RecyclerView.addOnLayoutChangeListener(...)`
resolves to the plain `android.view.View` implementation.

`android.jar`'s own signature
(`javap -p -classpath android.jar android.view.View`):

```
public void addOnLayoutChangeListener(android.view.View$OnLayoutChangeListener);
```

Official platform doc (fetched live via the `android-docs` MCP server,
`get_api_reference` for `android.view.View`, `developer.android.com/reference/android/view/View`),
verbatim:

> `addOnLayoutChangeListener(View.OnLayoutChangeListener listener)`
> — Add a listener that will be called when the bounds of the view
> change due to layout processing. Added in API level 11.

A rotation or multi-window resize changes the `RecyclerView`'s
measured width/height, which triggers a layout pass and therefore a
bounds change — so the listener fires in both cases, consistent with
`design.md` decision 3's plan to recompute span count from such a
listener instead of a separate `onConfigurationChanged` hook. API
level 11 is far below this project's `minSdk`, so no
`SDK_INT` guard is needed.

### 4. `IconDownloadTask` constructor signatures — confirmed, with one correction to the task's assumption

`yaacc/src/main/java/de/yaacc/util/image/IconDownloadTask.java:43-72`
(read directly — this class is yaacc's own code, not a library):

```java
public IconDownloadTask(ImageView imageView) {                                   // L43-45
    this(imageView, 48, 48);
}

public IconDownloadTask(ImageView imageView, BrowseContentItemAdapter browseContentItemAdapter) {  // L47-54
    this.imageView = imageView;
    this.cache = IconDownloadCacheHandler.getInstance();
    this.browseContentItemAdapter = browseContentItemAdapter;
    this.deviceId = null;
    this.targetWidth = 48;
    this.targetHeight = 48;
}

public IconDownloadTask(ImageView imageView, String deviceId) {                  // L56-63
    this.imageView = imageView;
    this.cache = IconDownloadCacheHandler.getInstance();
    this.browseContentItemAdapter = null;
    this.deviceId = deviceId;
    this.targetWidth = 48;
    this.targetHeight = 48;
}

public IconDownloadTask(ImageView imageView, int width, int height) {            // L65-72
    this.imageView = imageView;
    this.cache = IconDownloadCacheHandler.getInstance();
    this.browseContentItemAdapter = null;
    this.deviceId = null;
    this.targetWidth = width;
    this.targetHeight = height;
}
```

The `(ImageView, int, int)` overload exists exactly as the task
described, at the line range the task cited.

**Deviation from the task's assumption**: the task description states
this overload is "unused today". A repo-wide grep
(`grep -rn "new IconDownloadTask(" --include=*.java .`) shows it is
**already called once**, elsewhere in the same package:

```
yaacc/src/main/java/de/yaacc/browser/BrowseReceiverDeviceAdapter.java:197:
    new IconDownloadTask(holder.albumArt, 512, 512).execute(Uri.parse(player.getAlbumArt().toString()));
```

(`BrowseReceiverDeviceAdapter` loads 512×512 album art for a receiver
device's "now playing" view — a different adapter, different
`ImageView` field, unrelated to the browse-content-item list this spec
touches.) The other call sites are:

```
BrowseReceiverDeviceAdapter.java:213: new IconDownloadTask(holder.icon, device.getIdentity()...)   // (ImageView, String) overload
BrowseContentItemAdapter.java:489-490: new IconDownloadTask(holder.icon, this)                     // (ImageView, BrowseContentItemAdapter) overload — the one this spec replaces
BrowseDeviceAdapter.java:171: new IconDownloadTask(holder.icon)                                     // (ImageView) overload
```

This does not make the `(ImageView, int, int)` overload unsafe to add
a second, independent call site for in
`BrowseContentItemAdapter.onBindViewHolder` — each call constructs an
independent `IconDownloadTask` instance with its own final fields, and
`BrowseReceiverDeviceAdapter`'s existing usage shares no state with
`BrowseContentItemAdapter`'s. But Group 2 must account for one real
behavioral side effect of switching constructors, found by reading
`onPostExecute` (`IconDownloadTask.java:106-120`) together with how
`BrowseContentItemAdapter` uses its `asyncTasks` list
(`BrowseContentItemAdapter.java:85, 118, 491, 583-591, 593-597`):

```java
protected void onPostExecute(Bitmap result) {
    if (deviceId != null && !deviceId.equals(imageView.getTag())) {
        return;
    }
    if (result != null) {
        imageView.setVisibility(android.view.View.VISIBLE);
        imageView.setImageBitmap(result);
    }
    if (browseContentItemAdapter != null) {
        browseContentItemAdapter.removeTask(this);   // only called when this ctor set the field
    }
}
```

- `onBindViewHolder` (`BrowseContentItemAdapter.java:489-491`)
  explicitly does `asyncTasks.add(iconDownloadTask)` itself for every
  `IconDownloadTask` it creates, regardless of which constructor is
  used — that add call is unconditional.
- Removal from `asyncTasks` happens **only** via
  `removeTask(this)` inside `onPostExecute`, and that call is itself
  gated on `browseContentItemAdapter != null`.
- The `(ImageView, BrowseContentItemAdapter)` constructor (today's
  call) sets that field to `this`, so every successfully-completed
  thumbnail load removes itself from `asyncTasks`.
- The `(ImageView, int, int)` constructor always sets
  `browseContentItemAdapter = null` — it has no parameter for it. If
  `onBindViewHolder` is changed to call this overload instead (as the
  spec's Group 2 task describes), completed `IconDownloadTask`s will
  **stop removing themselves from `asyncTasks`**. Nothing else in
  `BrowseContentItemAdapter` clears or trims that list on success —
  `clear()` (`BrowseContentItemAdapter.java:291-299`) doesn't touch it,
  and the only other reader, `cancelRunningTasks()`
  (`BrowseContentItemAdapter.java:583-589`), only iterates/cancels, it
  doesn't remove entries either. So `asyncTasks` would grow by one
  finished (never-removed) `AsyncTask` + its captured `ImageView`
  reference per bound row for the lifetime of the adapter instance —
  a small but real unbounded-growth leak, not present today.
- This is a finding for Group 2 to resolve (e.g. keep passing a
  removal callback some other way, or accept/mitigate the growth), not
  something this research task fixes — flagging it per this task's
  scope (`tasks.md`'s Group 1 Constraints: "do not let Group 2 write
  code against unverified AndroidX API behavior" — this is yaacc's own
  code, not AndroidX, but the same "verify before Group 2 builds on it"
  spirit applies, so it's recorded here for Group 2 to see).

### Sources

- `androidx.recyclerview:recyclerview:1.3.1` `classes.jar`, extracted
  from
  `~/.gradle/caches/modules-2/files-2.1/androidx.recyclerview/recyclerview/1.3.1/3cb90a3c17fbb25e196f069101d99cd2f024d974/recyclerview-1.3.1.aar`,
  inspected with `javap -public`/`javap -c -p`.
- `androidx.cardview:cardview:1.0.0` `classes.jar`, extracted from
  `~/.gradle/caches/modules-2/files-2.1/androidx.cardview/cardview/1.0.0/158dbc2e2bc502815821191b04446b8f663c1874/cardview-1.0.0.aar`,
  inspected with `javap -public` (confirms `CardView extends
  android.widget.FrameLayout`, already the version used by
  `browse_content_item.xml:31`).
- `/home/user/android-sdk/platforms/android-36/android.jar` (this
  project's `compileSdk 36`), inspected with `javap -p` for
  `android.view.View` and `android.view.ViewGroup`.
- `developer.android.com/reference/android/view/View` — fetched live
  via the `android-docs` MCP server's `get_api_reference` tool, for
  `addOnLayoutChangeListener`'s documented contract.
- `yaacc/src/main/java/de/yaacc/util/image/IconDownloadTask.java:43-120`
  and `yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java:85,118,291-299,489-491,583-597,626-629`
  — read directly from this repo's working tree.

## As shipped: reconciliation with the design above (spec `2026-10-17-browse-card-grid-view`)

This section was written during planning/research, before implementation.
The feature is now built and both review gates have passed (general
review and security review — see
`.claude/specs/2026-10-17-browse-card-grid-view/review.md` and
`security-review.md`; the one security Critical, an inherited
`httpcore5` CVE regression, is covered below and fully traced in that
spec's `decisions.md`). The notes below record the concrete numbers and
gaps a future reader needs, rather than re-deriving them from the diff.

**Card icon size — 96dp, confirmed shipped.** The research above left
the exact dp value open ("~96-120dp"); the implementation settled on
the lower end, **96dp**, in two places that must stay in sync:
`yaacc/src/main/res/layout/browse_content_item_card.xml:67-68`
(`browseContentItemIcon`'s `layout_width`/`layout_height`, with a
layout comment explaining the choice) and
`BrowseContentItemAdapter.CARD_ICON_SIZE_DP = 96`
(`yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java:100`),
which feeds `dpToPx(...)` to size the `IconDownloadTask`'s requested
width/height for `CARD` mode (vs. the existing 48dp `LIST_ICON_SIZE_DP`
for list mode) — this is the actual fix for the fixed-48px thumbnail
bug `design.md`'s "Thumbnail resolution" section targeted.

**`targetCardWidthPx` / `TARGET_CARD_WIDTH_DP` — 160dp, lives in
`ContentListFragment`.** The span-count math (`CardGridSpanCount
.computeSpanCount`) is driven by `ContentListFragment
.TARGET_CARD_WIDTH_DP = 160`
(`yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java:85`),
converted to pixels by the private `targetCardWidthPx()` method
(`ContentListFragment.java:342-344`, `TARGET_CARD_WIDTH_DP *
getResources().getDisplayMetrics().density` — density-scaled, not a
hardcoded pixel constant). Both the initial span-count computation on
toggle (`ContentListFragment.java:330`) and the width-change listener's
recompute (`ContentListFragment.java:294`) call this same method, so
there is one source of truth for the target width.

**Not verified headlessly — needs on-device confirmation.** Per this
project's existing pattern for runtime-only concerns (see the
httpclient5 Android-crash issue and the media3 runtime behavior
documented elsewhere in this file, neither of which a unit test or
`javap` inspection can exercise), the following are implemented and
compile/lint clean but have **not** been visually or interactively
confirmed, and need checking on a real device or emulator before
calling this feature done end-to-end:

- **Grid visual layout** — card spacing, `CardView` elevation/corner
  radius consistency with the list row, and whether the four
  `ImageButton`s fit comfortably under the 96dp icon + title without
  crowding at the chosen `targetCardWidthPx`.
- **Thumbnail sharpness** — whether 96dp-requested bitmaps actually
  render crisp (not upscaled/blurry) at the card's rendered size across
  density buckets; `IconDownloadTask`'s request size is correct by
  inspection, but the resulting bitmap's visual quality on screen is
  not.
- **DPAD cross-card navigation feel** — per `design.md` decision 4, v1
  deliberately relies on Android's default focus-search for movement
  *between* cards (nothing was hand-wired); whether that default
  behavior actually feels right for grid navigation on a TV/DPAD-driven
  device is unverified. Within-card button navigation needs no separate
  check — it reuses the same generic `onCreateViewHolder` wiring the
  list layout already uses, unchanged.

Flag these three for whoever reviews the PR as open on-device items, not
as silently assumed-working.

**Column count "scales with screen width" — unit-tested for the
span-count math only, not tablet-visually-confirmed.** `CardGridSpanCount
.computeSpanCount` has unit test coverage for its floor-division
behavior (phone-portrait width → 2, just-under-3× width → still 2,
wide/tablet-sized width → more than 2, narrower-than-target width →
never below 2 — see `CardGridSpanCountTest`), and the width-change
listener wiring that calls it on rotation/resize is implemented. None of
that has been confirmed by actually running the app on a tablet or a
resized multi-window on a larger screen — do not read "scales with
width" as tablet-tested; it is math-tested only.

**Known v1 limitation — no view-mode toggle button in landscape.**
`yaacc/src/main/res/layout-land/fragment_content_list.xml` has its own
`contentListSortToggle` row but, unlike the portrait
`fragment_content_list.xml`, was never given a
`contentListViewModeToggle` button (confirmed absent by grep — the
toggle button only exists in the portrait layout,
`fragment_content_list.xml:69`). `initViewModeToggle()` null-checks and
returns early in landscape, so nothing crashes, and a mode chosen in
portrait still *applies* correctly after rotating to landscape (the
persisted preference and the "set up LayoutManager for the persisted
mode" step both run regardless of the button's existence) — landscape
just has no way to *change* the mode via that button. This is a
deliberate, accepted v1 gap (see `decisions.md`'s "No view-mode toggle
in the landscape layout" entry), not an oversight silently left
unfixed; a follow-up to add the button to the landscape layout is
out of scope for this spec.

**Dependency note — httpcore5 CVE regression, fixed on this branch.**
This branch inherited an `httpclient5`/`httpcore5` version pairing from
`feat/issue252` that reintroduced two patched CVEs in `httpcore5`/
`httpcore5-h2`; Fix Group 1 corrected it by keeping `httpclient5` at
`5.2.1` (avoids the unrelated Android crash that version pairing was
chosen for) while bumping `httpcore5` and the transitively-pulled
`httpcore5-h2` to `5.4.4` (`yaacc/build.gradle:30,38,44`). This is part
of this branch's shipped state and both review gates passed against it;
the full CVE identification, decompilation evidence, and rationale are
already documented in
`.claude/specs/2026-10-17-browse-card-grid-view/decisions.md` and
`security-review.md` — not repeated here.
