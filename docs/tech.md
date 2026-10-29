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

### Large SAF folders

A Browse of a folder with thousands of files in the app's own SAF server (`SafFolderBrowser`) used to take longer than the
client's socket timeout. Verified in the code (androidx `documentfile` 1.1.0, `javap`): `TreeDocumentFile.listFiles()` queries only
`document_id`; then every `isDirectory()`, `getName()`, `lastModified()`, `getType()`, `length()` and `canRead()` (its mime check)
of every child is one more `ContentResolver.query` (`DocumentsContractApi19.queryForString/queryForLong`). `YaaccContentDirectory.browse`
ran `getSize` (listing), `browseContainer` (listing + `isDirectory()` per child) and `browseItem` (listing + `isDirectory()` +
two `getName()` per file, and with a sort 2 to 4 provider queries per comparison, so tens of thousands) for every chunk request.

As built:
- `SafDirectoryLister` lists a folder with ONE query on `DocumentsContract.buildChildDocumentsUriUsingTree(folderDocumentUri, id)`
  (columns: document id, display name, mime type, last modified, size) and returns immutable `SafDirectoryEntry` values in the
  provider's order. The uris of the children are built like `DocumentFile.listFiles()` does (`buildDocumentUriUsingTree`), so
  short ids and the metadata cache keys are unchanged. `FLAGS` is not queried: nothing uses it, and `canRead()` cannot be derived
  from it.
- `SafListingCache` (plain JVM): keyed by the folder's tree uri, at most 8 folders and 100000 entries in total, entries older
  than 30 s are listed again, least recently used first out, single flight (a request for a folder that is being listed waits, for
  at most 120 s, for that listing). Failed listings (query error, not a directory, not readable) are never kept. It is cleared on
  `SAFCacheManager.clearCache()`, `removeCachedUnder()` (permission given up) and when the SAF path preferences change.
  New files in a folder show up after at most 30 s.
- `SafListingSupport` (plain JVM): item candidates (no directories, no `*m3u`), directories, the stable sort by `dc:title` / `dc:date`
  (keys computed once per entry, same results as before - the unit test compares it with the former comparator on random data)
  and the paging. Only the page slice gets `createItem` / the container objects and short ids.
- `canRead()` of a child is read permission on its uri (`checkCallingOrSelfUriPermission`, a call to the system, not to the
  provider) and a non-empty mime type (known from the listing), so the "[X]" prefix and the restricted flag are the same as
  before without a provider query. Containers need it for every folder only when sorting by title (the prefix is part of the
  sort key); items only for the page.
- Provider queries per Browse request of a folder with N files and page size P (counted from the former code): about 4N + 13P
  with the default order (N for `isDirectory()` in the container pass, 3N for `isDirectory()` and two `getName()` per file in the
  item pass, about 13 per page item in `createItem` and its logging); a title sort adds about 2 N log2 N, a date sort about
  4 N log2 N (property queries inside the comparator). For N = 5000, P = 100: about 21000, 130000 (title) and 250000 (date).
  Now: 3 provider queries for the first request (root `isDirectory()`, `canRead()`, and ONE listing query with N rows),
  0 for the following requests of that folder within 30 s, plus P permission checks (system calls). A cache miss of the SAF metadata
  cache no longer queries the provider either (name, mime type, size come from the listing).
  `YaaccContentDirectory.browse` uses one browser instance for count, containers and items.
- One log line at D level per Browse: `SAF browse <id> list=<n> entries from cache|provider in <ms> ms, page <start>-<end> in <ms> ms`.
  The per-file lines are verbose, and the full DIDL response of `CDResponse` is verbose (D logs its length).
- Client: `HttpRequestSender` gives ContentDirectory actions (SOAPACTION contains `:service:ContentDirectory:`) a response timeout
  of 30 s (`CONTENT_DIRECTORY_RESPONSE_TIMEOUT`, `RequestConfig.setResponseTimeout`, verified in httpclient5 5.3.1
  `InternalExecRuntime.execute`); other requests keep the 10 s socket timeout.
- Cling lock: `AbstractActionExecutor.execute` runs the whole action in `DefaultServiceManager.execute`, which holds the fair
  `ReentrantLock` of the service (timeout `YaaccUpnpServerService.LOCK_TIMEOUT` = 5 s) for the entire Browse. A second Browse, a GENA subscribe
  (`LocalGENASubscription` constructor -> `getCurrentState()`) and `LocalGENASubscription.end()` (-> `getPropertyChangeSupport()`) wait for it. `end()` is
  called from `LocalItems.maintain()` inside `RegistryImpl.maintain()`, which is `synchronized` like `RegistryImpl.getDevice`: a slow Browse can
  therefore block `UpnpClient.getDevice()` / `getProviderDevice()` calls on the main thread for up to 5 s. Not changed (Cling); a Browse that
  takes a fraction of a second is the fix.

Not verified without a device: the provider's time for the one query of a 5000+ file folder (it depends on the provider; for the
external storage provider it stats each file), and the permission check behaviour of providers other than the external storage one.

Server configuration screen (shared folders tree): it used the same per-property `DocumentFile` queries on the main thread (`exists()` per child
on open; per bound row `getName()`, `isDirectory()` twice and, for folders, a full `listFiles()` for the expand symbol). Now one worker thread of the
activity reads a folder with `SafDirectoryLister` (shared cache) or `File.listFiles()` into plain `TreeNodeData`; binding asks nothing, every folder shows
the expand symbol until it was read and is empty, stale results are dropped by a per-node and a tree generation, children are inserted as ONE
`notifyItemRangeInserted` (`TreeNodeManager.Change`, `ArrayList`). Queries for N files: before about N + 3 + 7V on the main thread when opening (V visible rows)
and about 3N when scrolling; now 3 on the worker (0 within 30 s) and 0 on the main thread. D log: `SAF tree <uri> children=<n> loaded in <ms> ms`.

- The shared-folder tree of the server configuration shows folders only (`TreeNodeFactory`): only folders can be selected as a shared path, files were shown without a check box. A folder that holds nothing but files shows the expand arrow until it was opened once and then reads as empty.

- **Permission of sub folders and "[X]".** The grant of a folder picked with the system picker covers everything below it, but only for uris of that tree (`.../tree/<picked>/document/<child>`). Sub folders are therefore addressed as `SafDirectoryLister.childDocumentUri(parent, documentId)` and their permission is checked on that uri; `DocumentFile.fromTreeUri` accepts it. Before, a sub folder was addressed as a tree of its own (`buildTreeDocumentUri(authority, childId)`), which no grant matches, so the sub folders of a picked folder could show `[X]` although they are readable. `[X]` now means the app really holds no permission for the folder (e.g. a grant that was revoked).

## Browse card-grid view

The content tab can show a list or a grid of cards (`ContentListFragment` + `BrowseContentItemAdapter`,
`yaacc/src/main/java/de/yaacc/browser/`). The feature is built, reviewed and security-reviewed; its planning folder
was removed after shipping (the full history is in git). This section holds the platform facts it relies on; the
decisions and the shipped numbers follow in "Browse card-grid view: decisions and as shipped". The facts were verified by
direct inspection of the vendored AndroidX bytecode (`javap` of the `classes.jar` in the exact `.aar` Gradle resolves) and,
for `View.addOnLayoutChangeListener`, the official platform doc fetched live. Artifacts inspected:

- `androidx.recyclerview:recyclerview:1.3.1`, the version the dependency graph resolves to (there is no explicit
  `implementation` line in `yaacc/build.gradle`; it comes transitively through `appcompat`/`material`).
- `androidx.cardview:cardview:1.0.0`, already in use (`browse_content_item.xml` wraps the row in a `CardView`).

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
contentList.getLayoutManager()).setSpanCount(newCount)` (called from a
width-change listener) can be called
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
  since `LinearLayoutManager` already is the common base.

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
bounds change — so the listener fires in both cases, and a span count recomputed from such a
listener needs no separate `onConfigurationChanged` hook. API
level 11 is far below this project's `minSdk`, so no
`SDK_INT` guard is needed.

### 4. `IconDownloadTask` constructors: the `(ImageView, int, int)` overload leaks a task list entry

`yaacc/src/main/java/de/yaacc/util/image/IconDownloadTask.java` (yaacc's own code) has these constructors:
`(ImageView)` (48x48), `(ImageView, BrowseContentItemAdapter)` (48x48, keeps the adapter), `(ImageView, String deviceId)`
(48x48) and `(ImageView, int width, int height)`. The last one is also used by `BrowseReceiverDeviceAdapter` for 512x512
album art, and it always sets `browseContentItemAdapter = null`.

`BrowseContentItemAdapter#onBindViewHolder` adds every task it creates to `asyncTasks`, and a task removes itself only in
`onPostExecute` through `browseContentItemAdapter.removeTask(this)`, which is gated on the adapter field being non-null.
Calling the `(ImageView, int, int)` overload from the adapter would therefore never remove finished tasks and
`asyncTasks` would grow by one entry (with its `ImageView`) per bound row for the life of the adapter. Resolution as built:
a fourth overload `IconDownloadTask(ImageView, BrowseContentItemAdapter, int width, int height)` sets both the adapter
(cleanup preserved) and the size; no existing constructor changed.

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

## Browse card-grid view: decisions and as shipped

Both review gates passed. Planning history (requirements, design, tasks, decisions, review cycles) was removed after
shipping; git history has it. What a maintainer needs:

**Design.** One `BrowseContentItemAdapter`, one backing list, one set of click handlers; only the view layer varies.
- `BrowseContentItemAdapter.ViewMode { LIST, CARD }`, `ViewMode.DEFAULT = CARD` (used on a fresh install and when the stored
  value is unreadable; an explicit earlier choice stays). Persisted as a string under `settings_browse_view_mode_key`
  (stored key `browse_view_mode_key`), read by the adapter and by `ContentListFragment`. `setViewMode` persists and calls
  `notifyDataSetChanged()`, no reload (the data does not change, unlike a sort change).
- `getItemViewType` returns `viewMode.ordinal()` (it used to return the position, which defeated view recycling). The list
  row (`browse_content_item.xml`) and the card (`browse_content_item_card.xml`) use the same view ids and the same
  `ViewHolder`, so the per-content-type button visibility, click handlers and thumbnail loading are shared.
- The header has an `ImageButton` `contentListViewModeToggle` next to the sort toggle, in the portrait and the landscape
  layout. `ContentListFragment#applyLayoutManagerForViewMode` sets a `LinearLayoutManager` (list) or a `GridLayoutManager`
  (cards), also for a persisted mode when a layout has no button. While in card mode a layout-change listener re-applies the
  span count with `setSpanCount` on every width change (rotation, multi-window), never recreating the layout manager
  (facts 1-3 above). `scrollToPositionId` needs no change because `GridLayoutManager` is a `LinearLayoutManager`.
- Span count: `CardGridSpanCount.computeSpanCount(availableWidthPx, targetCardWidthPx) = max(2, availableWidthPx /
  targetCardWidthPx)` (pure JDK, unit-tested), with `ContentListFragment.TARGET_CARD_WIDTH_DP = 160` scaled by the display
  density: two columns on a normal phone in portrait, more on a landscape phone or a tablet.
- Thumbnails: the card image is a `SquareImageView` (height equals width) and `CARD_ICON_SIZE_DP = 320` is requested for it
  (`LIST_ICON_SIZE_DP = 48` for the list), through the four-argument `IconDownloadTask` constructor (fact 4). The first
  design said a 96dp icon; the square view replaced it.
- D-pad: movement inside a card reuses the generic key listener of the list row; movement between cards uses Android's
  default focus search (nothing is hand-wired). Revisit only if a TV check shows it feels wrong.

**Dependency pin and its residual risk (`yaacc/build.gradle`).** `httpclient5:5.3.1`, `httpcore5:5.3.6` and
`httpcore5-h2:5.3.6` are the newest versions that run on Android. From the 5.4 line on, `httpclient5`'s
`DefaultHttpClientConnectionOperator` and `httpcore5`'s `ReflectionUtils` get a static initializer that probes
`jdk.net.Sockets`/`ExtendedSocketOptions` (hidden JDK APIs that ART blocks for app code), so merely loading either class
crashes with `NoSuchMethodError` on a device (confirmed by decompiling several versions; the `httpcore5` path is the
embedded server's `SingleCoreIOReactor.prepareSocket`). An intermediate pairing of `httpclient5:5.2.1` with
`httpcore5:5.4.4` was tried to get the `httpcore5` security fixes and crashed on a device for that reason.
Accepted residual risk: `httpcore5` CVE-2026-54399 (HTTP/1.1 header parsing, memory exhaustion) and CVE-2026-54428
(HTTP/2 HPACK decoder in `httpcore5-h2`) are fixed only from 5.4.3 on; there is no 5.3.x backport. The exposure is the
embedded UPnP HTTP server against a malicious or compromised device on the LAN. Options to close it, not done (both are real
work): a local shim of `org.apache.hc.core5.util.ReflectionUtils` that wraps the probe (needs a build step that removes the
class from the dependency jar, else the duplicate class fails the build, and a re-check on every version bump), or moving the
embedded server off the asynchronous reactor stack (unconfirmed that the blocking classes avoid the same path). Check the
bytecode again before bumping either library.

**Tests.** `CardGridSpanCountTest` (floor behaviour: phone width gives 2, just under 3 cards still 2, wide gives more, narrower
than one card never below 2) and `BrowseContentItemAdapterTest` (view type per mode, `setViewMode` persists and does not
reload, thumbnail size per mode). Open device checks (grid spacing and button fit, thumbnail sharpness at 320dp,
D-pad between cards, column count on a tablet, in multi-window and in landscape) are listed in "Not verified on a device".

## Design decisions and rationale

The reasons behind the design of the #287 work, per feature area, each pointing at the section that describes the code as built.
The planning folder `.claude/specs/2026-10-23-unified-device-sheet/` (`design.md`, `decisions.md`, `known-risks.md`) holds the same
decisions in more detail while the share-code consolidation (Phase 4) is open; the earlier planning history is in git.

### Device sheet (as built: "Unified device sheet")
- One bottom sheet with one list grouped by receiver, 0..N player-session rows under each receiver. Players and receivers are not 1:1: a
  renderer can hold a music and an image player and the local device up to three singletons, so a merged row would hide sessions.
  Sessions whose device is not a receiver (a renderer that dropped off) are listed in a trailing group, never hidden.
- Sessions are ordered by a creation sequence that `PlayerService` assigns, not by player id (`AVTransportPlayer` ids are random, local
  ids are fixed per type). The receiver tab's single row shows the newest playing session, else the newest overall.
- `ReceiverRowBinder` is shared by the tab and the sheet, `RendererStatusMonitor.getShared()` is the one status poller, and the sheet polls
  and listens only while started.
- The sheet opens at half height on touch devices and fully expanded in D-pad mode, because rows below the screen edge can take focus
  without scrolling; `setFitToContents(false)` must come before `STATE_HALF_EXPANDED`, and an app overlay supplies the DayNight theme.

### Classic and new layout (as built: "Classic and new layout")
- Two activities, not one with hidden tabs: tab positions are tied to the `BrowserTabs` order in adapter, activity and saved state, and the
  server list is a different navigation model. A "Layout" setting picks one; classic stays byte-for-byte what it was (its footer, tabs and
  back behaviour), apart from leak fixes.
- An invisible `LauncherActivity` holds the filters and forwards a fresh allowlisted intent. The layout is compared on `onResume`
  (effective layout, 2 s relaunch guard), never on a preference listener or a result code. One process-wide idle timer counts started browsers.
- A fresh install and an install updated from a version without the setting start in the new layout; the new layout owns the receiver
  bar (receivers that play now) and a mini-player with one row per running player; the device sheet is new-layout only.
- The toolbar has one timer symbol; the remaining time is the toolbar subtitle because narrow screens drop an action item's text. A stored
  duration of 0 never starts the timer.
- Android backup is off, because it copied settings, stored share links and the receiver selection off the device.

### Sharing files and links (as built: "Sharing local files")
- A per-share, in-memory registry entry (128-bit random key, expiry, allowed addresses) instead of the persistent proxy setting, so a share
  never changes the user's settings. The HTTP server already runs whenever the process lives, so nothing is started or stopped for a share.
- A local file is held by its descriptor, opened only after the user confirmed the receivers (the sender's read grant lives as long as the
  receiving activity record); nothing is opened, copied or registered for an unconfirmed share. Non-seekable descriptors fall back to a capped,
  watched copy in `cache/incoming`.
- The strict address check is a setting (default on) because some renderers fetch from another address than they announce; off keeps key and
  expiry only. The key is not secret from the LAN: any control point that can query the receiver can read it.
- The exported launcher forwards one `content:` URI as one ClipData item plus the read flag behind a single-use launch token; the browsers
  repeat every check and take a file only with a valid token, and only in the effective layout. Only other apps' audio, video and image
  content is accepted (`file:` and any provider of this app are refused).
- Provider calls are bounded, because a Binder call cannot be interrupted: one call per provider, 3 tracked providers, 4 threads per pool.
- Receivers are picked per action in the new layout (the sheet in pick mode); the normal sheet has no selection checkboxes; one picked action
  at a time, because the stored selection is global and read back by `PlayerService.createPlayer`. Classic keeps its tab selection; only a
  shared file opens the picker there, with the selection pre-ticked and restored afterwards. The picker of the new layout ticks the
  receivers of the last confirmed pick (`LastPickedReceivers`, key `last_picked_receivers`, remembered by `ReceiverPickController#start`);
  the selection itself is still cleared after every action.
- Residual risk accepted by the owner: Android checks only that yaacc itself can read a forwarded URI, and yaacc holds broad media permissions.
  Mitigations are the type check, the human-in-the-loop pick that shows the cleaned name, the address and expiry limits and the read flag
  that the system only lets a sender set for URIs it can read itself. See "Threat model", "Security invariants of the share feature" and "Known limitations".

### Look and settings (as built: "Look and settings: theme, gray scale, log viewer")
- Theme is a three-state list setting (System, Dark, Light) that replaces the "Dark mode" check box; an existing choice migrates once.
- The whole app is gray scale in Material 3 style: every theme color is a gray with a dark variant in `values-night`, so controls look the
  same in both themes; the dark page is pure black, the light title bar has the page color (details below).
- Card view is the default on a fresh install; the log viewer's "Clear" is a time marker because the system may refuse to empty an app's log
  buffer; mini-player rows have an exit button besides stop (stop keeps the player, exit removes it).

### Browse resilience (network drops)
- `BrowseItemLoadTask` repeats a failed Browse on its worker thread via `BrowseRetryPolicy`: 3 tries in total, waits of 500 ms and 1500 ms, only
  for "no answer" (no result, or an `UpnpFailure` with `isTransportFailure()`: no connection/timeout/no response, device or HTTP sender missing).
  A UPnP error answered by the server (e.g. invalid sort) is not repeated, the sorted-to-unsorted fallback is unchanged; it stops when the task is cancelled.
- If all tries fail the list is not cleared: the items stay, `BrowseContentItemAdapter.isLoadFailed()` is set (reset by the next successful chunk or
  `clear()`), loading ends. A real empty folder (result without failure) still clears. Chunk offsets are unchanged; items are added only on success.
- `NetworkDeviceListener` tells `NetworkRecoveryListener`s (held weakly, via `UpnpClient`'s relay) after it re-enabled for a new network (not at startup);
  `ContentListFragment` registers while started and, if the load failed and none runs, continues the folder with `populateItemList(false)`
  (debounce 2 s, `NetworkRecoveryReloadGate`). Not verified on a device.
- `UpnpClient.browseSync` returns a failure result instead of throwing when the server service, its network listener or HTTP sender is missing.
- Resume (suspend/Doze): the registry expires remote devices by wall clock, so after a long sleep the provider/renderers can be missing, and
  `UpnpClient.isInitialized()` is false while the `NetworkDeviceListener` is disabled (`getDevice` then returns null for every id). Activity `onResume`
  calls `UpnpClient.refreshAfterResume()` (worker thread): `NetworkDeviceListener.reenableIfStale()` does `disable()`+`enable()` if the listener is
  disabled, the active network is not `currentNetwork`, or a receive loop ended (`isReceiving()`); then M-SEARCH if that happened or the last one is
  >= 30 s old (`ResumeRefreshPolicy`). Log lines start with `Resume:`.
- `ContentListFragment` keeps the folder when its server leaves the registry or is missing on `onResume`/restore (`loadFailed` is set); `deviceAdded/Updated`
  of the same UDN (`FolderServerMatch`) or the next resume/network event continues it (`shouldReloadNow`, no debounce). Another server replaces it.
- Play: `PlayerService.createPlayer`/`UpnpClient.addToPlaylist` call `awaitReceiverDevices()`: a selected receiver missing in the registry is looked for
  3 times, 2.5 s apart, with a search before each wait (`ReceiverAvailabilityPolicy`, never on the main thread); then `getReceiverDevices()` drops it as before.

## Unified device sheet

Research notes for the device sheet (Phase 1), updated to match the code as built. Line numbers quoted in
section 6 are from the first research and may have moved. Section 8
summarizes how the pieces fit together as built.
Verified 2026-10-23 against `com.google.android.material:material:1.14.0`,
the version already declared in `yaacc/build.gradle:20` (nothing added).
Two sources were used:

- **[javap]**: `classes.jar` and `res/values/values.xml` extracted from
  `~/.gradle/caches/modules-2/files-2.1/com.google.android.material/material/1.14.0/b44cfc942dbe846c607aa50738e2bb1272796f8c/material-1.14.0.aar`,
  inspected with `javap -public`, `javap -constants` and `javap -c -p`.
- **[ref]**: the official API reference, fetched live through the
  `android-docs` MCP server (`get_api_reference`):
  `developer.android.com/reference/com/google/android/material/bottomsheet/BottomSheetBehavior`.
  That page's "View Source" link points at the `1.14.0` tag, so the
  reference matches this version.

### 1. Classes and imports

```java
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
```

From [javap] (`javap -public`):

```
public class BottomSheetDialogFragment extends androidx.appcompat.app.AppCompatDialogFragment {
  public BottomSheetDialogFragment();
  public BottomSheetDialogFragment(int);            // contentLayoutId
  public android.app.Dialog onCreateDialog(android.os.Bundle);  // returns new BottomSheetDialog(getContext(), getTheme())
  public void dismiss();
  public void dismissAllowingStateLoss();
}
public class BottomSheetDialog extends androidx.appcompat.app.AppCompatDialog {
  public BottomSheetBehavior<android.widget.FrameLayout> getBehavior();
  public void setDismissWithAnimation(boolean);
  public void setCancelable(boolean);
  public void setCanceledOnTouchOutside(boolean);
  ...
}
public class BottomSheetBehavior<V extends View> extends CoordinatorLayout$Behavior<V>
    implements com.google.android.material.motion.MaterialBackHandler {
  public static final int STATE_DRAGGING = 1, STATE_SETTLING = 2, STATE_EXPANDED = 3,
                          STATE_COLLAPSED = 4, STATE_HIDDEN = 5, STATE_HALF_EXPANDED = 6;
  public void setFitToContents(boolean);   public boolean isFitToContents();
  public void setHalfExpandedRatio(float); public float getHalfExpandedRatio();
  public void setState(int);
  public void setSkipCollapsed(boolean);
  public void setHideable(boolean);
  public void setDraggable(boolean);
  public void setMaxHeight(int);
  public void addBottomSheetCallback(BottomSheetBehavior$BottomSheetCallback);
  public static <V extends View> BottomSheetBehavior<V> from(V);
}
```

### 2. Getting the behavior

`BottomSheetDialog.getBehavior()` calls the private
`ensureContainerAndBehavior()` lazily if the behavior doesn't exist yet. That
method inflates `design_bottom_sheet_dialog`, finds the
`R.id.design_bottom_sheet` `FrameLayout`, and calls
`BottomSheetBehavior.from(bottomSheet)` and then `setHideable(cancelable)`
[javap: `javap -c -p ... BottomSheetDialog`, `getBehavior` and
`ensureContainerAndBehavior`]. So the fragment can call
`((BottomSheetDialog) requireDialog()).getBehavior()` at any point after
`onCreateDialog`, and never gets null back. There's no need for
`BottomSheetBehavior.from(view.getParent())`.

### 3. Starting half-expanded, then dragging to full height

Verified behavior [javap: `javap -c -p ... BottomSheetBehavior`]:

- **Defaults.** The constructor sets `fitToContents = true` and
  `halfExpandedRatio = 0.5f`. [ref] agrees: "Default value is true" for
  `setFitToContents`, and "The default value is 0.5" for
  `setHalfExpandedRatio`.
- **`setHalfExpandedRatio(float)`** throws `IllegalArgumentException`
  ("ratio must be a float value between 0 and 1") unless `0 < ratio < 1`.
  The half-expanded top offset is `parentHeight * (1 - ratio)`, from
  `calculateHalfExpandedOffset()`.
- **`setFitToContents(true)` while the state is `STATE_HALF_EXPANDED`
  forces `STATE_EXPANDED`.** The bytecode is
  `setStateInternal(fitToContents && state == 6 ? 3 : state)`, so **call
  `setFitToContents(false)` before `setState(STATE_HALF_EXPANDED)`**.
- **`setState(STATE_HALF_EXPANDED)` with `fitToContents == true`** turns
  into `STATE_EXPANDED` whenever the half-expanded offset is less than or
  equal to `fitToContentsOffset`, that is, whenever the content is no taller
  than the half-height. [ref] describes `STATE_HALF_EXPANDED` as "used when
  fitToContents is false".
- **`setState(STATE_DRAGGING | STATE_SETTLING)`** throws
  `IllegalArgumentException`. `setState(STATE_HIDDEN)` on a non-hideable
  sheet only logs a warning.
- **Expanded offset.** `getExpandedOffset()` returns `fitToContentsOffset`
  when `fitToContents` is true. Otherwise it returns `expandedOffset`
  (default 0, or the top inset when padding for the system window). With
  `fitToContents = false`, "expanded" therefore means the **top of the
  screen**, and the user drags up from half to get there.
- **Sheet height (not in the original plan).** The dialog's
  sheet container (`design_bottom_sheet_dialog.xml`, id
  `design_bottom_sheet`) is `layout_height="wrap_content"` [javap: the
  AAR's `res/layout/design_bottom_sheet_dialog.xml`]. With
  `fitToContents = false`, a short list (two or three receivers) would be
  pinned at the top in STATE_EXPANDED, leaving an empty scrim below it. To
  make "drag to full height" mean the sheet fills the screen, set the
  container's height to `MATCH_PARENT`, as in the snippet below.
- **Dialog start state.** `BottomSheetDialog.onStart()` only changes the
  state when it is `STATE_HIDDEN`, and then sets `STATE_COLLAPSED`. So a
  state set before `show()`, or in the fragment's `onStart()` after
  `super.onStart()`, is kept. Without a layout pass, `setState` stores the
  requested state through `setStateInternal`, and it is applied on the
  first `onLayoutChild`.
- **Dragging below half-expanded** goes to `STATE_COLLAPSED`, at the peek
  height. Use `setSkipCollapsed(true)` to make a drag down from expanded or
  half go straight to hidden, which dismisses the dialog. [ref]: "Setting
  this to true has no effect unless the sheet is hideable". The dialog sets
  hideable to its `cancelable` flag, which is true by default.

Usage pattern. As built, this lives in
`DeviceSheetFragment#applyInitialSheetState`. It runs once, from the first
`onStart()` after `super.onStart()`, so coming back from a player activity
keeps the user's drag position. It sets the container height through
`setLayoutParams`, and passes `STATE_EXPANDED` instead of
`STATE_HALF_EXPANDED` when not in touch mode (see section 5):

```java
@Override
public void onStart() {
    super.onStart();                          // DialogFragment shows the dialog here
    BottomSheetDialog dialog = (BottomSheetDialog) requireDialog();
    View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
    if (sheet != null) {                      // full-height container, so EXPANDED fills the screen
        sheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        sheet.requestLayout();
    }
    BottomSheetBehavior<FrameLayout> behavior = dialog.getBehavior();
    behavior.setFitToContents(false);         // must come before HALF_EXPANDED (see above)
    behavior.setHalfExpandedRatio(0.5f);      // 0 < ratio < 1, else IllegalArgumentException
    behavior.setSkipCollapsed(true);          // dragging down from half dismisses
    behavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
}
```

### 4. Theme (not in the original plan)

> Superseded (Material 3 migration): `AppTheme` now has the parent `Theme.Material3.DayNight`, which sets `bottomSheetDialogTheme` itself;
> the app overlay `ThemeOverlay.App.DeviceSheet` stays (explicit `getTheme()`) with the parent `ThemeOverlay.Material3.BottomSheetDialog`.
> The text below describes the Bridge theme it was written for.

`BottomSheetDialogFragment.onCreateDialog` builds
`new BottomSheetDialog(getContext(), getTheme())`. With theme 0, the private
`BottomSheetDialog.getThemeResId` resolves `?attr/bottomSheetDialogTheme`.
If that attribute isn't set, it falls back to
`R.style.Theme_Design_Light_BottomSheetDialog`
[javap: `getThemeResId` bytecode].

yaacc's `AppTheme` has `Theme.MaterialComponents.DayNight.Bridge` as its
parent (`yaacc/src/main/res/values/styles.xml:21`). In the 1.14.0
`values.xml`, the Bridge chain
(`...DayNight.Bridge -> ...Light.Bridge -> Base.V14.Theme.MaterialComponents.Light.Bridge -> Platform.MaterialComponents.Light -> Theme.AppCompat.Light`)
**does not set `bottomSheetDialogTheme`**: the Bridge base style has 68
items, none of them for dialogs or sheets. The non-Bridge
`Base.V14.Theme.MaterialComponents.Light` sets it to
`@style/ThemeOverlay.MaterialComponents.BottomSheetDialog`. Without action,
the sheet would therefore use the light `Theme.Design` dialog theme, even
in night mode. There were two ways to fix it:

- Override `getTheme()` in `DeviceSheetFragment` to return
  `com.google.android.material.R.style.ThemeOverlay_MaterialComponents_BottomSheetDialog`.
- Add `<item name="bottomSheetDialogTheme">@style/ThemeOverlay.MaterialComponents.BottomSheetDialog</item>`
  to `AppTheme`.

**As built:** the first, through an app style.
`DeviceSheetFragment#getTheme()` returns `R.style.ThemeOverlay_App_DeviceSheet`,
declared in `yaacc/src/main/res/values/styles.xml` with
`ThemeOverlay.MaterialComponents.BottomSheetDialog` as its parent. Being an
overlay, it keeps `AppTheme`'s DayNight colors. `AppTheme` itself is
unchanged.

### 5. Back and D-pad focus inside the dialog

- **Back.** Back on a cancelable dialog calls `Dialog.cancel()`.
  `BottomSheetDialog.cancel()` dismisses straight away when
  `dismissWithAnimation` is false, which is the default. When it is true,
  it calls `behavior.setState(STATE_HIDDEN)` instead, and the dialog's
  callback dismisses it once the sheet is hidden [javap: `cancel()`].
  `BottomSheetDialog` also creates a `MaterialBackOrchestrator` over the
  behavior and starts listening for back callbacks only while it is
  `cancelable` (`updateListeningForBackCallbacks`).
  `BottomSheetBehavior.handleBackInvoked()` sets `STATE_HIDDEN` if the
  sheet is hideable, otherwise `STATE_COLLAPSED`. It checks
  `SDK_INT >= 34` for the predictive-back animation. Either path ends with
  the dialog dismissed, so **Back dismisses the sheet with no extra code**,
  as long as the default `cancelable = true` is left alone.
  `BottomSheetDialogFragment.dismiss()` animates the hide first when the
  dialog has `dismissWithAnimation` set and the behavior is hideable.
- **D-pad focus is trapped in the dialog.** The dialog is its own window
  (platform `Dialog`), so D-pad focus search stays inside it and can't
  reach the Content tab behind it. Material adds no key handling of its
  own: no `onKeyDown` or `dispatchKeyEvent` in `BottomSheetDialog`'s
  `javap -c -p` member list. The behavior's `updateAccessibilityActions`
  only adds accessibility expand and collapse actions, not D-pad bindings.
- **Risk (inferred from bytecode, not yet seen on a TV).** In
  `STATE_HALF_EXPANDED`, the sheet is laid out at its full height and
  moved down with `ViewCompat.offsetTopAndBottom` (`onLayoutChild`). The
  bottom half is off-screen but still laid out, so RecyclerView rows there
  are attached and focusable. D-pad down can move focus to a row below the
  screen edge, and nothing scrolls, because the row is inside the
  RecyclerView's own bounds. **As built:** the sheet opens in
  `STATE_EXPANDED` when the UI mode is `UI_MODE_TYPE_TELEVISION` or the
  activity's decor view isn't in touch mode
  (`DeviceSheetFragment#isTouchInput`), and in `STATE_HALF_EXPANDED`
  otherwise. Still to confirm in the manual TV check.
- **Initial focus.** The plan wanted focus on the first receiver's
  checkbox. Nothing in Material sets initial focus. **As built:** when not
  in touch mode, the fragment calls `DeviceSheetAdapter#requestInitialFocus()`,
  and the adapter posts a focus request when it binds the first
  receiver row (the checkbox in Phase 1; since Phase 3 the normal sheet has no
  checkbox and the row takes the focus, in pick mode the first checkbox). Stable ids (`R:<udn>` / `P:<playerId>` keys) and `DiffUtil`
  keep focus on the same row across refreshes.

### 6. How the Player tab learns about new or ended players

`grep -rn "addPlayer\|removePlayer\|shutdown(" yaacc/src/main/java/de/yaacc/player/PlayerService.java`:

```
106:    public void addPlayer(Player player) {
122:    public void removePlayer(Player player) {
193:        shutdown();
295:                addPlayer(result);
332:                addPlayer(result);
382:                    shutdown(player);
395:                    shutdown(result);
423:            shutdown(result);
433:            shutdown(result);
519:    public void shutdown(Player player) {
530:    public void shutdown() {
533:            shutdown(player);
```

**Answer: polling on `onResume`. There's no listener or broadcast.**

- `PlayerService.addPlayer` (`PlayerService.java:106-120`) puts the player
  into `currentActivePlayer`, a plain `HashMap` (`:59`). It registers a
  `"playing"` property listener on the player that only calls
  `updateForegroundState()`, then calls `updateForegroundState()` itself.
  `removePlayer` (`:122-125`) and `shutdown(Player)` (`:519-525`) remove
  the player from the map and call `updateForegroundState()`. **None of the
  three notifies any observer.** `updateForegroundState` only manages the
  foreground notification.
- Callers: `addPlayer` from `createPlayer...` (`PlayerService.java:295,
  332`), and again from each player's async `onServiceConnected`
  (`AbstractPlayer.java:159`, `LocalImagePlayer.java:91`). `removePlayer`
  is called from `onServiceDisconnected` (`AbstractPlayer.java:167`,
  `LocalImagePlayer.java:98`) and `AbstractPlayer.onDestroy`
  (`AbstractPlayer.java:965`). `shutdown(this)` is called from `exit()`
  (`AbstractPlayer.java:732`, `LocalImagePlayer.java:227`).
- `PlayerListFragment` reads `upnpClient.getCurrentPlayers()`
  (`UpnpClient.java:829-834`, which delegates to
  `PlayerService.getCurrentPlayers()` at `:445-448`, an **unmodifiable live
  view** of the map's values, not a copy). It reads it in
  `populatePlayerList()` (`PlayerListFragment.java:80-93`), which is
  triggered only from `init` (`:58`, on view creation) and `onResume`
  (`:64-67`). It does register as a `UpnpClientListener` (`:57`), but that
  interface (`UpnpClientListener.java:29-39`) only carries device
  added/removed/updated events, and all five callbacks in the fragment are
  empty. A player that starts or ends while the Player tab is visible
  shows up only after the tab is resumed again.
- **Reusable by `DeviceSheetFragment`? No.** Refreshing on `onResume`
  doesn't help a sheet that stays open while playback starts. For example,
  the user picks a receiver and then taps play, and the session row should
  appear. Per-player state changes *are* observable through
  `Player#addPropertyChangeListener` / `removePropertyChangeListener`
  (`Player.java:131,138`), which fire `"playing"` (`AbstractPlayer.java:465`)
  and the item-change property (`AbstractPlayer.java:571`). That covers
  updating an existing session row, but not adding or removing one.
- **Added in Group 2:** a player-set listener on `PlayerService`,
  `addPlayerListChangeListener(PlayerListChangeNotifier.Listener)` /
  `removePlayerListChangeListener(...)`, with `onPlayerAdded(Player)` and
  `onPlayerRemoved(Player)`. It fires after the map has changed. `addPlayer`,
  `removePlayer` and `shutdown(Player)` are reached from
  `onServiceConnected`/`onServiceDisconnected`, which run on the main
  thread, and from `createPlayer...`, whose thread depends on the caller,
  so callbacks are posted to the main thread. UI code registers through
  `UpnpClient.addPlayerListChangeListener` / `removePlayerListChangeListener`
  instead: `UpnpClient` keeps the listeners and attaches them to the
  service whenever it (re)binds, without duplicates, so a sheet opened
  before `PlayerService` is bound, or kept open across a rebind, still gets
  events. The sheet registers in `onStart()` and unregisters in `onStop()`
  (with `onDestroyView()` as a backstop). Because `getCurrentPlayers()` is
  a live view of the map, the sheet (`DeviceSheetAdapter#refresh`) copies
  it, `new ArrayList<>(...)`, on the main thread before building
  `DeviceSessionGrouper.PlayerRef`s. `ReceiverRowBinder#primaryPlayer`
  iterates the live view directly on the main thread; that is safe because
  the backing map is a `ConcurrentHashMap` (weakly consistent iteration).
- **Repeat calls in both directions. The notifier ignores them.**
  - **Add twice.** `addPlayer` can run twice for the same player: once
    from `createPlayer...` (`PlayerService.java:295, 332`) and once from
    `onServiceConnected` (`AbstractPlayer.java:159`,
    `LocalImagePlayer.java:91`). Each call also adds another anonymous
    `"playing"` listener that is never removed
    (`PlayerService.java:113-117`). That is harmless today, because the
    listener is idempotent, and out of scope here.
  - **Remove twice.** `shutdown(Player)` (`PlayerService.java:519-525`)
    removes the player from the map and then calls `player.onDestroy()`.
    `AbstractPlayer.onDestroy()` calls `playerService.removePlayer(this)`
    again (`AbstractPlayer.java:958-965`).
  - So the notifier (`PlayerListChangeNotifier`, as built in Group 2)
    decides from what `put` returns: `null` fires **"added"**; the same
    instance fires nothing (a re-add); a *different* instance fires
    **"removed"** for the replaced player and then **"added"** for the new
    one (a same-id replacement - `MultiContentPlayer`'s id is fixed). Removal uses `remove(id, player)` and fires
    **"removed"** only if that exact instance was still in the map, so the
    second removal from `onDestroy()`, or a stale instance after a
    replacement, notifies nothing. Otherwise one player start or stop
    would notify twice, or for the wrong player.
- **Group 2: threads that write `currentActivePlayer`, and the fix.** The
  map is written off the main thread. `ContentItemPlayTask.doInBackground`
  calls `ContentListFragment.play(upnpClient.initializePlayers(...))`,
  which reaches `PlayerService.createPlayer` and then `addPlayer` on a
  background thread. The renderer path does the same
  (`AvTransportMediaRendererPlaying.java:88`). Meanwhile the Player tab
  reads the live `getCurrentPlayers()` view on the main thread. As a
  `HashMap`, the map could throw `ConcurrentModificationException` or
  lose entries. It is now a `ConcurrentHashMap`. Its `values()`
  iteration is weakly consistent and never throws, and `put`/`remove`
  are atomic, so the "added"/"removed" decision taken from their return
  values (`PlayerListChangeNotifier`) is reliable. Keys are `int` ids,
  and every put site dereferences the player before the put, so there
  are no null keys or values. `getCurrentPlayerById(null)` returns null
  explicitly. The listener API is
  `PlayerService.addPlayerListChangeListener(PlayerListChangeNotifier.Listener)`
  / `removePlayerListChangeListener(...)`. Callbacks are posted to the
  main thread. A listener removed before a queued delivery runs is not
  called.

### 7. `DeviceSessionGrouper`

- `yaacc/src/main/java/de/yaacc/browser/DeviceSessionGrouper.java` has the
  public API of the design, implemented in Group 2. `PlayerRef` exposes
  record-style accessors `playerId()`, `deviceId()`, `playing()` and
  `sequence()` (the creation sequence), matching `Row`'s `kind()`,
  `deviceId()` and `playerId()`. These are plain final classes, not
  records, with no `android.*` imports.
- `group` lists receivers in the given order, each followed by its
  sessions in ascending creation sequence (oldest first).
  `primarySession` picks the playing session with the highest creation
  sequence, otherwise the highest overall; equal sequences fall back to
  the higher player id, so the result never depends on input order. The
  player id is only the lookup key: `AVTransportPlayer` ids are random
  and local player ids are fixed per type, so they say nothing about age.
- `yaacc/src/test/java/de/yaacc/browser/DeviceSessionGrouperTest.java`
  (JUnit 4.13.2, plain JVM) pins two readings that the design left
  open:
  1. Sessions on a device that isn't a receiver get **no RECEIVER row**.
     A receiver row means bound device controls, which a vanished device
     can't have. The adapter labels the trailing group from the session
     row's `deviceId()`. Those sessions stay contiguous per device, in
     ascending creation-sequence order (oldest first), after all receiver
     groups. As built in Group 2 (fix cycle 1), sessions are ordered by the
     creation sequence `PlayerService#getPlayerCreationSequence` assigns,
     not by player id (see "Design decisions and rationale").
  2. With no receivers at all, every session is still listed.

### 8. As built: how the pieces fit

- **Entry points (new layout only).** The sheet is reached from the new layout's
  receiver bar (`R.id.browserReceiverBar` in `activity_browser.xml`, bound by
  `ReceiverBarController`) through `BrowserHost.showReceivers()`. (Phase 3: the
  no-receiver prompt is gone; the same fragment also serves as the "Play on..."
  picker, see "Sharing local files", "As built: the receiver picker".) The classic layout (`TabBrowserActivity`) has no
  entry point: `showReceivers()` there still switches to the Receiver tab, and no
  classic fragment opens `DeviceSheetFragment`. (Phase 1 had put the sheet into
  the classic Content tab; owner feedback, Group 11, reverted that.)
- **Receiver bar label.** Phase 1 showed the selected receivers; since Phase 3
  `ReceiverBarController#label` names the receivers that have a player in
  `PlayerService.getCurrentPlayers()` (a paused player counts), cleaned, merged,
  sorted and joined with `"; "`, or `receiver_bar_none_playing` ("No receiver
  playing"). The bar refreshes on the player-list listener and the receiver
  events. See "Sharing local files", "As built: the receiver picker".
- **Receiver choice per action (new layout only).** Phase 1 opened the sheet
  when playing with no receiver selected
  (`ContentListFragment#openDeviceSheetIfNoReceiverSelected()` and the
  `receiver_bar_none_selected` text; both are removed). Now play, play-all, an
  item tap and add to playlist call `ContentListFragment#startPlayAction`, which
  opens the "Play on..." picker first. In classic `startPlayAction` starts at
  once, so play behaves as on `origin/feat/issue287`, and the
  `error_no_receiver_selected` toast in `PlayerService.createPlayer` stays for
  classic and for non-UI callers.
- **Sheet contents.** `DeviceSheetFragment.show(FragmentManager)` does
  nothing if the sheet is already showing or the state is saved.
  `DeviceSheetAdapter#refresh()` takes the receivers from
  `getDevicesProvidingAvTransportService()` (the Receiver tab's source
  order: local device first, then by name), resolves each by id through
  `UpnpClient.getDevice(id)`, and lays the rows out with
  `DeviceSessionGrouper.group`. Receiver rows are bound by
  `ReceiverRowBinder`, the same binder the Receiver tab's
  `BrowseReceiverDeviceAdapter` uses; the sheet's rows have no selection
  checkbox (Phase 3; the classic Receiver tab keeps its checkbox through the
  four-argument `bind`). Session rows
  (`device_sheet_player_session_item.xml`) show the player name, current
  title and album art (through `IconDownloadTask`), plus the device id for
  sessions whose device isn't a receiver; all LAN strings are set with plain
  `setText`. Tapping a session sends the player's own notification
  `PendingIntent` (`ReceiverRowBinder.openPlayer`), as the Player tab does.
- **Receiver row tap.** It opens the activity of the device's primary
  session (`DeviceSessionGrouper.primarySession`). Only the
  `UpnpClient.LOCAL_UID` row is a no-op, as before the extraction; yaacc's
  own renderer (a `LocalDevice` with its own UDN, controlled through an
  `AVTransportPlayer`) opens its player.
- **Live updates, only while visible.** `startLiveUpdates()` runs from
  `onStart()` and `stopLiveUpdates()` from `onStop()` (with
  `onDestroyView()` as a backstop), so nothing polls while a player activity
  is on top or the app is in the background. Coming back, the list is
  rebuilt once. While started, the sheet:
  - subscribes to `RendererStatusMonitor.getShared()` with the AVTransport
    devices (10 s poll per remote renderer);
  - listens for player add/remove through
    `UpnpClient.addPlayerListChangeListener` and rebuilds the list;
  - polls every 2 s for the local device's status (it has no monitor feed)
    and for changed session titles or art, rebinding only rows whose shown
    state changed.
- **One shared monitor.** `RendererStatusMonitor.getShared()` is the only
  instance. `subscribe(listener, devices)` starts polling devices not yet
  polled; `unsubscribe(listener)` stops all polling once the last listener
  leaves, and a generation counter drops results still in flight. The
  bookkeeping is in `MonitorSubscriptions` (plain Java, unit-tested in
  `MonitorSubscriptionsTest`). The Receiver tab (`ReceiverListFragment`)
  uses the same instance, subscribing in `onResume()` and unsubscribing in
  `onPause()`. All of it is main-thread only.
- **Cleanup.** `onDestroyView()` calls `DeviceSheetAdapter#release()`, which
  shuts down the sheet's `ReceiverRowBinder` executor, so no thread is left
  behind per open.
- **Known limitations (follow-ups).** The classic
  layout does not offer the sheet (owner decision, Group 11); there the Receiver
  and Player tabs are the only way to control receivers and players. The sheet
  doesn't add or remove receivers that appear or vanish on the network while
  it is open (known receivers' status does update). After rotation it returns
  to its initial half/full position. `RendererStatusMonitor` polls on a
  single thread, so one slow renderer delays status for every receiver in
  both the tab and the sheet; transport controls are unaffected.

## Classic and new layout

Two browser activities share one app: the **classic** layout
(`TabBrowserActivity`: four tabs) and the **new** single-screen layout
(`BrowserActivity`: server list, then a server's content, with a receiver bar
and a mini-player on every level). The "Layout" setting picks one. This section
records the layout as built (Phase 2, Groups 5-9), verified 2026-10-24 against the checkout at `e312ee8b` on branch
`feat/unified-device-sheet`, followed by the Group 5 research it was built
from (condensed to the platform facts). Group 11 (owner feedback, see "Owner feedback
(Group 11)") changed three behaviors after that verification: the timer entry,
the classic footer and the mini-player. "As built" subsections cite classes, not line
numbers. The look and settings changes made afterwards (layout default, theme, gray scale,
log viewer, server list button, mini-player exit, timer subtitle) are in "Look and settings: theme, gray scale, log viewer".

Versions (nothing added by Phase 2): `yaacc/build.gradle` has `targetSdkVersion
33`, `minSdkVersion 27`. `appcompat:1.8.0` resolves `androidx.activity:activity:1.8.0`,
`androidx.fragment:fragment:1.5.4` and `androidx.core:core:1.16.0`
(`./gradlew --offline :yaacc:dependencies --configuration debugRuntimeClasspath`).
`androidx.preference:preference:1.2.1` and `material:1.14.0` are unchanged;
`swiperefreshlayout` is not a dependency, so the new layout has a refresh
button and no pull-to-refresh.

Sources, and how each claim in the research subsections is marked:

- **[code]**: read in this repo, cited as `file:line` (pre-Group-6 numbers).
- **[javap]**: `javap -public` on `activity-1.8.0.aar` from
  `~/.gradle/caches/modules-2/files-2.1/androidx.activity/activity/1.8.0/`.
- **[docs]**: official documentation, fetched through the `android-docs` MCP
  (it worked in this session) or `curl` on developer.android.com. URL given.
- **[aosp]**: AOSP source, tag `android-14.0.0_r1`, fetched from
  `raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/`
  (`android.googlesource.com` was not reachable). Platform behavior that the
  public docs do not state. Newer releases may differ.
- **[reasoned]**: inferred from code or the sources above, not run on a device.
  "Known limitations / owner decisions" lists what the owner still has to
  confirm.

### As built: overview

All classes are in `de.yaacc.browser` unless a package is given.

| Class | Role |
|---|---|
| `de.yaacc.LauncherActivity` | Invisible (`Theme.NoDisplay`), exported entry. Holds the launcher, Android TV and share filters and starts the browser of the effective layout with a fresh intent. |
| `LauncherForwardPolicy` | Plain Java. Decides what the launcher forwards (see "Launcher"). |
| `BrowserLauncher` | `effectiveLayout(String)` / `effectiveLayout(Context)`, `targetClass(...)`, `relaunchIfOtherLayout(Activity, Layout)`, the `NEW_LAYOUT_AVAILABLE` constant (`true`). |
| `BrowserLayoutChoice`, `BrowserLayoutSwitch`, `RelaunchGuard` | Plain Java: stored string to `Layout`, the "relaunch?" decision, the loop guard. |
| `TabBrowserActivity` | Classic. Exported, no intent filters, implements `BrowserHost`. |
| `BrowserActivity` | New. `exported="false"`, no intent filters, implements `BrowserHost`. |
| `BrowserHost` | Navigation the fragments and helpers may ask of their activity. |
| `BrowserActivitySupport` | Moved verbatim out of `TabBrowserActivity`: runtime permission request (code 101, result not handled), battery-optimization prompt (started on every `onCreate`), and the common menu items (exit, settings, about, log). |
| `ServerListFragment` | Server list for both layouts (see "Server list"). |
| `ContentListFragment` | Content of one server for both layouts. Classic: shows the original static footer (provider line, receivers line). New: hides it; the host owns the receiver bar. |
| `BrowserBackNavigation` | Plain Java: back decision of the new layout. |
| `ShareIntentHandler`, `ShareUriPolicy` | Shared handler for a link or one file shared into yaacc (Phase 3 added the file path, the pending share and the picker, see "Sharing local files"). |
| `ShutdownTimerController`, `ShutdownTimerToggle` | Shutdown timer UI logic and the toolbar tap decision (stop, or ask for a duration). |
| `StreamingPermissionHelper` | MediaProjection permission flow of the yaacc server row. |
| `BackgroundIdleTimer`, `StartedBrowserCounter` | One process-wide idle exit. |
| `ReceiverBarController` | Receiver bar binding and label (new layout only). |
| `MiniPlayerBar`, `MiniPlayerAdapter`, `MiniPlayerRows`, `MiniPlayerArtKeys`, `MaxHeightRecyclerView`, `MiniPlayerPresenter` | Mini-player (new layout only): controller, row adapter, row order and art key, per-player art bookkeeping, height-capped list, text cleaning. |
| `de.yaacc.upnp.server.LocalServerLifecycle` | Starts or stops `YaaccUpnpServerService` from the preferences; both browsers call it from `onResume`. |

### As built: `BrowserHost`

```java
public interface BrowserHost {
    void showServers();
    void showContent();
    void showReceivers();
    void showPlayers();
    default boolean providesShutdownTimer()  { return false; }  // toolbar owns the timer
    default boolean providesReceiverBar()    { return false; }  // activity owns the receiver bar and the sheet
    default boolean showsBackAtContentRoot() { return false; }  // back arrow also at a server's root
    // Phase 3, see "Sharing local files": default no-ops returning false
    default boolean pickReceivers(PickedAction action, String shareName, Collection<String> initialIds) { return false; }
    default boolean isPickOpen(String pickId)                { return false; }
    default void    onPendingShare(String token, String displayName) { }
}
```

Callers tolerate "no host" and may call from any thread (`deviceAdded` is a
`UpnpClientListener` callback), so each implementation hops to the main
thread itself.

| Method | Classic (`TabBrowserActivity`) | New (`BrowserActivity`) |
|---|---|---|
| `showServers()` | `setCurrentTab(SERVER)` | `popBackStack("browser_content", POP_BACK_STACK_INCLUSIVE)`; a no-op when the server list is already on top, and skipped once the fragment state is saved |
| `showContent()` | `setCurrentTab(CONTENT)` | runs pending transactions, then replaces the container with a new `ContentListFragment` and adds it to the back stack under `browser_content`; a no-op when a `ContentListFragment` is already on top, skipped when finishing or state saved. The provider is stored by `UpnpClient#setProviderDevice`, which `ContentListFragment` reads itself, so no argument is passed |
| `showReceivers()` | `setCurrentTab(RECEIVER)` | `DeviceSheetFragment.show(getSupportFragmentManager())` (ignores a second call or a saved state) |
| `showPlayers()` | `setCurrentTab(PLAYER)` | no-op: the mini-player follows the running players by itself |

Capability defaults: the new activity returns `true` for all three, classic
keeps `false`.

Live call sites: `ServerListClickListener` (`parent instanceof BrowserHost`,
then `showContent()`), the `ShareIntentHandler` callback (classic passes
`this::showPlayers`, new passes `null`), `ServerListFragment` (`providesShutdownTimer()`,
and an `instanceof BrowserHost` guard around `setLocalServerState`),
`ContentListFragment` (`providesReceiverBar()`, `showsBackAtContentRoot()`,
`showServers()`). The four `getParent() instanceof TabBrowserActivity`
branches of the research (`getParent()` is `null` for a plain activity, so they
never ran) were removed, not wired: classic back on the Content root, Receiver
and Player tab is still swallowed (`onBackPressed()` returns `true`), as before.
Making classic back work is an owner decision.

### As built: launcher, effective layout, relaunch

**Launcher.** `LauncherActivity extends android.app.Activity` (not
AppCompat; `Theme.NoDisplay` is a framework theme), no `launchMode`, no
`taskAffinity`, label `@string/title_activity_main`. It holds the three filters
that used to sit on `TabBrowserActivity`, unchanged: `VIEW`+`MAIN` /
`DEFAULT`+`LAUNCHER` (the `VIEW` half has no `<data>`), `MAIN` /
`LEANBACK_LAUNCHER`, and `SEND` / `DEFAULT` with `text/plain`. Phase 3 added a
fourth, `SEND` / `DEFAULT` with `audio/*`, `video/*` and `image/*` (see "Sharing
local files"). `onCreate`
builds a **fresh** `Intent(this, BrowserLauncher.targetClass(this))`, applies
the forward policy, calls `startActivity` and `finish()` in `finally`
(`Theme.NoDisplay` requires `finish()` before `onResume` completes). Any
`RuntimeException` while reading the incoming intent is caught, logged by
class name only, and the launch is plain.

**Forward policy** (`LauncherForwardPolicy.decide(action, type, text)`, plain
Java, compared exactly and case-sensitively). This subsection is the Phase 2
state; Phase 3 adds ONE more forward, a single `content:` file (fresh intent,
one ClipData item, the read flag and a launch token, see "Sharing local files",
"As built: from the share menu to a pending share"). Everything below still
holds for the text forward and the plain launch:

- Forwarded: only `android.intent.action.SEND` with type `text/plain` and a
  non-empty text of at most `ShareUriPolicy.MAX_TEXT_LENGTH` (2048) chars. The
  forward carries action `SEND`, type `text/plain` and `EXTRA_TEXT`, nothing
  else. The text is `getStringExtra(EXTRA_TEXT)`, else the text of the last
  `ClipData` item that has one and is within the cap (a clip item's URI is
  never read).
- Everything else (`MAIN`, `VIEW`, no action, unknown actions, another type,
  empty or over-long text) becomes a plain launch. A text over the cap is
  dropped, not truncated.
- Never copied: flags (so no `FLAG_GRANT_*`), `data`, `ClipData`, URIs,
  `EXTRA_STREAM`, `EXTRA_INTENT`, other extras, categories, selector. The
  incoming intent is not modified. Origin is erased: no browser reads
  `getReferrer()` or `getCallingPackage()`.

**Effective layout.** `BrowserLauncher.effectiveLayout(Context)` is the only
reader of the preference (see "Layout preference"). It returns `CLASSIC` when
`NEW_LAYOUT_AVAILABLE` is `false`, else `BrowserLayoutChoice.resolve(stored)`.
`NEW_LAYOUT_AVAILABLE` is `true` since Group 8; keep the switch so a build
without a usable new layout cannot strand a stored `"new"` (backup restore,
adb) on a layout that does not exist. `targetClass(Context)` and
`targetClass(Layout)` are a two-way `if` on the enum. The launcher, the three
internal notification launches (`Yaacc.createYaaccGroupNotification`,
`PlayerService.createSessionActivity`, `PlayerService.updateServiceNotification`:
explicit, no extras, `FLAG_IMMUTABLE`) and the resume check all go through it.

**Resume check.** `BrowserLauncher.relaunchIfOtherLayout(activity, own)`
computes the effective layout once, asks `BrowserLayoutSwitch.shouldRelaunch(own,
effective)` and, if the layouts differ, asks `RelaunchGuard.shared().tryRelaunch()`
(one relaunch per 2000 ms, process-wide, monotonic clock; when it denies, the
activity stays and logs). Then it starts `new Intent(activity, targetClass(effective))`
(nothing from `getIntent()`), calls `overridePendingTransition(0, 0)` and
`finish()`; it returns `true` so the caller skips the rest of its callback. No
`CLEAR_TASK`: `finish()` only, so players and the other activities stay.
`TabBrowserActivity` calls it at the top of `onResume`; `BrowserActivity`
calls it first in `onCreate` (before `setContentView`, the share handler or any
listener) and again in `onResume`. Consequence of the order (Phase 2): a share that reached a
`TabBrowserActivity` whose layout was no longer the effective one was handled by
its `onCreate` handler. Since Phase 3 `ShareIntakeGate` makes both activities
discard the payload unread when they are not the effective layout, so such a
share is dropped, not played in the wrong branch. The launcher normally starts
the right class, so this only affects stale explicit launches. A switch does not touch players, `PlayerService` or the UPnP
client (they hang off the `Application`); the idle counter goes 1, 2, 1 and
never arms.

### As built: back, toolbar, settings round trip (new layout)

- **Back.** `BrowserActivity` registers one `OnBackPressedCallback` through
  `getOnBackPressedDispatcher().addCallback(this, callback)`; it does not override
  `onBackPressed()`. `BrowserBackNavigation.decide(onServerList, contentAtRoot)`:
  in a content folder below the root `ContentListFragment#onBackPressed()` pops
  one folder; at the root of a server `showServers()`; on the server list
  `Yaacc.exit()` then `finish()` (as `ServerListFragment#onBackPressed` does in
  classic). `ContentListFragment#isAtRoot()` is the null-safe read-only root
  test (`onBackPressed()` always returns `true`, so it cannot tell). The in-screen
  back arrow is also shown at a server's root when `showsBackAtContentRoot()` is
  true (content description `browser_back`) and calls `showServers()` there.
  Classic is unchanged: its `onBackPressed()` override still indexes
  `getFragments()` by tab position.
- **Toolbar** (`res/menu/activity_browser.xml`; ids shared with
  `activity_main.xml` for settings, log, about and exit so
  `BrowserActivitySupport.handleCommonMenuItem` serves both): checkable
  shutdown-timer item (`ifRoom`; while a timer runs it is checked, always shown
  and its title is the remaining time; it is the only entry of the timer),
  settings, log, about, and an always-visible exit item. The item is updated in place (the timer ticks every
  second), not through `invalidateOptionsMenu()`. The controller lives in the
  activity (attach in `onStart`, detach in `onStop`) so the item keeps updating
  on every level.
- **Timer tap** (`ShutdownTimerToggle.decide(running)`): running: `STOP`;
  otherwise `ASK_DURATION`, which opens the picker with
  `ShutdownTimerController.showPicker(fm, title, true)`. Confirming stores the
  chosen duration and, only if it is above 0, starts the timer; a duration of 0
  is stored but never starts a timer (starting with 0 ms would call
  `Yaacc.exit()` at once). A tap never starts a timer from the stored duration
  unseen. `setConfiguredMillis` still restarts a running timer with the new
  value. The former overflow entry and its string were removed in Group 11
  (see "Owner feedback (Group 11)" below).
- **Settings round trip.** `SettingsActivity.onPause()` sets a main-thread
  static `leftSettings`; `SettingsActivity.consumeLeftSettings()` reads and
  resets it. Both browsers call `LocalServerLifecycle.apply(this,
  SettingsActivity.consumeLeftSettings())` from `onResume` (after the layout
  check): with the local server preference on, the service is stopped first
  when the user visited settings, then started; with it off, stopped. Settings
  stays reachable from the new toolbar (overflow), so the layout can always be
  switched back there.

### As built: server list (`ServerListFragment` reuse)

There is no `ServerRootFragment`. `ServerListFragment` serves both layouts;
`onCreateView` inflates `fragment_server_root.xml` when its activity is a
`BrowserHost` with `providesShutdownTimer()`, else `fragment_server_list.xml`.
Both layouts use the same ids (`serverList`, `serverListRefreshButton`,
`serverListLocalServerEnabled`, `serverListLocalServerSettings`, status icons).

`fragment_server_root.xml` (top level of the new layout): a "Servers" header
(`serverRootHeader`, marked as an accessibility heading) with the refresh
button, the `serverList` (`BrowseDeviceAdapter`, shared with classic), and a
footer with the server switch, the gear (opens
`YaaccUpnpServerControlActivity`) and the provider/renderer/proxy status icons
(content descriptions from the `settings_local_server_*` strings). The footer
exists only at this level. The live-stream buttons (audio, video) are on the
yaacc row of `browse_device_item.xml`, as in classic. There are no timer rows;
the timer lives in the toolbar. Server names and icons come from the LAN:
`setText` and `IconDownloadTask` only. The switch writes only the
`settings_local_server_chkbx` preference; the service is started or stopped by
`LocalServerLifecycle.apply` on the next `onResume`, as in classic.

`ServerListFragment` and `ContentListFragment` register as `UpnpClientListener`
in `init` and remove themselves in `onDestroyView`;
`ContentListFragment` also cancels its load tasks there. For this
`UpnpClient#removeUpnpClientListener` was added and its listener list is a
`CopyOnWriteArrayList`. `PlayerListFragment` and `ReceiverListFragment`
(classic only) still never remove their listener.

### As built: shutdown timer and streaming permission

- **Timer.** The timer is in `Yaacc` (`startShutdownTimer`,
  `stopShutdownTimer`, `isShutdownTimerRunning`,
  `getShutdownTimerRemainingMillis`). Listeners (`ShutdownTimerListener#onTick`)
  are a `CopyOnWriteArrayList` with `addShutdownTimerListener` (`addIfAbsent`)
  and `removeShutdownTimerListener`; ticks and listener calls run on the main
  thread (`startShutdownTimer` re-posts itself there), and a failing listener is
  logged and skipped. The old single-slot listener field is gone, so the
  application no longer holds a destroyed fragment. `ShutdownTimerController`
  (plain class owned by the UI; `attach(Display)` in `onStart`, `detach()` in
  `onStop`) keeps the configured duration (a `long`, key
  `settings_shutdown_timer`), starts/stops the timer, shows the
  `MaterialTimePicker` (24 h, keyboard input, built from millis instead of from a
  formatted string) and publishes the state on attach. Because the state comes from
  `Yaacc`, the classic Server tab shows the running timer's state whenever the
  view is recreated (rotation, tab re-creation, layout switch); the old code
  restored only the remaining-time text and held the destroyed fragment as the
  single listener (a leak).
- **MediaProjection.** `StreamingPermissionHelper` serves every fragment that
  hosts `BrowseDeviceAdapter`: `createRequestIntent()` (null before Android 10)
  sets a static `requestPending`; the fragment starts it with
  `startActivityForResult(..., REQUEST_CODE_MEDIA_PROJECTION)` (2001) and passes
  `onActivityResult` to `onPermissionResult(requestCode, resultCode, data)`,
  which ignores a wrong request code or a result without a pending request.
  The permission `Intent` goes straight to `MediaProjectionHelper` and stays in
  its `volatile` static fields: never in a Bundle, preferences or another
  Intent, and its extras are not logged. `MediaProjectionHelper` keeps a
  `CopyOnWriteArrayList` of stop callbacks (`addStopCallback` /
  `removeStopCallback`), invoked on the main thread; the helper installs one
  process-wide state reset (preferences and `BrowseDeviceAdapter` flags set to
  `false`) and each screen adds its own refresh callback in `onStart` and
  removes exactly that callback in `onStop`. `BrowserActivity` does not
  override `onActivityResult`, so only the fragment that asked can feed a
  result. The `pending*` and streaming flags stay in `BrowseDeviceAdapter`; a
  result arriving after process death is ignored (it enabled nothing before
  either).

### As built: share handler

`ShareIntentHandler(Context appContext, UpnpClient)` replaces
`TabBrowserActivity#checkIfReceivedShareIntent`. `handle(Intent, Runnable
onPlayersStarted)` and `shutdown()` are main-thread. Both browsers call
`handle(getIntent(), ...)` in `onCreate` and `handle(intent, ...)` in
`onNewIntent`, and `shutdown()` in `onDestroy`.

This subsection is the Phase 2 state of the TEXT path. Phase 3 added a second overload,
`handle(Intent, Runnable, BrowserHost pickHost)`, which both browsers call through `ShareIntakeGate`, and the file path with
the pending share and the picker; see "Sharing local files" ("As built: from the share menu to a pending share", "As built: links").
In short: in the new layout (`pickHost.providesReceiverBar()`) a link is not played at once, it goes through the "Play on..."
picker and never falls back to `UpnpClient.LOCAL_UID`; classic keeps the behavior described below; a file is read from ClipData
item 0 (used only after the launch-token check). Where the text below differs, the Phase 3 section wins.

- Only `ACTION_SEND`. Text source: `EXTRA_TEXT`, else the text of the last
  `ClipData` item that has text and is at most 2048 chars. For a text share a clip
  item's URI is never used (before Phase 2 it was); a SHARED FILE is the exception
  added in Phase 3: its one `content:` URI is read from ClipData item 0 only.
- The text is removed from the intent (`removeExtra(EXTRA_TEXT)`,
  `setClipData(null)`) so a recreated activity does not replay it. After
  process death the system re-delivers the original intent and the link plays
  again (as in classic before).
- **Link policy.** `ShareUriPolicy.extractUrl(text)`: non-empty text of at most 2048 chars; the first `http://` or `https://`
  (ASCII, case-insensitive) at the start of the text or after white space or an opening bracket or quote starts the link, which
  ends at the first white space or control character and loses trailing punctuation; it needs a host (`hostOf`: letters, digits,
  dot, hyphen, underscore, or an IPv6 literal, optional port, a user name in front is not the host). Nothing after the link is used.
  Reason: only web links are relayed by the proxy, and apps share a link inside a sentence as often as alone. A rejected text is only
  logged, with no payload, and shows the notice `share_not_accepted`. The text is never used to build an intent, PendingIntent or file path.
- **Waits and fallback** (classic and a missing host; in the new layout a link goes through the
  picker instead and does not wait or fall back): 0 s; +3 s when no receiver is ready; +3 s
  and `upnpClient.startService()` when the player service is not initialized.
  The worker then falls back to `UpnpClient.LOCAL_UID` when still no receiver
  is ready. The toasts ("start in N seconds", "no receiver found using local
  device") are hard-coded English.
- **Threads.** One `newSingleThreadScheduledExecutor()` per handler. A main
  `Handler` (application context only) posts the toasts and `play()`. Exceptions
  on the worker are caught and logged by class name only (before Phase 2 a failure on the worker thread was not caught).
- **Shutdown policy.** `shutdown()` sets a flag, clears the callback and calls
  the executor's graceful `shutdown()`: a share that is already waiting still
  plays (a rotation must not drop it), later `handle` calls are ignored. This is a
  deliberate deviation from "cancel pending work". The
  `onPlayersStarted` callback is held in a `volatile` field that `shutdown()`
  clears, so the queue does not pin a destroyed activity and a late share does
  not switch tabs on a dead screen. At most 3 shares may be queued per handler;
  more are ignored (logged without payload). A share waiting when the process
  dies is lost.
- No timeout on `MediaMetadataRetriever#setDataSource` inside
  `createPlayableItem`: a hostile host can hold the single worker thread (and the
  three slots) until its handler is gone. Follow-up.

### As built: idle timer

`BackgroundIdleTimer` (process-wide, application context only) replaces the
per-instance handler in `TabBrowserActivity`. Both browsers call
`browserStarted(this)` in `onStart` and `browserStopped(this)` in `onStop`.
`StartedBrowserCounter` (plain Java, JVM-tested) counts started browsers and
schedules the expiry (5 minutes, main-looper `Handler`) only when the count
drops to 0; a start cancels it; a stop without a start is ignored. When it
fires it re-reads the preferences and the player list and calls `Yaacc.exit()`
only if no player runs and the local server, renderer and proxy options are all
off. A counter is needed because on a layout switch (and on rotation) the old
browser's `onStop` runs after the new browser's `onResume`, so "arm in
`onStop`, cancel in `onResume`" would leave a timer running that quits the app
five minutes later.

### As built: receiver bar and classic footer

**New layout.** `ReceiverBarController(TextView bar, UpnpClient, Runnable
openSheet)` is owned by `BrowserActivity` only. `bind()` tints the compound
drawables, sets the click listener (tap or D-pad select opens the sheet), an
accessibility delegate (click action "Choose receivers") and adds itself as
`UpnpClientListener`; `unbind()` removes all of it. It refreshes through
`receiverDeviceAdded` / `receiverDeviceRemoved` (hopping to the main thread)
and once on every `bind()`; late events after `unbind()` are ignored. The
label is `ReceiverBarController#label` (Phase 3: the cleaned names of the receivers
that have a player, case-insensitive sort, joined with `"; "`, else
`receiver_bar_none_playing`; it no longer shows the selection), set with
`setText`; besides the receiver events the controller listens to the player list. `BrowserActivity` owns one bar below the container
(`activity_browser.xml`: separator, mini-player list, receiver bar in a
vertical `LinearLayout`, so a hidden mini-player cannot move an anchor) and
binds it in `onStart`/unbinds in `onStop` (and `onDestroy` as a backstop).

**Classic layout.** `ContentListFragment` shows the original static two-line
footer of `origin/feat/issue287`: `contentListCurrentProvider` (the provider's
friendly name, icon `ic_baseline_sensors_32`) and `contentListCurrentReceivers`
(the selected receivers' friendly names joined with `"; "`, in the client's
order, icon `ic_baseline_devices_32`), under `contentListBottomSeperator`. They
are not clickable and not focusable; the compound drawables are tinted with
`ThemeHelper.tintDrawable`; the texts are set when the provider is selected
and on receiver add/remove (`showCurrentProvider()` / `showCurrentReceivers()`).
The fragment does not use `ReceiverBarController` or `DeviceSheetFragment` any
more. Which footer shows is decided in `initFooter()` from the host:
`showsStaticFooter = host == null || !host.providesReceiverBar()`. When the host
provides the bar (new layout) the three footer views are set `GONE`, and nothing
visible anchors to them: in portrait the three sit in a never-`GONE`
`contentListBottomBar` container that the list anchors to; the landscape layout
(`layout-land/fragment_content_list.xml`) keeps the original chain (separator
above provider above receivers) that nothing outside the chain anchors to.

### As built: mini-player

`MiniPlayerBar` (controller) drives `mini_player_bar.xml`, a
`MaxHeightRecyclerView` (`R.id.miniPlayerBar`) included above the receiver bar in
`activity_browser.xml`. It shows **every** running player as one row
(`mini_player_row.xml`, a `MaterialCardView`: art, title, receiver name, previous /
play-pause / next / stop). The list is `GONE` while no player exists and is bound
by `BrowserActivity` between `onStart` and `onStop`. It replaced the single
"featured player" bar of Groups 8-10 (Group 11); `MiniPlayerPicker` and its tests
are gone.

- **Order.** `MiniPlayerRows.order(List<PlayerRef>)` reuses the device sheet's
  ordering (`DeviceSessionGrouper.sortedOldestFirst`): creation sequence
  (`UpnpClient#getPlayerCreationSequence`, assigned by `PlayerService` per added
  player) ascending, oldest first, equal sequences by player id; null entries are
  skipped, a duplicate player id keeps only its first entry (stable ids must not
  repeat), the input is not modified. `refresh()` hands it `PlayerRef` copies built
  from an `ArrayList` copy of `getCurrentPlayers()` and catches `RuntimeException`
  (a player mutating under the poll; retried on the next poll).
- **Height cap.** `MaxHeightRecyclerView` caps a `wrap_content` list at
  `mini_player_list_max_height` (180dp, three rows of 60dp including margins) and
  at most 40 % of the height offered to it, whichever is smaller; beyond that the
  list scrolls inside. An `EXACTLY` height is left alone.
- **Rows.** `MiniPlayerAdapter` has stable ids (the player ids) and diffs with
  `DiffUtil`, so a poll that changes nothing rebinds nothing and a changed title,
  play state or art key rebinds only its own row. Item animations are off.
- **Updates.** While bound: a `PlayerListChangeNotifier.Listener` registered
  through `UpnpClient#addPlayerListChangeListener` (main thread) plus a 2 s
  `Handler` poll (`POLL_INTERVAL_MS = 2000`) for title and play state, plus one
  extra refresh 500 ms after a command. `unbind()` removes the listener and
  both callbacks; nothing polls while the activity is stopped. The poll does
  in-memory reads only (title, play state, registry lookup), no network.
- **Text.** Title: the current item's title, else the player name
  (`MiniPlayerPresenter.title`). Receiver: the device name resolved with
  `UpnpClient#getDevice(deviceId)` (also answers `LOCAL_UID` with this device),
  cached per device id once resolved, until `unbind()`, else the player name
  (`receiverLabel`). Both are set with `setText` into one-line, ellipsized
  views (no `autoLink`, no HTML). `MiniPlayerPresenter.cleanText` (these strings
  come from LAN devices): whitespace runs, including line breaks, tabs, U+2028/2029,
  U+0085 and other space separators, become one space; control characters are
  dropped; so are LRM U+200E, RLM U+200F, ALM U+061C, the embeddings and
  overrides U+202A-202E, the isolates U+2066-2069, ZWSP U+200B, WJ U+2060 and BOM
  U+FEFF; the result is trimmed and cut at 200 chars (UTF-16 units) without
  splitting a surrogate pair. ZWJ U+200D and ZWNJ U+200C are **kept** (emoji
  sequences, Persian). **Not stripped:** tag characters U+E0000-E007F (part of
  some flag emoji), variation selectors (FE00-FE0F, E0100-E01EF), soft hyphen
  U+00AD, U+034F, U+180E, invisible operators U+2061-2064, deprecated format
  characters U+206A-206F, U+FFFC, and blank fillers (U+115F, U+1160, U+3164,
  U+FFA0, U+17B4, U+17B5), so a name made only of those can render empty.
  Strong RTL letters can still reorder neighbouring text.
- **Album art (per row).** The art key is `MiniPlayerRows.artKey(playerId,
  artUri)` = `playerId + ":" + artUri`, built from `Player#getAlbumArt()`
  (in-memory) and never from `getIcon()`. `Player#getIcon()` is read **once per
  player and key** (`MiniPlayerArtKeys.isNewKey`, in `MiniPlayerAdapter.bindArt`),
  never on the poll path: `AVTransportPlayer#getIcon()` queues a new download on
  every call while the 512x512 art is not cached, so calling it each poll would
  request a broken or hostile art URL every 2 s. On that first sight the row
  also starts `IconDownloadTask(art, key)`, which drops its result unless
  `art.getTag()` still equals the key. The adapter keeps that one `getIcon()`
  result per player (a `SparseArray`), so a recycled view holder bound again to a
  key already seen shows the stored icon (or the placeholder) and then reads only
  the in-memory `IconDownloadCacheHandler` (48x48): no request, so scrolling
  rows in and out of view or players flipping order floods nothing. A holder
  that was recycled clears its tag, so a late result for the old key drops
  itself. A player that disappears takes its key and icon with it
  (`retainOnly`); `unbind()` clears all of it. While uncached this costs two
  downloads of the same URL per player and key change (the `getIcon()` one and the
  task's).
- **Commands.** Previous, play/pause (`MiniPlayerPresenter.toggleAction(isPlaying)`),
  next and stop run on a single-thread executor created in `bind()` and shut
  down in `unbind()` (commands already queued still run). A command goes to the
  player of the row that was tapped; the play/pause decision follows that
  player's state at the moment of the tap. A tap on the row sends the player's
  own `getNotificationIntent()` through `ReceiverRowBinder.openPlayer` with an
  empty fill-in `Intent` (no extras, data or action).
- **Accessibility / D-pad.** The list itself is not focusable; each row is
  focusable and clickable before its buttons, so the D-pad reaches every row and
  then each of its buttons; each button has a content description (`mini_player_*` strings),
  each row has a click action label (`mini_player_open`), the art is marked
  decorative.

### As built: Layout preference

- `ListPreference` in `res/xml/preference.xml`, first item of the Appearance
  category: key `@string/settings_browser_layout_key`
  (`setting_strings.xml`, non-translatable), title `settings_browser_layout_title`
  ("Layout"), `summary="%s"`, default `new` (a fresh install; see "Look and settings"). Entries
  `settings_browser_layout_classic` ("Classic (tabs)") and
  `settings_browser_layout_new` ("New (single screen)") in 7 locales
  (`values/browser_layout.xml` and `strings.xml`); the stored values
  `classic` / `new` are the non-translatable `browser_layout_values` array.
  `BrowserLayoutValuesTest` pins the array and the default to
  `BrowserLayoutChoice.STORED_CLASSIC` / `STORED_NEW` / `DEFAULT_STORED_VALUE`.
- **One reader.** The key is read only in
  `BrowserLauncher.effectiveLayout(Context)`: `getString(key, null)` (default
  `null`: installs that never opened Settings have no stored value, because
  `Yaacc` calls `PreferenceManager.setDefaultValues(this, R.xml.preference,
  false)`), `RuntimeException` caught (a value of another type is classic), then
  `NEW_LAYOUT_AVAILABLE`, then `BrowserLayoutChoice.resolve`: exactly `"new"`
  gives `NEW`; null, empty, unknown or differently cased gives `CLASSIC`. The
  only writer is the `ListPreference`. The stored string is only compared, never a
  class name, action, extra, path or reflection target. Do not add a second
  reader.
- **Default** is `new` for a fresh install and for an installation updated from a version without the setting (see
  "Look and settings: theme, gray scale, log viewer"), Android TV included.
- **Switch.** There is no relaunch in Settings. The change applies when the user
  returns to a browser screen: the resume check ("Launcher, effective layout,
  relaunch") sees the mismatch and runs `relaunchIfOtherLayout`.
  `SettingsActivity` has no `android:exported` and no filter (not exported, and it
  reads no intent).

### As built: manifest

Re-checked 2026-10-24 with `./gradlew :yaacc:processDebugManifest --offline`
(`yaacc/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`).

- `LauncherActivity`: exported, `Theme.NoDisplay`, the three filters above (plus the Phase 3 media `SEND` filter, see "Sharing local files"). The
  only `MAIN`+`LAUNCHER` and `MAIN`+`LEANBACK_LAUNCHER` component (read the
  research check "exactly one exported launcher" as `MAIN`+`LAUNCHER`: four other
  activities declare `LAUNCHER` with `VIEW` only).
- `TabBrowserActivity`: `exported="true"`, **no** intent filters, no
  `launchMode`/`taskAffinity`. Kept exported so a home-screen shortcut that stored
  the old component still works; its resume check redirects when the setting says
  `new`.
- `BrowserActivity`: `exported="false"`, no filters, no `launchMode`/`taskAffinity`.
- `SettingsActivity`, `YaaccUpnpServerService`, `ImageViewerBroadcastReceiver`:
  no `exported` attribute and no filter, so not exported.
- Exported components in the merged manifest (12): `LauncherActivity`,
  `TabBrowserActivity`, `ImageViewerActivity`, `MusicPlayerActivity`,
  `AVTransportPlayerActivity`, `MultiContentPlayerActivity`, `AboutActivity`,
  `YaaccUpnpServerControlActivity`, `YaaccLogActivity`, `ServerAutostart`
  (`BOOT_COMPLETED`), and from libraries `androidx.media3.session.BluetoothValidationActivity`
  and `androidx.profileinstaller.ProfileInstallReceiver`. 21 `uses-permission`
  entries, as before Phase 2.
- `android:allowBackup="false"` is set on `<application>` (owner decision 2026-10-25;
  before that it was unset, default `true`).
- The source manifest was changed by Group 6 (`LauncherActivity` added, filters
  moved off `TabBrowserActivity`) and Group 7 (`BrowserActivity` added); Groups
  8, 9 and 11 did not touch it. `yaacc/build.gradle` has no Phase 2 change and no
  dependency was added.

### Known limitations / owner decisions

- **`allowBackup` (decided: off).** Auto Backup used to copy the default
  preferences file (layout, receiver selection, stored shared-link entries (retired in Phase 3), SAF cache entries)
  to the user's Google account. The owner chose `android:allowBackup="false"`
  (2026-10-25): users set the app up again on a new device. No backup or
  `dataExtractionRules` exclusion rules are needed then.
- **Stale notification PendingIntents.** The three internal PendingIntents keep
  the class chosen when the notification was posted. After a layout switch a tap
  starts the old class, which redirects at once, and may leave a second browser
  instance on the stack. A possible fix is `FLAG_ACTIVITY_REORDER_TO_FRONT` on the
  relaunch intent (fresh intent, no forwarded data) or rebuilding the notification
  on a layout change. To be observed in the manual checks.
- **Android TV.** D-pad reach of the new layout is not verified. The setting is
  visible on TV and a fresh install starts in the new layout like on a phone; the new toolbar, receiver bar and
  mini-player are focusable by design. Recovery is the same Settings screen.
- **`ImageDownloader` / `IconDownloadTask`** accept any URL scheme the JVM
  handles, with no connect/read timeout and no byte cap, and run on the serial
  AsyncTask executor. The mini-player makes the exposure continuous (the art of
  every running player is fetched once per player and art URI on every browser screen). A scheme allowlist
  (http/https), timeouts and a size cap are follow-ups.
- **Names from the LAN.** The device-sheet and server-list name bindings and the
  classic footer lines show raw friendly names (trim only, as the original footer
  did): bidi controls and a name such as `Kitchen; Bedroom` are not neutralised.
  The mini-player's text and, since Phase 3, the receiver bar's names go through
  `MiniPlayerPresenter.cleanText`; the picker's rows use the cleaned friendly name.
- **Classic does not offer the device sheet** (owner decision, Group 11). The
  Receiver and Player tabs are the only way to control receivers and players in
  the classic layout, and playing with no receiver selected shows the old toast
  (`error_no_receiver_selected`) instead of a prompt. Whether the classic layout
  should ever get the sheet is closed with "no" (owner decision). The one
  exception (Phase 3): a shared file opens the pick-mode sheet in classic too.
- **Share backlog** (still open, waiting for a discussion with the owner; `tasks.md` "Owner's open items"): which
  share (receiving into yaacc, or also the per-item share out of yaacc); the
  classic link auto-play and its blind 3 s waits; a notice when a share is dropped
  for its type; what the new layout shows when a share starts; a cool-down after a
  cancelled pick; re-opening the picker after a rotation; tightening
  `ShareUriPolicy`: done, see "Link policy".
  Done in Phase 3: files and `content:` URIs, audio/video/image `SEND` filters,
  picking the receiver per action, localized feedback for failed shares (see
  "Sharing local files").
- **Other open items.** No timeout around `MediaMetadataRetriever#setDataSource`
  (share worker); `RendererStatusMonitor` polls on one thread (one slow renderer
  delays all status); `PlayerService.getPlayer()` returns a mutable view; remote
  devices claiming `LOCAL_UID` are not dropped at `UpnpClient` ingress;
  `PlayerListFragment` / `ReceiverListFragment` never remove their
  `UpnpClientListener`; `Yaacc.startShutdownTimer(0)` exits at once (the classic
  switch with no configured duration and `setConfiguredMillis(0)` on a running
  timer can still reach it; the new toolbar cannot);
  `AVTransportPlayer#getNotificationIntent` uses `FLAG_CANCEL_CURRENT`, so
  each call cancels the previous PendingIntent with the same identity; the share
  handler also runs on every recreation after process death.
- **Device checks still pending.** The layout, mini-player, timer and footer checks are collected with all other open device checks under
  "Not verified on a device" (in "Sharing local files").

### Owner feedback (Group 11)

After the first on-device check the owner asked for three changes.
They supersede earlier text of this section wherever the two differ.

1. **One shutdown-timer entry (new layout).** The toolbar icon is the only
   entry; the overflow entry "Set shutdown timer" and its string
   (`shutdown_timer_set`, 7 locales) are removed. A tap stops a running timer;
   otherwise it opens the duration picker and confirming a duration above 0
   starts the timer (`ShutdownTimerToggle.decide(running)`, picker with
   `startOnConfirm`). While running, the icon shows the remaining time. Classic's
   Server tab is unchanged.
2. **Classic keeps the original footer, the sheet is new-layout only.** The
   classic Content tab ends in the static provider line and receivers line (not
   clickable) as on `origin/feat/issue287`; the receiver sheet and the tappable
   receiver bar exist only in the new layout, and the "no receiver selected
   opens the sheet" prompt is not active in classic (removed altogether in Phase 3). The open question
   "does classic keep the sheet" is answered "no". See "As built: receiver bar and classic footer".
3. **Mini-player shows all running players.** One compact row per player,
   in the sheet's session order (oldest first), height capped at about three rows
   with an inner scroll, hidden when nothing runs. `MiniPlayerPicker` and its
   "featured session" notion are gone, replaced by `MiniPlayerRows`,
   `MiniPlayerArtKeys`, `MiniPlayerAdapter` and `MaxHeightRecyclerView`. See
   "As built: mini-player".

## Look and settings: theme, gray scale, log viewer

As built for #287 (these were only in the CHANGELOG before). Rationale: "Design decisions and rationale".

- **Layout default.** `res/xml/preference.xml` defaults the Layout list to `new` and `BrowserLayoutChoice.DEFAULT_STORED_VALUE` is `STORED_NEW`,
  so a fresh install starts in the new layout. `BrowserLayoutChoice.resolve` returns `CLASSIC` only for the exact stored value `classic`; `null`, empty and unknown values give `NEW`, and
  `Yaacc.onCreate` runs `setDefaultValues`, which stores `new` for an install updated from a version without the setting (owner decision
  2026-10-29, formerly such installs were pinned to classic). Android TV follows the same rule.
- **Theme setting.** `ListPreference` `settings_theme_key` (stored key `settings_theme_key`) in Appearance, values `system` / `dark` / `light`
  (`theme_values` in `res/values/theme_choice.xml`, labels `settings_theme_system|dark|light`). `ThemeChoice.nightMode` maps them to
  `AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM|YES|NO`; null and unknown values give the mode of `DEFAULT_STORED_VALUE`, which is `dark` (the
  check box was on by default). `Yaacc.onCreate` first runs `ThemeChoice.migrateLegacyDarkMode(preferences, settings_dark_mode_key,
  settings_theme_key)` once: when the new key is absent and the old boolean exists, it stores `dark` for true and `system` for false and removes
  the old key; then it applies the mode. `SettingsFragment` applies a change at once.
- **Gray scale (Material 3).** `AppTheme` (`res/values/styles.xml`) has the parent `Theme.Material3.DayNight` (Material Components 2 and its
  `Bridge` theme are gone) and keeps the support action bar. Every color role the Material 3 components read is set to a gray in
  `res/values/colors.xml` (`gray_<role>`, light) with the dark values in `res/values-night/colors.xml`; `GrayscaleResourcesTest` pins that
  every color is a gray, that both files define the same names, that the theme sets every role, that text reaches 4.5:1 on its background
  in both schemes, and that the launcher icon is the only colored asset. The error roles are not set: the library's red only shows for an
  error of a text field, which the app never raises. `colorSurfaceTint` does not exist in `material:1.14.0` (no tint to switch off);
  elevation overlays are off in the Material 3 themes. The library points `android:textColorPrimary|Secondary|Tertiary|Hint|Highlight`
  (and the inverse variants) at its own tinted baseline colors, so `AppTheme` replaces all of them (`res/color/gray_text_primary.xml`,
  `gray_text_secondary.xml`, `gray_text_highlight`); `textColorSecondary` also feeds `colorControlNormal`, the default icon tint.

  | Role | Light | Dark |
  |---|---|---|
  | `background`, `surface`, `surfaceDim` | `#F6F6F6` (`surfaceDim` `#DADADA`) | `#000000` (pure black page) |
  | `surfaceContainerLowest` / `Low` / `(Container)` / `High` / `Highest` | `#FFFFFF` / `#F1F1F1` / `#EEEEEE` / `#EAEAEA` / `#E2E2E2` | `#000000` / `#0F0F0F` / `#161616` / `#1E1E1E` / `#262626` |
  | `surfaceBright`, `surfaceVariant` | `#FFFFFF`, `#E2E2E2` | `#2C2C2C`, `#262626` |
  | `onSurface`, `onBackground` (primary text) | `#1B1B1B` | `#E3E3E3` |
  | `onSurfaceVariant` (secondary text, default icons) | `#5F5F5F` | `#A8A8A8` |
  | `outline`, `outlineVariant` (hairline dividers) | `#777777`, `#D9D9D9` | `#6F6F6F`, `#2A2A2A` |
  | `primary` on `onPrimary` | `#3A3A3A` on `#FFFFFF` | `#D6D6D6` on `#1B1B1B` |
  | `primaryContainer` on `onPrimaryContainer` | `#DADADA` on `#1B1B1B` | `#D6D6D6` on `#1B1B1B` |
  | `secondary` on `onSecondary` | `#5F5F5F` on `#FFFFFF` | `#BDBDBD` on `#1B1B1B` |
  | `secondaryContainer` on `onSecondaryContainer` | `#E4E4E4` on `#1B1B1B` | `#3A3A3A` on `#E3E3E3` |
  | `tertiary` on `onTertiary`, `tertiaryContainer` on `onTertiaryContainer` | `#6B6B6B` on `#FFFFFF`, `#D0D0D0` on `#1B1B1B` | `#C4C4C4` on `#1B1B1B`, `#4A4A4A` on `#E3E3E3` |
  | `inverseSurface`, `inverseOnSurface`, `inversePrimary` | `#303030`, `#F1F1F1`, `#BDBDBD` | `#E3E3E3`, `#2F2F2F`, `#3A3A3A` |

  Title bar and system bars: the title bar (`Widget.App.ActionBar`, parent `Widget.Material3.Light.ActionBar.Solid`) is flat (no elevation)
  and takes `?attr/colorSurface`, which is the page color in both themes (light gray in the light theme, black in the dark one); title,
  menu icons and the overflow button use `colorOnSurface` through `ThemeOverlay.App.ActionBar` (the former dark `actionBarTheme` overlay is
  gone). `android:statusBarColor` is the surface color and `android:navigationBarColor` the page color; `android:windowLightStatusBar` and
  `...LightNavigationBar` read the bool `gray_light_system_bars` (`true` in `values`, `false` in `values-night`). `targetSdk` is 33, so the
  system does not force edge-to-edge and these colors are honoured; raising it to 35 makes the bars transparent and the title bar would
  need inset handling. `actionBarSize` is the `app_bar_height` dimen (56dp, 48dp in landscape, the AppCompat height) instead of the 64dp
  of Material 3. The white vector icons of the menus (`activity_image_viewer`, `activity_yaacc_log`) are tinted with `app:iconTint`.
  The settings section headlines use `PreferenceThemeOverlay.App` (bold, `colorOnSurface`). The keyboard-move highlight in the playlist uses
  `gray_highlight`, the device state badge `colorSecondary`/`colorOnSecondary`, the selected row of the server folder tree
  `colorSecondaryContainer`/`colorOnSecondaryContainer`, and the pick sheet's hint the primary text color instead of the error color.
  `ThemeHelper.tintDrawable` tints with `colorOnSurface` (falls back to the platform foreground color when a theme lacks it).
- **Shapes and components.** Cards are `MaterialCardView` with `Widget.App.CardView` (parent `Widget.Material3.CardView.Filled`: filled with
  `colorSurfaceContainerHigh`, because the library default is the highest container; no elevation, no outline, no state animator):
  `.Content` (20dp corners) for the card grid, `.List` (16dp) for the mini-player row, receiver, player, server and playlist cards. The list
  row `browse_content_item.xml` stays a transparent flat row. 1dp separators use `colorOutlineVariant`; texts use `colorOnSurface`
  (secondary: `colorOnSurfaceVariant`); icon tints stay `colorControlNormal`. Bottom sheets (`ThemeOverlay.App.DeviceSheet`, parent
  `ThemeOverlay.Material3.BottomSheetDialog`), dialogs and the time picker (`ThemeOverlay.App.TimePicker`, parent
  `ThemeOverlay.Material3.MaterialTimePicker`) take their Material 3 look from the theme. The image viewer and the players keep their own
  black backgrounds.
- **Card view and landscape.** `ViewMode.DEFAULT` is `CARD`; the landscape content layout has the `contentListViewModeToggle` button and
  `ContentListFragment` applies the grid for the stored mode in either orientation (see "Browse card-grid view: decisions and as shipped").
- **Server list button (new layout).** `contentListServersButton` in the content header (portrait and landscape layouts; 44dp wide in landscape)
  uses the icon of the Server tab, is visible only when `showsBackAtContentRoot()` (the new layout) and calls `BrowserHost#showServers()` from any
  folder depth. The arrow still goes back one step. Before any content is loaded the path label already clears the back button
  (`applyFolderNameMargin`).
- **Mini-player exit button.** Each row has previous, play/pause, next, stop and exit (40dp wide so five fit). Exit runs `player.exit()` on the main
  thread like the exit button of the player screen and the row disappears with the player-list notification; stop only stops the playback.
- **Shutdown time as toolbar subtitle.** While a timer runs, `BrowserActivity#applyShutdownTimerState` sets the toolbar subtitle to
  `ShutdownTimerToggle.subtitle(name, remaining, running)` ("Shutdown timer: HH:MM:SS", null clears it), because a toolbar drops the text of an
  action item with an icon on narrow screens; the item itself stays checked and always shown.
- **Pick sheet buttons.** OK is a plain bold text button like Cancel, and the pick rows and buttons use theme text colors so they are readable on
  the dark sheet.
- **Log viewer.** `YaaccLogActivity` reads the log on a single background thread and refreshes every 2 s while open (`REFRESH_INTERVAL_MS`), and scrolls
  down by itself only while the user is at the end. "Clear" (menu `menu_clear_log`) stores the current time under the preference `yaacc_log_cleared_at`
  and also tries `logcat -c`; the marker is what makes it reliable. `LogCommand.build(level, clearedAtMillis, zone)` returns `logcat -d *:<level>`,
  or `logcat -d -T "MM-dd HH:mm:ss.SSS" *:<level>` after a clear (device time zone, `Locale.US`); the level must be one of `V D I W E F`, anything else
  falls back to `E`.

## Research notes (Group 5): platform facts behind the layouts

Written before the Phase 2 code existed and condensed afterwards to what stays true; the plans, option comparisons and
review-driven rewording are in git. "As built" above wins wherever it differs. Marks: **[code]** read in this repo (class names,
not line numbers, which have moved); **[javap]** `javap -public` on `activity-1.8.0.aar` from
`~/.gradle/caches/modules-2/files-2.1/androidx.activity/activity/1.8.0/`; **[docs]** official documentation (URL given);
**[aosp]** AOSP `android-14.0.0_r1` from `raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/`
(newer releases may differ); **[reasoned]** inferred, not run on a device.

### (a) Host coupling in the classic activity
- `Activity.getParent()` "returns the parent Activity if this is a child Activity of an ActivityGroup" and is deprecated since
  API 35 [docs: `https://developer.android.com/reference/android/app/Activity#getParent()`]. `TabBrowserActivity` is a plain
  `AppCompatActivity` and its fragments live in its own `FragmentManager`, so `requireActivity().getParent()` is `null` [reasoned].
  Four `getParent() instanceof TabBrowserActivity` branches therefore never ran: `PlayerListFragment` and `ReceiverListFragment`
  back, `ContentListFragment` back at the folder root, and the `ServerListFragment.deviceAdded` auto-jump to the Content tab. Their
  handlers still return `true`, so in classic back is swallowed on the Content root, Receiver and Player tabs; only the folder pop
  and the Server-tab exit work. They were removed, not wired (wiring would change classic).
- Live sites: `ServerListClickListener` (server row click, `parent` is the activity handed to `BrowseDeviceAdapter`) and the share
  handler after `player.play()`; both go through `BrowserHost` now.
- Tab order is `SERVER(0)`, `CONTENT(1)`, `RECEIVER(2)`, `PLAYER(3)` (`BrowserTabs`); `TabBrowserFragmentStateAdapter` creates one
  fragment per tab and classic `onBackPressed` indexes `getFragments()` by the selected tab position.
- Non-fragment references that only build an explicit `Intent` to the class: `Yaacc.createYaaccGroupNotification`,
  `PlayerService.createSessionActivity` and `PlayerService.updateServiceNotification` (all `FLAG_IMMUTABLE`, no extras);
  `UpnpClient` uses the class name as a log tag. No code finds "the current browser": there are no static `Activity`/`Fragment`
  references; fragments use `getActivity()`, adapters the `Context` they were built with; `UpnpClient` and `PlayerService` hang off
  the `Application`. `Yaacc.exit()` finishes every app task and ends with `Runtime.getRuntime().exit(0)`, so it works whichever
  browser is on top.

### (b) Back handling
- Classic: `TabBrowserActivity.onBackPressed()` asks the fragment of the selected tab (`OnBackPressedListener`, "return true if
  your processing has priority"), else `super`. Server tab: `Yaacc.exit()` + `finish()` (back quits the process). Content: cancels
  loads; below the root it pops one `Navigator` position and reloads, at the root it only refreshes the header; always `true`.
  Receiver and Player: `true`, i.e. nothing.
- API for the new activity [javap, activity 1.8.0; docs `https://developer.android.com/reference/androidx/activity/OnBackPressedDispatcher`]:
  `ComponentActivity.getOnBackPressedDispatcher()` is `public final`; `addCallback(LifecycleOwner, OnBackPressedCallback)` follows the
  owner's lifecycle and is removed on `DESTROYED`. Do not override `onBackPressed()`: from API 33 back moves to an ahead-of-time model
  and it "should not be used" [docs: `https://developer.android.com/reference/android/app/Activity#onBackPressed()`]. The classic
  override still works because `targetSdkVersion` is 33 and the manifest has no `android:enableOnBackInvokedCallback`.

### (c) The Settings round trip, the idle timer and the relaunch
- `SettingsActivity` is opened with a plain `startActivity` from four places (the browser, `ImageViewerActivity`,
  `AVTransportPlayerActivity`, `YaaccUpnpServerControlActivity`), never for a result. Before Phase 2 it set a static
  `TabBrowserActivity.leftSettings` in `onPause()`; the browser's `onResume` then stopped and restarted `YaaccUpnpServerService` when
  the "local server" preference was on (so changed server settings took effect), else just started it, and stopped it when the
  preference was off. Now `SettingsActivity.consumeLeftSettings()` + `LocalServerLifecycle.apply`.
- Latent bug the relaunch made visible: the idle timer was a per-instance `Handler`; `onStop()` posted the 5-minute exit and
  `onResume()` cancelled only that instance's callbacks, so after a rotation or relaunch the old instance still called `Yaacc.exit()`
  five minutes later [reasoned]. A plain cancel-on-resume is not enough either: the old browser's `onStop` can arm the timer after the
  new browser's `onResume` cancelled it, hence one process-wide timer that counts started browsers.
- Why the layout is checked in `onResume`: Settings always sits on top of a browser or player screen, so `onResume` is a guaranteed point
  after the user is done (no result code, no listener); it works for all four entry points and also corrects stale launches (a
  notification `PendingIntent` posted before the switch starts the "wrong" class, which redirects itself). Rejected:
  `OnSharedPreferenceChangeListener` (fires while Settings is on top and the browser is stopped; registry holds listeners weakly),
  `ActivityResult` (Settings is never started for a result; default `RESULT_CANCELED` on back), `recreate()` (cannot switch classes).
  Cost: one frame of the old UI, hidden by `overridePendingTransition(0, 0)`.
- `PreferenceManager.setDefaultValues(this, R.xml.preference, false)` with `readAgain=false` "sets the default values only if this method
  has never been called in the past" [docs: `https://developer.android.com/reference/androidx/preference/PreferenceManager#setDefaultValues(android.content.Context,int,boolean)`].
  An update therefore does not get the default of a preference added later: `getString(key, null)` returns `null` for it. Always read
  the Layout preference with default `null`; `resolve(null)` is the new layout.
- Without the effective-layout comparison and the guard, a stored `"new"` while the new layout is unavailable (backup restore, adb,
  downgrade) would bounce between the activities forever.

### (d) Shutdown timer and MediaProjection in `ServerListFragment`
- The timer lives in `Yaacc` (`CountDownTimer`, ticks every second, `exit()` at the end); it did not know whether it ran or what was
  left, and kept ONE listener (the fragment, never cleared: a fragment and activity leak after rotation). The configured duration is a
  `long` under `settings_shutdown_timer`; the old UI parsed `HH:MM` back out of a formatted string. As built: `ShutdownTimerController`,
  `Yaacc#isShutdownTimerRunning()`, `getShutdownTimerRemainingMillis()` and a listener list.
- MediaProjection: the fragment registered a STATIC stop callback (a second instance replaced the first, nothing unregistered it);
  `BrowseDeviceAdapter` asks the fragment (stream-permission callback) which calls `startActivityForResult(..., 2001)`; streaming state
  is process-wide (`static isAudioStreaming`/`isVideoStreaming`, preferences reset to `false` at every process start). The result is
  delivered to the fragment that started it, so nothing had to move to the activity (`StreamingPermissionHelper` serves every fragment
  hosting the adapter). `registerForActivityResult` exists in fragment 1.5.4 / activity 1.8.0 [javap]; the old API still works.

### (e) The share handler before Phase 2 (`checkIfReceivedShareIntent`)
- Only `ACTION_SEND`; text from `EXTRA_TEXT`, else the LAST `ClipData` item with text or a URI; it removed the extra and cleared the
  clip data (mutating the intent) so a recreated activity does not replay it. Delays: 0 s, +3 s when no receiver is ready, +3 s and
  `startService()` when the player service is not initialized; if still no receiver, a toast and `setReceiverDeviceIds({LOCAL_UID})`;
  then `createPlayableItem(uri)` (`IOException` not caught on the worker), `initializePlayersWithPlayableItems`, `play()`, switch to the
  Player tab. A new single-thread executor per call was never shut down. `onNewIntent` is effectively unreachable in classic:
  `TabBrowserActivity` has no `launchMode`, so a second `ACTION_SEND` starts a new instance [aosp: `ActivityStarter.complyActivityFlags`,
  reasoned]; both browsers still pass `onNewIntent` to the handler for an explicit `SINGLE_TOP` start.

### (f) A `Theme.NoDisplay` trampoline that forwards `ACTION_SEND`
- `Theme.NoDisplay` is the "default theme for activities that don't actually display a UI; that is, they finish themselves before being
  resumed" [docs: `https://developer.android.com/reference/android/R.style#Theme_NoDisplay`]. An invisible activity that has not called
  `finish()` before `onResume()` completes logs a warning and, for `targetSdkVersion` > 22 (this app: 33), throws `IllegalStateException`
  [aosp: `Activity.performResume`]. So `finish()` goes in a `finally` around `startActivity`. It must extend `android.app.Activity`
  (a framework theme, not AppCompat).
- `CATEGORY_LEANBACK_LAUNCHER` is a plain category on a `MAIN` filter (API 21) and works on a `NoDisplay` activity like `LAUNCHER`
  [docs: `https://developer.android.com/reference/android/content/Intent#CATEGORY_LEANBACK_LAUNCHER`]; the launcher shows the launcher
  component's label (`@string/title_activity_main`), icon and banner fall back to `<application>`; the manifest has no leanback
  `uses-feature`.
- `android:exported`: with intent filters `true` is required for other apps to start the activity; explicit intents ignore filters, so
  the browsers need no filters once the trampoline holds them [docs: `https://developer.android.com/guide/topics/manifest/activity-element#exported`].
  `launchMode`/`taskAffinity` default to `standard` / one affinity per app [docs: same page, `#lmode`, `#aff`]; none is set, so
  trampoline, target and notification launches share a task.
- URI grants [docs]: `FLAG_GRANT_READ_URI_PERMISSION` grants read on the intent's data and any ClipData URIs and "only the grant flags of
  the top-level Intent are used" (`https://developer.android.com/reference/android/content/Intent#FLAG_GRANT_READ_URI_PERMISSION`);
  `setClipData`: the flags "will operate on any URI items included in the clip data"; for `ACTION_SEND` without ClipData, `EXTRA_STREAM`
  and `EXTRA_TEXT` "will be copied there for you when calling `Context.startActivity(Intent)`"; grants are temporary and "expire
  automatically when the receiving app's task stack is finished" (`https://developer.android.com/training/secure-file-sharing/share-file`);
  persistable grants need `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`, which a share does not carry.
- Forwarding a grant [aosp `android-14.0.0_r1`, not stated in the public docs]: (1) nothing strips `FLAG_GRANT_*` on the intent the receiver
  sees; (2) on `startActivity`, `ActivityStarter` calls `UriGrantsManagerService.checkGrantUriPermissionFromIntent(intent, callingUid, ...)`
  and requires that the caller itself holds the permission by manifest or by a strong enough URI grant, which the trampoline does (the
  system granted it on delivery), so a re-grant to the real activity succeeds if the provider allows granting; (3) the grant is owned by the
  receiving `ActivityRecord`'s `UriPermissionOwner` and removed with the record, the target gets its own entry, so finishing the trampoline
  does not revoke the target's access, but `startActivity` must happen in `onCreate`, before the trampoline is destroyed; (4) same-uid
  source and target does not bypass the check (the shortcut looks at manifest permissions only); (5) `new Intent(Intent o)` copies flags
  and ClipData, so a copy carries the grant exactly like the original - which is why the launcher builds a fresh intent: it is exported,
  and any app can send it any intent (no flags, no `data`, no ClipData URIs, no `EXTRA_STREAM`/`EXTRA_INTENT`, no selector).
- Re-tapping the launcher icon: the task's root intent component stays the trampoline after it finishes, so a launcher start matches the
  task and brings it forward rather than adding an instance [aosp `ActivityStarter.complyActivityFlags`, reasoned; confirm on a device].
  Internal launches that name the browser class directly add an instance on top, as before. Android 12+ notification-trampoline
  restrictions concern broadcast receivers and services started from notifications, not activities starting activities (not researched further).

### (g) Manifest
- The old `TabBrowserActivity` entry had three filters: `VIEW`+`MAIN` / `DEFAULT`+`LAUNCHER` (the `VIEW` half has no `<data>` and matches
  only data-less `VIEW`), `MAIN` / `LEANBACK_LAUNCHER`, and `SEND` / `DEFAULT` with `text/plain`. All three moved to `LauncherActivity`.
- "Exactly one launcher" must be read as exactly one `MAIN`+`LAUNCHER` component: `ImageViewerActivity`, `MusicPlayerActivity`,
  `AVTransportPlayerActivity` and `MultiContentPlayerActivity` declare `LAUNCHER` with `VIEW`+`DEFAULT` only, so they do not appear in launchers.
- Nothing in the repo needs `TabBrowserActivity` by name except the three internal `PendingIntent`s, but a home-screen shortcut created
  before the change may store its component, so it stays exported without filters (non-exported would break such a shortcut) and its
  resume check redirects when the setting says `new`. The repo has no shortcuts XML, no app widgets and no `ComponentName`/`setClassName` built from a
  string; the only explicit-class `PendingIntent`s to the browser are the three internal ones (other notifications point at the player and server-control
  activities). `SettingsActivity` has no `exported` and no filter, so it is not exported.

## Sharing local files

What another app can share into yaacc (a link, or ONE audio, video or image file), how a share is served to the
receivers, and how the receiver is picked per action (Phase 3, Groups 12-16).
The "As built" subsections describe the code at `e3e0a247` on branch `feat/unified-device-sheet` (Groups 12-15 passed
the general and the security review); they cite classes, not line numbers. The platform facts the build started
from follow under "Research notes (Group 12)". Group 17 of the share-code consolidation (Phase 4) renamed and merged some classes
(`SharedFileService` is `ShareService`, which absorbed `ShareProxyUrl` and `ShareFailureMessage`; `ShareRegistrar`,
`DescriptorWindow`, `ReceiverBarLabel` and `ReceiverSelection` live on as `ShareRegistry#registerOrEvict`,
`HeldFileSource#clampLength`, `ReceiverBarController#label` and `UpnpClient`; `de.yaacc.upnp.server.share` is now part of
`de.yaacc.share`); the names in this section are the current ones. The rest of the consolidation (Groups 18-21) is open and
Group 22 updates the names again. Nothing here was run on a device: "Not verified on a device" lists what only a device can confirm.

### As built: overview

| Class | Role |
|---|---|
| `de.yaacc.LauncherActivity` | Exported entry. Forwards a text share or ONE file to the browser of the effective layout with a fresh intent. The SEND filters for `audio/*`, `video/*` and `image/*` are its only manifest change of Phase 3. |
| `de.yaacc.share.LaunchNonces` | Single-use launch token, minted by the launcher after its checks, required for a file at the browsers. |
| `browser.ShareIntentHandler`, `browser.ShareIntakeGate`, `share.StreamIntake`, `share.SharedContentPolicy`, `share.PendingShare` | Intake in the browser: repeats every check, keeps ONE pending share (text only), confirm/cancel by token. |
| `share.ShareService`, `share.SharedFileOpener`, `share.HeldFileSource`, `share.WatchedCopy`, `share.IncomingCache` | After the confirm: open (hold) or copy the file, register it, build the item. |
| `share.ShareRegistry`, `share.UnclaimedShares`, `share.ShareKeyTracker`, `player.ShareLifetimeListener` | The in-memory registry (random keys, allowed addresses, expiry), the claim window and the release when a player ends. |
| `upnp.server.http.YaaccUpnpServerContentHttpHandler`, `ProxyShareResponder` | Serve `/proxy/<key>` before the preference gate. |
| `share.ProxyAccessPolicy`, `share.ShareAddresses`, `share.ShareAddressResolver` | Which remote addresses may fetch a share. |
| `share.ByteRangeResolver`, `share.RangeStreamer`, `share.PositionalSource`, `share.ShareConcurrencyLimiter` | Range maths, streaming from the held file, per-share response cap. |
| `share.LinkTargetPolicy`, `share.HttpLinkTarget`, `share.LinkContentType`, `UpnpClient#createPlayableItem` | The link path (http/https). |
| `share.ProviderCallGate`, `share.BoundedCall`, `share.AbandonableCall`, `share.ProviderKeys` | Bound what a content provider can pin. |
| `browser.ReceiverPickController`, `browser.DeviceSheetFragment` (pick mode), `share.PendingPick`, `share.PickSheetResult`, `share.ActionFlight`, `share.AfterStartSelection`, `share.ReceiverPickResult` | The "Play on..." picker. |
| `browser.ReceiverBarController` | Receiver bar: the receivers that play. |

### As built: from the share menu to a pending share

1. **Manifest.** `LauncherActivity` has four filters: `MAIN`+`VIEW`/`LAUNCHER` (unchanged), `MAIN`/`LEANBACK_LAUNCHER`,
   `SEND`+`DEFAULT` with `text/plain`, and `SEND`+`DEFAULT` with three `<data>` elements `audio/*`, `video/*`,
   `image/*`. `git diff 071fc9d2 -- yaacc/src/main/AndroidManifest.xml` is exactly that one filter (7 lines).
   `TabBrowserActivity` stays exported without a filter, `BrowserActivity` stays `exported="false"`. No `SEND_MULTIPLE`.
2. **Launcher forward** (`LauncherForwardPolicy.decideStream`, `LauncherActivity#buildStreamForward`). The filter only
   looks at the sender's declared type, so the launcher checks: action `SEND`; a stream type (`audio/`, `video/`,
   `image/`); `EXTRA_STREAM` equals ClipData item 0; the incoming flags carry `FLAG_GRANT_READ_URI_PERMISSION`;
   `SharedContentPolicy` accepts the URI (a `content:` URI, not `file:`/`http:`, no own authority, length cap);
   `OwnProviders.isOwnAuthority` finds no provider of this app behind the authority (the app has more than one);
   on Android 15+ it also asks `getInitialCaller().checkContentUriPermission(...)`, for the log only (see below). Then it forwards a FRESH intent: action `SEND`, the declared type, ONE ClipData item
   (`ClipData.newRawUri`), `FLAG_GRANT_READ_URI_PERMISSION` only (the system checks the grant for `data` and
   ClipData, never for `EXTRA_STREAM`, so the URI is not repeated there), and the launch token
   (`LaunchNonces.EXTRA_NAME`). Any failure, or a `SecurityException` from `startActivity`, falls back to a plain launch.
   Text shares are forwarded exactly as before.
3. **Launch token.** The browsers are exported (classic) or reachable by internal intents, so any app can send them a file
   and skip the launcher's checks (including the Android 15 sender check). `LaunchNonces.mint()` returns 128 bits from a
   `SecureRandom`, kept only in the `Yaacc` application object: valid 30 s (`ShareLimits.LAUNCH_NONCE_TTL_MILLIS`), single
   use, at most 8 unused (oldest dropped), constant-time compare, monotonic clock. `ShareIntentHandler` accepts a FILE only if
   `consume(token)` succeeds; otherwise the intent is consumed and the share dropped with a payload-free log, before any
   provider call. Text needs no token. A re-delivery after process death carries a dead token and is dropped.
4. **Layout gate.** `ShareIntakeGate.admitOrDiscard(own, effective, intent)` runs in `onCreate` and `onNewIntent` of both
   browsers before the handler: an activity takes the payload only if it renders the effective layout (the same rule as
   the relaunch check), else the payload (text, clip data, `EXTRA_STREAM`, token) is removed unread. The launcher always
   starts the activity of the effective layout, so a launcher share is admitted in both layouts. A share that races a
   layout switch (about 2 s) is lost.
5. **Intake checks in the handler** (`ShareIntentHandler`, `StreamIntake.validate`). Repeated in full because the activities
   can be started directly: action `SEND`; exactly ONE ClipData item (only item 0 is read, never `EXTRA_STREAM`); no `data`;
   the read flag in the intent's own flags; content policy; not an own provider. The intent is consumed once. On a worker
   thread (`BoundedCall`, 3 s) the provider is asked for its type (`ContentResolver.getType`) and display name; the MIME is
   `SharedContentPolicy.chooseMimeType(providerType, intentType)` output only (concrete `audio/`, `video/`, `image/`,
   else the share is dropped); the name goes through `DisplayName` (control, bidi and other Unicode format characters removed,
   whitespace collapsed, 200 chars, fixed fallback text `share_unnamed_file`).
6. **Pending share** (`PendingShare`). At most ONE, holding only the validated URI text, the cleaned name, the type and the kind
   (`FILE` or `LINK`): no descriptor, no copy, no registry slot. A second share while one is pending or a probe runs is dropped
   (never replaces), with the fixed toast `receiver_pick_busy`. It is bound to a random token and times out after 2 minutes
   (`ShareLimits.PENDING_SHARE_TIMEOUT_MILLIS`). `BrowserHost#onPendingShare(token, name)` tells the activity, which opens the
   picker (`ReceiverPickController#offerShare`).
7. **Provider gate** (`ProviderCallGate`, `BoundedCall`, `ProviderKeys`). A binder call into a provider cannot be
   interrupted. One provider (the package that owns the authority, from `PackageManager.resolveContentProvider`) gets at most
   ONE call in flight, for as long as that call is outstanding (no expiry); at most 3 providers have a call outstanding
   (`PROVIDER_MAX_TRACKED`); each pool (intake probe, confirmed open) has 4 threads (`PROVIDER_POOL_THREADS`). Providers whose
   package cannot be resolved all share ONE key (`ProviderKeys.UNRESOLVED`). At intake a call the gate or pool refuses
   gives the toast `share_busy` (`ShareService#failureTextRes`); a timeout or a failing provider at intake is only logged; after the
   confirm a failure gives `share_open_failed` (or `share_busy` when the gate or the copy slot was full).

### As built: after the confirm

`ShareIntentHandler#confirm(token, ids, afterStarted, finished)` takes the pending share by token and runs `ShareService#prepare`
on the file executor:

1. **Open** (`SharedFileOpener.openDescriptor`, through `BoundedCall`, 20 s): `openAssetFileDescriptor(uri, "r")`. A descriptor
   is "seekable" if `fstat` says regular file and `lseek` works. A seekable one becomes a `HeldFileSource` (below). Otherwise
   the copy fallback runs.
2. **`HeldFileSource`** (`PositionalSource`): the provider's descriptor is `dup`ed and the provider's closed, so the provider
   process is not pinned by a stable reference; the provider's start offset and length are clamped to the real file size
   (`HeldFileSource#clampLength`); reads are `Os.pread` (no shared file offset, so ranges run concurrently, 64 KiB chunks); an in-flight
   counter defers the close to the last reader, so a descriptor number is never reused under a running read, and close never
   blocks the caller.
3. **Copy fallback** (`SharedFileOpener.copyAndHold`, `WatchedCopy`, `BudgetedCopier`, `CopyBudget`, `IncomingCache`): to
   `getCacheDir()/incoming/` (never `cache/shared/`, which `FileProvider` exposes), random file names, at most 512 MiB per file
   (`SharedContentPolicy.MAX_COPY_BYTES`), at most 1 GiB in the directory, 64 MiB of free space must remain. One copy at a time,
   on its own daemon thread, caller waits at most 5 minutes (`COPY_MAX_MILLIS`); at the deadline the caller closes the input and
   output, deletes the partial file and returns, the worker is abandoned (at most 3 abandoned workers at once, then "busy").
   The copy is deleted when its source is closed (`DeleteOnCloseSource`), and `Yaacc` sweeps `incoming/` at start (the registry is
   memory-only, so any file found is an orphan).
4. **Register.** After the local HTTP server answers (`HttpServerProbe`, a loopback connect, polled for up to 3 s) and the phone
   has an address, `ShareRegistry#registerOrEvict` puts a `LOCAL_FILE` entry with the allowed addresses of exactly the chosen
   receivers into the `ShareRegistry`. When the registry is full, `evictOldestReleased()` runs once and `register` is retried;
   still full gives the toast `share_registry_full`. On any failure the source is closed, the copy deleted and the key removed
   before `prepare` returns null.
5. **Item.** `SharedItemFactory.create(mime, cleanedName, proxyUri, size, 0, 0)`: the provider type, size and cleaned name; no
   metadata is read from the shared file, so no duration is announced (the research planned a `MediaDataSource`; it was not built).
   The item is played on the receivers; the selection is cleared (new layout) or restored (classic) afterwards.
6. **Claim window.** `UnclaimedShares.registered` is called after `register`; a key that no player claimed within 2 minutes
   (`CLAIM_WINDOW_MILLIS`) is removed by the sweeper, which closes the descriptor.

### As built: the registry

`ShareRegistry` (plain Java, thread safe, injectable clock and random source):

- Key: 16 bytes from a `SecureRandom`, unpadded base64url, 22 characters of `[A-Za-z0-9_-]`
  (`ShareRegistry.isWellFormedKey`). Never reused while the registry lives (the last 256 retired keys are remembered, 8 draws per
  register). Nothing is persisted.
- Entry: kind (`HTTP_LINK` with an `HttpLinkTarget`, `LOCAL_FILE` with an `AutoCloseable` `PositionalSource`), MIME type, the
  allowed address set (normalized, fixed at registration), grace and absolute maximum.
- Lifetime: 10 minutes after the first `release` (`IDLE_GRACE_MILLIS`), at most 6 hours after registration
  (`ABSOLUTE_MAX_MILLIS`), at most 8 entries. An expired entry is never returned and its target is closed exactly once, outside the
  lock. `Yaacc` runs a sweeper every 60 s (`share-sweeper`: expiry, unclaimed shares, idle responses) and `removeAll()` on exit.
- Release: `ShareLifetimeListener` and `ShareKeyTracker` remember the keys of each player and release them when the player is
  removed (`PlayerListChangeNotifier.Listener#onPlayerRemoving` runs before `onDestroy()` empties the item list); a key is released
  only when no other player still uses it. Cancel, a failed start and a timeout remove or release the share they created.
- The old `proxy_link_*` preferences are retired: `Yaacc#removeLegacyProxyLinks` deletes them at start, nothing reads or writes them.

### As built: serving

`YaaccUpnpServerContentHttpHandler` routes every path whose first segment is exactly `proxy` to `ProxyShareResponder` BEFORE the
handler's preference gate, so a share is served whatever `settings_local_server_chkbx`, the provider or the proxy preference say. The HTTP
server listens whenever the app process lives (`Yaacc.onCreate` starts `YaaccUpnpServerService`, which creates the server
unconditionally); a share starts and stops nothing and waits only until the server answers. Order of decisions:

1. Path `/proxy/<key>` or `/proxy/<deviceId>/<key>` with a well-formed key, a registry entry that is live: else the same constant
   404 page (no path, key or link is echoed, and the responder logs none of them). Other segment counts: 404.
2. `ProxyAccessPolicy.isAllowed(restrictToReceivers, entry addresses, remote address)`; the remote address comes only from the
   connection's endpoint details, never from a header; a missing or unparsable address is denied. Failure: 403 and a log line
   "Media proxy refused a request from <address>" (no key). The flag is read on every request:
   `settings_proxy_restrict_receivers_chkbx` (stored key `proxy_restrict_receivers_chkbx`), default `true` in code, in
   `preference.xml` and when the read fails. With the flag off every address passes this step; key and expiry still apply.
3. `deviceId` (3-segment form), only now: `RendererIdPolicy.isValid` (`[A-Za-z0-9:._-]`, 1-200), else 404.
4. `LOCAL_FILE`: `ByteRangeResolver.parseHeader` + `resolve` against the source size: no header, an invalid header or more than one
   range is 200 whole; one satisfiable range is 206 with `Content-Range`; an unsatisfiable one is 416 with `bytes */<len>`; suffix
   ranges are handled. `HEAD` opens and reads nothing. The body streams from the held file by `Os.pread` through `RangeStreamer`.
5. `HTTP_LINK`: the existing upstream relay (see "As built: links"); an upstream `Range` is rebuilt from the parsed numbers, never
   passed through.
6. Limits: at most 4 concurrent responses per share (`ShareConcurrencyLimiter`, 503 beyond); a response that makes less than
   16 KiB of progress in 60 s loses its slot (not its stream: if the reader resumes it asks for a slot again and aborts only when the
   share is full). Every share response carries `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox`;
   `Content-Type` is the entry's MIME type.

The share code paths do not log the proxy key or the proxy URL, and no share URI, name, key or proxy URL is written at the default log level:
`ShareLogRedactor` (case- and percent-insensitive `/proxy/...`) is applied to the SOAP request, AV-transport, image-load and download
log lines that can carry them. Not redacted: the VERBOSE traces of incoming SOAP/GENA messages (`ReceivingAction`,
`YaaccUpnpServerProtocolRequestHandler`) and the DEBUG album-art URL logs (`ImageDownloader`); someone who turns those levels on and
shares the log can disclose a proxy address. Other URIs are logged at the default level (ERROR) by existing code that is not part
of the share feature: album-art URIs (`AVTransportPlayer`) and local storage (SAF) URIs.

**3-segment form and server-side seeking.** `/proxy/<deviceId>/<key>` IS produced: `AVTransportPlayer#modifyProxyUrlWithDeviceId`
rewrites a proxy URL of this server into it, but only for a remote renderer whose per-renderer preference
`manage_external_seeking_<deviceId>` ("enable server-side seeking for external URLs", `SettingsFragment.MANAGE_EXTERNAL_SEEKING`)
is on. It is served for `HTTP_LINK` entries only (`ServerSideSeeking`, `SeekPositionCalculator`, `RendererStateMap`: at most 8
renderer states per share, least recently used first, dropped with the share). The device id is validated after key and address
and is only used for the preference reads and that bounded map. The research claim that this form is never produced no longer holds.

### As built: links (http and https)

- Intake: text of at most 2048 characters (`ShareUriPolicy#extractUrl`, `http`/`https` only, see "Link policy"). In the new layout (`BrowserHost#providesReceiverBar`) a link is not
  played at once: it becomes the one pending share (kind `LINK`, shown as "Link: <host>" with `ShareUriPolicy#hostOf`, never as the
  whole link; "Shared link" if no host can be shown), the picker opens, and `confirm` stores the
  chosen receivers, builds the item and plays it; a refused link or a start without a player gives the share back
  (`UpnpClient#discardShareOf`) and shows `share_link_failed`. A link never falls back to this device in the new layout. Classic and a
  missing host keep the old behavior: wait up to 3 s (+3 s) for receivers, else play on `UpnpClient.LOCAL_UID`.
- `UpnpClient#createPlayableItem(Uri, Set<String> receiverIds)` (worker thread): reads metadata with `MediaMetadataRetriever`; if
  that fails, the type comes from a bounded `HEAD` (`LinkContentType`, redirects followed by hand, at most 3, every hop checked); only
  if that fails too the link is assumed to be an image, with a payload-free log line. The share code paths do not log the link; the DEBUG album-art logs and VERBOSE protocol traces
  listed under "As built: serving" are not redacted.
- With the media proxy option (`settings_local_server_proxy_chkbx`) ON the link is registered (`HTTP_LINK`, allowed addresses
  from `receiverIds` captured at confirm time through `ShareAddressResolver`, never from the stored selection) and the receiver gets
  `http://<phone ip>:49157/proxy/<key>`. With it OFF the receiver gets the link itself and no registry entry exists. If the phone has
  no address, the server does not answer in 3 s or the registry is full (`share_registry_full`), the direct link is used.
- `LinkTargetPolicy.isAllowedTarget` (plain Java, nothing is resolved) runs only when the proxy option is on: it refuses literal
  loopback, unspecified and link-local addresses (including IPv4 hidden in IPv6 and non-dotted-decimal IPv4 spellings), `localhost`,
  `*.localhost` and every address of the phone itself; private LAN ranges stay allowed (NAS links). Known gaps are listed under
  "Known limitations".

### As built: the receiver picker

- Pick mode is `DeviceSheetFragment#showPicker` (tag `DeviceSheetPicker`; own layouts `fragment_device_sheet_pick.xml`,
  `device_sheet_pick_item.xml`): title "Play on..." (`receiver_pick_title`, accessibility heading), for a share the cleaned name
  below it (`PickTitle`), one `CheckBox` row per receiver including this device, hint `receiver_pick_none_hint`, Cancel and OK.
  The ticks live in the adapter, never in `UpnpClient`'s stored selection (the normal sheet writes through, a cancel could not be
  silent). `filterTouchesWhenObscured` is set on the rows and both buttons. The list refreshes when a device appears or leaves.
- The answer is delivered once through `FragmentManager.setFragmentResult` (`PickSheetResult` gates it): confirm from OK, cancel from
  `onDismiss` (back, outside tap, swipe, Cancel). `PendingPick` (in the activity, via `ReceiverPickController`) holds the one open pick:
  a new request never replaces it; a confirm with receivers runs the action once; a confirm with none keeps the sheet open
  (`ReceiverPickResult.decide`); a cancel runs `onCancelled` once and never hands out the ticks; a stale id changes nothing. The
  pending action lives in the activity: a rotation or process death with the picker open cancels it and releases a pending share.
- `ActionFlight`/`PickedAction`/`GuardedPickedAction`: from the confirm until the action reports `done`, no second picked action may
  begin (the stored selection is global and is read back by `PlayerService.createPlayer`); the picker refuses to open meanwhile
  (toast `receiver_pick_busy`). A flight nobody ends expires after the copy maximum plus 1 minute.
- New layout: `ContentListFragment#startPlayAction` (play, play-all, item tap, add to playlist): confirm sets the chosen ids
  (`UpnpClient#setReceiverDeviceIds`), runs `ContentItemPlayTask`, and `UpnpClient#clearReceiverDevices()` clears the selection in
  `finally`; cancel changes nothing. A share's confirm calls `ShareIntentHandler#confirm` with the ids; its after-start hook applies
  `AfterStartSelection` (new layout clear, classic restore the selection read at confirm time). Add to playlist
  (`UpnpClient#addToPlaylist`, unchanged by Phase 3): if none of the chosen receivers has a player, `initializePlayers` starts a
  new player on each chosen receiver; otherwise the item is added only to the playlists of the chosen receivers that already have
  one, and a chosen receiver without a player gets nothing (open owner question, `tasks.md` "Owner's open items").
- Classic: normal browsing never shows the sheet and `startPlayAction` starts at once; the static footer stays. A pending SHARED FILE
  opens the same picker, with the classic selection as the initial ticks (see "Known limitations").
- Receiver bar: `ReceiverBarController#label` names the receivers that have a player in `PlayerService.getCurrentPlayers()` (names as in the
  mini-player: friendly name, else the player's name, cleaned by `MiniPlayerPresenter.cleanText`, duplicates merged, sorted, joined
  with "; "), else `receiver_bar_none_playing`. It refreshes from the player-list listener and the receiver events.
  `receiver_bar_none_selected` and `ContentListFragment#openDeviceSheetIfNoReceiverSelected()` no longer exist. The normal sheet has
  no checkboxes (`ReceiverRowBinder#bind(holder, device)`), its title is `device_sheet_title` ("Receivers").

### Threat model

Entry: the launcher is exported and accepts `SEND` from every app; `TabBrowserActivity` is exported too. Everything below is a
decision of the reviewed design (see "Design decisions and rationale").

- **Own data.** `file:` URIs and any `content:` URI of a provider of this app (not only the file provider) are rejected at the
  launcher and again at the browser. Only audio, video and image content of other apps is accepted, by the provider's own type.
- **Permission forwarding.** Android honors the read flag only if the forwarding app holds the access; the launcher forwards one
  URI, read flag only, nothing else of the incoming intent, behind the launch token. The grant lasts as long as the receiving
  activity record; a held descriptor outlives it, so the descriptor is opened only after the user confirmed, while the activity is
  alive.
- **Exposure on the LAN.** A share is reachable only by a 128-bit random key (22 characters), from the chosen receivers' addresses
  while the restriction setting is on (default), for at most the player's life plus 10 minutes (6 hours at most), at most 8 at once,
  4 responses each. The HTTP server itself runs whenever the app runs (existing behavior); without registry entries `/proxy/` answers
  404.
- **The key is not a secret from the LAN.** The proxy URL, key included, is what the receiver is told to play, so any UPnP control
  point that can query that receiver (for example `GetMediaInfo` or `GetPositionInfo` on its AVTransport service) can read it. With the
  restriction ON such a device is still refused, because its address is not one of the chosen receivers' addresses. With the
  restriction OFF the key is NOT a protection against anybody on the network: it only stops blind guessing. The setting's
  texts and docs say so.
- **Local apps.** When this device is a chosen receiver, loopback and the phone's own addresses are allowed (the in-app player fetches
  from them), so a hostile app on the phone is stopped only by the key. The key is not published by the app: the notification and
  the legacy `MediaSession` metadata carry title and name only; Media3 does not send `MediaItem.localConfiguration` to controllers
  (reasoned from the media3 1.11.1 bytecode, not observed on a device). Exception: see "Known limitations" (local video).
- **Confused deputy.** Android checks only that yaacc can read the URI. Yaacc holds broad media permissions and SAF grants, so a
  hostile sender could name MediaStore or SAF content that it cannot read itself. Mitigations: the audio/video/image type check; a
  human in the loop (every share needs a receiver pick that shows the cleaned file name); the address and expiry limits; on Android 15+
  the read flag that the incoming intent must carry together with the URI in its ClipData: the system only lets a sender launch with that flag if the
  sender itself can read the URI, so a sender cannot name a URI it has no access to (reasoned from the platform behaviour, not device-verified). The Android 15+ caller
  check (`getInitialCaller()`) is only logged: through the system share sheet the initial caller is the sharesheet, not the sending app, and the check said
  "denied" for every gallery share (seen on a device on 2026-10-29), so blocking on it made sharing from a gallery impossible. A browser activity started directly
  cannot bypass the launcher's checks for a file (launch token).
- **Link fetch (SSRF).** With the proxy option on, the phone fetches the shared link for the receivers. `LinkTargetPolicy` narrows
  what it will fetch (see "As built: links"); the answer goes to the chosen receivers only, not back to the sender.
- **Resources.** Provider calls are bounded as described above; the copy is capped and watched; descriptors are closed on expiry,
  release, eviction, `removeAll()` and exit; the share code paths log no URI, name, key or proxy URL (a refused request logs the remote address only). No share URI, name, key or
  proxy URL is written at the default log level; the VERBOSE protocol traces and DEBUG album-art logs are not redacted, and existing
  non-share code logs other URIs (album art, local storage URIs) at ERROR (see "As built: serving").

### Security invariants of the share feature

The properties the share code upholds and its tests pin (numbering as in the planning notes, `design.md`). A change to the share code must keep
all of them; a review re-derives them from the code.

- **SI-1 Keys.** 16 bytes from `SecureRandom`, 22 characters of unpadded base64url, never handed out twice while the registry lives (the last 256
  are remembered, 8 draws per registration), format-checked before any lookup; malformed input never throws.
- **SI-2 Registry.** Monotonic clock (not wall time); 10 minutes idle grace after release, 6 hours absolute; a cap on active entries; each target
  closed exactly once and outside the lock; a failed `register` leaves ownership with the caller; eviction touches only released entries;
  `removeAll()` on exit; a periodic sweeper.
- **SI-3 Request chain.** For `/proxy/`: path shape, key format, lookup, address policy, renderer id, serving, in that order. Every bad key gets
  the same constant 404 body, 403 only follows a valid key, HEAD and range continuations take the same chain, key and path are never echoed or logged.
- **SI-4 Address step.** The remote address comes only from the connection's endpoint details. The restriction is ON by default in code, in
  `preference.xml` and when the read fails. Zone ids are stripped, IPv4-mapped IPv6 is normalized, names are never resolved, an empty allowed set
  denies everything while restricting, loopback and the phone's own addresses count only when the local device was chosen, and the allowed set is
  built from the ids captured at confirm and copied unmodifiable into the entry.
- **SI-5 Ranges and headers.** An invalid or multiple range is answered 200 whole, an unsatisfiable single range 416 `bytes */len`; long arithmetic
  with saturating digits and a header length cap; an upstream `Range` is re-serialized from parsed numbers; `Content-Range` is built from numbers; no
  `Content-Disposition`; `Content-Type` comes from the re-validated entry MIME; `X-Content-Type-Options: nosniff` and CSP `sandbox` on every response.
- **SI-6 Held file.** `dup` of the provider's descriptor and the original closed; a regular-file and `lseek` check; start offset and length clamped to
  the real size; `pread` at `start + position`; close deferred to the last reader, never blocking the caller, exactly once, new reads refused after
  close; a failed open leaves the descriptor with the caller; the late result of a timed-out open is closed.
- **SI-7 Concurrency.** At most 4 responses per share (503 on the 5th); an idle watchdog takes a stalled reader's slot; a resumed reader aborts only
  when the share is full; release is idempotent; the lease is released when the producer cannot be built.
- **SI-8 Launcher forward.** A fresh intent: `ACTION_SEND` with the exact lower-case media type, ONE `ClipData.newRawUri` item and
  `FLAG_GRANT_READ_URI_PERMISSION` only, no `data`, no `EXTRA_STREAM`, no other extra. `EXTRA_STREAM` must equal ClipData item 0, the incoming flags
  must carry the read grant, the content policy must accept the URI, any provider of this app is refused (fail closed), the API 35+ caller check
  is only logged (it denies shares that come through the system share sheet), a `SecurityException` falls back to a plain launch, `finish()` runs in `finally`, and the incoming intent is never modified.
- **SI-9 Launch nonce.** 128 bits from `SecureRandom`, valid 30 s, single use, at most 8 outstanding, constant-time compare, never logged; a file
  that reaches a browser without a valid nonce is dropped.
- **SI-10 Browser intake.** The browser repeats every check (action, exactly one ClipData item, no `data`, the read flag in the intent's own flags,
  item 0 only and never `EXTRA_STREAM`, content policy, own-provider check); the intent is consumed BEFORE validation; only the activity of the
  effective layout hands a payload to the handler.
- **SI-11 Pending state.** One pending share (a second offer is dropped, never replaces) with a 2-minute monotonic timeout, a 128-bit token compared
  in constant time and a single `take`; nothing is opened, copied or registered before the confirm; one open pick (stale, forged or repeated ids
  ignored, state cleared before callbacks run); the sheet answers once; one picked action in flight with a watchdog; after the start the new layout
  clears the selection and classic restores a copy; clearing never alters an existing share's allowed set.
- **SI-12 Provider calls.** Never on the main thread; type, name and open are bounded (3 s, 3 s, 20 s); at most one call in flight per provider (no
  expiry), at most 3 tracked providers of a 4-thread pool, unresolved providers share one key; the MIME type only through `chooseMimeType`; the name
  is cut at 1024 characters, only a string column is read, then cleaned by `DisplayName`.
- **SI-13 Copy fallback.** File names `share-<32 hex>.bin` from `SecureRandom` with `CREATE_NEW` in the private `cache/incoming`, never from provider
  data; the sweep deletes only exact-shape regular files and never follows links; budget = min(512 MiB, 1 GiB minus what is there) with a 64 MiB
  free-space reserve re-checked before EVERY chunk; a declared size over the budget is refused before a byte is written; one copy at a time; the
  watchdog closes the input and the worker's output, deletes the partial file, returns the slot and abandons at most 3 workers; the held copy is
  closed first and then deleted.
- **SI-14 Ownership.** Every `register` is paired with an owner (the source is closed if registration returns null after one evict-retry or on any
  throw); a share no player claimed within 2 minutes is removed; claim and release are idempotent; keys are remembered before a player's `onDestroy`.
- **SI-15 Link policy.** No DNS; the authority is taken after the LAST `@`; loopback, 0/8, link-local, the phone's own addresses, mapped / compatible /
  NAT64 IPv6 forms, non-strict IPv4 spellings, `localhost` names and look-alike characters are refused; private LAN ranges are allowed; redirect hops
  of the content-type probe are re-checked (max 3, HEAD only, body never read); only http and https; no address means no URL and nothing is registered.
- **SI-16 Logging and toasts.** No key, URI, name, proxy URL or provider exception text; fixed texts, enum names and simple class names;
  `ShareLogRedactor` removes `(?i)(?:/|%2f)proxy(?:/|%2f)[^\s"'<>]*`; toasts are fixed resources.
- **SI-17 Limits.** The values in `ShareLimits` do not change without a security review.
- **SI-18 Surfaces.** The manifest differs from `071fc9d2` by exactly the one SEND filter; no new component, permission, dependency or preference
  key for shares; `BrowserActivity` is not exported; the proxy URL is on no MediaSession or notification surface.
- **SI-19 Picker UI.** `filterTouchesWhenObscured` on OK, Cancel and the rows; names and LAN texts reach the screen only through `setText` after
  cleaning; the share name only through the cleaner with a fixed fallback.

### Known limitations

The share feature has known, accepted limitations; the full technical list is kept with the project specification
(`.claude/specs/2026-10-23-unified-device-sheet/known-risks.md` while that folder exists), not in the published docs. In summary:

- The link policy is a best-effort filter, not a complete protection: it is enforced only when the media proxy option is on and
  it does not resolve host names.
- A share is protected by a random key and, by default, by the receivers' addresses (setting "Restrict the media proxy to the
  chosen receivers"). With that setting off, or when this phone itself is a chosen receiver (other apps on the phone then only need the key), the key is the only protection.
- When "this device" plays a shared video, the video app that opens it receives the proxy address.
- Providers are called one at a time per app with timeouts; a provider that never answers can make later shares fail with a
  "busy" message until its call returns.
- The picker has no cool-down after a cancel; in the classic layout a shared file opens the picker with the saved receivers ticked.
- A rotation or process death while the picker is open drops the share.

### Not verified on a device

Everything in this document is code, tests and reading of platform sources. The owner tried the device sheet and the layouts on a phone (2026-10-24,
the feedback is built in) and later fixes followed his feedback; the share feature, the picker and the checks below were never run on a device or an
emulator. Open checks, by area:

- **Share chain.** Sender -> launcher -> browser with a real grant on Android 11-16 and real senders (gallery, files, messengers);
  `getInitialCaller()` via the system chooser on API 35+; the launcher's `SecurityException` fallback; whether `resolveContentProvider` sees a freshly
  forwarded provider on Android 11+; whether closing a provider stream wakes a blocked read; whether a descriptor keeps working after the grant ends
  and after the sender app dies (and the kill behaviour of the stable provider reference with the `dup` mitigation); which providers return pipes or
  sub-sections and how `fstat`/`lseek`/`pread` behave on FUSE-backed `DocumentsProvider` descriptors; the type of `EndpointDetails#getRemoteAddress()`
  and the IPv4/IPv6 forms real connections report; the address real renderers fetch from compared with their SSDP location; whether they handle
  `HEAD`, 206, 416 and `Connection: close`; the effect of `stopService` plus a bound service on notification and process priority with the local
  server setting off, and survival of a long stream across screen-off (WifiLock, Doze); Android 15 and 16 changes to URI grants and foreground-service
  starts (only Android 14 sources were read); the proxy URL not appearing on the notification or lock screen of the local player.
- **Picker.** Opens on play, play-all, item tap and add to playlist; cancel, back, tap outside and swipe change nothing; OK with nothing ticked shows
  the hint (TalkBack announces it); the pick sheet layout (fit to contents, buttons stay visible, the list scrolls) with 10+ receivers, on a small
  screen and in landscape; D-pad and TV focus; dark mode; receivers appearing while it is open; rotation with it open (closes, no crash); Home and
  back with it open; a share arriving while the browser is in the background; the classic share path (selection ticked, restored after the start).
- **Layouts.** Switching in both directions with players running; a stale notification tap after a switch; rotation in both layouts; process death
  and restore; the 5-minute idle exit; the TV launcher entry and D-pad reach of the new layout; re-tapping the launcher icon (no second browser
  instance); the splash screen on Android 12+ with the invisible launcher; the toolbar timer and its subtitle, the mini-player rows (several
  players, scrolling beyond about three rows, buttons incl. exit, tap to open) and the sheet at every level; the restored classic footer in portrait
  and landscape; sharing a link from another app (task placement, playback starts).
- **Look.** Card grid spacing and the fit of the four buttons, thumbnail sharpness of the 320dp request across densities, D-pad movement between
  cards, the column count in landscape, on a tablet and in multi-window; toolbar colors and icons in both themes (page-colored title bar, status and navigation bar colors and icon shades, menu icons also in the
  image viewer and the log viewer); gray controls and bold settings headlines; the log viewer's clear and refresh.
- **Other.** `MediaDataSource` metadata on API 27 for the formats users share is not built; the copy fallback's real speed and the cache behaviour
  under low storage.

### Phase 4 notes (consolidating the share code)

Verified 2026-10-26 for Group 17 of Phase 4. Android types are allowed in the share classes; these are the two facts the
refactor relies on. Phase 4 changes no behaviour, so nothing here is a new feature.

**(a) `java.net.URI` builds the proxy URL (`ShareService#proxyUrl`).** The 7-argument constructor
`new URI(scheme, userInfo, host, port, path, query, fragment)` is documented for Android (API level 1) as: "If a host is given
then it is appended. If the host is a literal IPv6 address but is not enclosed in square brackets (`'['` and `']'`) then the
square brackets are added", and it throws `URISyntaxException` when the components do not form a valid URI
(<https://developer.android.com/reference/java/net/URI#URI(java.lang.String,%20java.lang.String,%20java.lang.String,%20int,%20java.lang.String,%20java.lang.String,%20java.lang.String)>,
read through the android-docs reference for compileSdk 36; `yaacc/build.gradle:75` compileSdk, `:111` minSdkVersion 27). The
Android class is the same OpenJDK-derived implementation as the desktop one; the run below is on the desktop JDK 21.0.12
(`java V.java` with `new URI("http", null, host, 49157, "/proxy/AbC-_9", null, null)`), not on a device:

| host argument | result |
|---|---|
| `192.168.1.5` | `http://192.168.1.5:49157/proxy/AbC-_9` |
| `fd00::1` and `fe80::1` | `http://[fd00::1]:49157/proxy/AbC-_9` (brackets added) |
| `[fd00::1]` (already bracketed) | unchanged brackets |
| `fe80::1%wlan0` (zone id) | `http://[fe80::1%wlan0]:49157/proxy/AbC-_9` (zone kept) |
| `0.0.0.0` | `http://0.0.0.0:49157/proxy/AbC-_9` |
| ` 192.168.1.5` or `192.168.1.5 ` (blank at the ends), `a b` | `URISyntaxException: Illegal character in authority` |

So `proxyUrl` trims the address first, as the string builder it replaced did, and returns null for a blank address, an empty or
null key and for a `URISyntaxException` (an address with an inner blank, which the old builder would have put into the URL
unchecked; the address comes from `InterfaceResolutionHelper.getIpAddress`, which returns an interface address or `"0.0.0.0"`,
never such a text). Characters of the key that `URI` would quote cannot occur: a registry key is 22 characters of unpadded
base64url (`ShareRegistry.isWellFormedKey`). The old and the new output were compared on these inputs before the old class was
deleted (identical for every row that is valid; the only differences are the two throwing rows, where the old builder returned
a URL with a blank in it). `ShareService#hasAddress(ip)` (non-null, non-blank) replaces the `"x"` probe of
`UpnpClient#createHttpLinkShare` and `ShareService#prepare`; it is true for `"0.0.0.0"`, so the offline behaviour is unchanged.

**(b) Mocking Android types in the unit tests.** There is no Robolectric. `yaacc/build.gradle:121`
`unitTests.returnDefaultValues = true` makes the platform stubs return `null`, `0` and `false` instead of throwing, so
`Looper.getMainLooper()` is null (`UpnpClient#awaitHttpServer` relies on it, `UpnpClient.java:1841`), `new Handler(looper)` does
nothing and posts nothing, and `Uri.parse` returns null (a test that needs a parsed `Uri` stubs it). Mockito is
`mockito-core:5.24.0` (`yaacc/build.gradle:15`) with its default inline mock maker, which can mock final classes and methods and
static methods. Existing tests mock Android types by instance only: `Context`, `ContentResolver`, `Cursor`, `PackageManager` and
`Uri` in `ShareIntentHandlerFileTest` (lines 155-230) and `ProviderProbeTest` (lines 37-38), `Intent` and `ClipData` in
`ShareIntakeGateTest` (line 47) and `ShareIntentHandlerFileTest` (209-232). None of them uses `mockStatic` or
`mockConstruction` (the Phase 4 plan assumed `ShareIntakeGateTest` did; it does not). `ShareServicePrepareTest` and
`UpnpClientLinkShareTest` (Group 17) are the first to use them, with these findings:
- `mockStatic(Uri.class)`, `mockStatic(Toast.class)`, `mockStatic(InterfaceResolutionHelper.class)` and
  `mockConstruction(Handler.class)` work on the stub jar. A posted toast is then visible as a resource id (the captured
  `Runnable` is run with `Toast.makeText` mocked).
- A static mock is bound to the thread that created it. `ShareService#prepare` runs the provider call on its own pool thread
  (`BoundedCall`), where the stubs of the test thread do not apply and `Os.fstat` returns null, so a real `HeldFileSource` can
  never be opened in a unit test. The test installs `mockStatic(HeldFileSource.class)` from the answer of the mocked
  `ContentResolver.openAssetFileDescriptor`, which runs on that pool thread, and closes the static mock in its own answer.
  A second prepare on another thread needs its own `mockStatic(Uri.class)`.
- `PositionalSource#size` and `#close` are final; Mockito stubs and verifies them on a `mock(HeldFileSource.class)`.

## Research notes (Group 12): platform facts behind the share feature

Research done on 2026-10-25 before the share code was built, condensed afterwards to the facts a maintainer needs; the plans,
option comparisons, API sketches for the red tests and review-driven rewording are in git. The as-built text above wins where it
differs. Nothing here was run on a device; every platform statement rests on documentation or platform source, so the chain from a
real sender to a played file is in "Not verified on a device". Versions: `targetSdkVersion 33`, `minSdkVersion 27`, `compileSdk 36`;
`httpcore5:5.3.6`, `httpcore5-h2:5.3.6`, `httpclient5:5.3.1`; `androidx.core:1.16.0`.

Marks: **[code]** read in this repo; **[docs]** official documentation (URL given); **[aosp]** `frameworks/base` at `android-14.0.0_r1`
(`raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/...`); **[libcore]** LineageOS `android_libcore`,
branch `lineage-21.0` (Android 14 based); **[httpcore]** the `httpcore5-5.3.6` / `httpcore5-h2-5.3.6` source jars from Maven Central;
**[reasoned]** inferred, not run. Android 15 and 16 sources were not read and may differ.

### Platform and code facts that shaped the design
1. **The local HTTP server runs whenever the app process does, whatever `settings_local_server_chkbx` says** [code]: `Yaacc.onCreate`
   always calls `startService(YaaccUpnpServerService)`, `UpnpClient` binds it with `BIND_AUTO_CREATE` for the life of the process,
   `onStartCommand` never reads the preference and `initialize()` creates the HTTP server unconditionally on the wildcard address, port
   49157. The preference only decides which UPnP services the local device publishes and whether `LocalServerLifecycle` keeps the service
   in the foreground (see (e)).
2. **Another app's `content:` URI can be one that yaacc may read on its own.** The manifest requests `MANAGE_EXTERNAL_STORAGE` and
   `READ_MEDIA_*` and the app keeps SAF tree grants; the system's forward check passes when the CALLER holds access by its own
   permissions, not only by a grant from the sender. Android has no public API to tell "granted by the sender" from "readable by us
   anyway" (see (a)).
3. **`MediaMetadataRetriever.setDataSource(String)` names a path or an http URI**; a failure is a `RuntimeException`, which
   `createPlayableItem` turns into "assume image" (a shared MP3 would be announced as an image). For a descriptor use
   `setDataSource(FileDescriptor, long, long)` or a `MediaDataSource` (API 23).
4. **Closing a descriptor another thread is still reading is a security bug**: the number can be reused at once by an unrelated socket or
   file. Holding a descriptor from `ContentResolver` also holds a stable reference to the provider's process (see (b)).

### (a) How `ACTION_SEND` delivers one file, and what the trampoline may forward
**Delivery.**
- `ACTION_SEND`: `getType()` is the MIME type, `EXTRA_STREAM` carries the data; without ClipData it "will be copied there for you when
  calling `Context.startActivity(Intent)`"; `*/*` if the type is unknown [docs: `https://developer.android.com/reference/android/content/Intent#ACTION_SEND`].
  `openAssetFileDescriptor(uri, "r")` already goes through `openTypedAssetFileDescriptor(uri, "*/*")` [aosp `ContentResolver.java`], so
  use it, not `openInputStream`.
- The copy happens in the SENDER's process: `Instrumentation.execStartActivity` calls `intent.migrateExtraStreamToClipData` [aosp]. For
  `ACTION_SEND` it builds ClipData item 0 = the stream URI and, if there is a stream, adds `FLAG_GRANT_READ_URI_PERMISSION`; it does
  nothing when a ClipData is already set. So a plain sender delivers item 0 = stream URI plus the read flag, but a sender that sets its own
  ClipData and flag may have `EXTRA_STREAM` and the ClipData name DIFFERENT URIs. `ACTION_SEND_MULTIPLE` is a different action and is not
  matched by a SEND filter.
- Read the extra with `IntentCompat.getParcelableExtra(intent, name, Uri.class)` (androidx.core 1.10.0+; `Intent.getParcelableExtra(String,
  Class)` is API 33, minSdk is 27) [docs: `https://developer.android.com/reference/androidx/core/content/IntentCompat`] and wrap it: a crafted
  extra can throw.
- `<data android:mimeType="audio/*"/>`: "MIME type matching in the Android framework is case-sensitive ... always specify MIME types using
  lowercase letters" [docs: `https://developer.android.com/guide/topics/manifest/data-element`]; three `<data>` elements in one
  `SEND`+`DEFAULT` filter match any of them, `*/*` and `Audio/MP3` do not. The filter sees only the SENDER's `getType()`, which is untrusted
  and may be a wildcard, so the real type is checked on the content: `ContentResolver.getType(uri)` "returns a MIME type for the content, or
  null if the URL is invalid or the type is unknown" [docs: `https://developer.android.com/reference/android/content/ContentResolver#getType(android.net.Uri)`];
  a Binder call (not on the main thread) whose answer is untrusted text. Order: provider type if acceptable, else a concrete acceptable
  intent type, else reject.

**Who may grant, and what Android honors** [aosp `services/core/java/com/android/server/uri/`, `.../wm/ActivityStarter.java`]:
- Only `content:` URIs are granted at all; other schemes get "nothing to do" and no access. `ContentResolver` itself opens `file:` and
  `android.resource:` URIs without any grant, which is why the policy must reject them.
- A grant is checked on every start for the intent's `data` AND every ClipData item URI (recursing into a clip item's nested `Intent`);
  `EXTRA_STREAM` and other extras are never looked at, and only the top-level intent's flags count.
- Per URI (`checkGrantUriPermissionUnlocked`): (1) if the target already holds access by manifest permissions nothing is recorded; (2) the
  provider must have `grantUriPermissions` (and match `uriPermissionPatterns`), else `SecurityException`; (3) the CALLER must hold access
  itself, by manifest permission or a strong enough URI grant, else `SecurityException "UID n does not have permission to <uri>"`. Step 3 is
  the rule "Android honors the flag only if the forwarder holds the access". The shortcut in (1) looks at manifest permissions only, so a
  same-uid forward is not exempt.
- A grant is recorded per TARGET uid and URI with the receiving `ActivityRecord` as owner and lasts until the last owner is removed
  (`ActivityRecord.removeFromHistory` -> `removeUriPermissionsLocked`; the grant is also recorded for an activity that receives `onNewIntent`). The docs say "expire automatically when the receiving app's task
  stack is finished" [docs: `https://developer.android.com/training/secure-file-sharing/share-file`]; the source is finer: the lifetime of
  the activity record(s) that received the intent, usable by every component of the uid meanwhile; process death does not end it, the
  record does. Nothing in `UriGrantsManagerService`, `UriPermission` or `UriPermissionOwner` refers to a `FileDescriptor`: ending a grant
  never reaches descriptors already handed out.
- The launcher's forward: the system records the sender's grant on the launcher's record; in `onCreate` the launcher calls `startActivity`
  with `callingUid` = yaacc, the check above runs for every ClipData URI of the FORWARD and passes only for a URI that really reached the
  launcher (and only if the provider still allows grants); a second grant is recorded on the TARGET's record, so finishing the trampoline
  does not revoke it and it lasts until the browser's record is removed (a rotation keeps it, reasoned). A URI the launcher cannot
  access makes `startActivity` throw `SecurityException` in the launcher [reasoned]. `setClipData(null)` or removing extras in the browser
  revokes nothing.
- Narrowest safe forward: explicit `Intent`, `ACTION_SEND`, the validated concrete type, `ClipData.newRawUri` with exactly ONE item and no
  `Intent` item, `FLAG_GRANT_READ_URI_PERMISSION` only [docs `Intent#setClipData`: the flags "will operate on any URI items included in the
  clip data"]; no `EXTRA_STREAM` (the system checks the ClipData, not the extra), never a write, persistable or prefix grant (a prefix grant
  would cover sibling files), no `data`, more than one item, other extras, categories, selector or incoming flags. Accept the URI only if the
  action is `SEND`, the content policy accepts it, ClipData item 0 equals `EXTRA_STREAM`, the incoming flags include the read grant, and the
  authority is not served by any provider of this app (`PackageManager.resolveContentProvider(authority, 0).packageName`; the app has more
  than the `${applicationId}.fileprovider`, whose paths expose only `cache/shared/`). A `content://media/...` URI is a legitimate share from
  several gallery apps, so the `media` authority is not rejected.
- None of this proves the SENDER holds the access: `getCallingPackage()` is null for a plain `startActivity`, `Activity.getReferrer()` honors
  a sender-supplied `EXTRA_REFERRER`, and `checkUriPermission(uri, pid, uid, flags)` for our own uid is true for grants and our own
  permissions alike. Accepted residual risk (see `known-risks.md` item 14). The browser side treats the forwarded intent as untrusted too:
  `TabBrowserActivity` is exported, so any app can send it an intent.

### (b) Descriptor lifetime, seekability, serving ranges, copy fallback
**An open descriptor outlives the grant.** Access is checked when the descriptor is OPENED (`ContentProvider.Transport.openAssetFile` /
`openTypedAssetFile` call `enforceFilePermission` [aosp `ContentProvider.java`]); the `ParcelFileDescriptor` is duplicated into our process by
the kernel and later reads hit the file, not the provider's permission check; ending a grant edits a table only [aosp + reasoned]. "You own
this descriptor and are responsible for closing it when done" [docs: `https://developer.android.com/reference/android/content/ContentResolver#openFileDescriptor(android.net.Uri,%20java.lang.String)`].
Things that end a descriptor's usefulness: the sender deleting or truncating the file while we stream (an unlinked inode stays readable,
truncation shows as short reads), a provider that returns a pipe, the provider process dying.
- **Stable provider reference.** `openAssetFileDescriptor` returns a `ParcelFileDescriptorInner` holding `acquireProvider(uri)` (a stable
  reference) until closed [aosp `ContentResolver.java`]. The stable kind "turns on ... the mechanism in the platform clean up processes that
  are dependent on a content provider if that content provider's process goes away" [docs: `ContentResolver#acquireUnstableContentProviderClient(Uri)`]:
  if the sender is killed while we hold the descriptor, yaacc can be killed too [reasoned]. Mitigation as built: `ParcelFileDescriptor.dup(...)`
  (a plain descriptor via `fcntl(F_DUPFD_CLOEXEC)`) and close the original at once. The dup shares the file offset with the original, which
  does not matter with positional reads. At most one descriptor per share, at most 8 shares.

**Seekability.**
- Mode `"r"` "could be a pipe or socket pair to enable streaming of data. Opening with the "rw" mode implies a file on disk that supports
  seeking" [docs, same page]; do not use `"rw"` (needs write access, fails for a read grant).
- Prefer `openAssetFileDescriptor` over `openFileDescriptor`, which throws `FileNotFoundException("Not a whole file")` for a provider that
  returns a sub-section. Use `getStartOffset()` and `getDeclaredLength()` (`-1` = to the end, `UNKNOWN_LENGTH`); `getLength()` returns the
  declared length or else `getStatSize()`; the constructor requires `startOffset == 0` when the length is unknown. Every read position is
  `startOffset + position` and every read is capped by the length.
- `ParcelFileDescriptor#getStatSize()` returns the size via `fstat`, `-1` if the descriptor is not a file [docs; aosp: size only for
  `S_ISREG`/`S_ISLNK`]. A provider may declare a length for a pipe, so the declared length does not prove seekability.
- Rule (public API only): seekable if `Os.fstat(fd)` reports a regular file (`OsConstants.S_ISREG`) AND `Os.lseek(fd, 0, SEEK_CUR)` succeeds
  (ESPIPE for pipes and sockets). `android.system.Os` `fstat`, `lseek` and `pread` are "Added in API level 21" [docs: `https://developer.android.com/reference/android/system/Os`].
  FUSE-backed `DocumentsProvider` proxy descriptors are regular files to `fstat` and seek [reasoned; may be slow]. Not seekable, unknown
  size or zero length: copy fallback or reject.

**Serving ranges without closing the descriptor.**
- Positional reads only: `Os.pread(FileDescriptor, byte[], int, int, long)` ("Added in API level 21", throws `ErrnoException`,
  `InterruptedIOException`) does not touch the file offset, which a `dup` shares with the original (`ParcelFileDescriptor.dup` javadoc:
  "shared state such as file position"). `FileChannel.read(ByteBuffer, long)` is positional too ("does not modify this channel's position"
  [docs: `https://developer.android.com/reference/java/nio/channels/FileChannel`]; on Android it ends in a native `pread64` [libcore
  `FileChannelImpl`/`FileDispatcherImpl`]). `Os.pread` was chosen because a `FileChannel` is an `AbstractInterruptibleChannel`:
  interrupting a thread inside a read closes the channel for everyone and it stays closed, and HttpCore's I/O threads can be interrupted on
  shutdown; `Os.pread` has no state. `FileInputStream(FileDescriptor)` is not the owner on Android (`isFdOwner = false`), so closing it does
  not close the descriptor.
- Never close the shared descriptor from a request; closing belongs to the registry (`AutoCloseable` target) on expiry, release or
  `removeAll()`, guarded against a running read (an in-flight counter with deferred close, or a read/write lock): `readAt` after close ends
  the response.
- Do not hand the shared descriptor to `MediaMetadataRetriever.setDataSource(FileDescriptor, ...)`: the native side seeks and reads it
  [docs: `https://developer.android.com/reference/android/media/MediaMetadataRetriever#setDataSource(java.io.FileDescriptor,%20long,%20long)`].
  A `MediaDataSource` over the source would be the clean way (API 23); it is not built, so a shared file's item has no duration.
- Threading: the handler runs on the reactor's I/O threads (`setIoThreadCount(8)`); `produceData` is called from them [httpcore]. A `pread`
  of a local file is fine, a FUSE-proxy descriptor can block a thread: read 16-64 KiB per call and keep `availableData()` positive while
  bytes remain. For `HEAD` HttpCore ends the stream after the headers even when the handler supplies an entity
  (`ServerHttp1StreamHandler` [httpcore]), so the handler opens and reads nothing and sets the length headers from the known size.

**Copy fallback.** Only for a non-seekable descriptor or unknown length; never copy from a seekable one. Directory: `new File(getCacheDir(),
"incoming")`, NOT `cache/shared/` (exposed by `FileProvider` through `file_paths.xml` and used by `util/ShareFileTask`); random file names
(never the provider's display name or the URI), no extension from the sender. Count bytes while copying, stop at the cap, also stop when
`getUsableSpace()` is below the remaining size plus a margin. Cleanup: the copy source deletes its file on `close()`, `removeAll()` closes
all, and `cache/incoming/` is swept at process start (the registry is memory-only, so any file found is an orphan).

### (c) The remote address of a request
The `handle(Message<HttpRequest, byte[]>, ResponseTrigger, HttpContext)` method receives an `HttpContext` that the stream handlers create
as `HttpCoreContext` and fill with `setEndpointDetails(getEndpointDetails())` for HTTP/1.1 (`ServerHttp1StreamDuplexer`) and HTTP/2
(`ServerH2StreamMultiplexer`) [httpcore 5.3.6]. `EndpointDetails#getRemoteAddress()` returns a `java.net.SocketAddress`
(`BasicEndpointDetails` built from `ioSession.getRemoteAddress()`):
`EndpointDetails d = HttpCoreContext.cast(context).getEndpointDetails();` then `d.getRemoteAddress() instanceof InetSocketAddress` and
`getAddress().getHostAddress()` (`HttpCoreContext.cast` exists since 5.3). Never use `getHostName()` (reverse DNS on the I/O thread). Missing
details or another address type means deny. That the type is `InetSocketAddress` for a TCP session is [reasoned]. IPv6 `getHostAddress()`
carries a scope after `%`; `ProxyAccessPolicy.normalize` strips it. The glue stays a four-line function and the decision lives in `ProxyAccessPolicy`.

### (d) A receiver's address, and who resolves the receivers for a playback
- Remote device: `RemoteDevice#getRoot().getIdentity().getDescriptorURL()` is a `java.net.URL` (the SSDP `LOCATION`) [vendored cling
  `RemoteDeviceIdentity`]. Embedded devices copy the root's descriptor URL, so root and children share the address; `UpnpClient.getDevice(id)`
  may return an embedded device, so use `getRoot()`. Second candidate: `device.getDetails().getBaseURL()` (`<URLBase>`), which cling prefers
  for relative service URLs; include both if the hosts differ. `URL.getHost()` of an IPv6 literal keeps the brackets (strip exactly one
  pair before `ProxyAccessPolicy`); a host NAME must not be resolved on the main thread (`NetworkOnMainThreadException`).
- cling keeps one `RemoteDeviceIdentity` per root, so yaacc only knows the address of the last announcement and maybe the `URLBase` host; a
  multi-address renderer (Wi-Fi + Ethernet, IPv4 + IPv6) may fetch from another address. That is the documented risk and the reason for the
  "Restrict the media proxy to the chosen receivers" setting. Whether a dual-stack accept reports `::ffff:a.b.c.d` or an `Inet4Address` is
  not verified [reasoned: the JDK maps to `Inet4Address`]; `normalize` handles both.
- This phone: the `UpnpClient.LOCAL_UID` dummy device (`LocalDummyDevice`, no `RemoteDeviceIdentity`) and, if the server publishes a renderer,
  a `LocalDevice` with no URL. Allowed addresses for them: `127.0.0.1`, `::1`, `InterfaceResolutionHelper.getIpAddress(context)` and every
  address of `getBindAddresses(context)`. `getIpAddress` returns the first non-loopback IPv4 of the first non-blacklisted (or the selected)
  interface, and `"0.0.0.0"` (not null, not `127.0.0.1`) when there is none, e.g. wifi off. The in-app player fetches the proxy URL whose
  host is `getIpAddress` [reasoned] (`usesCleartextTraffic="true"`).
- Selection storage [code]: `UpnpClient.getReceiverDeviceIds()` (preference `settings_selected_receivers_title`, default `{LOCAL_UID}` ONLY
  when the key is absent), `setReceiverDeviceIds`, `addReceiverDevice`, `removeReceiverDevice` (the last two fire
  `receiverDeviceAdded/Removed`). An EMPTY stored set is returned as is by `getStringSet` (`SharedPreferencesImpl.getStringSet`: `v != null ?
  v : defValues` [aosp]), so it does not revert to the default and fires no event; hence `clearReceiverDevices()`. The set returned by
  `SharedPreferences.getStringSet` must not be modified ("you must not modify the set instance returned by this call" [aosp
  `SharedPreferences.java`]); `getReceiverDevices()` used to do so and now works on a copy.
- Playback reads the selection in `PlayerService.createPlayer(UpnpClient, List<PlayableItem>)` (twice: the empty check with the "no receiver
  selected" toast, and the loop) and creates one player per receiver; players keep the device id, so clearing the selection afterwards does not
  stop them. `createPlayerForLocalDevice` (the inbound renderer path) does not read the selection. All play variants end in
  `ContentListFragment.play(List<Player>)`, the one place for "after the players have started". A proxy URL needs the receivers first: pick ->
  allowed addresses -> register -> build the URL -> create players.

### (e) The local server, the foreground service and Android 12+/14
- `LocalServerLifecycle.apply(Context, boolean restart)`: with the preference on, optional `stopService` then `startForegroundService`; off:
  `stopService`. Callers: `onResume` of both browsers; other starters: `Yaacc.onCreate` (plain `startService`, always), `ServerAutostart`
  (boot), `YaaccUpnpServerControlActivity`. `onStartCommand` creates the registry and `NetworkDeviceListener`, starts the `initialize()` thread
  (HTTP server with retries, then the UPnP device) and calls `startForeground` every time; `START_STICKY`; `onDestroy` throws a `NullPointerException` at `networkDeviceListener.disable()` if `onStartCommand` never ran. The preference listener reacts to
  the local-server, provider and receiver checkboxes (`createUpnpDevice()`), the selected interface (`restartServer()`) and the serve-media
  toggles; `settings_local_server_proxy_chkbx` is read per request.
- `stopService` does not destroy a service that still has a `BIND_AUTO_CREATE` binding: "if a stopped service still has ServiceConnection
  objects bound to it with the BIND_AUTO_CREATE set, it will not be destroyed until all of these bindings are removed", and a foreground
  service's notification is removed when it is stopped [docs: `https://developer.android.com/reference/android/content/Context#stopService(android.content.Intent)`].
  So with the preference off the server runs without its notification.
- Readiness: `UpnpClient.isInitialized()` (service bound, registry and `networkDeviceListener` initialized) does NOT mean the HTTP server is
  listening. A loopback connect to port 49157 (or `GET /health`, answered 200 for everyone) tells. After `HTTP_SERVER_MAX_RETRIES` (3) failed
  starts there is no server, and a share must fail with feedback instead of waiting forever.
- Android 12 (API 31)+: apps "can't start foreground services while the app is running in the background, except for a few special cases"
  (`ForegroundServiceStartNotAllowedException`); exemption: "your app transitions from a user-visible state, such as an activity"
  [docs: `https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start`]. A share arrives by an activity start, but
  a start from a background callback is not guaranteed; the service is already running, so the constraint is avoided.
- Android 14 (API 34): foreground services must declare a type and, for `mediaProjection`, hold a MediaProjection grant
  [docs: `https://developer.android.com/about/versions/14/changes/fgs-types-required`]. The manifest declares
  `foregroundServiceType="mediaProjection"` on the server service and `FOREGROUND_SERVICE_MEDIA_PROJECTION`; with `targetSdkVersion 33` this is
  not enforced. Raising the target to 34 would make `showNotification()`'s `startForeground` fail without a projection.
- The runtime permission `POST_NOTIFICATIONS` is requested by `BrowserActivitySupport.requestMissingPermissions` (code 101).

### (f) Platform facts for the receiver picker
- Fragment result API: "if you set a result without a corresponding listener, the result is stored in the FragmentManager until you set a
  listener with the same key" [docs: `https://developer.android.com/guide/fragments/communicate`]; `fragment:1.5.4` has it. The bundle can carry
  `confirmed` and the ids as `ArrayList<String>`; the pending action (a lambda) cannot be parceled, so it lives in the activity: it survives a
  rotation only if kept in a retained holder (it is not), and is dropped with process death, where a result without a pending action is ignored.
- `DialogFragment.onDismiss` runs in every case (back, outside tap, swipe, after a confirm), so a cancel must be reported from `onDismiss`
  unless a confirm was already sent.

### Sources
- developer.android.com (reference): `content/Intent`, `androidx/core/content/IntentCompat`, `content/ContentResolver`, `os/ParcelFileDescriptor`,
  `system/Os`, `java/nio/channels/FileChannel`, `content/Context#stopService`, `media/MediaMetadataRetriever`; guides: `topics/manifest/data-element`,
  `training/secure-file-sharing/share-file`, `guide/fragments/communicate`, `develop/background-work/services/fgs/restrictions-bg-start`,
  `about/versions/14/changes/fgs-types-required`.
- AOSP `android-14.0.0_r1`: `services/core/java/com/android/server/uri/*`, `.../wm/{ActivityStarter,ActivityRecord}.java`,
  `core/java/android/content/{Intent,ContentResolver,ContentProvider,SharedPreferences}.java`, `.../res/AssetFileDescriptor.java`,
  `.../os/ParcelFileDescriptor.java`, `.../app/{Instrumentation,SharedPreferencesImpl}.java`; libcore (`lineage-21.0`): `FileInputStream`, `FileChannel`,
  `FileChannelImpl`, `IOUtil`, `FileDispatcherImpl`, `Os`; HttpCore 5.3.6 sources: `HttpCoreContext`, `EndpointDetails`, `ServerHttp1StreamDuplexer`,
  `ServerHttp1StreamHandler`, `ServerH2StreamMultiplexer`, `AbstractBinAsyncEntityProducer`.

## Android TV, themed icon and the audio permission

- The manifest sets `android:banner` (`drawable-xhdpi/tv_banner.png`, 320x180 px, the icon background with the play button and the app name) and declares touchscreen, leanback, wifi and microphone as `required="false"`. `dataExtractionRules` (everything excluded) and `fullBackupContent="false"` repeat the existing `allowBackup="false"` for Android 12 and later.
- `mipmap-anydpi-v26/ic_launcher*.xml` have a `<monochrome>` layer (`drawable/ic_launcher_monochrome.xml`, a play button in a ring); it is used for themed icons on Android 13 and later. Keep the `-v26` folder name: an unqualified `mipmap-anydpi` made one launcher show the legacy icon.
- `RECORD_AUDIO` is declared because `AudioPlaybackCaptureConfiguration` needs it. `ServerListFragment` asks for it before the screen capture permission when a stream button is tapped (`StreamingPermissionHelper.hasRecordAudioPermission`, `onRecordAudioResult`); a denial switches the stream buttons off like a denied screen capture.
- Check the TV banner without a TV: `aapt2 dump badging app-debug.apk` shows `banner=` and `leanback-launchable-activity`; the real look needs an Android TV emulator image.
