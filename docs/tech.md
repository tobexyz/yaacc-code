# Technical notes: verified APIs

## Issue #252: Sort by Date / Recently Added for UPnP folders

Shipped in two specs on this branch, both fully built, reviewed, and
security-reviewed (final verdict PASS on both gates for both specs; one
or two early review cycles FAILed and were fixed before merge, per the
loop's own rules). Full planning history (requirements/design/tasks/
decisions/review cycles, ~3400 lines) removed after shipping — this is
the compact record. Git history has the rest if ever needed.

### Client-side sort (yaacc browsing a remote DLNA/UPnP server)

Files: `ContentListFragment`, `BrowseContentItemAdapter`,
`BrowseItemLoadTask` (`yaacc/src/main/java/de/yaacc/browser/`).

- Header toggle (Name/Date) next to the folder path, each with its own
  remembered direction (persisted via `SharedPreferences`, applied on
  app restart and across folders).
- Sends `SortCriterion` (`-dc:date`/`+dc:date`/`dc:title`) through
  `UpnpClient.browseSync(...)` → `Browse`'s `orderBy` first, so a
  compliant server returns pre-sorted results. Falls back to a
  client-side sort once the whole folder has loaded (no mid-load
  reshuffle).
- If a server **rejects** `SortCriteria` outright (a UPnP action
  failure), the app retries the same request unsorted instead of
  showing an empty folder, and remembers the rejection for the rest of
  that folder's chunked load.
- "Date" is disabled per-folder when nothing loaded so far has a
  `dc:date` value — no offering a no-op sort.
- Name mode keeps folders listed before items; Date mode fully
  interleaves containers and items by date.

### Server-side sort (yaacc's own embedded UPnP server honoring other clients' sort requests)

Files: `YaaccContentDirectory`, `SortSupport`, and the 20
`*FolderBrowser`/`*ItemBrowser` classes
(`yaacc/src/main/java/de/yaacc/upnp/server/contentdirectory/`).

- `GetSortCapabilities` now advertises `dc:title,dc:date` instead of an
  empty list, and `Browse` requests with a valid `SortCriteria` for
  either property actually determine result order (previously silently
  ignored, always returning each browser's hardcoded default order).
- `SortSupport` is the shared, pure-JDK (no `android.*` imports, unit
  testable on plain JVM) utility: `validateSupported(...)`,
  `toMediaStoreSortOrder(...)` (pushes sort into the existing
  `ContentResolver.query`'s `sortOrder` arg for MediaStore-backed
  browsers — cheapest, avoids reading a whole cursor just to re-sort
  it), and `toComparator(...)` (in-memory fallback for SAF/synthetic
  container browsers with no cursor).
- `dc:date` wiring per content type: music already had it (`YEAR`);
  images now attach the already-queried `DATE_TAKEN` to the DIDL item
  (was queried but never wired); video now also queries `DATE_ADDED`;
  SAF files use `DocumentFile.lastModified()`.
- A `SortCriteria` for anything other than `dc:title`/`dc:date` is
  rejected with `UNSUPPORTED_SORT_CRITERIA`, same as malformed syntax.
  Default (no `SortCriteria`) order is unchanged — confirmed via a
  stable-sort argument during review, not just asserted.
- `MAX_SORT_CRITERIA = 8` guards against a resource-exhaustion DoS via
  an arbitrarily long duplicate-criteria chain (added during the
  security-review cycle that initially FAILed on this).

### Known limitations (by design, not bugs)

- Sort mode/direction is global, not remembered per folder.
- Only `dc:title`/`dc:date` are supported — no `upnp:artist`/genre/etc.
  on either side.
- Manual on-device verification against real DLNA servers (the gap
  neither spec could exercise in this sandboxed environment) was done
  by the user directly during development — confirmed working,
  including the server-rejects-sort-criteria fallback path and the
  Date-button-disabled-when-no-dates behavior.

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
