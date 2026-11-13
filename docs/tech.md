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

#### Browse speed of a big folder (issue #299, Home Assistant timeout)

Report: a Browse of a folder with 5570 files (Home Assistant: one request, `RequestedCount=0`, waits 5 s, then hangs up, our server logs `Broken pipe`) logged
`SAF browse 11009995661 list=5570 entries from cache in 0 ms, page 0-5569 in 6783 ms`. The listing was cached (0 ms), so the 6783 ms are the page build: **1.22 ms per entry**
(derived: 6783 / 5570), and the DIDL writing comes after that number. The answer must stay the complete list (no cap, no virtual "more" folders); only speed can change.

Where the time goes. *Measured* = timed on a desktop JVM in the unit test environment (this sandbox, JIT warm after the first rounds, so a phone is slower by a factor of
roughly 3 to 10, which is an estimate). *Derived* = read from the code, cannot be timed without a device.

| # | Cost per entry (before) | Where | Evidence | After |
|---|---|---|---|---|
| 1 | `contentDirectory.getIpAddress()` for every resource url: `InterfaceResolutionHelper.getNetworkInterface` reads two preferences, splits the blacklist and calls `NetworkInterface.getNetworkInterfaces()` (a `getifaddrs` call and one Java object per interface and address) | `ContentBrowser.getUriString` (called from `SafFolderBrowser.createItem`), `InterfaceResolutionHelper.java:getNetworkInterface` | Measured: 8 to 21 us per call on a box with 2 interfaces (48 to 118 ms for 5570). Derived for a phone: more interfaces (wlan, rmnet_data0..7, dummy, p2p, lo, ...), a netlink dump per call, so 0.2 to 1 ms; this and (2) are the only per entry calls that leave the process, and together they fit the 1.22 ms | once per Browse (`SafFolderBrowser.itemFactory`), `SafItemFactory` builds the url from a prefix |
| 2 | `SafDirectoryLister.canRead` -> `checkCallingOrSelfUriPermission`, a Binder call into the system server, for every file (`browseItem`) and every sub folder (`childFolders`) | `SafFolderBrowser` | Derived: one Binder round trip is 50 to 150 us on an idle phone and more while the system server is busy (the preload asks the provider at the same time). Not measurable on the JVM | once per folder and Browse (`SafReadAccess`) |
| 3 | `SAFCacheManager.getMetadata`: the global lock per entry, with a cold cache one entry built, serialized and written into the write buffer; every 250 changes a flush commit that rewrites the whole preferences XML | `SAFCacheManager.getMetadataInternal`, `scheduleFlushLocked` | Measured: 14 to 70 us per entry cold (78 to 394 ms for 5570, with mock `Uri` overhead of a few us), 35 to 42 commits for two passes over 5570 entries; warm 5 to 14 us. A phone is 3 to 10 times slower, so 0.3 to 1.5 s cold (derived). The lock hold times are short, the preload never extracts while holding the lock (`extractAndCache` calls the extractor outside `synchronized`), so there was no long lock wait | `getMetadataBulk`: lock per 256 entries, one flush at the end. Measured: 88 to 150 ms cold, 47 to 83 ms warm, 1 commit |
| 4 | `createItem` rest: `replaceAll` regex (compiles a pattern per call), `MimeType.valueOf` per entry, mime to extension (`MimeTypeMap`; with no extension a D line per file), protocol info lookup, `YaaccLogger.v("..." + x)` strings built per entry although the level is off (`Generated URI`, `Item created`, `CACHE_HIT_*`, `Created shortId mapping`), `formatEpochMillisAsDate` per entry | `SafFolderBrowser.createItem`, `SAFCacheManager` | Measured together with the Cling objects (`toClingItem`, `DIDLObject.Property`): 7 to 140 ms for 5570 (large spread: JIT warm-up) | `SafItemFactory`: precompiled pattern, per mime type work once, date per day once, no per entry log strings (`YaaccLogger.isVerbose()`) |
| 5 | DIDL: `DIDLParser.generate` builds a DOM and runs the platform XML transformer. 5570 items are 2.5 MB and about 33000 elements | `YaaccContentDirectory.browse` | Measured: 79 to 327 ms on the JVM (the Android DOM and Xalan transformer are slower: derived 0.5 to 3 s). Then Cling's SOAP layer escapes the 2.5 MB once more (not changed) | `DidlWriter` for 300 objects and more. Measured: 9 to 59 ms (10 to 20 ms warm) |
| 6 | The preload (`SAFCacheManager.traverseAndCache`) asks the same provider about every file (`isDirectory()`, `getType()`) and also called `file.getName()` for a verbose log line per file even with the level off: three provider queries per file, 36.7 s for the `.thumbnails` folder in the owner's log | `SAFCacheManager.java:traverseAndCache` | Read from the code. No lock is held while it queries (so it did not block the Browse on `lock`), but it competes for the provider, the Binder threads and the processor | the per file log is guarded; the preload and the queued duration tasks wait (at most 10 s each time) while a Browse of the SAF content runs (`SafBrowseGate`) |

Not on the list (checked): `awaitLoaded()` is a latch read after the load; `readStoredLocked` is one `HashMap` read in the preferences; `createShortIdLocked` is two map puts;
the `SafCacheWriteBuffer` is two map operations per entry; sorting is the key-per-entry sort of `SafListingSupport` (a few ms for 5570); `SafDirectoryLister.childDocumentUri`
builds an `android.net.Uri` per page entry (derived 5 to 10 us each, 30 to 60 ms for 5570, left as it is because the short ids and cache keys depend on that exact string).

As built:
- `SafReadAccess` (plain JVM): asks the system for the folder's own uri once and uses the answer for every child whose uri is in the same tree
  (`<authority>/tree/<tree id>/document/`). **Assumption**: a grant of a tree (what the system picker gives, persisted) covers every document below it, so a granted
  folder means granted children of that tree. It is used in that direction only: a folder that is not granted, a child outside the folder's tree (also a tree id that only
  starts the same), or a folder uri that is not a tree document uri make the child be asked on its own, exactly as before. A child without a mime type is "not readable" without
  any call (`DocumentFile.canRead()` semantics). One `SafReadAccess` per folder and Browse (the browser instance lives for one Browse). Not changed: `browseMeta` asks for the one document.
- `SAFCacheManager.getMetadataBulk(List<SafMetadataRequest>)`: the same results as `getMetadata` per request (same order, null where the single call answers null, transient ids before the
  load on the main thread). The lock is taken per 256 entries (`BULK_CHUNK`, bounds the wait of the duration tasks and the preload to a couple of milliseconds even on a phone), the write
  is only scheduled when the request ends, so a cold folder is one commit with all new entries instead of one per 250 changes.
- `SafItemFactory` (plain JVM) builds the item from the listing entry and the metadata; the resource url prefix `http://<ip>:<port>/saf/` is looked up once per Browse.
- `DidlWriter` (plain JVM) writes the DIDL for answers of 300 objects and more if every object is a plain item (only `dc:date` as extra property, resources) or a plain folder, and every string is
  made of characters whose escaping is certain. Anything else (other properties, `refID`, write status, search/create classes, desc metadata, control characters, surrogates, C1 characters, U+2028/9, U+FFFE/F)
  returns null and the answer is written by `DIDLParser` as before. `DidlWriterTest` compares both writers over 2540 generated objects with special characters (`& < > " '`, umlauts, CJK, `]]>`, a CDATA start, 300 character
  titles, empty strings): identical, and byte identical where the attribute order is the same. **Not provable on the JVM**: the order in which a serializer writes the attributes of one element. The JVM's DOM sorts them
  alphabetically (`childCount, id, parentID, ...` and `duration, protocolInfo, size`), the writer uses the order of the generator (`id, parentID, childCount, restricted, ...`, `protocolInfo, size, duration`), which is the
  insertion order a DOM keeps on Android as far as known; no XML parser depends on it. The root declares `xmlns`, `xmlns:dc`, `xmlns:sec`, `xmlns:upnp` in that order (as the JVM serializer does).
- `SafBrowseGate`: `YaaccContentDirectory.browse` opens it for a Browse that goes to the `SafFolderBrowser` (until the DIDL is written); the preload waits between two files and the
  background duration tasks before an extraction, each time at most 10 s, the time waited does not count as a stall of the preload. A Browse never waits for them.
- Logging: no log call that builds its text with `+` runs per entry unless its level is on (`YaaccLogger.isVerbose()` / `isDebug()`).

Log lines (D level):
- `SAF browse <id> list=<n> entries from cache|provider in <ms> ms, page <a>-<b> in <ms> ms (permissions <ms> ms in <folder checks>+<entry checks> checks, metadata <ms> ms, ip <ms> ms, items <ms> ms)`;
  the four times are the parts of the page time (permissions of the files and folders of the page, `getMetadataBulk`, the ip lookup, building the items with the Cling objects). `<folder checks>+<entry checks>` is
  `1+0` for a folder the app may read; many entry checks mean the folder was not granted.
- `CDS didl <n> items <bytes> bytes in <ms> ms (direct|dom)`: the writing of the answer (`direct` = `DidlWriter`, `dom` = `DIDLParser`), bytes are UTF-8 bytes of the XML before Cling puts it into the SOAP envelope.

Budget for 5570 entries on a phone (derived from the measured JVM numbers times 3 to 10, NOT measured on a device): permissions 1 Binder call, ip 1 lookup, metadata 0.1 to 0.8 s cold and 0.05 to 0.4 s warm,
items 0.1 to 0.3 s, DIDL 0.1 to 0.4 s: well under 2 s warm, about 1.5 s cold, where before the page alone took 6.8 s.

What remains: the first listing of a slow or network provider (`SafDirectoryLister.load`, one query with N rows, nothing here helps; a background listing would be the next step); the SOAP envelope that Cling builds around the
2.5 MB result (it escapes the text again); the same per entry `getIpAddress()` in the MediaStore browsers (`ContentBrowser.getUriString` is used by them for every item: not changed, out of scope);
`childDocumentUri` per page entry. Not verified without a device: every phone number above, whether the system's answer for a tree document equals the answer for the tree for all providers (the assumption of `SafReadAccess`), and the attribute order of
the Android DOM serializer.

Manual test: restart the app, set the log level to D, let Home Assistant (or any control point) browse the 5570 file folder once (navigate there from another folder, so the metadata cache is cold). The log shows
`SAF browse ... (permissions ... in 1+0 checks, metadata ..., ip ..., items ...)` and `CDS didl 5570 items ... bytes in ... ms (direct)`; the first Browse should answer in about 1.5 s, a second one (within 30 s) faster. If `permissions` still shows
thousands of checks the folder is not granted (its files are marked `[X]`).

Server configuration screen (shared folders tree): it used the same per-property `DocumentFile` queries on the main thread (`exists()` per child
on open; per bound row `getName()`, `isDirectory()` twice and, for folders, a full `listFiles()` for the expand symbol). Now one worker thread of the
activity reads a folder with `SafDirectoryLister` (shared cache) or `File.listFiles()` into plain `TreeNodeData`; binding asks nothing, every folder shows
the expand symbol until it was read and is empty, stale results are dropped by a per-node and a tree generation, children are inserted as ONE
`notifyItemRangeInserted` (`TreeNodeManager.Change`, `ArrayList`). Queries for N files: before about N + 3 + 7V on the main thread when opening (V visible rows)
and about 3N when scrolling; now 3 on the worker (0 within 30 s) and 0 on the main thread. D log: `SAF tree <uri> children=<n> loaded in <ms> ms`.

- The shared-folder tree of the server configuration shows folders only (`TreeNodeFactory`): only folders can be selected as a shared path, files were shown without a check box. A folder that holds nothing but files shows the expand arrow until it was opened once and then reads as empty.

- **Permission of sub folders and "[X]".** The grant of a folder picked with the system picker covers everything below it, but only for uris of that tree (`.../tree/<picked>/document/<child>`). Sub folders are therefore addressed as `SafDirectoryLister.childDocumentUri(parent, documentId)` and their permission is checked on that uri; `DocumentFile.fromTreeUri` accepts it. Before, a sub folder was addressed as a tree of its own (`buildTreeDocumentUri(authority, childId)`), which no grant matches, so the sub folders of a picked folder could show `[X]` although they are readable. `[X]` now means the app really holds no permission for the folder (e.g. a grant that was revoked).

### New files and folders in a shared SAF folder (issue #299)

Report: after a torrent downloaded a folder tree into an included SAF folder, the new content only showed up after removing and adding the
folder again. Cause (read from the code, not reproduced on a device): **the server listed the new content, but no control point was told.**
`SafListingCache` re-lists a folder at most 30 s after it was loaded, so a Browse sees new files and sub folders at once. Control points
(VLC, BubbleUPnP, Kodi, Windows, Home Assistant, yaacc as a client) cache Browse results and refresh them only when the ContentDirectory's
`SystemUpdateID` changes (GENA event or `GetSystemUpdateID`). Before, the number was raised only when the *list of shared folders* changed
(`de.yaacc.SAF_PATHS_CHANGED`, receiver `YaaccUpnpServerService.safPathsChangedReceiver`, line 215, which calls `notifyContentDirectoryChanged()`, line 225; sent
by `MediaPathFilter.saveSafPathes`, line 74-81). Removing and adding the folder is exactly such a change - which is the user's workaround ("rescan").

| | Candidate | Real? | Evidence |
|---|---|---|---|
| (a) | `SAFCacheManager` metadata holds stale or wrong data for files that appear later | Partly (not the cause) | `getMetadataInternal` (SAFCacheManager.java:237-282) is keyed by the document uri only and never compares size or date again: a file seen while it was still written keeps its first `fileSize` (mime type comes from the extension first, `extractMimeType`, line 466, so a `.part` file and the renamed file are different uris and different entries; the duration of an audio/video file seen half written is extracted once and kept). The size was used for `res@size` of the DIDL item (`SafFolderBrowser.createItem`); **fixed**: the size of the (fresh) listing is used when the provider has one. The HTTP handler does not use the cached size. The cached duration of a half written file is **not** changed. |
| (b) | `SafListingCache` TTL/LRU keeps a listing too long | No | `LISTING_TTL_MILLIS` = 30 s (SafDirectoryLister.java:52), checked on every access (SafListingCache.java:112), so a listing is never older than 30 s when a request uses it; the root listing of a selected tree is cached under the same rule (it goes through `SafFolderBrowser.listing`, line 143, like every folder). Invalidated by `SafDirectoryLister.clearCache()` (clear button, permission given up, SAF path preferences). A Browse after a bump always lists again if the entry is older than 30 s. |
| (c) | Server control tree view shows a stale tree | Yes, cosmetic, not the cause | `YaaccUpnpServerControlActivity.readChildren` (line 345) reads a folder once when it is opened and keeps the children until the tree is rebuilt. It only affects the selection screen, not what is served. Not changed. |
| (d) | "Selected" semantics: sub folders of a granted tree not served | No | The SAF root lists the selected paths (`SafFolderBrowser.browseContainer`, line 283-296); every folder below is listed from the provider with all its sub folders (`childFolders`, `SafListingSupport.directories`), no sub folder needs to be selected. A check box on a sub folder (`TreeViewHolder`, line 128-141) only adds it as one more top-level entry. **Ticking or unticking a folder** (`saveSelectedSafPathes` sends no broadcast) also raises the `SystemUpdateID` now: `YaaccUpnpServerService.onSharedPreferenceChanged` asks for it for both SAF preference keys. Adding a folder writes both preferences and sends the broadcast; all requests go through `DebouncedAction` (1 s), so one user action is ONE change. |

**Start value (issues #298, #299).** `YaaccContentDirectory` used to start the `SystemUpdateID` at 0 on every server start, so content that changed while the server was stopped was not announced to a control point that last saw 0. It starts with `SystemUpdateIds.startValue(now)` now: the seconds since the epoch as an unsigned 32 bit number (at least 1), so every start is higher than the one before (changes while the server runs increment it by one each, far fewer than seconds pass between starts). The two-argument constructor `YaaccContentDirectory(Context, long)` lets tests start at 0. No setting: it costs nothing.

As built:
- `SafChangeDetector` (plain JVM): one 64 bit fingerprint per watched folder - the sum of one mixed hash per entry (name, document id, last modified,
  size, directory flag) combined with the entry count, so the order the provider returns the entries in does not matter. The first listing of a
  folder is the baseline, not a change; a listing that failed (query error, permission lost, not a directory: `SafDirectory.isCacheable()` false) is neither a change nor a baseline and keeps
  the old fingerprint. Changed folders are collected; `takeDue()` hands them all over at once (many changed folders = ONE bump) if the last bump is at least
  15 s ago (`MIN_BUMP_INTERVAL_MILLIS`), otherwise the change waits (`delayUntilBump()`), it is never dropped. `takeDue()` starts the interval at once, so a change seen while the
  event is being sent waits for the next one; `giveBack()` undoes it when no bump was possible (no ContentDirectory yet). At most 16 folders are known, the least recently watched is forgotten first.
- `SafWatchSet` (plain JVM): the roots of the selected SAF trees (`MediaPathFilter.getSelectedSafPathes`) always, then the folders a Browse listed in the last 15 min
  (`SafFolderBrowser.listing` reports them), newest first, 16 folders at most (roots first). No recursion: a library can have 100000 files, and the directory entry of
  a direct sub folder carries its last-modified date, which a file system based provider (the external storage provider) changes when a file is added to it, so the root's fingerprint then also notices a new file one level down (provider dependent, not verified on a device).
- `SafChangeMonitor` (plain JVM): `runPass(roots)` lists the watched folders one by one with `SafDirectoryLister.refresh` (the provider is asked even if the cached listing is
  younger than 30 s, and the fresh listing REPLACES the cached one - `SafListingCache.refresh`, which keeps the old listing if the new one failed and does not put a listing back
  that a `clear()` overran), compares, and calls `flush()`. `onBrowse(folder, listing, fromCache)` is the hook of a normal Browse: it marks the folder as watched and, if the listing was
  just read from the provider (or the folder is not known yet), compares it with the earlier one - no extra query. A difference asks the runner for a `flush()` (it never bumps on the Browse thread: that thread holds
  the service lock of the Browse action). `flush()` bumps through `YaaccUpnpServerService.bumpContentDirectory()` = `YaaccContentDirectory.changeSystemUpdateID()` (raise and event to the subscribers) and
  logs `SAF change detected in <n> folder(s), SystemUpdateID bumped` (D); one `SAF change pass: ...` line (V) per pass, no per-file lines.
- `SafChangeRunner` (Android glue, owned by the service): one daemon thread (`saf-change-check`, a `ScheduledThreadPoolExecutor`), first pass 10 s after the start, then
  30 s after the end of the previous pass (`SafChangeMonitor.pauseAfterPass`: three times the duration of a pass if that is longer than 10 s, so a huge folder
  does not keep the provider busy). It exists only while `shouldWatch(server on, content provider on, serve SAF folders on, at least one selected SAF folder)`; `update()` is called when the
  device is (re)created and when the SAF preferences change, `stop()` in `onDestroy()` and `restartServer()`. Otherwise there is no thread and no query. A pass and the bump run on that thread, not on the main thread and not on a
  Cling or HTTP thread. When the shared folders change (`SAF_PATHS_CHANGED`, or the SAF preferences) the monitor forgets everything it saw (that change is bumped by the existing code).
- Cost of a pass: for each watched folder (at most 16) the three queries of `SafDirectoryLister.load` - two single row queries (`isDirectory()`, `canRead()`) and ONE query for all children. `SafDirectoryLister.MAX_CACHED_FOLDERS` was raised from 8 to 16 so a pass does not push the
  folder a control point is paging through out of the cache (the sum of the entries, 100000, still bounds the memory).
- Delay: a change is seen by the next pass (up to 30 s, longer after a slow pass), the bump follows at once, or after the rest of the 15 s interval if there was a bump just before. So about 30 to 45 s in the worst case.
  A file that is written all the time (a download, a copy) changes size and date in every pass: the control point is told about every 15 s to 30 s as long as that goes on.

Why no `ContentObserver` (decision): polling is the baseline either way, and the observer cannot replace it. The documentation of `DocumentsProvider.queryChildDocuments`
(developer.android.com/reference/android/provider/DocumentsProvider) says a provider notifies only "to support change notifications" by calling `Cursor.setNotificationUri` with
`DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)` - a provider *may* do that, nothing says the external storage provider or any third party provider (Drive, Nextcloud, SD card, Samba apps) does. The uri
is the plain one, not the `.../tree/<id>/document/<id>/children` uri that `buildChildDocumentsUriUsingTree` gives (the one the listing uses), so an observer would have to be registered per folder on a provider specific uri, and a download that writes
all the time would send an event per write that needs the same coalescing. Not verifiable without a device and a provider; a wrong guess means a feature that works on one phone only. **No `FileObserver`**: a SAF tree has no file path, and inotify would not see other providers.

Limits: only the roots and the lately browsed folders are watched; a sub folder nobody browsed within 15 min that a control point still shows from its cache is not noticed (a change in a direct sub folder of a watched folder is, through its date). More than 16 selected roots: only the first 16 (alphabetically) are watched. A change
of a folder that can not be listed (provider gone, permission lost) is not reported. The check does not run while the process or the device sleeps (a `ScheduledThreadPoolExecutor` uses `System.nanoTime`, no wake lock, no alarm).
`YaaccContentDirectory.changeSystemUpdateID()` after a service lock timeout (5 s) throws a `RuntimeException`: the monitor keeps the change and retries with the next pass.

Not verified without a device: how fast and how correctly real providers report `last_modified` of directories and of files that are still being written, and which control points react to a `SystemUpdateID` event (the event
itself, its delivery to a subscriber and the numbering are covered by `LocalServicesEventsTest`; whether VLC, Kodi, BubbleUPnP, Windows or Home Assistant then browse again is up to them).

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
(cleanup preserved) and the size; no existing constructor changed. (Later replaced: the adapter overloads are gone, `IconDownloadTask(ImageView, String requestKey, int, int, Listener)`
tells the adapter when a task ends, also when it was cancelled; see "Content list: sort and thumbnails off the main thread" in "Large SAF folders".)

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

- **Content list: sort and thumbnails off the main thread.** The client-side sort of a finished folder (`setAllItemsFetched`) no longer runs in the main thread:
  `BrowseSorter` (plain JVM, the former comparators, test compares with the old code on random data) sorts a COPY on one small daemon thread owned by the adapter
  (idle after 5 s, `release()` in `ContentListFragment.onDestroyView` shuts it down, re-created on demand); the main thread swaps the list and calls
  `notifyItemRangeChanged(first, count, payload)` for the changed range only (nothing if the order did not change; the payload keeps the rows from animating).
  A generation counter drops the result after `clear()`, `cancelRunningTasks()` (sort mode/direction change, back), `release()` or an `addAll` meanwhile (that one sorts again);
  a scroll target (`scrollToPositionId`) that arrives while the sort runs waits for the swap, because it is a position in the sorted list. D log:
  `Browse sort n=<N> mode=<mode> in <ms> ms off the main thread`. `objects` is an `ArrayList` now (it was a `LinkedList` read by position per bind).
  Binding: one `IconDownloadTask` per recycled row (`ViewHolder.thumbnailTask`) only when the item has a thumbnail, the previous one is cancelled on re-bind and in
  `onViewRecycled`, the same request key (uri + size, `ThumbnailSlot`) is not loaded or reset again, a decoded thumbnail in the memory cache is shown at once, and
  finished/cancelled tasks leave `asyncTasks` (`IconDownloadTask.Listener`; `cancelRunningTasks` empties the list). `IconDownloadCacheHandler` is a `ByteLruCache`
  (`Bitmap.getByteCount`, 1/16 of the heap, 8 to 32 MB; it used to count one unit per bitmap against a byte limit). The tinted type icons are resolved once per icon and
  night mode (`ConfigKeyedCache` of drawable constant states, a new drawable per row). `SquareImageView.onMeasure` returns at once for an exact width.
  `getObjects()` (play all) can return the unsorted list during the few milliseconds of the sort.

- **SAF metadata cache: own file, batched writes, no I/O on the main thread.** `SAFCacheManager` kept everything (`saf_cache_<document uri>` = serialized `SAFMetadata`
  with duration and short id, `saf_id_counter`) in the DEFAULT preferences with one `apply()` per extracted file and per new short id; each `apply()` rewrites the whole
  XML file, `QueuedWork.waitToFinish` can block the main thread at activity stop, and the constructor (first use, possibly on the main thread) deserialized the whole map.
  The short id maps are NOT stored separately: they are rebuilt from the metadata entries (a folder's id, created by `getOrCreateShortId` alone, only moves the counter and does not
  survive a restart; unchanged). Now: the cache lives in the preferences file `saf_cache` (same keys). The constructor does no I/O; the cache's own thread `SAFCacheIO`
  loads it: first the one-time, idempotent migration of `saf_cache_*` and `saf_id_counter` out of the default preferences (`SafCacheMigration.plan`: the new file is committed first,
  then the old keys are removed; an interrupted run is finished by the next one, a value already in the new file is never overwritten, the counter takes the larger value), then
  the maps (`SafShortIds`; the counter never falls behind a restored numeric id). Changes are collected in `SafCacheWriteBuffer` and committed with `commit()` on `SAFCacheIO` in one
  go when `SafCacheFlushPolicy` says so: after 250 changes or 3 s after the first unwritten change, and at the end of a preload; the app exit (`Yaacc.clearCache`) waits at most 1 s for
  the last write (`flushIfCreated`). Reads come from memory (LRU of 1000, the buffer, the preferences' own map). A caller on the main thread before the load ended does not wait
  (`SafCacheLoadGate`): `getMetadata` returns metadata with a transient id (`t1`, valid in this process only) and persists nothing, `getUriForShortId` returns null; other threads wait up to 30 s.
  `lruCache` is only touched under the manager's lock now (the preload thread used it without the lock). `clearCache()` / `removeCachedUnder()` clear memory at once and the file on the
  I/O thread behind the load. A short id created less than 3 s before the process is killed is lost together with the counter that went with it, so after the restart the same numbers can be given to other uris
  (the metadata entries and the counter are in one commit, so the stored state itself is always consistent). The in-memory LRU does not trim the file any more: its trim (`getEvictedKeys()` was drained by the `isEmpty()` check before `trimCache()`
  ran) never removed anything in practice, and removing entries beyond the 1000th would make their short ids unknown after a restart. D logs: `SAF cache written: <n> puts, <m> removes ... in <ms> ms`, `CACHE_MIGRATION: ...`.
  The preload (`extractAndCache`) used to store a bare duration string ("00:03:12") under `saf_cache_<uri>`; `getMetadata` expects the serialized `SAFMetadata` there, could not parse it, and built and extracted again.
  Now every writer of the key writes the serialized `SAFMetadata`: the preload updates the entry of the file (duration set; mime type, size and short id of an existing entry stay, a new entry gets the id the uri has or a new one) and the background task of
  `getMetadata` uses the same `storeDurationLocked`. A file needs a preload extraction only when it has no entry or an entry with an empty duration; the queued background task skips a file that got its duration in the meantime. Readers
  (`getMetadata`, deprecated `getDuration`) accept a bare value stored by an older version (`SafCacheEntries`, plain JVM) and upgrade it in place without an extraction (D log `CACHE_UPGRADED`); `getDuration` returned the serialized
  metadata as the duration before. Key names and short ids are unchanged. Test seam: `SAFCacheManager.DurationExtractor`. A file the retriever cannot read stays without a duration and is tried again at the next app start (not in the same process).

- **Selection of SAF objects (#318).** Metadata, size, both listings and `/saf/<key>/<name>` only answer for a document in a folder that is selected for the server (`SafSelection.contains` / `SafDocumentUris.isInSelectedTrees`: the selected folder, below it by path-like id, a wholly selected tree, or `DocumentsContract.isChildDocument` for ids that are no paths); a short id of a deselected folder answers like an unknown id.

- **BrowseMetadata of SAF objects.** `SafFolderBrowser.browseMeta` used to Base64-decode the short id of `SAF_PREFIX + shortId` (it is a number mapped by `SAFCacheManager.getUriForShortId`, not an encoding), so a
  sub folder or file got a wrong or no answer, and an item got its own id as parent. Now: id -> uri via the short id (`SafDocumentUris.shortIdOf`), ONE provider query for the document
  (`SafDirectoryLister.queryDocument`: name, mime, date, size), folder or file from the mime type. Parent: the SAF folder for a granted tree (document id = tree id), for the top of a selection (a selected folder, `SafDocumentUris.isSelectedFolder`) and for a uri that is not a tree
  document; else the parent document id from the path-like id (`primary:DCIM/Camera` below the tree `primary:DCIM`, no query) or, for opaque ids, from `DocumentsContract.findDocumentPath`
  (API 26, document ids from the top of the tree to the document), written like `buildDocumentUriUsingTree` and mapped to its short id (`getOrCreateShortId`), so it equals the id the listing gave that folder.
  A sub folder is built like in the listing of its parent (`[X] ` prefix and restricted flag from `canRead`), with its child count from the cached listing; a granted tree like in the SAF folder's
  listing; a file by `createItem`. An unknown id or a document that is gone gives error 701 (`YaaccContentDirectory.browse`: a null result of any `browseMeta` is "no such object" now, it was
  an empty DIDL with count 1). D log: `SAF meta <id> folder|item parent=<id>`. `TotalMatches` of a BrowseMetadata: see "BrowseMetadata counts" below.
  Not verified on a device: `findDocumentPath` for providers with opaque ids.

- **BrowseMetadata counts.** `YaaccContentDirectory.browse` called `browser.getSize` first for every browser, so a BrowseMetadata answered `TotalMatches` = the child count of the folder (and listed the folder for it). UPnP ContentDirectory: BrowseMetadata returns the one
  object, `NumberReturned` = `TotalMatches` = 1. Now `getSize` is called for BrowseDirectChildren only (before `browseChildren`, as before); BrowseMetadata answers 1/1, and an object that does not exist still gives 701 (`browseMeta` is asked first, `getSize` is not
  called for it). Unchanged: DirectChildren counts, the test-content branch (it answers `TotalMatches` 1 for its folders, found on the way), an unknown BrowseFlag (handled like DirectChildren). There is no Search action, so `browse` is the only place with these counts.
  Test: `YaaccContentDirectoryBrowseCountTest` (a fake `ContentBrowser` through the package-private `findBrowserFor`).

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
  The same chain (`yaaccUpnpServerService.getNetworkDeviceListener().getHttpRequestSender()`) was in `searchDevices` (`getUdpTransiver`), `getMute`, `setMute`, `getVolume`, `setVolume` and
  `AVTransportPlayer.getHttpRequestSender()` (used by about 15 AVTransport callbacks): all use `UpnpClient.getHttpRequestSender()` (public, null-safe) now; mute/volume/search log one line
  (W/D) and return the default (false, 0, nothing), the player passes the null on. `ActionCallback.run()` (base of every action callback) turns a missing sender into the callback's own
  `failure(...)` and a `RuntimeException` out of `SendingAction` into a failure as well, so waits like `AVTransportPlayer.waitForActionComplete` (spins until `actionFinished`, 30 s watchdog) are
  released by the failure path instead of an NPE that kills the pool thread. `SubscriptionCallback` (not used by any caller at present) fails/skips on a missing sender.
- Resume (suspend/Doze): the registry expires remote devices by wall clock, so after a long sleep the provider/renderers can be missing, and
  `UpnpClient.isInitialized()` is false while the `NetworkDeviceListener` is disabled (`getDevice` then returns null for every id). Activity `onResume`
  calls `UpnpClient.refreshAfterResume()` (worker thread): `NetworkDeviceListener.reenableIfStale()` does `disable()`+`enable()` if the listener is
  disabled, the active network is not `currentNetwork`, or a receive loop ended (`isReceiving()`); then M-SEARCH if that happened or the last one is
  >= 30 s old (`ResumeRefreshPolicy`). Log lines start with `Resume:`.
- `ContentListFragment` keeps the folder when its server leaves the registry or is missing on `onResume`/restore (`loadFailed` is set); `deviceAdded/Updated`
  of the same UDN (`FolderServerMatch`) or the next resume/network event continues it (`shouldReloadNow`, no debounce). Another server replaces it.
- Registry protocol handler: `NetworkDeviceListener.enable()` builds a new `UpnpProtocolHandler` (new `MulticastReceiver`/`UdpTransiver`; the `HttpRequestSender` is kept) on every
  (re-)enable and `disable()` closes the old sockets, but `RegistryImpl.upnpProtocolHandler` was set once in `YaaccUpnpServerService.onStartCommand`, so after the first network
  change the registry's renewals, ALIVE and BYEBYE went through the first handler (closed sockets; null if the service had started without a network). Now `enable()` sets the new handler in the
  registry (inside the listener's lock, before the receivers start) and `disable()` sets null; the field is `volatile`. The registry copes with null: `LocalItems.advertiseAlive/advertiseByebye`
  skip, `RemoteItems.renewOutgoingSubscription` postpones (the subscription stays due, the next maintenance run renews it with the new handler) and the shutdown unsubscribe skips (they used to
  dereference it). Local devices are announced again (`Registry.advertiseLocalDevices()`, on its own thread) after the listener was enabled for a new network by the network callback or the
  resume check; the wifi-state path already re-created the device, which announces on add. Verified by reading and with mocks (`NetworkDeviceListenerHandlerTest`, `RemoteItemsHandlerTest`).
  Not verified without a device: that other devices see the ALIVE after a real network change.
- HTTP server protocol handler: `YaaccUpnpServerProtocolRequestHandler` got the `UpnpProtocolHandler` of the moment `createHttpServer` ran, once; a service that started without a network kept null and every sync request (control, subscribe, event, retrieval) threw
  a `NullPointerException` (answered 500), and after a network change the captured handler was the old one. It takes a `Supplier<UpnpProtocolHandler>` now (`YaaccUpnpServerService.currentProtocolHandler()` = `registry.getUpnpProtocolHandler()`, the live volatile field)
  and resolves it once per `process()`. No handler (or a supplier that throws): `503 Service Unavailable` and one W log line (`No protocol handler available, answering 503 ...`); a handler that has no protocol for the request (`createReceivingSync` returns null): 501
  instead of an NPE. Test: `YaaccUpnpServerProtocolRequestHandlerTest`. Not changed (found on the way): `syncProtocol` is an instance field of the one handler shared by all requests, so `responseSent`/`responseException` can refer to another request's protocol under
  concurrency. Not verified without a device: the 503 on a real client while the network is down, and a request in the moment of a network change.
- `RegistryImpl` had two accessor pairs for the one `volatile upnpProtocolHandler` field (`get/setProtocolHandler` and `get/setUpnpProtocolHandler`, the latter declared in `Registry`). The first pair is removed, `RemoteItems` uses the interface's `getUpnpProtocolHandler()`.
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
    devices (10 s poll per remote renderer: transport state and track title);
  - listens for player add/remove through
    `UpnpClient.addPlayerListChangeListener` and rebuilds the list;
  - polls every 2 s for the local device's status (it has no monitor feed)
    and for changed session titles or art, rebinding only rows whose shown
    state changed.
- **Volume and mute are live** (`ReceiverVolumeLiveUpdater`, started and stopped
  with the live updates; the Receiver tab does the same from `onResume()` /
  `onPause()` through the same `ReceiverRowBinder`). Before, they were read once
  per bound row (`DeviceVolumeStateLoadTask` / `DeviceMuteStateLoadTask`), so a
  change in Home Assistant showed only after the row was rebound. Now:
  - *This phone* (the local row, yaacc's own renderer): `SystemVolume.addChangeListener`
    shows a change made through the app or the RenderingControl service at once
    (the listener only posts to the main thread; it is removed in `stop()`), and
    each 2 s tick reads the music stream again, because the hardware keys are only
    broadcast while a controller is subscribed (`SystemVolumeBroadcastWatcher`).
  - *Other receivers*: `GetVolume` / `GetMute` as plain SOAP actions, no GENA
    (the callback address is often unreachable behind a proxy), only for rows that
    are on screen and for receivers that have the actions. A pool of 3 daemon
    threads, one request per receiver at a time. `ReceiverStateRefreshPlan`
    (plain Java, `ReceiverStateRefreshPlanTest`) decides: a receiver is read at
    most every 6 s (every 3rd tick), is skipped while its request runs, and after
    failures the pause doubles (12 s, 24 s, 48 s, 60 s max).
  - *The user wins*: a read is dropped while a slider is held, for 3 s after the
    user's last action on the row, and when the user acted after the read was
    started. Showing a read sets the progress / checked state programmatically and
    never sends it back: the slider listener only sends `fromUser` changes (it is
    set in `ReceiverRowBinder.bind`, not after the first load, and no longer
    de-duplicates against a stale `lastVolume`).
  - Status updates (`RendererStatusMonitor`, the local poll) rebind a row with the
    payload `ReceiverRowBinder.PAYLOAD_STATUS` -> `bindStatus` only, so they no
    longer re-run the volume/mute load tasks. `RendererStatus` has no volume any
    more (it was 50 whenever the receiver did not answer, and the local row's was
    always 50), and the monitor no longer sends `GetVolume`.
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
(`BrowserActivity`: the top level lists servers, receivers and the running players as sections of one page; a server's content has a receiver bar
and a mini-player below it). The "Layout" setting picks one. This section
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

`fragment_server_root.xml` (top level of the new layout): the `serverList`, one
`ConcatAdapter` with three sections, and a footer. The sections:
`SectionHeaderAdapter` "Servers" (broadcast icon, the one refresh button), `BrowseDeviceAdapter`
(shared with classic), `PickCardAdapter`, `SectionHeaderAdapter` "Receivers"
(devices icon, a line above), `DeviceSheetAdapter.receiversOnly` (the
sheet's adapter without the player session rows: the Players section lists them; the sheet keeps them), `SectionHintAdapter`
("No other receivers found yet." while only the local device, which is always listed, is a receiver; updated after every `DeviceSheetAdapter.refresh()` through `setOnRefreshed`), then `SectionHeaderAdapter` "Players" (hidden while there is no player)
and the rows of a `MiniPlayerBar` created in page mode (`MiniPlayerBar(Context, UpnpClient, Consumer<Boolean>)`: its adapter is a part of
the page's list and it reports whether there is any row; bound between `onStart` and `onStop` of the page). The headers carry
the accessibility heading and the icons are decorative; a line (`sectionHeaderDivider`) is above every header but the first.
`BrowserActivity` puts the receiver bar and the mini-player of the content into `browserBottomStack` and shows (and binds) them only
while a `ContentListFragment` is on top (`updateBottomStack`, on back stack changes, `onStart`/`onStop`), so the top level has no
second set of subscriptions. The receiver rows are
kept live by `ReceiverLiveUpdates` (status monitor, group feed, player list
listener, 2 s poll, volume updater), which was extracted from `DeviceSheetFragment`
and is used by both; the page runs it between `onStart` and `onStop`, refreshes the
rows when a device is added/removed/updated (coalesced by `Coalescer` into one rebuild per 300 ms; it also calls `devicesChanged()`, because the status monitor and the group feed took the receivers of the start as a snapshot) and releases the adapter in
`onDestroyView`. Because the sheet's adapter is one part of a `ConcatAdapter` here,
`DeviceSheetAdapter.visibleReceiverRows()` finds its rows through the holders
(`AdapterPositions.laidOutRows`: `getBindingAdapter() == this`, `getBindingAdapterPosition()`), not through the
list's global positions; `BrowseDeviceAdapter` and `ServerListClickListener` use `AdapterPositions.bindingPosition` for the same reason. At most 64 receivers are listed and polled (`ReceiverLiveUpdates.MAX_RECEIVERS`, also in the sheet). The `ConcatAdapter` keeps the default config (no stable
ids across the parts). The list's change animation is off (no flashing on
live updates). The footer with the server switch, the gear (opens
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

### D-pad navigation of the content items

- `DpadActionNavigation` decides which action button of a content item gets the focus on right/left, skipping hidden or disabled ones (`BrowseContentItemAdapter`: the item's key listener moves right to the first shown button, the buttons' listener moves between them and back to the item on the first one). The default focus search never reaches buttons inside the focused item, because they do not lie beside it. The icon buttons draw `drawable/focus_ring` as foreground while focused, so the focus is visible on a TV; left on an item is left to the focus search (previous card of the grid).

### Play queue of one media kind

- Tapping a non-video item (or "play all") queues the loaded siblings of the item's folder. SAF folders mix images, videos and audio, the media store folders do not. A mixed queue chooses the multi content player, which starts every item with `ACTION_VIEW` and shows the app chooser. `SameMediaKind.of` therefore narrows the queue to the kind (`image`, `audio`, `video` of the first resource's content format) of the tapped item before it is rotated to start there (`ContentListFragment.playAllChildsOfParentFrom` and the server re-browse fallback). A target without a known kind keeps the whole queue.

## Issue #294: ranged answers of large local files never ended

Reported by maitelorf with the analysis and the proposed fix (end the stream in the same `produceData()` call as the last write).
A request such as `Range: bytes=100000000-100499999` for a local file above 50 MB (or a range above 50 MB) got a body that was shorter
than `Content-Length` by the part still in the 8 KB buffer, and the connection stayed open until the client timed out (VLC reads the
end of an MP4 first, so such files failed first).

### What HttpCore 5.3.6 does (read from the bytecode, `javap -p -c` on `httpcore5-5.3.6.jar`; the jar has no sources)

- `AbstractHttp1StreamDuplexer#onOutput`: per output event it calls `produceOutput()` once (which ends in
  `AsyncServerExchangeHandler.produce(channel)` -> `AsyncEntityProducer.produce(channel)`, also when `available()` is 0), then
  `isOutputReady()` (offsets 105 and 109). When that is false and the output buffer is empty it clears `OP_WRITE` (offset 158). Nothing
  asks for output again by itself.
- `ServerHttp1StreamHandler#isOutputReady` is `exchangeHandler.available() > 0` while the response is in the body state (offset 40).
- `AbstractBinAsyncEntityProducer#available`: `availableData()` while the state is ACTIVE, else the number of bytes in its internal buffer.
- `AbstractBinAsyncEntityProducer#produce`: calls `produceData(channel)` exactly once (only in ACTIVE); then, in FLUSHING, flushes the buffer
  and, when it is empty, sets END_STREAM and ends the stream.
- The channel handed to `produceData`: `endStream()` -> `streamEnd`: ACTIVE -> FLUSHING, flush, and if the buffer is empty END_STREAM and
  `endStream()` on the real channel; else the next `produce()` flushes the rest (`available()` is then the buffered byte count).
- `write(ByteBuffer)` -> `writeData`: a chunk of at most the fragment size (8192 here) goes into the internal buffer and is flushed only when
  the buffer is full; the call takes the chunk whole or returns 0 when the buffer cannot make room after a partial flush. The return value
  has to be honored.

### Cause

`createStreamingEntityProducer` returned `availableData() = 0` as soon as `bytesRead >= rangeLength`. The call that wrote the last chunk did
not end the stream, so `produceData()` was never called again: a partial last chunk stayed in the internal buffer (the client got a multiple
of 8192 bytes) and `endStream()` never ran (a range whose length is a multiple of 8192 arrived complete but never ended). Reproduced in
`RangedFileEntityProducerTest` against the unchanged logic: `expected.length=500000 actual.length=499712` (61 * 8192) for the reporter's
range, `stream ended for bytes=8192-24575` for a multiple of 8192, `actual.length=0` for a range below one chunk, and, with a client that takes
1000 bytes per write, `actual.length=24384 ... first differed at element [8192]` (a chunk that `write()` did not take was dropped).

### Fix

- `RangedStreamEntityProducer` (new, package-private): a body of `length` bytes from byte `start` of a stream. It ends the stream in the
  `produceData()` call that writes the last byte; `availableData()` is 8192 until then and does not ask the stream; a chunk the channel did not
  take is kept and offered first on the next call; the stream is opened by the first `produceData()`, `skip()` is repeated (falling back to
  `read()`) until `start` is reached, and the stream is closed at the end, on a failed read or write, in `failed()` and in `releaseResources()`.
  A stream that ends early fails the response instead of ending it with less than the announced length. `isRepeatable()` is false.
- `RangedFileEntityProducer` (new): the same over a `FileInputStream` of a local file; first used for files above 50 MB (or a range above
  50 MB), since the follow-up below for every ranged request of a local file.
- `RangeWindow` (new): which bytes one `Range` entry selects. `bytes=0-0` is the first byte (an end of 0 used to mean "no end"), an end beyond
  the file is clipped, a suffix longer than the file is the whole file, a range that selects nothing (start at or beyond the size, end below start,
  suffix 0, empty file) has length 0 and the body ends at once (before: a negative length). The `Content-Range` header of the handler uses it,
  so it names the bytes that are sent (before: the end was not clipped and a suffix range was announced as the whole file).
- `SafContentHolder#getEntityProducer` (SAF documents, with and without range) uses `RangedStreamEntityProducer` and `RangeWindow`. Before, the
  skip to the start used `skip()` once (it may skip less), `bytes=0-0` served the whole document, and a start beyond the end served the whole
  document.
- The `DocumentFile` branch of `ContentHolder#getEntityProducer` without a range reported `input.available()` as available data. That is 0 at
  the end of a file and for providers that buffer nothing, so by the rule above the last read that ends the stream never happens; it now
  reports 8192 while the stream is open (as the external URL producer does).

### Verification

`RangedFileEntityProducerTest`, `RangedStreamEntityProducerTest` and `RangeWindowTest` drive the producers the way HttpCore does: one
`produce()` for the first output event, then `produce()` while `available() > 0`; the body is compared byte for byte and the stream must be ended.
Cases: the reporter's range in a sparse 120 MB file, a range at the end of the file, a length that is a multiple of 8192 and one that is not,
below one chunk, the whole file, `bytes=0-0`, an end beyond the file, suffix ranges, a start at or beyond the end, an empty file, a client that
takes 1000 bytes per write, streams with short reads / short skips / `available() == 0`, and that the input is closed after the body, after a
failed read or write, and by `failed()` and `releaseResources()`.

### Not covered / left

- Not run on a device or with VLC. The real `DataStreamChannel` was not exercised (the tests use a fake channel with the semantics above).
- `ContentHolder#readRangeFormFile` (files up to 50 MB, in memory) and the status 206 for every range request: done in the follow-up below.
- The external URL producer and the `DocumentFile` branch of `ContentHolder` ignore the return value of `write()`, so under back pressure
  (a client that reads slowly) a chunk can be dropped as shown above. The external URL producer ends its stream correctly (it reads until
  the origin signals the end, `availableData()` is 8192 while open). Both could use `RangedStreamEntityProducer`.

### Follow-up: 416 and the ranges of files up to 50 MB

#### What `getContentLength()` returns (the status needs the FULL size)

Read for every `ContentHolder` subclass in `YaaccUpnpServerContentHttpHandler`; it is the size of the whole resource, never the length of a range:

| Holder | `getContentLength()` | Note |
|---|---|---|
| `ContentHolder`, `byte[]` content (thumbnail, default icon) | `content.length` | |
| `ContentHolder`, local file path (media store, album art file) | `file.length()` | 0 for an empty file |
| `ContentHolder`, `http(s)` link | `Content-Length` of a HEAD request to the origin (5 s timeouts), else -1 | one request per call, so the handler asks once (see the plan) |
| `ContentHolder`, `content:` URI (not a file) | `DocumentFile.fromSingleUri(...).length()`, -1 on an exception | a provider that does not know the size reports 0 |
| `SafContentHolder` | now `max(0, documentFile.length())` of the document the producer reads (before: a second `fromSingleUri` lookup) | status, `Content-Range` and body come from one size; 0 = unknown size of the provider, a range then gets 416 (before: 206 with an empty body) |
| `LiveStreamContentHolder` | -1; its `ranges` are always empty | ranges are not supported: answer 200 (before: 206 without `Content-Range` and the whole live stream) |
| `ByteArrayContentHolder`, `FileContentHolder` | never constructed (dead code) | |

#### `RangeResponsePlan` (plain JVM, `RangeResponsePlanTest`)

`RangeResponsePlan.of(ranges, size)` decides status, `Content-Range` and the window; `ContentHolder#getRangePlan()` computes it once per request from `getContentLength()`
(so the log line, the status line and the body use one size, and an external link is asked once instead of twice or three times). `handle()` calls `plan.applyTo(responseBuilder)`
for every holder and sets the entity only if `plan.hasBody()`.

| Request | Size | Answer |
|---|---|---|
| no usable `Range` | known or unknown | 200, no `Content-Range`, whole resource |
| range | unknown (< 0, external link without length) | 206 without `Content-Range`, the range is passed on to the origin (as before) |
| satisfiable range | known | 206, `Content-Range: bytes first-last/size` of the `RangeWindow` (end clipped, suffix = tail), body = exactly that window |
| start >= size, `-0`, last < first, any range of an empty resource | known | 416, `Content-Range: bytes */size`, NO entity (`Content-Length: 0`), the resource is not opened |

HEAD gets the same status and headers (HttpCore sends `Content-Length` of the entity and no body; checked over a real server in `RangedResponseOverHttpTest`).
`Accept-Ranges: bytes` and the DLNA headers are set on every answer of a found resource, a 416 included.

#### Decisions

- **Unit other than `bytes`, unparsable header, a number beyond `long`, header over 1024 characters**: ignored, answer 200. `HttpRange.parseRangeHeader` throws a `RuntimeException`
  for all of them (also `NumberFormatException`; it matches `bytes=` case-sensitively and does not accept a space after a comma), which used to leave `handle()` with an exception.
  `RangeResponsePlan.parseHeader` catches it. RFC 9110 14.2 allows ignoring any `Range` header; an unknown unit must be ignored.
- **Several ranges**: the first one is served as 206 (as before), the others are ignored; no `multipart/byteranges`. If the FIRST one is not satisfiable the answer is 416 even if a later
  one would be (RFC 9110 14.1.2 would serve that one; not worth a second code path). An external link gets only the first range upstream now (before: all, and an origin may answer multipart).
- **Last position below the first (`bytes=10-5`)**: 416 (RFC 9110 14.1.1 calls it invalid and lets a server ignore or reject it). `ByteRangeResolver` (shares) ignores it and answers 200.
- **`If-Range`**: not supported, ignored (the range is served).
- **Size 0**: an empty resource has no satisfiable range: 416 `bytes */0`. A SAF provider that reports 0 for an unknown size therefore also gets 416.
- **416 body**: empty (the task asked for no producer; `ProxyShareResponder` sends a short HTML text).
- **Live streams** answer 200 to a range request (they used to answer 206 without `Content-Range`).

#### Small files

`readRangeFormFile` and the 50 MB branch are removed: every ranged request of a local file uses `RangedFileEntityProducer` with the window of the plan (the file is read in 8 KB steps, no
heap for the body). Reasons: one behavior for all sizes, less code, no `OutOfMemoryError` risk for a range of up to 50 MB. A body of a normal range is byte for byte what the old code sent where it was
correct (`SmallFileRangeCompatTest` has a copy of the old code and compares open ranges, suffix ranges up to the file length, an end beyond the file, the whole file); the differences are the old bugs:

| Request (file of 100000 bytes) | Old in-memory body | Now |
|---|---|---|
| `bytes=100-199` | 99 bytes under `Content-Range: bytes 100-199/100000` | 100 bytes |
| `bytes=0-0` | the whole file | 1 byte |
| `bytes=200000-` | `NegativeArraySizeException` | 416 |
| `bytes=-900000` (suffix longer than the file) | `IOException` (negative seek) | 206, the whole file |
| a short `RandomAccessFile.read` | a body padded with zeros | the stream producer reads until the window is full |

Also removed: the 206 text "DocumentFile range requests not implemented" for a `content:` URI (a ranged request now streams the window of the document like `SafContentHolder`) and the full-body answer
of an in-memory resource (thumbnail) to a range request: it now sends the slice under the `Content-Range` it announces. The file without a range is still `AsyncEntityProducers.create(file)`.

#### Other users of the ranges list

`lookupContent`, `lookupAlbumArt`, `lookupThumbnail` and `lookupSafContent` only pass the list on; all of them reach the plan through `ContentHolder`. `ProxyShareResponder` (shares) uses `ByteRangeResolver`, which already
answers 200/206/416 with `Content-Range: bytes */size`, `Accept-Ranges` and a short HTML body, and never throws on a header: its 416 handling is the model for the status and header. Not changed.
Differences kept on purpose: the content handler serves the first of several ranges (the resolver answers 200), treats `last < first` as 416 (the resolver ignores the header), and an unknown size is 206 without
`Content-Range` in both. `RangeResponsePlan` duplicates the resolver's decision on `HttpRange` objects; unifying both would change those cases for one of the two users and was left.

#### Verification

`RangeResponsePlanTest` (status table, garbage headers), `SmallFileRangeCompatTest` (old vs new body, in-memory slice, live holder), `RangedResponseOverHttpTest` (a real HttpCore 5.3.6 server on the loopback interface built like the
one of `YaaccUpnpServerService`, raw socket client, the real `ContentHolder`, a 300 kB and a sparse 120 MB file: status line, `Content-Range`, `Content-Length`, body bytes, 416 with and without HEAD, garbage headers, HEAD with a range).
Not covered by a test: the part of `handle()` before the lookup (path, preferences, MediaStore) and after it, `SafContentHolder` and the `content:` branch (need a device), an external link.

#### curl checks (device; `N-5000` etc. stand for the computed number; `<url>` of a local file below 50 MB (S) and above 50 MB (B); `-i` shows the head, `-s -o /dev/null -w '%{http_code} %{size_download}\n'` the size)

| Request | S (size N) and B (size N) |
|---|---|
| `curl -i <url>` | `200 OK`, no `Content-Range`, `Content-Length: N`, N bytes |
| `curl -i -H 'Range: bytes=0-0' <url>` | `206 Partial Content`, `Content-Range: bytes 0-0/N`, `Content-Length: 1`, 1 byte (same for S and B) |
| `-H 'Range: bytes=0-99'` | `206`, `bytes 0-99/N`, 100 bytes |
| `-H 'Range: bytes=100000-100499'` | `206`, `bytes 100000-100499/N`, 500 bytes, connection ends at once |
| `-H 'Range: bytes=N-5000-'` (open end) | `206`, `bytes N-5000-(N-1)/N`, 5000 bytes |
| `-H 'Range: bytes=-500'` | `206`, `bytes N-500-(N-1)/N`, 500 bytes |
| `-H 'Range: bytes=-99999999999'` (suffix > file) | `206`, `bytes 0-(N-1)/N`, N bytes |
| `-H 'Range: bytes=N-100-N+999999'` (end beyond) | `206`, `bytes N-100-(N-1)/N`, 100 bytes |
| `-H 'Range: bytes=0-9,20-29'` | `206`, `bytes 0-9/N`, 10 bytes |
| `-H 'Range: bytes=N-'` or `bytes=N+1000-` | `416 Requested Range Not Satisfiable`, `Content-Range: bytes */N`, `Content-Length: 0`, no body |
| `-H 'Range: bytes=-0'`, `bytes=10-5` | `416`, `bytes */N`, no body |
| `-H 'Range: bytes=abc'`, `items=0-5`, `bytes=0-5,x`, `garbage` | `200 OK`, no `Content-Range`, N bytes |
| `curl -I -H 'Range: bytes=100-199' <url>` (HEAD) | `206`, `bytes 100-199/N`, `Content-Length: 100`, no body |
| `curl -I -H 'Range: bytes=N-' <url>` (HEAD) | `416`, `bytes */N`, `Content-Length: 0` |
| `curl -I <url>` (HEAD) | `200`, `Content-Length: N`, no `Content-Range` |

### The `Date` header of the HTTP server (found while testing #294)

A `curl -I` against a phone with the German language showed `Date: So., 01 Nov. 2026 17:55:36 GMT+00:00`. Two causes:
- **httpcore5 5.3.6** (`HttpDateGenerator`, read with `javap`) builds its formatter with `DateTimeFormatterBuilder.appendPattern("EEE, dd MMM yyyy HH:mm:ss zzz").toFormatter()`,
  which uses the **default locale** of the device: day and month names in the device language, the zone name as the platform writes it (on Android `GMT+00:00`). RFC 9110 wants the
  English IMF-fixdate `Sun, 01 Nov 2026 17:55:36 GMT`. Reproduced on the JVM with the default locale `de_DE` (`ServerDateHeaderTest`).
- **`YaaccUpnpServerProtocolRequestHandler`** (the answers of the UPnP protocol: SOAP, GENA) set `Date` to `System.currentTimeMillis()` as a plain number (`Date: 1793555736000`), and the
  library keeps a header that is already there. It uses `HttpDates.now()` now.

Fix: `HttpDates.imfFixdate` (fixed English pattern, `Locale.US`, UTC, literal `GMT`, two digit day) and `ServerHttpProcessor`, set with `H2ServerBootstrap.setHttpProcessor`: it adds the `Date`
before the library's interceptor would. Pitfall found by the loopback test: `setHttpProcessor` gives ONE processor to both protocols, while the default is `HttpProcessors.server()` for HTTP/1.1 and
`H2Processors.server()` for HTTP/2 (`H2ServerBootstrap.create`, bytecode); the HTTP/2 chain on an HTTP/1.1 connection ends in an empty answer. `ServerHttpProcessor` therefore holds both chains
(`customServer(null)` = the default ones, same `Server` header) and picks by the protocol version in the context (set by `ServerHttp1StreamHandler` / `ServerH2StreamHandler`).
Not verified without a device: HTTP/2 on a real connection (the unit test sets the version in the context), and the Android formatting of the old header (read from the report, not reproduced).

## Play mode (repeat and shuffle per player)

Repeat and shuffle belong to the player, not to the settings. The settings `settings_replay_playlist_chkbx` (default true) and
`settings_music_player_shuffle_chkbx` (default false) are read in one place, `PlayModeSettings.defaultPlayMode(Context)`, when a player is asked for its
mode the first time; after that the player owns a `PlayMode` and nothing writes the settings. The mode lives as long as the player.

### Pure classes (plain JVM, tests in `src/test/java/de/yaacc/player`)
- `PlayMode`: immutable `RepeatMode {OFF, ALL, ONE}` + `shuffle`. `fromSettings(replay, shuffle)` (replay true is ALL), `withRepeat`, `withShuffle`, `nextRepeat()` (OFF, ALL,
  ONE, OFF), `toggleShuffle()`, `toUpnpPlayMode()` / `fromUpnpPlayMode(String)`. UPnP mapping (the one Home Assistant's `media_player.repeat_set` / `shuffle_set`
  use through async_upnp_client): (OFF,off) NORMAL, (OFF,on) SHUFFLE, (ALL,off) REPEAT_ALL, (ALL,on) RANDOM, (ONE,any) REPEAT_ONE. `DIRECT_1`, `INTRO`, null and unknown
  strings read as NORMAL. Repeat one with shuffle reads back as REPEAT_ONE without shuffle (the protocol has no such value).
- `PlayOrder`: the order the items play in, a permutation of the item indexes. Without shuffle the list order. With shuffle it is created once (new playlist, shuffle switched on,
  stop) and kept, so previous walks back along it. A created order starts with the current item (switching shuffle on/off keeps it); a new shuffled playlist and a stop start
  with a random item. The position is not stored: it is looked up from the current index, which the player owns (ExoPlayer changes it too). `fit(size, current)` adapts to a
  playlist that grew (appended, with shuffle inserted at random behind the current item) or shrank (created again, current first). `next(current, repeat, autoAdvance)`:
  repeat ONE repeats an item that ended by itself (`autoAdvance`), a skip treats ONE like OFF (ExoPlayer does the same: no wrap around); ALL wraps; OFF returns `NONE`.
  `previous` wraps to the last item (the old behavior); `following` / `preceding` do not wrap (next-item title, `hasNextMediaItem`).
  Editing: `positionOf(index)` / `itemAt(position)` map list index and position (identical without shuffle), `move(from, to)` shifts the items in between (a drag) and only changes the
  order, `remove(index)` takes a removed item out and moves the higher indexes down while keeping the order of the rest (unlike `fit`, which would shuffle again),
  `isAtOrBeforeCurrent(position, current)` is the lock rule.
- `RepeatPolicy` (package private): `PlayMode` / `RepeatMode` to the ExoPlayer constants and back; `stopsAtEnd(mode)` is true for repeat OFF. `adoptExoRepeatMode(current, exoRepeat, ownChange)` /
  `adoptExoShuffleMode(current, exoShuffle, ownChange)` decide what an ExoPlayer callback means: null (keep) for our own echo or no difference, otherwise the mode with that one part replaced.

### Player contract
- `Player.getPlayMode()` / `setPlayMode(PlayMode)` and the `Player.PROPERTY_PLAY_MODE` property change (old and new `PlayMode`, fired on the caller's thread, only if the value
  differs). `Player.onItemEnded()` (default `next()`) is "the item ended by itself": the timer broadcast (`PlayerServiceBroadcastReceiver`) and the end detection of
  `AVTransportPlayer` call it; `next()` is a skip.
- `AbstractPlayer` (AVTransportPlayer, MultiContentPlayer, LocalMediaSessionPlayer): `moveToNext(itemEnded)` / `moveToPrevious()` move `currentIndex` by the `PlayOrder`;
  `setItems` no longer shuffles the list itself (the list keeps the order it was given; the playlist dialog shows the play order with shuffle, see "Playlist dialog" below). `stop()` resets the order and the index
  (`restart()`: 0, or the first of a new shuffle). The end of the list with repeat OFF calls `stop()` after the command flag is released: before, `next()` called `stop()`
  while `isProcessingCommand` was still true, `stop()` returned at once and the player stayed blocked (found while replacing the settings read).
  `doPostLoadItem` starts the auto-advance timer for a single item too when the mode is repeat ONE.
- `LocalImagePlayer` is not an `AbstractPlayer`: it only holds the mode; `ImageViewerActivity` follows it (`followPlayer`) and `ImageViewerViewModel` plays the slideshow by it
  (`PlayOrder`, auto-advance stops the slideshow on the last image with repeat OFF, repeat ONE keeps the image, a skip at the end with OFF stays). Without a player (an external
  `ACTION_VIEW`) the view model keeps its own mode (from the settings).

### Engines
- `LocalMediaSessionPlayer` (ExoPlayer, Media3 1.11.1): `applyPlayMode(player, order)` (main thread) sets `setRepeatMode` (OFF/ALL/ONE), `setShuffleModeEnabled` and, when shuffle
  is on, `setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(int[] order, seed))` with the `PlayOrder` of the player (current item first), so the list is played through once
  with repeat OFF. It is applied at init, before every `startItem` (after `resetPlayOrder(index)`) and in `onPlayModeChanged` (the order only when shuffle changed). The
  ExoPlayer list is the player's list (media item index = list index), so `onMediaItemTransition` sets `currentIndex` for AUTO, REPEAT and SEEK (not PLAYLIST_CHANGED, whose
  first item is only ExoPlayer's pick) and fires `PROPERTY_ITEM`; before, only a seek updated the index and an item that ended by itself left the screen on the old title.
  `STATE_ENDED` with repeat OFF calls `stop()` (as before). `getNextItemTitle()` uses the cached `getNextMediaItemIndex()`. ExoPlayer navigation (`hasNextMediaItem`,
  `seekToNextMediaItem`) treats REPEAT_MODE_ONE as OFF, as `PlayOrder.next` does for a skip.
- External controllers: `LocalMediaSessionPlayer` adds a `Player.Listener` whose `onRepeatModeChanged` / `onShuffleModeEnabledChanged` call `setPlayMode(...)` with the mode from
  `RepeatPolicy.adoptExo*` (only the changed part is taken over, so a pending change of the other part is not overwritten). `PROPERTY_PLAY_MODE`, the buttons and the renderer's
  `CurrentPlayMode` follow. Loop guard: `applyPlayMode` holds the main-thread flag `applyingPlayMode` while it sets ExoPlayer's modes (the callbacks are synchronous on the application
  looper), and a value equal to the play mode is no change anyway. When an external controller turns shuffle on, ExoPlayer first picks a random order of its own; `setPlayMode` creates
  the `PlayOrder` (current item first) and `onPlayModeChanged` posts `applyPlayMode` with it, which installs it with `setShuffleOrder`. `applyPlayMode` is always posted, never run
  inside a listener callback (ExoPlayer delivers events raised inside a callback after it). The Media3 session of the AVTransport player (`AVTransportPlayerWrapper`) already writes to
  the `PlayMode` (`setRepeatMode` / `setShuffleModeEnabled`) and reads from it (`getRepeatMode` / `getShuffleModeEnabled`), so a controller there needs nothing more.
- `AVTransportPlayer` (controller of a remote renderer): the app sends one item at a time, so the next item is `AbstractPlayer`'s job (`onItemEnded()` from the transport-state
  and the position polling, the timer). No `SetPlayMode` is sent to the remote renderer (it would repeat a single item itself). `AVTransportPlayerWrapper.setRepeatMode` /
  `getRepeatMode` / `setShuffleModeEnabled` / `getShuffleModeEnabled` use the player's `PlayMode` (they used to write and read the settings) and `notifyPlayModeChanged()`
  tells the Media3 listeners; `hasNextMediaItem` and the media item indexes follow the order. `AVTransportController` (controls a device without our playlist) hides the buttons.
- `MultiContentPlayer` needs nothing of its own: the timer of `AbstractPlayer` ends an item (`ACTION_VIEW` per item), `onItemEnded()` picks the next by the order.

### UI
- Buttons: `PlayModeControls.Binding` binds a repeat and a shuffle `ImageButton` to a `Player` (click, follow `PROPERTY_PLAY_MODE` on the UI thread, `detach()` in `onDestroy`).
  Style `Widget.MaterialComponents.Button.UnelevatedButton`, `android:foreground="@drawable/focus_ring"`, `app:tint="?attr/colorControlNormal"`. State without color: repeat ONE
  has its own icon, an inactive control (repeat OFF, shuffle off) has alpha 0.5; the content description names the state (`repeat_off|all|one`, `shuffle_off|on`, all seven
  locales; `PlayModeResourcesTest`). Music and AVTransport screens: both buttons are the ends of the second row (`layout_weight` for the six buttons, so the row fits 320dp
  portrait and 640dp landscape); the multi content screen has a row of its own (its first row is already wider than a phone); the image viewer has `menu_repeat` and
  `menu_shuffle` (`ifRoom`; title and icon alpha follow the mode in `onPrepareOptionsMenu`).
- Playlist dialog (`PlaylistDialogFragment`, `PlaylistItemAdapter`): shows the items in `Player.getPlayOrder()` order: the shuffled play order with shuffle, the list order without. A row is a
  position of that order; the item is `items.get(order[position])`; the adapter keeps a snapshot of both. The playing item (bold, a marker) is found by its index in the order. The dialog follows
  `PROPERTY_PLAY_MODE` and `Player.PROPERTY_PLAYLIST` (items set, added, removed, moved; the order created again at a start or a stop) with a full refresh (`notifyDataSetChanged`, only if the
  shown order differs), and `PROPERTY_ITEM` / `playing` with a rebind of the rows (lock, marker). The listener is removed in `onDestroyView`. Editing goes to the player:
  `moveInPlayOrder(fromPosition, toPosition)` (shuffle on: `PlayOrder.move`, the list is not touched; off: the list is moved) and `removeItem(itemIndex)` (list and `PlayOrder.remove`; the current
  index follows when the removed item has a lower list index, which is possible with shuffle). Both refuse a locked position: the playing or paused item and everything before it in the shown
  order; a stopped player has nothing locked and starts with the first shown item afterwards. The adapter applies a successful edit itself (`notifyItemMoved` / `notifyItemRemoved`, precise, so a
  running drag is not interrupted) and ignores the change event it caused. The rows ask the holder for their position when clicked (the position at bind time is stale after a move).
  `LocalMediaSessionPlayer` mirrors an edit into ExoPlayer without rebuilding the list: `moveMediaItem` (list moved), `removeMediaItem` and `setShuffleOrder` with the edited order (the
  old `syncPlaylistToExoPlayer`, which rebuilt the part behind ExoPlayer's current item and was wrong for items before it, is gone). The next-item text of the player screens
  (`getNextItemTitle`: `PlayOrder.following`, ExoPlayer's `getNextMediaItemIndex` for the local player) and the media item indexes of the Media3 wrapper already follow the order. No other screen
  (mini-player, device sheet, notification) lists a queue.
- Not done: the mini-player and the device sheet rows have no play mode control (no simple hook; the rows would need two more buttons).

### Media renderer (`upnp/server/avtransport`)
- `SetPlayMode`, `GetTransportSettings` and the `CurrentPlayMode` state variable (allowed values: the cling `PlayMode` enum, unchanged) existed but only stored the value. Now:
  `YaaccAVTransportService.setPlayMode` rejects an unknown value with 712 (`PLAYMODE_NOT_SUPPORTED`; the old code threw it inside a `catch (AVTransportException)` that swallowed it)
  and calls `AvTransport.setPlayMode`. `AvTransport` stores the value in its `TransportSettings`, puts `CurrentPlayMode` into the `LastChange` (only when it changes), gives the mode
  to the players of the transport (`UpnpClient.getCurrentPlayers(transport)`) and, through `attachPlayers(...)` in `AvTransportMediaRendererPlaying.onEntry`, to every player started
  later; a change at a player (`PROPERTY_PLAY_MODE`) is stored and evented the same way and is kept for the next player of the transport. As long as nothing was set or reported,
  `getTransportSettings()` returns the mode a new player would get from the settings (not NORMAL). `GetTransportSettings` also answers in the NoMediaPresent state now.
  `RecQualityMode` stays NOT_IMPLEMENTED. There is no `PossiblePlayModes` variable in AVTransport:1; controllers read the allowed values of `CurrentPlayMode`.
- Tests: `AvTransportPlayModeTest` (service actions with the real state machine, a mocked `UpnpClient` and `Player`).
- **Events (`LastChange`).** `CurrentPlayMode` is not evented itself (`sendEvents=false`, allowed values: the seven UPnP modes); like `TransportState` and the others it travels inside the `LastChange`
  variable: `<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"><InstanceID val="0"><CurrentPlayMode val="REPEAT_ONE"/>...`. Home Assistant (async_upnp_client `DmrDevice`) reads `play_mode` from the state
  variable `CurrentPlayMode`, which it fills from these events and, only when it is not subscribed or at its first update, from the `GetTransportSettings` answer (output `PlayMode`, related variable
  `CurrentPlayMode`); after its own `SetPlayMode` it does not poll, it waits for the event. Three things were missing for an event to reach it:
  1. **The subscription was never established.** `ReceivingSubscribe` registers the subscription at the service and sends the initial event in `responseSent(...)`, which the server must call once the SUBSCRIBE
     answer was written. `YaaccUpnpServerProtocolRequestHandler` never did (and kept the protocol of the request in a field of the handler shared by all I/O threads), so a control point got a subscription id and
     then nothing, neither the initial event nor a later one. Now `handle(...)` hands the answer to `DeliveryReportingResponseProducer`, which calls `responseSent` (or `responseException`) of the protocol of *that*
     request when the exchange is over. `ReceivingSubscribe.EventSendingSubscription.establishAndSendInitialEvent()` registers first and then reads the state again (under the monitor of the subscription), so a change
     that happened between the first read (at SUBSCRIBE) and the registration - Home Assistant sets shuffle and repeat with two calls in a row, right when it subscribes - is in the initial event.
  2. **Nobody fired the `LastChange`.** `AvTransportServiceManager` (a `LastChangeAwareServiceManager`) is the manager of the service: the initial event is the full state of instance 0
     (`YaaccAVTransportService.appendCurrentState`; if that fails the accumulated event is sent instead of failing the subscription), and the `LastChange` of the service is an `ObservableLastChange` whose
     listener asks the manager to fire after every `setEventedValue` (states of the state machine, `AvTransport.storePlayMode`, buttons at a player). `eventMaximumRateMilliseconds` of the `LastChange` variable was removed:
     Cling drops (does not delay) an event inside that time and a fired `LastChange` is cleared, so a dropped change was lost for good.
  3. **An event must never hold up a command.** Actions run under the lock of the service manager (`AbstractActionExecutor` -> `manager.execute`); a command that does not get it within `LOCK_TIMEOUT` (5 s) is
     answered with UPnP error 501 "Failed to acquire lock in milliseconds" and the control point drops it. Cling's `LastChangeAwareServiceManager.fireLastChange()` takes that lock and delivers to the subscriptions
     while holding it; the methods of a `LocalGENASubscription` are `synchronized` and `registerOnService()` / `end()` need the manager lock from inside that monitor, so a subscription that was busy (or a subscriber
     that did not return) made the event thread and that caller wait for each other, with every command queued behind them (reproduced: `AvTransportCommandsAndEventsTest`, 501 after the lock timeout). Also
     `PropertyChangeSupport.firePropertyChange` stops at the first listener that throws. Now the event thread (`avtransport-events`, a daemon thread that ends when idle) takes **no manager lock**: it takes what changed
     out of the `LastChange` in one step (the `LastChange` monitor, microseconds; take and clear are atomic, no change is lost), then hands the `EVENTED_STATE_VARIABLES` event to each listener of the service on its own,
     logging and skipping one that throws. Changes in a row are sent together; two events are at least 250 ms apart (end of one to start of the next), except that a change of `TransportState` / `CurrentTransportActions` is sent at once (see "the transport leaves PLAYING when the playback ends").
  Log (D, tag `AvTransportServiceManager`): `AVT event: CurrentPlayMode=<mode> (instance 0) sent to <n> subscribers` when a fired event carries a play mode (n = subscriptions that took it, 0 means nobody is subscribed),
  `AVT initial event for a new subscriber: CurrentPlayMode=<mode>` for each SUBSCRIBE, and per received command (tag `YaaccAVTransportService`) `AVT SetPlayMode <mode> from <User-Agent>: applied to <n> players`
  (`rejected (712)` for an unknown value). `SetPlayMode` takes the `RemoteClientInfo` of the request as an unannotated last parameter (Cling fills it) for the User-Agent in that line.
- **`SetPlayMode` in every state.** It is not a state transition: it is answered 200 (a `SetPlayModeResponse`) in NoMediaPresent, Stopped, Playing and Paused for all seven values and changes no state
  (`AvTransportCommandsAndEventsTest.setPlayModeIsAnsweredAndAppliedForEveryModeInEveryState`: states x modes through the real receiving path and the real state machine).
- **Memory of the mode.** A mode set by a control point or at a player (`playModeChosen`) is given to every player the transport starts later. Home Assistant plays one item at a time and every item is a new player (the
  one that plays is shut down by `PlayerService.createPlayer...`), so the mode has to survive that replacement; but it must not survive the *end* of the playback, or the next playback of the day starts with
  the shuffle that was chosen yesterday. So: `attachPlayers(...)` (called from `AvTransportMediaRendererPlaying.onEntry`) remembers the players of the transport and registers a `PlayerListChangeNotifier.Listener` at the
  `UpnpClient`; every removal of a player runs `checkPlayersGone()`: if none of the attached players is among `UpnpClient.getCurrentPlayers()` any more (closed in the app, ended, removed), `forgetPlayMode()` clears
  `playModeChosen` / `playModeKnown`, so `getTransportSettings()` and the next player are the settings again, and, if that differs from what the control point last saw, `CurrentPlayMode` of the settings is put
  into the `LastChange` (Home Assistant shows it). The listener is removed at that point and registered again by the next `attachPlayers`. `playersStarting()` / `playersStarted()` around the creation of the players in
  `onEntry` keep the removal of the *replaced* player from counting as "no player left" (its notice comes on the main thread, possibly before the new player is attached); `playersStarted()` looks again, so a
  new player that could not be created ends in a forgotten mode. A mode set while the transport never had a player stays for the first one. For the *play mode*, state changes of the player do not matter, only its removal: a stopped
  player is still a player (`stop()` keeps it). (The *transport state* does follow the stop of the player, see "the transport leaves PLAYING when the playback ends".) Tests: `AvTransportPlayModeMemoryTest`.
- Tests: `AvTransportEventTest` (event XML, initial event, `GetTransportSettings`, SCPD declarations, scheduling), `AvTransportCommandsAndEventsTest` (commands and events together, with the harness below),
  `RendererHarness` (the real `AnnotationLocalServiceBinder` service, `YaaccUpnpServerProtocolRequestHandler.handle`, `UpnpProtocolHandler`, `ReceivingAction` / `ReceivingSubscribe`, Cling's message parsing and
  action executor, a mocked `UpnpClient` with fake players and a mocked `HttpRequestSender` that records the event messages).
- Home Assistant sends one item at a time (`media_player.play_media`: Stop if possible, `SetAVTransportURI`, `Play`; no `SetNextAVTransportURI`), so repeat one/all repeat that item (the app's
  `onItemEnded` with a one-item list) and shuffle has nothing to reorder. Next/Previous are reported in `CurrentTransportActions` in every state (see `getPossibleTransportActions`), so Home Assistant offers
  them; `AvTransportMediaRendererPlaying.next()/previous()` only log.
- `UpnpClient.getCurrentPlayers(AvTransport)` does not look up "the players of this transport": it returns the active players of the *kind* the current track's mime type needs
  (`getPlayerClassForMimeType`). A `SetPlayMode` therefore reaches the player of that kind and the log says `applied to 0 players` when there is none. By reading the code (not reproduced): `initializePlayers`
  takes the mime type from the DIDL `protocolInfo` only and a track without it gets a `MultiContentPlayer`, while `getCurrentPlayers(transport)` falls back to the file extension and looks for an
  audio / video / image player, so for such a track `SetPlayMode` (and Stop / Pause, which use the same lookup) would not find the player.

### Media renderer: RenderingControl events, the other services, the idle log (owner test, 2026-11-01)
- **Shared machinery (`upnp/server/events`).** `AvTransportServiceManager` was split: `AsyncLastChangeServiceManager` (a `LastChangeAwareServiceManager`) holds everything described above (full state as the initial event; `LastChange` taken out in one step and
  sent from one daemon thread without the manager lock; 250 ms spacing; a subscriber that throws is logged and skipped; D log lines `<AVT|RCS> event: ... sent to n subscribers` and `<AVT|RCS> initial event for a new subscriber: ...`), and the two
  services only create their instance (`attachEvents(lastChange, propertyChangeSupport, service)`) and say what the log shows. `ObservableLastChange` moved there too and now **drops a value that equals the one put last** (per instance and
  variable, compared by its attributes): it is not collected and does not ask for an event, so `Stop` while stopped, `Play` while playing or a volume callback that finds the same volume send nothing. (What was put last is what every subscriber
  knows: the state at SUBSCRIBE plus every change since; `forgetPutValues()` is called when collected changes had to be dropped unsent.) Unit tests: `ObservableLastChangeTest`.
- **RenderingControl: what existed.** `YaaccAudioRenderingControlService` had `GetMute/SetMute/GetVolume/SetVolume` on the `AudioManager` music stream (the value is the system's, nothing is stored; the app's own controls `UpnpClient.setMute/setVolume`, `Player.setMute/setVolume`,
  the device sheet and the hardware keys all end there), declared `LastChange` as evented (with `eventMaximumRateMilliseconds = 200`, which makes Cling drop events) - and **nothing else**: the manager was a plain `DefaultServiceManager`, `getCurrentInstanceIds()` and
  `getCurrentChannels()` returned `null` (so the full state could not be built), nobody ever put anything into the `LastChange`, and no one watched the system volume. A subscriber got an empty `LastChange` and never an event; a controller that is subscribed
  does not poll (Home Assistant: `GetMute/GetVolume` only at its first update or when not subscribed), so its mute/volume was whatever the first poll returned.
- **RenderingControl: now.** `RenderingControlServiceManager` (same base as AVTransport). Instance 0, channel `Master`. The initial event has `Mute`, `Loudness`, `Volume`, `VolumeDB` (all `channel="Master"`) and `PresetNameList`
  (the base class' `appendCurrentState`); a change event has only what changed: `<Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/"><InstanceID val="0"><Mute channel="Master" val="1"/>...` / `<Volume channel="Master" val="40"/>`.
  State variables: `LastChange` evented without rate limit, `Volume` (ui2, 0-100), `Mute`, `Loudness`, `VolumeDB`, `PresetNameList` not evented individually (`sendEvents=false`) - the declarations were right, only `eventMaximumRateMilliseconds` was removed.
  - `SystemVolume` (new, `upnp/server/renderingcontrol`): the one place that reads and writes the music stream (percent rounded to the nearest step - before it was floor, so 99 % became 14 of 15 steps = 93 %; no division by zero when the stream reports a range of 0).
    `UpnpClient.isMute/setMute/getVolume/setVolume` use it, so the app's own volume controls and `SetVolume/SetMute` run the same code. A write through it tells the in-process listeners.
  - Sources of a change: (1) `SetMute/SetVolume` and the app (in-process listeners of `SystemVolume`, immediate); (2) outside (hardware keys, the system panel, other apps): `SystemVolumeBroadcastWatcher`, a receiver for
    `android.media.VOLUME_CHANGED_ACTION` and `android.media.STREAM_MUTE_CHANGED_ACTION` (not in the public SDK: `AudioManager` does not list them, checked in the API reference; AudioService has sent them for many versions and many apps use them).
    Registered with `ContextCompat.RECEIVER_NOT_EXPORTED`, nothing is trusted from the intent (it only means "read again"; the music stream is checked when the stream extra is present). A `ContentObserver` on `Settings.System` was considered and
    rejected: it sees the volume but not the mute flag. No timer, no polling.
  - **Only while somebody listens.** `SubscriberAwarePropertyChangeSupport` (the support the manager gives to the GENA subscriptions) tells the service when the first subscriber comes and the last one goes (a device removal ends the local subscriptions too:
    `LocalItems` -> `end(DEVICE_WAS_REMOVED)`). The first one starts the listeners, takes the current values as "known" and from then on only a difference is put into the `LastChange`; the last one stops them. Log (D): `RCS watching the system volume: first subscriber` /
    `RCS not watching the system volume: no subscriber left`.
  - `Channel` and `InstanceID` are not checked (unchanged behavior): every channel name and every instance answers with the one master volume. Home Assistant sends `Channel=Master`. An `AudioManager` that refuses (`SecurityException`) is answered with UPnP error 501 `ACTION_FAILED`.
  - Log (D, tag `RenderingControlServiceManager`): `RCS event: Mute=<bool> Volume=<n> (instance 0) sent to <n> subscribers` (only the variables that changed), `RCS initial event for a new subscriber: Mute=<bool> Volume=<n>`; per command (tag `YaaccAudioRenderingControlService`): `setMute(<bool>)`, `setVolume(<n>)`.
  - Tests: `RenderingControlEventsTest` (SCPD declarations, actions with `Channel=Master`, initial event, events for the control point / the app / the "system" callback (a fake `VolumeWatcher`), no event when unchanged, watcher started with the first subscriber and stopped with the last,
    a command during an event, a throwing or stuck subscriber, a refusing `AudioManager`), run through `ServiceHarness` (the harness of `RendererHarness`, generalized: any annotated local service, the real receiving path, a recording `HttpRequestSender`).
- **Other local services.** ConnectionManager (`SourceProtocolInfo`, `SinkProtocolInfo`, `CurrentConnectionIDs` are evented plain variables), ContentDirectory (`SystemUpdateID`; `SearchCapabilities`/`SortCapabilities` are not evented; `ContainerUpdateIDs` and `TransferIDs` are not declared at all - they are
  CDS:2 variables) and the Microsoft media receiver registrar (the four `...UpdateID`) use Cling's `DefaultServiceManager`. With the subscription now really registered they deliver their initial event; checked with `LocalServicesEventsTest`:
  all three send a valid, non-empty initial event and nothing throws on subscribe. Found and changed:
  - ConnectionManager was created with `sinkProtocolInfo = null`; the event/answer was valid (`<SinkProtocolInfo></SinkProtocolInfo>`, Cling writes null as empty) but any other reader of the field would fail; it is an empty `ProtocolInfos` now (same output).
  - ContentDirectory **never changed `SystemUpdateID`**: `changeSystemUpdateID()` existed (protected) and nobody called it, so after the initial event no event was ever sent. It is public now, increments under the monitor of the object but fires the property change **outside** of it (the
    manager takes its lock to read the new value, an action that holds that lock may need the monitor: a lock order inversion that ends in a 5 s lock timeout), logs `CDS event: SystemUpdateID=<n>` (D) and survives a subscriber that throws. The `eventMaximumRateMilliseconds = 200` of `SystemUpdateID` was removed (Cling drops, not delays: a dropped
    last number leaves the control point on an outdated one). `YaaccUpnpServerService` calls it (on its own thread, the main thread must not wait for the lock a long Browse holds) when the shared folders change (`de.yaacc.SAF_PATHS_CHANGED`). Not done: an observer of the
    MediaStore (no content observer exists in the app, so changes to the device's own media do not bump the number) and the end of the SAF duration preload (it does not change what is listed). New files and folders inside a shared SAF folder do bump it since issue #299 (see "New files and folders in a shared SAF folder").
  - These services still deliver to their subscribers on the thread that fires, not through an event thread: a subscriber that throws stops the ones behind it for that one event (the manager's listener catches and logs it). They change rarely and have no commands that depend on the delivery, so
    the machinery of the LastChange services was not copied.
- **The log flood (`doSetTrackInfo`).** Root cause (from reading the code, and the unit tests that show the state's way out; not reproduced on a device): `AvTransportMediaRendererPlaying.updateTime()` scheduled the next tick on a **new `Timer` per tick** and re-armed itself as long as the field `updateTime` was true. That flag was only cleared by `stop()`, `pause()`,
  `next()` and `previous()` of the state. A new `SetAVTransportURI` while playing (Home Assistant's `play_media` when something plays) leaves Playing through `setTransportURI` without clearing it, a player that is closed in the app leaves nothing at all, and `play()` / `onEntry`
  (re-entered by every Play, also Play while playing) started **another** chain without stopping the first. So each Play left one more chain that ran for the rest of the process, each tick once a second, each tick calling `getDuration()` on the (stale, closed) player - a `LocalMediaSessionPlayer`
  without media answers `"00:00:00"`, not `""`, so every tick wrote `doSetTrackInfo: ...Posinfo:... Duration: 00:00:00` at D level (with the object identity of the transport). N chains = N lines a second at random phases, which is the owner's picture (several per second, `.222 .360 .430 .650 .651`), with no player active.
  Not the cause: the new event machinery. `doSetTrackInfo` only sets fields of the `PositionInfo` (it never touches the `LastChange`); `RelativeTimePosition`/`AbsoluteTimePosition` are not in `appendCurrentState` and nobody puts them into the `LastChange`, they are only answered by `GetPositionInfo`.
  Fix: `TrackInfoUpdater` (package private, `upnp/server/avtransport`), owned by the Playing state: one scheduled task (shared daemon scheduler, ends when idle), started in `onEntry` (a second `start` replaces the first), stopped in the new `onExit()` (every way out of the state) and by itself
  when none of its players is among `UpnpClient.getCurrentPlayers()` any more; it asks the first active player (on the main thread, as before), writes the duration and elapsed time only when they differ from the position info and logs that at **verbose** level (`track info: ...`; also `track info updates started/stopped`).
  `next()`/`previous()` no longer stop the update (they only log). Tests: `TrackInfoUpdaterTest` (not started / stopped / no active player: no player call, no log, task cancelled; unchanged values: no write, no log; no event), `AvTransportCommandsAndEventsTest.thePositionInfoIsOnlyUpdatedWhileThePlayingStateIsCurrentWhicheverWayItIsLeft`.
  Manual test: with log level D set a player on the renderer (Home Assistant `play_media`), play twice, close the player in the app: no `doSetTrackInfo` / `track info` line may appear at D level, and none at all after the player is closed (V level: `track info updates stopped` once).

### Media renderer: the transport leaves PLAYING when the playback ends (issue #296, owner test 2026-11-01)
- **Symptom.** Home Assistant flow: repeat off -> `tts.speak` (2 s mp3) -> shuffle on -> 3 s -> repeat one -> `media_player.play_media` (music). The announcement played, the music never started.
- **Cause chain (owner's app log and Home Assistant's debug log, 19:51:34-19:51:37).**
  1. The player ends (`LocalMediaSessionPlayer`: "End of the playlist reached, repeat is off: stopping" -> `player.stop()`), and **nothing told the AVTransport state machine**: no event, `GetTransportInfo` kept answering
     PLAYING, the Playing state stayed current (`GetPositionInfo` already said RelTime 2 / Duration 2). Home Assistant's `transport_state` is the last evented `TransportState`: PLAYING (from the event at `SetPlayMode`).
  2. `async_play_media` of Home Assistant's `dlna_dmr` (read from its source): Stop if `can_stop`, `SetAVTransportURI`, then `if self._device.transport_state == PLAYING or not autoplay: return` - **no Play** - else Play. The log shows exactly that:
     Stop at 37.410 (answered 37.423), `SetAVTransportURI` 37.423-37.452, no Play.
  3. The STOPPED event of that Stop reached Home Assistant at 37.652, **242 ms after the Stop**: `AsyncLastChangeServiceManager` held it back for the pace (`MIN_EVENT_INTERVAL_MILLIS` = 250 ms from the end of the previous event). Even a correct
     Stop was therefore too late for a controller that looks at its cached state right after its own commands.
- **Fix 1: the end of the playback reaches the state machine.**
  - `Player.PROPERTY_STOPPED` ("stopped", old/new value null): `AbstractPlayer.stop()` (at the end of its task, after `setPlaying(false)` and the command flag) and `LocalImagePlayer.stop()` fire it. Whoever stops the player: the end of the
    playlist with repeat off (`LocalMediaSessionPlayer` `STATE_ENDED` -> `stop()`; `AbstractPlayer.advance` at the end of a list -> `stop()`), the stop button of the app, `onDestroy()`, the transport's own `Stop`. A pause does **not** fire it
    (`pause()` is `setPlaying(false)` and then `paused = true`, so the `playing` property cannot tell a pause from a stop). Repeat all / one never stop, so they never fire it.
  - `AvTransport` (listener `playerListener`, registered by `attachPlayers`) queues `requestStop(player)`; a removal of the last player (`checkPlayersGone` -> `playersGone`, which also forgets the play mode) queues `requestStop(null)`.
    `YaaccAVTransportService.createStateMachine` gives the transport the state machine's new signal `AvTransportStateMachine.playerStopped(Player)` as `setStopRequester`.
  - The states implement `playerStopped`: **Playing and Paused** ask `AvTransport.playbackEnded(player)` and, if it says yes, log `AVT player ended: transport -> STOPPED` (D) and return `AvTransportMediaRendererStopped.class` - the same transition as the
    `Stop` action, so `Stopped.onEntry` puts `TransportState` and `CurrentTransportActions` into the `LastChange` and `Playing.onExit` stops the `TrackInfoUpdater`; position info and media info stay readable. **Stopped and NoMediaPresent** return null (nothing
    changes; this is also why the player's own `stop()` after a `Stop` action causes no second transition).
  - `playbackEnded(player)` (inside the state machine, so Play/SetAVTransportURI cannot interleave): a player that is not among the transport's attached **and** active players says nothing (the player replaced by the next item stops late and must not
    drop the new playback; this is the guard that complements the `startingPlayers` one, which keeps the *removal notice* of the replaced player from counting as "no player left"); with several players the transport stays as it is while another attached, active
    player is playing or paused; for `null` (removal) it needs that no attached player is active any more. When it says yes it sets `playersAlreadyStopped`, which the Stopped state that follows reads once and then does **not** call `stop()` on the players
    again (they have stopped or are gone; it would be a second toast and, on removal, a stop of an unrelated player of the same kind that `getCurrentPlayers(transport)` finds).
  - **Locks.** The state machine (`StateMachineInvocationHandler.invoke`) is `synchronized`; a transition (with `Playing.onEntry` creating players, up to `waitForPlayerServiceComeUp`) holds that monitor, and actions hold the manager lock (5 s) and take the monitor
    inside. The requests therefore never run where they are raised: not on the player's timer thread, not on the main thread (the removal notice) and not inside a transition (`playersStarted()` calls `checkPlayersGone()` from `onEntry`; a nested transition
    on the same thread would run `Stopped` while `Playing.onEntry` is still going on). They are queued on one daemon thread (`avtransport-player-end`, ends after 10 s idle, ordered) that takes the monitor like an action does but **no manager lock**, so the
    order manager lock -> state machine monitor -> `LastChange` monitor is the only one there is (the event thread takes only the `LastChange` monitor). Inside the monitor `playbackEnded` calls only getters (`isPlaying`, `isPaused`, `getCurrentPlayers`), like
    `Playing.onEntry` already does. `AvTransportPlayerEndTest.theEndOfPlayersAndCommandsInParallelNeverBlockOrFail` runs ends, closes, Stop, SetAVTransportURI and Play in parallel: all answered 200, no lock timeout, the next playback works.
  - Not covered: a first Play for which no player at all could be created (nothing was ever attached) stays in Playing, as before.
- **Fix 2: a change of the transport state is not paced.** `AsyncLastChangeServiceManager.isUrgent(LastChange)` (default false; `AvTransportServiceManager` says true for a pending `TransportState` or `CurrentTransportActions`, the Cling classes that the states put
  in): `requestFireLastChange` then schedules at once, also when an event is pending for the pace, and `fireNow` does not wait for the pace. Everything that is pending goes out in that event (the `LastChange` cannot be split), the pace of the next
  *non-urgent* event is counted from its end, order and the single event thread are unchanged, and nothing is dropped (urgent only skips a wait). RenderingControl stays paced (volume/mute).
  Log: `AVT event: TransportState=<state> (instance 0) sent to <n> subscribers` (D) next to the existing `CurrentPlayMode` line.
- **Flow after the fix** (`AvTransportPlayerEndTest.announcementThenMusicLikeTheOwnersHomeAssistantFlow`): the announcement ends -> the transport is STOPPED and the event goes out at once; shuffle / repeat one are answered in Stopped; Home Assistant's Stop is answered
  200 in Stopped (no 701, no new event: `ObservableLastChange` drops an equal value); `SetAVTransportURI` -> Home Assistant's cached state is STOPPED, so it sends Play -> PLAYING. Stop while playing: the STOPPED event follows the PLAYING event within
  milliseconds (`theStoppedEventOfAStopIsNotHeldBackWhenTheLastEventJustWentOut`).
- **Fast clicks on Home Assistant's play / pause button (owner test, 2026-11-05).** Home Assistant's player has one play / pause button; clicked fast, a second `Pause` met the paused renderer and was answered with UPnP error 701 ("State ...AvTransportMediaRendererPaused doesn't support signal 'pause'"): the state machine looks a signal up by its name on the *current state class* and answers 701 when the class has no such method, and Cling's `PausedPlay` declares none. A command that asks for the state the renderer is in already is now a no-op answered 200 (the method returns `null`: no transition, no new event, no second `pause()` of the players): `pause()` in Paused, Stopped and NoMediaPresent. The Paused state also lists Seek, Next and Previous as possible actions and answered 701 to them: `seek` (REL_TIME) moves the players and the pause stays (`SeekTarget`, shared with Playing; it reads the target strictly - `H+:MM:SS[.fff]`, hours of at most 4 digits, at most 32 characters - and ignores anything else; the old lenient date parser took negative and huge numbers and was slow on very long digit strings under the transport's lock), `next` and `previous` do nothing, as in Playing. `Play` while PLAYING still restarts the item (accepted). A Pause in the app (player screen, notification) is still not reflected in the transport. Test: `AvTransportFastClicksTest` (all 7 failed before), `SeekTargetTest`.
- **Cover of a music track without a cover (owner's question, 2026-11-06).** The MediaStore music browsers (`MusicAllTitle...`, `MusicAlbum...`, `MusicArtist...`, `MusicGenre...`) give every track `http://<ip>:<port>/album/<albumId>` as `upnp:albumArtURI`; SAF tracks and the tracks of other servers carry none, so the client shows its own type icon. `YaaccUpnpServerContentHttpHandler.lookupAlbumArt` answered an album without a cover (Android 10 and later: `loadThumbnail` throws `IOException`; Android 9 and earlier: `ALBUM_ART` is null; also a settings or id problem) with the app logo `yaacc192_32`, and wrote a copy of it into the cache as `albumart<id>.jpg` for every such album. That is why the logo appeared in front of some tracks only. Now these answers are the music note glyph of the app (`res/raw/album_art_music_generic.png`, 512 x 512, gray `#8A8A8A` on transparent, the path of `ic_baseline_audiotrack_48`, rendered once from the vector; read once and kept, nothing is rendered or written per album) with `Cache-Control: public, max-age=86400` (`ContentHolder.cacheSeconds`) **only** where the answer says "this album has no cover" (`ALBUM_ART` null, `loadThumbnail` throws); a disabled server, a missing id, an album the table does not know (Android 9 and earlier; on Android 10 and later `loadThumbnail` throws for it as for a missing cover, so it is cacheable there) or a failed file write get the same picture without the header, and so does the app logo that stands in when the picture itself cannot be read (no picture at all: 404). An album id is only accepted in its canonical spelling (`canonicalAlbumId`: digits, no sign, no leading zeros): `Long.parseLong` takes `007`, `+7` and non-ASCII digits as 7, and since the cover cache file is named after the id, every spelling would have written a cover file of its own (1024 x 1024 JPEG, rewritten per request); other spellings get the generic picture without a header, and the file name uses the parsed number. A cover that exists is sent as before. `lookupThumbnail` (photos) keeps the app logo. Tests: `GenericMusicIconResourceTest` (reads and decodes the shipped PNG on the JVM), `AlbumArtFallbackTest` (the Android 9 branch of `lookupAlbumArt`: no cover, cover, no row, disabled server, missing id, unreadable picture; the unit tests run with `SDK_INT` 0, so the `loadThumbnail` branch of Android 10 and later is only reviewed, not run).
- **Other listeners of the transport state.** Controllers of this renderer (Home Assistant, other apps) through the events and `GetTransportInfo`; the app's own receiver lists (`RendererStatusMonitor`, the device sheet, `ReceiverRowBinder`) poll `GetTransportInfo` of
  receivers, including a YAACC receiver, so their rows now show STOPPED after a track ended instead of PLAYING. `AVTransportController`/`AVTransportPlayer` (the app as controller of *remote* renderers) are not affected.
- Tests: `AvTransportPlayerEndTest` (the reproduction - 10 of its tests failed before the fix: stuck PLAYING, STOPPED event 251 ms after PLAYING - plus replaced/late/foreign players, pause, two players, no player created, parallel ends and commands, pace kept for other variables),
  `RendererHarness` fake players now keep `isPlaying`/`isPaused` and fire `PROPERTY_STOPPED` from `stop()` like `AbstractPlayer`, `ServiceHarness.sentEventNanos`. Adapted: the tests that waited for *exactly one* event after subscribing to a playing transport now wait for at least one (the PLAYING
  change is no longer held back and may arrive right behind the initial event), and `AvTransportPlayModeMemoryTest.nothingIsSentWhenTheControlPointAlreadySawTheModeOfTheSettings` checks that no *play mode* event is sent when the last player is closed (a STOPPED event is, now).
- Not verified without a device / Home Assistant: the real timing of `LocalMediaSessionPlayer`'s end (`STATE_ENDED` -> main thread -> `stop()` task -> event), that Home Assistant then sends Play, and the toast/notification of the player at the end.
  Manual test: repeat off, play the flow of the issue; the log must show in order `End of the playlist reached, repeat is off: stopping`, `AVT player ended: transport -> STOPPED`, `AVT event: TransportState=STOPPED (instance 0) sent to 1 subscribers`, then Home Assistant's
  Stop / SetAVTransportURI / **Play** and `AVT event: TransportState=PLAYING`. Also: stop with the stop button of the player screen and close the player while playing: the same two `AVT` lines.

### Not verified on a device
ExoPlayer's callbacks (order of `onShuffleModeEnabledChanged` and the timeline change) when an external controller turns shuffle on and `setShuffleOrder` follows, system / Bluetooth / Wear / notification
controllers against the real media session, dragging and deleting in the playlist dialog on a touch screen and with the D-pad, `moveMediaItem` / `removeMediaItem` while playing in the shuffle order.
ExoPlayer's behavior with `setShuffleOrder` + REPEAT_MODE_ONE/ALL (order at the wrap around, next/previous in shuffle, `onMediaItemTransition` reasons), the layouts of the new buttons
(portrait and landscape, small phones, TV focus ring and D-pad order), the menu items of the image viewer in the title bar, Home Assistant `SetPlayMode` against the real renderer, and its GENA subscription to the AVTransport service (that its callback is reachable from the phone, that
it accepts the initial event and an event right behind it - they are sent from two threads of the registry pool and can overtake each other - and what it shows afterwards), Home Assistant's mute and volume against the renderer's RenderingControl events, and whether the device sends `android.media.VOLUME_CHANGED_ACTION` / `STREAM_MUTE_CHANGED_ACTION` (hardware keys, system panel) to the unexported receiver on every Android version (the changes through the app and the controller do not depend on it).

- **Items added during playback (local music player).** `LocalMediaSessionPlayer.addItems` appends the new items to the ExoPlayer playlist on the main thread when ExoPlayer's list was in step with the player's list before the addition (`ExoPlaylistSync.canAppend`; otherwise the next `startItem` builds the whole list), and installs the play order again when shuffle is on, so new items are placed at random behind the current one.

- **Unreachable event receivers.** `SendingEvent` reports each delivery to its `DeliveryListener`; the subscription (`ReceivingSubscribe.EventSendingSubscription`) counts failed deliveries in a row in `EventDeliveryHealth` and, at the third, removes itself from the registry and ends (`CancelReason.EXPIRED`), with one warning line `Event delivery to [...] failed 3 times in a row, dropping subscription ...`. A delivery that succeeds resets the count. A control point whose callback address is not routable from the phone (e.g. Home Assistant behind a proxy or in a container network without a callback URL override) therefore costs three connect timeouts, not one per event; when it renews, it gets 412 and subscribes again.

## YXC (the API of Yamaha MusicCast devices): behavior notes from open-source libraries (issue #307)

**Read this first.** These are our own notes about behavior that was read from the code of three open-source projects. They are not
Yamaha's documentation and nothing here comes from a Yamaha document. Firmware and models may behave differently; every point marked
"not verified" has to be checked on the owner's devices. "YXC" is the protocol abbreviation; "Yamaha" and "MusicCast" are trademarks of
Yamaha Corporation and are used here only to say which devices are meant. Identifiers that are part of the protocol (URL paths, JSON
keys, header names, enum values) are written as they appear on the wire. No code or text of the sources is copied.

Sources (downloaded 2026-11-02 into the session scratchpad, not into the repository):

| Tag | Project | Version read | License |
|---|---|---|---|
| `AMC` | `aiomusiccast`, github.com/vigonotion/aiomusiccast (Python, asyncio) | branch `main`; the files compared are identical to tag `0.15.0`, the version the Home Assistant integration requires | MIT (copyright 2021 Tom Schneider) |
| `PYA` | `pyamaha`, github.com/rsc-dev/pyamaha (Python, `requests` / `aiohttp`) | branch `master`, file header says version 0.3 | MIT (copyright 2017 Radoslaw Matusiak) |
| `HA` | Home Assistant core, `homeassistant/components/yamaha_musiccast` (the integration named `yamaha_musiccast`) | branch `dev` | Apache-2.0 |

`AMC: pyamaha.py` is an adapted copy of `PYA` (its first line says so); where both agree they are cited together, where they differ the
difference is named. Citation form: `tag: file (function or class)`. `HA` files are in the component folder above.
`AMC: musiccast_device.py` is the class `MusicCastDevice` unless another name is given.

### 1. Transport

- **Base URL.** `http://<device-ip>/YamahaExtendedControl/v1/<area>/<method>`; plain HTTP on the default port, no login, no TLS, no
  authentication header (AMC: pyamaha.py (class `System`, `Zone`, `Dist`, `NetUSB`: the `URI` tables); PYA: `pyamaha/__init__.py`
  (same classes)). The UPnP description of the device is on another port (HA falls back to `:49154/MediaRenderer/desc.xml` if SSDP did
  not tell it; HA: `__init__.py` (`get_upnp_desc`)), so the YXC port is not the UPnP port.
- **Areas** (first path segment): `system`, `netusb`, `tuner`, `cd`, `clock`, `dist` (the group, called "link"), and one segment per
  zone. The zone is selected by that segment: `main`, `zone2`, `zone3`, `zone4` (AMC: pyamaha.py (`ZONES`, `Zone.URI`); the library
  asserts the zone name is one of these four). Which zones exist comes from `getFeatures` (`zone[].id`, section 4); HA creates one
  player per reported zone and treats `main` as the default zone (AMC: musiccast_device.py (`fetch`); HA: `media_player.py`
  (`async_setup_entry`), `const.py` (`DEFAULT_ZONE`)).
- **GET or POST.** Reads and almost all setters are `GET` with query parameters; booleans are the lowercase words `true` / `false`
  (AMC: pyamaha.py (`_bool_to_str`)). The calls that send a JSON body by `POST` are the group calls `dist/setServerInfo`,
  `dist/setClientInfo`, `dist/setGroupName`, plus `system/setNameText`, the network setup calls, `netusb/setSearchString` and
  `clock/setAlarmSettings` (AMC: pyamaha.py (`Dist.set_*`, `System.set_name_text`, `NetUSB.set_search_string`, `Clock.set_alarm_settings`:
  these return a pair of URL and body, all others return a URL)). The body is the JSON text of an object; both libraries pass it as a
  string and set no content type of their own (AMC: pyamaha.py (`AsyncDevice.post`); PYA: `Device.post`). Whether the devices insist on
  a content type is not known; the libraries work without one.
- **Response envelope.** A JSON object whose first key is `response_code` (a number) and whose other keys are the data of the call,
  side by side in the same object (PYA: README.md (CLI example of `getDeviceInfo`); AMC reads fields directly from the top level, e.g. `power`, `group_id`; AMC: musiccast_device.py
  (`_fetch_zone`, `_fetch_distribution_data`)). Setters presumably return the object with the code only; the libraries never parse setter answers (not verified).
- **`response_code` meanings** (both libraries carry the same table and use it for nothing else; AMC: pyamaha.py (`RESPONSE_CODE`),
  PYA: `pyamaha/__init__.py`): 0 ok; 1 device still initializing; 2 internal error; 3 invalid request (method unknown or not applicable);
  4 invalid parameter (range, characters); 5 guarded (cannot be set in the current state); 6 timeout; 99 firmware update running;
  100 to 112 are for the streaming-service accounts (access error, other error, wrong user or password, expired / disconnected /
  limit-reached / invalid account, server maintenance, license error, read-only mode, station limit, access denied). **Neither AMC nor
  HA ever evaluates `response_code` or the HTTP status** (`grep` of all downloaded files: the only users of the number are the table and
  one comment about a service-account call). They detect failures only through transport exceptions and by re-reading the state
  (section 5). yaacc should evaluate the code itself: especially 5 (guarded) and 6 (timeout) after a group call.
- **Timeouts and retries.** GET requests carry a total timeout of 5 seconds (AMC: pyamaha.py (`AsyncDevice.get`)); POST has no timeout
  of its own (the session default applies). PYA has no timeout at all. There is no retry in the transport: a client error or timeout
  becomes a `MusicCastConnectionException` (AMC: pyamaha.py (`AsyncDevice.request`, `request_json`)). Retries exist only in the group
  calls (section 5), where each sequence is repeated once.
- **Cookies.** HA creates its HTTP session with a dummy cookie jar, i.e. cookies are not kept (HA: `__init__.py` (`async_setup_entry`),
  `config_flow.py`); the API does not need them.
- **Other transport detail.** `getNameText` is called by AMC with the id argument `None`, which the library renders literally into the
  URL as `?id=None` and still reads `zone_list`, `input_list` and `sound_program_list` from the answer (AMC: musiccast_device.py
  (`fetch`), pyamaha.py (`System.get_name_text`)). The intended form is without the parameter ("all"); the literal form evidently
  works. Not verified which of the two a device prefers.

### 2. Discovery

- **Marker in the UPnP description.** The device description XML (the document at the SSDP `LOCATION`) contains an element in a Yamaha
  namespace named `X_yxcControlURL` whose text is `/YamahaExtendedControl/v1/`. The library tests for the exact string
  `<yamaha:X_yxcControlURL>/YamahaExtendedControl/v1/</yamaha:X_yxcControlURL>` in the downloaded text (AMC: musiccast_device.py
  (`check_yamaha_ssdp`)); HA aborts the flow with the reason "control URL missing" otherwise (HA: `config_flow.py`
  (`async_step_ssdp`), `strings.json`). Consequences: the check is a plain string search, so a different namespace prefix would not
  match; our implementation should parse the XML and accept the element in any prefix whose namespace URI is the same. The namespace
  URI is not named in the libraries (not verified). **Verified on one real description (2026-11-02, see section 9):** the marker sits inside
  `<yamaha:X_device>` / `<yamaha:X_serviceList>` / `<yamaha:X_service>`, next to `X_specType`
  (`urn:schemas-yamaha-com:service:X_YamahaExtendedControl:1`) and `X_yxcVersion`; the namespace URI is `urn:schemas-yamaha-com:device-1-0`.
- **Control URL.** The libraries never use the element's value to build requests: they always use the fixed prefix
  `http://<ip>/YamahaExtendedControl/v1/` with `<ip>` = the host of the description URL (HA: `config_flow.py` (`async_step_ssdp`,
  `urlparse(...).hostname`); AMC: pyamaha.py (`AsyncDevice`)). So the port is 80. If the element ever carried another path or port,
  neither library would follow it.
- **What else identifies the device.**
  - SSDP-triggered flow: HA's manifest asks for the SSDP manufacturer text `Yamaha Corporation` (HA: `manifest.json`), then confirms
    with the marker above. The unique id is the UPnP serial number; the title is the UPnP `friendlyName` (HA: `config_flow.py`
    (`async_step_ssdp`)).
  - Manual flow (host typed in): `getDeviceInfo` must contain `system_id`; that value is the unique id, absent means "not a
    MusicCast device" (HA: `config_flow.py` (`_async_get_serial_number`); AMC: musiccast_device.py (`get_device_info`)). Whether the UPnP
    serial and `system_id` are the same string is not checked anywhere in the libraries (verified equal on the owner's devices, 2026-11-02).
  - `getDeviceInfo` also gives `device_id` (used as the base of all entity ids, `<device_id>_<zone>`), `model_name`, `system_version`
    and `api_version` (a number, compared with 1.19 and 1.17 in the code) (AMC: musiccast_device.py (`fetch`); HA: `entity.py`
    (`MusicCastDeviceEntity.device_id`)).
  - Names: `getNetworkStatus.network_name` is the name HA gives the player; `getNameText` gives the per-zone, per-input and
    per-sound-program display texts (the user can rename them) (HA: `media_player.py` (`async_setup_entry`); AMC: `fetch`).
- **Same-device mapping UPnP to YXC.** Both live on the same IP. yaacc's registry already has the description URL of each remote
  device; the host of that URL is the YXC host. No library maps the UPnP UDN to a YXC device id. **Verified on the owner's devices
  (2026-11-02):** the last 12 hex digits of the UDN (lower case) are the `device_id` of `getDeviceInfo` (upper case), and the
  `serialNumber` of the description is the `system_id`.
- **UPnP control URL of AVTransport.** For playing a URL AMC reads the description, takes the service whose id contains `AVT`, and
  uses its `serviceType` and `controlURL` on `http://<ip>:<description port>` (AMC: pyamaha.py (`AsyncDevice.dlna_avt_request`)).

### 3. Events (UDP push)

- **Registration.** There is no separate subscribe call. Any YXC request that carries the two headers `X-AppName: MusicCast/1.0` and
  `X-AppPort: <udp-port>` makes the device send events to that UDP port of the requester. AMC binds one UDP socket on all addresses
  with an OS-chosen port, then adds both headers to every later GET and POST, then sends one `getDeviceInfo` to register at once
  (AMC: pyamaha.py (`AsyncDevice.enable_polling`, `get`, `post`)); PYA does the same with a thread and a socket (PYA:
  `pyamaha/__init__.py` (`BaseDevice._init_socket`)). The libraries do not name the device-side source of the datagrams; they read
  from the socket and ignore the sender address (AMC: `MusicCastUdpProtocol.datagram_received`).
- **How long it lasts, renewals.** Neither library states a lifetime and neither has a renewal timer. AMC relies on the fact that
  every request repeats the headers; in Home Assistant a refresh of the whole state every 60 seconds (see "When no event arrives" below) plus every
  command re-sends them. **Lifetime of a registration: not stated in the sources, not verified.** yaacc should send the headers on every
  call and issue a harmless call (for example `getDeviceInfo`) on a timer shorter than any plausible lifetime until the owner's
  devices give a number.
- **Port.** The port is the ephemeral port of the client's own socket; there is no fixed port. One socket serves one device object in
  both libraries (one per device); a single socket for all devices should also work, because the port is only a number in the header (our inference, not verified).
- **Datagram format.** One UTF-8 JSON object per datagram. PYA reads at most 1024 bytes per datagram (PYA: `_socket_worker`); AMC
  has no limit. Datagrams that are not valid UTF-8 or JSON are logged and then handed on as `None`, which makes AMC re-read the whole
  state (AMC: pyamaha.py (`datagram_received`), musiccast_device.py (`handle`)). Code reading shows that after that re-read the
  handler would iterate over `None` and raise; whether this is harmless is not verified.
- **Top-level keys the libraries react to** (AMC: musiccast_device.py (`handle`)); every other key is ignored:

| Key | Content read | Reaction |
|---|---|---|
| `main`, `zone2`, `zone3`, `zone4` | `volume`, `power`, `mute`, `input` applied directly; flags `status_updated`, `play_info_updated` | flag set: `GET <zone>/getStatus` for that zone |
| `netusb` | `play_time` (seconds) is stored with the arrival time; flags `play_info_updated`, `preset_info_updated` | `GET netusb/getPlayInfo`, `GET netusb/getPresetInfo` |
| `tuner` | flag `play_info_updated` | `GET tuner/getPlayInfo` |
| `dist` | flag `dist_info_updated` | `GET dist/getDistributionInfo` |
| `clock` | flag `settings_updated` | `GET clock/getSettings` |
| `system` | flag `func_status_updated` | `GET system/getFuncStatus` |

  Notes: values such as the volume, the power state (`on` / `standby`), the mute flag and the input id arrive in the event itself
  (no follow-up needed); everything else of a zone (sound program, switches, sleep, ...) needs the `getStatus` call. A zone block with
  a new `input` that is, or was, `mc_link` also fires the group callbacks, so a group change made on the device is noticed from the
  zone event too (AMC: `_update_input`). The play position moves by itself between `play_time` events; HA computes it from the stored
  time of arrival (HA: `media_player.py` (`media_position_updated_at`)). Fields of the events that the libraries do not read (for
  example a device id at the top level, other `system` flags) are not described here.
- **When no event arrives.** HA's update coordinator refreshes the full state every 60 seconds (HA: `coordinator.py`
  (`SCAN_INTERVAL`, `_async_update_data`)); the refresh is `fetch()` and sends, in this order: `getNameText`, `netusb/getPlayInfo` and
  `netusb/getPresetInfo` (only if `getFeatures` lists `netusb` functions), `tuner/getPlayInfo` (if the tuner block exists),
  `dist/getDistributionInfo`, the clock settings (only when alarm functions exist), `<zone>/getStatus` for every zone and
  `system/getFuncStatus`. `getDeviceInfo`, `getNetworkStatus` and `getFeatures` are read once per object and never again (AMC:
  `fetch`). A failed refresh marks the entities unavailable; the integration code adds no back-off (HA: `coordinator.py`). During a group change the
  library waits up to one second, checking every 0.1 s, for the expected state to appear through events, and re-reads only
  `getDistributionInfo` when it does not (section 5). **Failure mode:** the group checks also compare the input of the zone
  (`mc_link`), which is updated only by events or `getStatus`, so without working events a join that needs the input check can fail and be
  retried (reading of `check_group_data`; not verified on a device).

### 4. Read calls

Structure of the answers as the libraries read them (keys not read by the libraries are not described; capture them on a device).

- **`system/getDeviceInfo`**: `device_id`, `system_id`, `model_name`, `system_version`, `api_version` (AMC: `fetch`; HA:
  `config_flow.py`). `api_version` and `system_version` are JSON numbers; PYA's README shows an answer that also has `destination`,
  `operation_mode`, `netmodule_version`, `netmodule_checksum`, `update_error_code` (PYA: README.md, CLI example). Not personal in itself, but `system_id` and `device_id` identify a physical unit.
- **`system/getFeatures`** (read once; AMC: `fetch`):
  - `system`: `func_list` (list of device-level function names, e.g. `party_mode`, `speaker_a`, `speaker_b`, `dimmer`; the names the
    libraries know are in AMC: const.py (`DEVICE_FUNC_LIST_TO_FEATURE_MAPPING`)), `range_step` (list of `{id, min, max, step}`; `id`
    `dimmer` is used), `input_list` (list of objects with at least `id`, `distribution_enable`, `play_info_type`; the last two are used
    to choose a "safe" input after leaving a group).
  - `zone`: a list, one object per zone: `id`; `func_list` (function names per zone; known ones in AMC: const.py
    (`ZONE_FUNC_LIST_TO_FEATURE_MAPPING`), e.g. `power`, `volume`, `mute`, `sound_program`, `equalizer`, `tone_control`, `pure_direct`,
    `link_control`); `input_list` (list of input **ids**, strings); `sound_program_list` (ids); `range_step` (`{id, min, max, step}`, ids
    used: `volume`, `equalizer`, `tone_control`, `dialogue_level`, `dialogue_lift`, `dts_dialogue_control`, `subwoofer_volume`);
    choice lists `tone_control_mode_list`, `equalizer_mode_list` (both default to just `manual` when absent), `surr_decoder_type_list`,
    `link_control_list`, `link_audio_delay_list`, `link_audio_quality_list`.
  - `netusb` (read as: the block exists and has a non-empty `func_list`), `tuner` (block exists), `clock` (`func_list`,
    `alarm_mode_list`, `alarm_preset_list`, `alarm_input_list`, `range_step` with `alarm_volume` and `alarm_fade`).
  - **Not read by any library:** a `distribution` block (client limit, compatible versions, `server_zone_list`). The only trace is a
    comment saying that the zones allowed as group server are in `server_zone_list` of `getFeatures` (AMC: pyamaha.py
    (`Dist.set_server_info` comment)). One comment says up to 9 client addresses can be given in one `setServerInfo` call.
    **Verified on three real devices (2026-11-02):** the block exists on all three: `version` (2.0), `compatible_client` (`[2]`),
    `client_max` (**9 on one device, 19 on the other two**, so 9 is not a protocol constant), `server_zone_list` (`["main"]`, the
    comment was right) and, on one device, `mc_surround` (an object; content not interpreted).
  - Unknown function names are only logged ("not known to the library"), never an error (AMC: `fetch`). yaacc must behave the same.
- **`<zone>/getStatus`** (AMC: `_fetch_zone`): `power`, `volume`, `mute`, `input`, `sound_program`, `sleep`, `party_enable` (device
  level, but delivered inside the zone answer; AMC stores it from whichever zone was read last, so a zone without it overwrites it with
  nothing: a quirk to avoid), and, if the zone supports them: `extra_bass`, `bass_extension`, `subwoofer_volume`, `adaptive_drc`,
  `enhancer`, `pure_direct`, `clear_voice`, `surround_3d`, `surr_decoder_type`, `dialogue_level`, `dialogue_lift`,
  `dts_dialogue_control`, `link_audio_delay`, `link_audio_quality`, `link_control`; objects `equalizer` (`mode`, `low`, `mid`, `high`)
  and `tone_control` (`mode`, `bass`, `treble`).
- **`system/getFuncStatus`**: `speaker_a`, `speaker_b`, `dimmer` (current value); readable only for functions the device lists in the
  system `func_list` (AMC: `_fetch_func_status`; the comment is in pyamaha.py (`System.get_func_status`)).
- **`system/getNetworkStatus`**: the libraries read `network_name` and `mac_address` (an object; HA uses its values as the device's
  MAC list) (AMC: `fetch`; HA: `__init__.py`). **Personal data:** the MAC addresses and the network name (it is usually a
  household label). The call has more fields (IP and Wi-Fi settings) that the libraries ignore; treat the whole answer as personal and
  never put it into fixtures or logs.
- **`system/getLocationInfo`**: only defined in the libraries, **never called** by AMC or HA (AMC: pyamaha.py
  (`System.get_location_info`)); its content is unknown from these sources. Room / location info may be personal; not verified.
- **`dist/getDistributionInfo`**: see section 5.
- **`system/getNameText`**: `zone_list`, `input_list`, `sound_program_list`, each a list of `{id, text}`. HA shows the input text as the
  source name and adds the id in brackets when the text equals another input's id or is not unique (HA: `media_player.py`
  (`source_mapping`)). Sound programs are shown by id; their texts are read but HA does not show them.
- **`netusb/getPlayInfo`**: `input` (the id of the network input currently selected, compared with the zone's input to know whether the
  zone plays network content), `playback` (`play` / `pause` / `stop`), `repeat`, `shuffle`, `artist`, `album`, `track`,
  `albumart_url` (a path on the device: HA prepends `http://<ip>`), `total_time`, `play_time` (seconds) (AMC: `_fetch_netusb`,
  `media_image_url`). `netusb/getPresetInfo` gives `preset_info`, a list of `{input, text}` (entries with input `unknown` are empty
  slots) (AMC: `_fetch_netusb_presets`).
- **Tuner** (`tuner/getPlayInfo`: `band`, `fm.freq`, `am.freq`, `rds.radio_text_a/b`, `dab.service_label`, `dab.dls`) is not
  relevant for yaacc.

What yaacc would use the calls for:

| Call | yaacc use |
|---|---|
| `system/getDeviceInfo` | confirm a YXC device, get `device_id`, `model_name`, `api_version` (feature gating, e.g. >= 1.17 for volume up / down) |
| `system/getFeatures` | zones, inputs, sound programs, ranges, which settings exist; once per device, cached |
| `<zone>/getStatus` | power, volume, mute, input, sound program, switches / numbers of the settings screen |
| `system/getFuncStatus` | speaker A / B, display brightness (device-level settings) |
| `system/getNetworkStatus` | not needed; skip (personal data) |
| `system/getLocationInfo` | not needed; skip |
| `dist/getDistributionInfo` | role, group id, group name, client list: the basis of the link UI |
| `system/getNameText` | display names of zones, inputs (shown instead of raw ids) |
| `netusb/getPlayInfo` | only to show what a device plays by itself; yaacc plays through UPnP, so optional |

### 5. Groups ("link")

Terms: **server** = the device that gets the music and distributes it; **client** = a device that plays what the server distributes.
Group id: 32 hex digits. "No group" is 32 zeros (AMC: const.py (`NULL_GROUP`); HA: `const.py`).

- **Who creates the id, and its format.** The controller. HA creates 128 random bits as 32 hex digits and writes them in uppercase; if
  the chosen server is already a server it reuses its current id (HA: `media_player.py` (`async_join_players`); HA: `homeassistant/util/uuid.py`
  (`random_uuid_hex`) is the generator, fetched for this check). The device does not hand out ids.
- **What the three states look like.** The libraries read from `getDistributionInfo`: `group_id`, `group_name`, `role`,
  `server_zone`, and `client_list` (list of objects of which only `ip_address` is read) (AMC: `_fetch_distribution_data`). They compare
  `role` only with `server` and `client`; **the value for "no group" is never named** in the code (the group id of 32 zeros is the
  test for "no group"). **Verified on three real devices (2026-11-02):** a device in no group reports `"role": "none"`, a group id of 32
  zeros, an empty `client_list` and a made-up `group_name` (`(Linked) <room>`: never use it for logic); a server reports
  `"role": "server"`, `"status": "working"` and `client_list` entries `{ip_address, data_type: "base"}`; a device that can be in a surround
  set adds an `mc_surround` object with its own `role` / `status` (`none`). The answer of a **client** is not captured yet. A client is
  recognizable from the zone status: `input` `mc_link`, `input_text` `MC Link`, `distribution_enable` false (a server keeps its own
  input and has `distribution_enable` true). For a client no server address is read: the libraries do not find the server from a client's answer. HA finds
  it by matching: a server entity whose `client_list` contains the client's IP **and** whose `group_id` equals the client's, and the
  client's zone input is `mc_link` (HA: `media_player.py` (`is_part_of_group`, `group_server`)). A reduced answer (keys missing for
  a device that is not a server) is tolerated by the code (`get` with defaults). Exact JSON for the three states: to be captured
  (task "Capture answers", Group 1).
- **Calls.** All on the device they name. `ip` always means the IP of the **other** device(s) as the controller sees it.
  - Server: `POST dist/setServerInfo` with `{group_id, zone, type: "add" | "remove", client_list: [ip, ...]}`; here `zone` is a string.
    With `{group_id: ""}` alone the device stops being a server and the group id returns to zeros.
  - Client: `POST dist/setClientInfo` with `{group_id, zone: [zone], server_ip_address}`; here `zone` is a **list** (this is how the code
    sends it, AMC: pyamaha.py (`Dist.set_client_info`) and musiccast_device.py (`mc_client_join`)). With `{group_id: ""}` alone the
    device leaves its group. A comment in both libraries says a device that is currently a server refuses `setClientInfo`; the server
    role must be cancelled first and `getDistributionInfo` must show a role other than `server` before the client call.
  - `GET dist/startDistribution?num=<n>` starts (or restarts) the distribution on a server; `GET dist/stopDistribution` stops it.
  - `POST dist/setGroupName` with `{name}`: UTF-8, up to 128 bytes, kept in volatile memory, empty means the default name. Defined in
    the libraries but **never called** by AMC or HA; the group name is only read back (AMC: pyamaha.py (`Dist.set_group_name`),
    `_fetch_distribution_data`). The effect of renaming is therefore not verified.
  - `<zone>/setInput?input=mc_link&mode=` on the client selects the link input (the libraries send the empty `mode=` too).
- **Link (create a group or add the first clients)**, as HA runs it for "server S, clients C1..Cn" (HA: `media_player.py`
  (`async_join_players`, `async_client_join`); AMC: `mc_client_join`, `mc_server_group_extend`):
  1. Power S on if it is off.
  2. If S is not a server but one of its zones is, that group is closed first; if S itself is a client it leaves its group first
     (see "A client that is itself a server").
  3. Choose the group id (reuse or new).
  4. For each client: power it on if needed; if it is a server, close that group first; if it is a client of another group, leave it first;
     then `POST setClientInfo {group_id, zone:[zone], server_ip_address: S}` followed by `setInput mc_link`.
     Wait until the client reports the group id and the input `mc_link`.
  5. Server side, once for all successfully joined clients: `POST setServerInfo {group_id, zone, type: "add", client_list}` then
     `GET startDistribution?num=<n>`. Wait until all clients are in the server's `client_list`, the group id is as chosen, the role is
     `server` and `server_zone` is the chosen zone.
  Order matters: clients first, then the server call that lists them, then start. A single `setServerInfo` can add several clients
  at once.
- **Extend** an existing group: the same steps with the server's current group id; only the new clients go through step 4 and 5.
- **Reduce** (one client leaves): the client first runs `POST setClientInfo {group_id: ""}` and waits for zeros; then the server runs
  `POST setServerInfo {group_id: <current>, zone, type: "remove", client_list:[ip]}`, waits until the client is gone from its list and, if
  clients remain, calls `startDistribution?num=<n>` again. If no client remains HA closes the group (AMC: `mc_client_unjoin`,
  `mc_server_group_reduce`; HA: `async_client_leave_group`, `async_check_client_list`).
- **Unlink the whole group** (server side): HA lets every client leave as above, then the server runs `GET stopDistribution`, then
  `POST setServerInfo {group_id: ""}` and waits for zeros (AMC: `mc_server_group_close`; HA: `async_server_close_group`).
- **`num`.** HA passes the number of clients that all known servers currently list, **computed before the call**; for the very first
  group it is therefore 0 (reading of `get_distribution_num`, not run). The library comment calls it the distribution number "on the
  current network". What the device does with it is not known (not verified; the owner's devices can show whether 0 works, which
  would simplify the call).
- **Expected-state checks, waits, retries.** Every group method holds one lock per device, performs its calls, then waits up to
  1 second (polling every 0.1 s) until all expected conditions hold; the data it checks is filled by events (`dist_info_updated` ->
  `getDistributionInfo`, zone `input`). On timeout it logs a warning, re-reads only `getDistributionInfo` and checks again. If the
  conditions still do not hold, the **whole method runs once more**; on the second failure it raises a group exception (AMC:
  `check_group_data`, `wait_for_data_update`, and the `retry=True` parameter of each method). HA retries a failed client join once more
  on top of that (HA: `async_join_players`). Expected conditions: extend = clients present, group id, role server, server zone; reduce =
  clients absent; close / unjoin = group id zeros; join = group id and input `mc_link`; zone join / leave (below) = input or power state.
- **Zones on one device.** A device can be client with one zone and server with another; the library and HA contain several special
  cases. (1) A zone of the **same device** as the server joins the server's `main` zone by selecting the input `main_sync`; this works
  only for the main zone as server, otherwise HA raises an error. (2) If the device is already a client of the same group through
  another zone, the zone joins by selecting `mc_link` only (no group calls). (3) Leaving a zone that uses `main_sync`, or one of several
  zones using `mc_link`, does not unjoin the device: AMC selects a "safe" input (the first input of `getFeatures` `system.input_list`
  that has `distribution_enable`, is not a link input, is allowed in that zone and is not the network input if another zone already
  uses it) and powers the zone off (AMC: `zone_join`, `zone_unjoin`, `get_save_inputs`).
- **A client that is itself a server.** HA closes that group first (every one of its clients leaves, then `stopDistribution` +
  `setServerInfo ""`), because the device refuses the client call while it is a server (HA: `async_client_join`). Likewise a chosen
  server that is a client first leaves its group with the forced form (`setClientInfo ""`), because a device cannot be both (HA:
  `async_join_players`).
- **Group changes made outside the library.** When a zone's input leaves `mc_link` (for example the user picks another source in the
  vendor app) HA's callback removes that device from the server's client list (`setServerInfo ... remove`), and closes the group if only
  the server is left (HA: `media_player.py` (`update_all_mc_entities`, `async_check_client_list`); AMC: `_update_input`). This suggests
  that the device does not always drop such a client on its own; not verified.
- **Which devices can be linked (HA).** HA accepts every MusicCast player entity of the installation as a member and sets the
  grouping feature on all of them without looking at `getFeatures` (HA: `media_player.py` (`MUSIC_PLAYER_BASE_SUPPORT`,
  `get_all_mc_entities`)). So the libraries do not tell which devices cannot link; check for the `mc_link` input in the zone's
  `input_list` and the `distribution_enable` flags (see section 4) as a gate in yaacc (not verified that these suffice).
- **Group and playback.** Playing is done on the server. HA's client entities take the cover art from their server (HA: `media_image_url`).
  When HA starts a URL on a device it first selects the input `server` with `mode=autoplay_disabled`, then sends UPnP AVTransport
  `Stop`, `SetAVTransportURI` (with DIDL metadata) and `Play` (AMC: `play_url_media`). A code comment says the AVTransport requests carry a
  User-Agent copied from the vendor's iOS app because otherwise the main zone switches itself to the `server` input on `Play`
  (AMC: pyamaha.py (`dlna_avt_request`)). **This affects yaacc directly** (we send AVTransport to the same devices); effect to be
  tested.

### 6. Settings the HA integration exposes

HA registers four platforms: `media_player`, `number`, `select`, `switch` (HA: `__init__.py` (`PLATFORMS`)). Nothing is hard-coded per
model: AMC turns every entry of the `func_list` of the device and of each zone into a feature flag (unknown names only logged), and a
table maps a flag to a "capability" (number, option or switch) built from `getFeatures` data; HA creates an entity per capability
(AMC: musiccast_device.py (`fetch`, `build_capabilities`), capability_registry.py; HA: `number.py`, `select.py`, `switch.py`
(`async_setup_entry`), `entity.py`). The entity exists only if the function is listed. Weak spot: the registry reads the range of
`volume`, `equalizer`, ... from `range_step` by id without a guard, so a device that lists the function but not its range would
break; yaacc should check both.

**Media player** (one per zone; HA: `media_player.py`; AMC: musiccast_device.py):

| Feature | YXC call (zone prefix `<zone>/` where it applies) | Notes |
|---|---|---|
| power on / off | `setPower?power=on` / `standby` | needs `power` in zone `func_list` |
| volume set / step | `setVolume?volume=<n>&step=1`, `setVolume?volume=up|down[&step=n]` | needs `volume`; see section 7 |
| mute | `setMute?enable=true|false` | needs `mute` |
| source (input) | `setInput?input=<id>&mode=` | choices = zone `input_list`; texts from `getNameText` |
| sound mode | `setSoundProgram?program=<id>` | choices = zone `sound_program_list` |
| play / pause / stop | `netusb/setPlayback?playback=play|pause|stop` | only when the zone's input is the network input |
| previous / next | `netusb/setPlayback?playback=previous|next`; tuner: `tuner/setFreq?band=..&tuning=auto_down|auto_up&num=0` or DAB `setDabService?dir=` | |
| repeat | api >= 1.19: `netusb/setRepeat?mode=off|one|all`; older: `netusb/toggleRepeat` (emulation compares the cached state) | |
| shuffle | api >= 1.19: `netusb/setShuffle?mode=on|off`; older: `netusb/toggleShuffle` | `shuffle` also has `songs` / `albums` values per a comment |
| presets | `netusb/recallPreset?zone=<z>&num=<n>`, `netusb/storePreset?num=<n>` | list from `netusb/getPresetInfo` |
| play URL | see section 5 (input `server`, UPnP AVTransport) | |
| browse | `netusb/getListInfo?input=..&index=..&size=8&lang=en&list_id=main`, `netusb/setListControl?list_id=main&type=select|play|return&index=..&zone=..` | not needed |
| group join / unjoin | section 5 | |

State shown: `on` + network input and `playback` = `pause` -> paused, `stop` -> idle, any other `on` state -> playing (HA: `state`).

**Switch** (`?enable=true|false`; all `BinarySetter`; the key to read from `getStatus` in brackets):
`setBassExtension` (`bass_extension`), `setExtraBass` (`extra_bass`), `setEnhancer` (`enhancer`), `setPureDirect` (`pure_direct`),
`setAdaptiveDrc` (`adaptive_drc`), `setClearVoice` (`clear_voice`), `set3dSurround` (`surround_3d`), all under `<zone>/`; device level
`system/setSpeakerA` and `setSpeakerB` (`getFuncStatus.speaker_a/b`) and `system/setPartyMode` (`party_enable`). Each exists if the
matching name is in the zone or system `func_list`.

**Number** (value range from the zone's `range_step` entry with the id in brackets; HA sets min, max and step from it; AMC checks that
a value is within the range **and** a multiple of the step before sending):
`setEqualizer?low|mid|high=` (`equalizer`), `setToneControl?bass|treble=` (`tone_control`), `setDialogueLevel?value=`
(`dialogue_level`), `setDialogueLift?value=` (`dialogue_lift`), `setDtsDialogueControl?num=` (`dts_dialogue_control`; marked
undocumented in the library), `setSubwooferVolume?volume=` (`subwoofer_volume`). Note: AMC's range check uses `value % step`, which
treats a range that does not start at a multiple of the step wrongly; yaacc should check `(value - min) % step`.

**Select** (the option list comes from the device where one is named in brackets; AMC lowercases the options and replaces spaces by
underscores for display and maps back to the original value before sending, so the raw value is sent):
`setEqualizer?mode=` (`equalizer_mode_list`), `setToneControl?mode=` (`tone_control_mode_list`), `setSurroundDecoderType?type=`
(`surr_decoder_type_list`), `setLinkControl?control=` (`link_control_list`), `setLinkAudioDelay?delay=` (`link_audio_delay_list`),
`setLinkAudioQuality?mode=` (`link_audio_quality_list`), `setSleep?sleep=` (fixed list 0, 30, 60, 90, 120 minutes; AMC rounds a value up
to a multiple of 30), and device level `system/setDimmer?value=` (range from the system `range_step` id `dimmer`; the value -1 means
automatic). Value names seen in the HA translations (normalized spelling): link control `standard`, `stability`, `speed`; link audio
delay `balanced`, `lip_sync`, `audio_sync` (and `audio_sync_on` / `audio_sync_off`); link audio quality `compressed`, `uncompressed`
(HA: `strings.json`). A comment in the library says the audio delay setting has no effect while link control is the stability-oriented
mode. These three are the "link control (audio delay / quality)" items of the plan.

**Known but not exposed by HA:** `setDirect`, `setBalance`, signal info, speaker pattern, HDMI out, `prepareInputChange`,
clock and alarm are in the libraries (AMC: `configure_alarm`), but none of the downloaded HA platform files uses them.

### 7. Volume

- **Unit.** The volume is an integer in the zone's own scale: `getFeatures` -> zone -> `range_step` -> entry `id` `volume` gives `min`,
  `max`, `step` (AMC: `fetch`). The same number is read in `getStatus.volume` and set with `setVolume`. AMC/HA map the 0..1 slider
  linearly onto `[min, max]`, rounding to an integer and **ignoring `step`** (AMC: `set_volume_level`; HA: `volume_level`). The scale
  is not percent and differs per model (the defaults 0..100 in the data class apply only to a zone without the volume function).
- **dB.** The libraries map a zone function named `actual_volume` to a feature flag, but nothing uses it: **no dB value is read or
  written** by AMC or HA (AMC: const.py (`ZONE_FUNC_LIST_TO_FEATURE_MAPPING`); `grep` for usages). Whether `getStatus` carries a dB
  value is not known from these sources (not verified).
- **Step.** `setVolume?volume=up` or `down`, optionally with `&step=<n>`; without a step the device uses its smallest step. The
  comment ties this to API version 1.17 or later. HA's volume up / down buttons send no step (AMC: `volume_up`, `volume_down`;
  PYA/AMC: pyamaha.py (`Zone.set_volume`)). For an absolute value AMC also sends `&step=1`, which the comment says matters only for up /
  down.
- **Mute.** `setMute?enable=true|false`; the state is `mute` in `getStatus` and in the zone event (AMC: `mute_volume`, `handle`).
- **Linked devices.** HA has **no group-volume logic**: every zone keeps its own volume entity and slider; nothing sets the clients when
  the server's volume changes, and nothing reads a group volume (HA: `media_player.py`, whole file). The libraries map the
  device function names `party_volume`, `party_mute` and `party_mode`; only `party_mode` is used (a switch on `system/setPartyMode`),
  the other two are not (AMC: const.py; capability_registry.py). **Whether a server's volume propagates to the clients is not answered by the sources; measured on three real devices 2026-11-02: it does not (a `setVolume` on the server leaves a linked client unchanged and the other way round), the manufacturer's own app propagates by itself**;
  it has to be tried (section 8). Because the client keeps its own volume entity in HA, a user can set client volumes independently.

### 8. Open points / what could not be verified

Status after the implementation (2026-11-03). Answered on the owner's real devices:
1. ~~Does the server's volume reach the clients?~~ No, in neither direction; `setVolume` of a client works while linked (section 7, 11). yaacc propagates the group volume itself.
3. ~~`getDistributionInfo` of a client~~ Answered (section 9, 10).
4. ~~`getFeatures` `distribution` block~~ Answered: yes, with `client_max` (section 9).
5. ~~`num` of `startDistribution`~~ Answered: the parameter is required, 0 for a new group (section 9).
6. ~~Error answers of group calls~~ Answered: the devices validate very little; only `setClientInfo` on a working server is refused with code 5 (section 10).
7. ~~After unlinking~~ Answered: a client keeps the role `client` and the input `mc_link` until another input is set (section 9, 10). **Experiment 2026-11-04 (owner, two devices):** stopping only the server side (`stopDistribution` -> `{"response_code":0}`, then `setServerInfo {"group_id":""}` -> `{"response_code":0}`) leaves the client unchanged: `main/getStatus` still `input: mc_link`, `getDistributionInfo` still `role: client`, the **old group id**, a made-up `group_name` `(Linked) <room>` and a `client_list` naming the client itself (`data_type: base`). So the device does not leave on its own and does not return to its earlier source; the client has to be cleared (`setClientInfo {"group_id":""}`) and the input set by the controller. yaacc already clears every client first and then the server (section 5), and puts the input back itself (pre-join input, else the monitor's last plain input, no guessing). **Still open:** whether the manufacturer's app remembers the last source on the device or in the app (owner to compare after a clean unlink from that app).
9. ~~Mapping a UDN to a YXC device~~ Answered (section 2 and 9).

Not answered (nothing in yaacc depends on them; the app does not use them):
2. Life time of an event registration beyond the 30 s renewal, and delivery to a phone behind Wi-Fi power save or client isolation (yaacc falls back to the 30 s read).
8. Behavior of UPnP AVTransport on a client, and the User-Agent effect (section 5). yaacc sends to the group server only.
10. Which form of `getNameText` the devices prefer: yaacc uses the form without `id` and tolerates a failure; the real answer was not captured (the test fixture `yxc/nameText.json` is invented).
11. Stereo pair and `main_sync` handling: `stereo_pair` is a known function name but nothing in the sources uses it.
12. Content and privacy of `getLocationInfo` (never used).

Not covered in these sources: Bluetooth, alarm details, network setup, firmware update, the cloud services; and no information on
rate limits of the device.

### 9. yaacc implementation (issue #307, slices 1 and 1b)

Slice 1 is the pure-JVM core in the package `de.yaacc.yxc` (`yaacc/src/main/java/de/yaacc/yxc/`): no Android classes except the platform
`org.json`, no discovery, no UDP listener, no UI, no strings, no manifest change. The package and the classes use the protocol
abbreviation; the brand names appear only in comments that say which devices are meant.

**Classes.**

| Class | Role |
|---|---|
| `YxcTransport` / `YxcHttpTransport` | `get(path)` / `post(path, json)` returning the body text. The HTTP version uses `HttpURLConnection` against `http://<host>[:port]/YamahaExtendedControl/v1/`, connect timeout 3 s, read timeout 5 s, no redirects, no proxy, answers capped at 1 MiB. It talks only to the address it was constructed with (the caller takes it from our own discovery); host and path are validated so that no other address can be reached through a path. `X-AppName: MusicCast/1.0` and `X-AppPort: <port>` are sent on every call only when an event port is configured. A non-2xx status is a `YxcHttpStatusException`. A POST body is sent as `text/plain; charset=utf-8` (what the libraries' HTTP clients end up sending; not verified that the devices care). |
| `YxcClient` / `YxcClientListAction` | The calls: `getDeviceInfo`, `getFeatures`, `getZoneStatus`, `getFuncStatus`, `getDistributionInfo`, `getPlayInfo`, `setPower`, `setInput`, and the group calls `setServerInfo` (zone as a string), `setClientInfo` (zone as a list), the two "clear" forms, `startDistribution`, `stopDistribution`, `setGroupName`. Arguments are validated before anything is sent (zone id, 32 hex digit group id, 1 to 64 client addresses per call as a sanity bound - the limit of a device is its `client_max`, checked by the group service -, group name at most 128 bytes). |
| `YxcException` | Every failure of a call: transport error, HTTP status, an answer that is not a JSON object with a numeric `response_code`, or a `response_code` other than 0. Carries the call name (path without query), the code and the HTTP status. The body of an answer is never put into a message (it may carry household names). |
| `YxcDeviceInfo`, `YxcFeatures` (+ `YxcZoneFeatures`, `YxcSystemInput`, `YxcRange`, `YxcDistributionFeatures`), `YxcZoneStatus` (+ `YxcEqualizer`), `YxcFuncStatus`, `YxcPlayInfo`, `YxcDistributionInfo` (+ `YxcDistributionClient`) | Immutable values read tolerantly: a missing key, a JSON null or a value of the wrong type reads as absent; unknown keys and function names are ignored. `YxcFeatures` keeps per zone the function names, input ids, sound program ids, the ranges of the numeric controls (`YxcRange.accepts` counts from the minimum) and every `*_list` option list; nothing is derived from a model name. `YxcDistributionInfo` keeps the role word as sent and a `Role` (`NONE`, `SERVER`, `CLIENT`, `UNKNOWN`); the word `none` is `NONE` (slice 1b; verified), and `NONE` is still derived from the null group id plus an empty client list when the word is absent or unknown. `YxcDeviceInfo.toString()` leaves the ids out. |
| `YxcEvent` / `YxcEventParser` | A UDP datagram becomes the zone blocks (`power`, `volume`, `mute`, `input`, the two update flags), the `netusb` block (`play_time` and its flags) and the update flags of `tuner`, `dist`, `clock` and `system`; unknown keys are dropped. Garbage (not UTF-8, not a JSON object, over 16 KiB, nested deeper than 16) gives `YxcEvent.unparsable()`; the parser never throws. |
| `YxcDescriptionParser` / `YxcDescriptionInfo` | Slice 1b: the UPnP device description to the YXC service (control URL, version, host and port of `X_URLBase`, UDN, serial number, friendly name); `null` when the device has no `X_yxcControlURL` service. See "Slice 1b" below. |
| `YxcGroupService` (+ `YxcGroupMember`, `YxcGroupResult`, `YxcSleeper`) | `link`, `extend`, `reduce`, `unlink`, `rename` in the call order of section 5. The number of clients is limited by `distribution.client_max` of the server's own `getFeatures` (9 only when the block or the key is missing); the clients it already has count. Waiting is polling of `getDistributionInfo` (and the zone input for a client) every 100 ms for up to 1 s with an injected `YxcSleeper`; a step that did not reach its state is repeated once. A new group id is 32 uppercase hex digits from an injected `Random`. Expected failures are a `YxcGroupResult` (`SUCCESS`, `FAILED`, `TIMEOUT`, with the step and the address of the device): nothing is thrown for an unreachable device, a refusal or a late device. |

**Where the checks differ from the libraries.**
- `response_code` and the HTTP status are checked on every call (sections 1 and 8 point 6: the libraries ignore both). A refusal such as
  code 5 ("guarded") is a failed step with the call name in the detail text.
- The libraries wait for events; slice 1 has no listener, so the same expected states are polled.
- All devices of a request are read before anything is changed, so an unreachable client or server changes nothing. If a step fails
  after clients were set up, the clients set up by this call are taken back (`setClientInfo ""`, best effort); a client that had to
  leave another group first is not put back, and the old server may still list it (a client's answer does not name its server).
- `reduce` and `unlink` still go on when a client cannot be reached (the server removes it). When a server is closed, a refused
  `stopDistribution` is not a failure, because the retry would otherwise fail on a device that already stopped; clearing the server
  role is what is checked. `reduce` to nobody and `unlink` of a server both end the group; `unlink` of a device in no group is a
  success without any change call.
- Power is switched on (not awaited) for a server and for each client that is not on.
- `startDistribution(num)` gets the number of clients the server listed before the call (0 for a new group); the meaning of the
  number is not known (section 5, `num`).

**Fixtures** (`yaacc/src/test/resources/yxc/`, loaded by `YxcFixtures`). Hand-written, modeled on the keys of sections 3 to 5, with invented
neutral values: ids like `TESTDEVICE01`, addresses from the documentation range `192.0.2.0/24`, group ids like `0123456789ABCDEF...`, file
names without model names. Since slice 1b the "not linked" sample uses the real word `none`. The scrubbed answers of three real devices
(a, b, c; `captured/`, see its `README.md`; scrubbed with `.claude/specs/2026-11-02-issue307-musiccast/capture/scrub.py`) are fixtures too
and are read by `YxcCapturedDevicesTest`, `YxcDistributionInfoTest`, `YxcGroupServiceTest` and `YxcDescriptionParserTest`. The scrub
replaced every 32-digit id by a numbered one, so `b_distInfo_none.json` and `c_distInfo_none.json` carry a group id ending in `2`
where the device sent zeros: only the role word is reliable in those two files. A unit test
(`OrgJsonAvailableTest`) fails if the Android stub of `org.json` instead of the real one is on the test classpath (the app sets
`unitTests.returnDefaultValues = true`, which would silently return nulls); the JVM tests get the real one from
`testImplementation 'org.json:json:20250517'`, production code uses the platform's `org.json`.

**Tests.** `YxcHttpTransportTest` and `YxcClientLoopbackTest` run against a real HttpCore server on the loopback address with the
canonical host name `localhost` (`LoopbackYxcServer`; the transport then has to address it as `localhost`, otherwise the server answers
421); `YxcGroupServiceTest` runs against `SimulatedYxcNetwork`, a few in-memory devices that implement the group calls (a device that
serves refuses the client call, a client is needed for `mc_link`), can be unreachable, can fail or refuse a call, answer late (virtual
time, nothing sleeps) or never.

**Slice 1b: aligned with three real devices.** Verified on three real devices (a, b, c) of the owner on 2026-11-02 ("verified" below means
that; the captures are in `yaacc/src/test/resources/yxc/captured/`). What changed in the code:

| Item | Before (slice 1) | Now |
|---|---|---|
| Role word of a device in no group | unknown; `NONE` only from the null group id; the fixture used an invented word | the word is `none`; `YxcDistributionInfo` maps it (case-insensitive) and still derives `NONE` from the null group id; `status` and `client_list[].data_type` are kept (`getStatus()`, `getClientEntries()`); the `mc_surround` object is noted (`hasMcSurround()`, `getMcSurroundRole()`); `group_name` is only carried |
| `getFeatures.distribution` | `server_zone_list` read from a comment; client limit unknown | `YxcDistributionFeatures` (`version`, `compatible_client`, `client_max`, `server_zone_list`, presence of `mc_surround`) via `YxcFeatures.getDistribution()`; `getServerZones()` reads it |
| Number of clients | fixed 9 per call (a comment of a library) | `client_max` of the server's features (9 on device a, 19 on b and c): a request is refused (step `VALIDATE`, nothing changed) when existing + new clients exceed it; fallback 9 only if the block or the key is missing. `YxcClient.MAX_CLIENTS_PER_CALL` became `MAX_CLIENT_ADDRESSES` = 64, a sanity bound only |
| Is a device a client | group info only | group info stays the authority for membership; `YxcZoneStatus.isLinkInput()` (`input` is `mc_link`) is used for the expected state after a join and to decide that a member only needs the input back (no second `setClientInfo`) |
| Fields | a few | status: `sleep`, `max_volume`, `input_text`, `distribution_enable`, `equalizer` (`mode`, `low`, `mid`, `high`), `link_control`, `link_audio_delay`, `link_audio_quality`, `disable_flags`; features: `account_enable` and `rename_enable` of the inputs, typed getters for the equalizer / link option lists; play info: `attribute`, `repeat_available`, `shuffle_available`; func status: `auto_power_standby` |
| Discovery facts | notes only | `YxcDescriptionParser` (below) |

Verified facts from the three devices (besides those in sections 2, 4 and 5):
- `getFeatures`: the zone entry has an `input_list` of ids (as the model assumed) and `range_step` with `volume` 0..60 step 1 and `equalizer`
  -10..10; the device-wide `system.input_list` has objects with `id`, `distribution_enable`, `rename_enable`, `account_enable`,
  `play_info_type` (`netusb`, `tuner`, `cd`, `none`); `mc_link` and `airplay` are not distributable, `mc_link` can be renamed;
  `sound_program_list` exists on two devices, `link_audio_quality_list` on one, `link_audio_delay_list` has 2 or 3 words
  (`balanced` on the newer devices); the dimmer range differs per device (-1..2, 0..2, -1..9).
- `getStatus`: `max_volume` 60, `disable_flags` 0, `equalizer` as an object; `input_text` and `sound_program` are absent on the oldest device,
  `link_audio_quality` on two. A server in standby keeps its input.
- `getPlayInfo`: `repeat_available` / `shuffle_available` can be empty lists although `repeat` / `shuffle` have a value; `attribute` is a
  number (fits an int, read as a long); a stopped device reported `play_time` -60000 (unit and meaning not known; the earlier
  assumption "seconds" is unconfirmed).
- `getDeviceInfo`: `system_version` and `api_version` are JSON numbers (a trailing zero is lost: 2.10 would read as 2.1), `device_id` is
  upper case, `system_id` is the UPnP serial number; the oldest device has no `serial_number`, `category_code` or `analytics_info`.
- `getFuncStatus`: `auto_power_standby`, `dimmer` (-1 is automatic on the devices that allow it).

**Discovery facts (code only, `YxcDescriptionParser`; the next slice uses it).** The YXC service of the UPnP description is the
`<X_service>` inside `<X_device>` (namespace `urn:schemas-yamaha-com:device-1-0`) that has an `<X_yxcControlURL>` (a path; seen once:
`/YamahaExtendedControl/v1/`); `X_specType` is `urn:schemas-yamaha-com:service:X_YamahaExtendedControl:1`, `X_yxcVersion` the version text
(padded with blanks on the device, trimmed), `X_URLBase` the `host:port` of the device. The control URL base is `http://` + `X_URLBase` +
control URL. A device that also lists the old `X_YamahaRemoteControl` service (its control URL is in `<X_controlURL>`) is still found by the
YXC element; a device with only the old service, or no Yamaha block, is "not a YXC device" (`null`). Identity: the last 12 hex digits of
the UDN equal `device_id` (case-insensitive) and the description's `serialNumber` equals `system_id`; both are checked by
`YxcDescriptionInfo.matchesDeviceId` / `matchesSystemId`. The old-plus-new variant was **not** captured: its test file is invented in the
style of the real one (`description_old_and_new_service.xml`).

Choice of the XML parser: `org.xmlpull.v1.XmlPullParser` through `XmlPullParserFactory`, not namespace-aware (names are compared by their
local part, so any prefix or an undeclared one works). It exists on Android (the app's UPnP code uses it) and the unit tests have
`xmlpull` / `kxml2` as test dependencies, so the same class runs on the JVM and on the phone. `android.util.Xml.newPullParser()` returns null
in the unit tests (`returnDefaultValues`); `javax.xml.parsers` exists on both, but (from memory, not checked) the Android implementation
supports only a few `setFeature` names, so the hardening could not be tested the same way on the JVM and on the phone. Safety: a document with `<!DOCTYPE` or `<!ENTITY` is refused before
parsing (so no entity or external resource can be declared) and the parser is told not to process a declaration; the text is capped at
256 Ki characters, nesting at 32, each value at 256 characters; the control URL must be a plain absolute path (no scheme, `..`, query);
`X_URLBase` must be `host[:port]`. Malformed input gives `null`, never an exception. Not verified on a phone: how the platform parser
treats an undefined entity (the JVM test shows that kxml2 refuses the document).

**Not in slices 1 and 1b:** discovery itself (SSDP / registry integration and the HTTP fetch of the description; the transport still has the
fixed control path and takes host and port, the parser's `getHost()` / `getPort()` fit that), the UDP listener and its lifetime, the model
that applies events, polling, any UI, strings and manifest entries. **Still needs a device (open points of section 8):**
- the answer of `getDistributionInfo` on a **client** (role word, group id, whether it names its server); the slice treats `client` as the
  word and `isLinkInput()` as a hint only;
- UDP events: a first listen test received nothing, which is not conclusive (registration headers, Wi-Fi, port, firewall not ruled out);
- the effect and the required value of `num` in `startDistribution`; whether one `setServerInfo` call may carry up to `client_max` (19)
  addresses (the old 9-per-call comment may have come from devices with `client_max` 9);
- the content type of the POST body; the error answers (`response_code` of a rejected group call, HTTP status of errors); whether a refused
  `stopDistribution` really happens; whether the group id is returned in the case we send it;
- whether the server's volume reaches the clients: the owner saw server 27 -> 31 while the client went 24 -> 38, but the sequence of the
  owner's own changes is unknown, so this is not conclusive.

### 10. UDP events measured on a real device (2026-11-02, issue #307)

- **Registration works as the libraries do it:** one request with the headers `X-AppName: MusicCast/1.0` and `X-AppPort: <port>` is enough. The device then sends unicast UDP datagrams to the **address the request came from** and the **port named in the header**; the source port of the datagrams is chosen by the device (44204 in the capture) and is not 41100.
- **Format of the first events seen:** while the device played a network source, one datagram per second, 55 bytes: `{"netusb":{"play_time":140},"device_id":"<device id>"}`. So `play_time` counts **seconds** (140, 141, 142 ... one per second) and every event carries the **device id at the top level** (the `device_id` of `getDeviceInfo`, the last 12 hex digits of the UPnP UDN). `YxcEvent.getDeviceId()` reads it.
- **Other traffic of the device** that is not an event: multicast datagrams to 239.255.255.250 port 51200 (560 bytes, about every 2 s) and the usual SSDP on 1900. Ignored.
- **Change events (captured 2026-11-02 while the owner moved the volume slider and pressed mute in the manufacturer's app):** the zone block holds only what changed: `{"main":{"volume":29},"device_id":"..."}` (49 bytes), `{"main":{"mute":true},...}`, `{"main":{"volume":35,"mute":false},...}`, and blocks can share one datagram (`{"main":{"mute":true},"netusb":{"play_time":149},...}`). While a slider is dragged the device sends **many volume events in a few hundred milliseconds** (29, 31, 33, 38, 39, then 38, 35, 34, 32, 31 within about 1.5 s; some with the same timestamp). UDP does not guarantee order, so the receiver should apply the last event it saw and re-read `getStatus` once after the events have been quiet for about a second. There was no `status_updated` flag in these events.
- **Group and track events (captured 2026-11-02):** an **unlink** produced `{"dist":{"dist_info_updated":true},"device_id":"..."}` twice, 70 to 200 ms apart in two measurements (a flag only: read `dist/getDistributionInfo`). A **track change** produced a burst of five datagrams in about 1.5 s: `{"main":{"signal_info_updated":true},"netusb":{"play_time":0,"play_info_updated":true,"play_queue":{"updated":true}},...}`, then variants with only `play_info_updated`, only `signal_info_updated` or `play_queue.updated`; `play_time` restarts at 0 (it also jumped back by a few seconds once). Apply the flags as "read again": for a burst, read `netusb/getPlayInfo` **once**, about half a second after the last flag.
- **Power on (captured 2026-11-02):** right before `{"main":{"power":"on"}}` a datagram with `main.signal_info_updated` and `netusb.account_updated`, `play_time` 0, `play_info_updated`, `play_queue.updated` arrives; the play flags then repeat from about 2.5 s to 5 s after the power event (also with `signal_info_updated` alone). Do not treat these as playback: wait for the power event and the debounce. A `play_time` second and `dist_info_updated` can be in one datagram, and `dist_info_updated` can come once or twice (about 200 ms apart), also during the 1 s play_time ticks of a playing group.
- **Power off (captured 2026-11-02):** a single datagram `{"main":{"power":"standby"},...}`, no other event around it.
- **Pause and play (captured 2026-11-02):** pause (19:18:52) and play (19:18:59) each gave one datagram `{"main":{"signal_info_updated":true},"netusb":{"play_info_updated":true}}`, the same for both, so the new state must be read with `getPlayInfo`. After the play a `signal_info_updated` alone followed 2 s later. The `play_time` ticks (1 per second) stop while paused (last 179) and go on from the same position (180 about 3.5 s after the play event); no `play_time` event is sent while paused, so a silent `play_time` is not an error.
- **Unlink again (second measurement):** two `dist_info_updated` 70 ms apart, the first inside the `play_time` datagram, the `play_time` ticks go on. Link and unlink do not differ in the event.
- **Link (captured 2026-11-02, server side):** linking gave one `{"dist":{"dist_info_updated":true}}` (about 0.5 s after a `play_time` tick), then the normal `play_time` ticks went on without a gap or a play/input flag. So a link and an unlink both look like the plain `dist_info_updated` already in the fixtures; read `dist/getDistributionInfo` to know what happened.
- **Events of a group client (captured 2026-11-02, device c while linked, then unlinked, powered off and on, linked again):**
  - A client sends **no `play_time` ticks while it is linked** (none in about three minutes while the server ticks every second); once it plays a source of its own (net radio) it sends `play_time: 0` with the play flag at the start. Volume and mute changes arrive as the same `main.volume` / `main.mute` events (one per step, 0.3 to 1 s apart).
  - **Leaving the group** is one datagram with everything: `system.bluetooth_info_updated`, `main` with the new `input` (here `tuner`) plus `status_updated` and `signal_info_updated`, `netusb` `play_info_updated` plus `play_queue.updated`, and `dist.dist_info_updated`; **joining** is the same with `input: "mc_link"`. So the input arrives with the event and the zone, group and play info are read afterwards (`YxcEventPlan` asks for all three).
  - `dist_info_updated` also comes alone, once or twice about 1 to 4 s apart, around such changes.
  - A **tuner** source floods `tuner.play_info_updated` (about 60 datagrams in 20 s, up to 3 a second, also after power on). Nothing is read for it. `system.bluetooth_info_updated` and `netusb.account_updated` alone are noise as well.
  - **Power:** `main.power: "standby"` alone; on the way up first a wake-up datagram (`bluetooth_info_updated`, `tuner.play_info_updated`, `account_updated`), then `main.power: "on"` together with `account_updated`.
- **Input changes of an unlinked device (captured 2026-11-02, tuner, net radio, bluetooth, cd, tuner):** every change is `main.input: "<id>"` plus `status_updated: true`; for net radio, bluetooth and cd it comes with `system.bluetooth_info_updated`, `netusb.play_info_updated` and `play_queue.updated`, for the tuner alone it is only the input and `status_updated`. A second datagram (`signal_info_updated` with the play flags) follows 0.2 to 1 s later, and the net-radio start adds `play_time: 0`. Power on can be the single event `{"main":{"power":"on"}}`, with `account_updated` about 80 ms later. The event carries the input id but no display name, so the row shows the readable id until the zone is read (`YxcDeviceState.withEvent` drops the old name).
- **Link test on real devices (2026-11-02, `capture/link_test.sh`, server and client of two different devices, both in no group at the start):**
  - `setClientInfo` + `setInput mc_link` on the client, `setServerInfo` (type `add`) + `startDistribution?num=<n>` on the server: all `response_code` 0; the client reached the group id and `mc_link` within about 1 s; the server then reports `role: "server"`, `status: "working"`, the client in `client_list`.
  - **`num` is required:** `startDistribution` without it answers `response_code` 4. `num=0` and `num=1` were both accepted for a new group with the same result (nothing visible differs); the app sends the number of clients before the call (0 for a new group), as the open-source clients do.
  - **Without `startDistribution` the server stays `role: "none"`** with group id zeros (its `client_list` already names the client) while the client is in the group: the group exists only after the start call.
  - **Group names:** the server's name is generated from the room names ("<room> + 1 Raum", localized by the device); the client's `group_name` is "(Linked) " plus **its own** room name, not the server's. The client's answer does not name the server, and its `client_list` lists itself. A row that says "linked with ..." therefore has to find the server by the group id (the device that reports `role: "server"` with the same group id), not from the client's `group_name`.
  - **Unlink with the calls above** (client `setClientInfo` empty, server `stopDistribution`, server `setServerInfo` empty): both group ids are zeros at once (0 to 3 s). **The client then keeps `role: "client"` for as long as its input is `mc_link`; it turns `none` only after the input is set to something else** (measured twice on two clients: after the explicit clear, minutes later, still `client` with zero ids; one `setInput?input=tuner` and the next read says `none`). The first reading of this as a delay was wrong. The client's `group_name` stays "(Linked) <own room name>" with group id zeros, so the name only means something while the group id is not zero. The manufacturer's app moves a client back to its earlier input (leave datagram in section 10); yaacc remembers the last input that was not `mc_link` and sets it after the leave (no fallback: when no earlier source was ever seen the device is left on the link input, because the first input of `getFeatures` can be a streaming service; owner's report 2026-11-03: the device landed on Napster, while the native app puts it back on its last source - how the native app does that is to be found out, see the experiment in the notes of #307).
- **Error probes on real devices (2026-11-02, `ERRORS=1`, a second client, three variants):** the devices **validate very little**; a `response_code` 0 does not mean the call made sense.
  - `setClientInfo` sent to a **working server** is refused with `response_code` 5; sent to a device whose group was never started (role `none`) it is accepted.
  - `setServerInfo` sent to a **client** is accepted (code 0) and changes the client's `client_list` (it then lists the server's address) while it stays a client; the answer of a client's own `client_list` is therefore not a reliable source for anything.
  - `setServerInfo` with **another group id** on a working server is accepted: the server takes the new id while its client keeps the old one, `status` stays `working`, and the group is silently broken. `startDistribution?num=99` is accepted as well.
  - A server that never got `startDistribution` and then received a `setServerInfo` with a group id became `role: "server"`, `status: "working"` on its own.
  - A server's `status` reads **`building`** about 3 s after `startDistribution` in one run and `working` in another; wait for `working`.
  - **Rules for yaacc that follow:** read the role and group ids of every device before each call and decide on those answers; never send `setServerInfo` with a group id other than the one the server reports; never send a group call to a device that is in another role; leave a client with `setClientInfo` empty, a server with `stopDistribution` + `setServerInfo` empty, and a device that ended up as the wrong role with the matching clear call; a group is ready only when the server is `working`, the clients report the id and the input `mc_link`.
  - **Aftermath of the probes:** the client stayed `role: "client"` with zero ids and input `mc_link` until the owner set the input back by hand (then `none`).
- **Not yet captured:** the user-agent quirk test.
- **Local firewall:** the datagrams arrive at the network card of the owner's computer; an earlier listener run saw none with the computer's firewall (`ufw`) active and one while a client was asked. The event listener of the app runs on the phone, where no such firewall exists, but a device that is not reachable by unicast from the phone (client isolation of the access point) would receive no events: keep polling as the fallback.


## 11. YXC slices 2 to 4 in yaacc: state, groups, volume and settings (#307)

Implemented in `de.yaacc.yxc` (own code, no library code copied):
- `YxcDiscovery` fetches the UPnP description of each renderer once (`YxcHttpDescriptionFetcher`: http only, no redirects, no proxy, size and time bounded) and keeps the verdict per UDN; the endpoint host is always the host of the registry's description URL, a document can only add its port when it names the same host.
- `YxcMonitor` keeps a `YxcDeviceState` per device. Lifetime: event source and polling run while a screen subscribes and 30 s after the last unsubscribe; every 30 s a complete read is made (this also renews the event registration, every request carries `X-AppName`/`X-AppPort`). Events: values (power, volume, mute, input) are applied directly, flags ask for a read after 500 ms of quiet (`YxcEventPlan`: play info, zone status, distribution info; a group event reads the zone too, because a client's input follows the link).
- Trust: the sender address is checked before a datagram is parsed (only watched devices), then the `device_id`. Events are display-only; actions must read the device first.
- Limits: JSON depth (`YxcJson.parseObject`, quote-aware scan + `StackOverflowError` handler), 15 s per HTTP exchange (`YxcWatchdog`), 1 MB body, 64 devices.
- UI: `YxcRowFeed` (main-thread, coalesced) -> `ReceiverRowBinder.bindGroupState` -> `YxcRowText` ("On · Net radio · Linked with <group>"); names from the device are cleaned and capped. Listeners hear only of changes the row shows.
- The events of a linked client, an input change, `startDistribution num` and the error answers were measured afterwards (section 10 and the notes below).

- **Groups (slice 3):** `YxcGroupOperations` forms, extends, reduces and dissolves a group from the picker's selection after fresh reads of every device involved (refusals: a device in another group, a client of someone else, a device that does not answer); `YxcGroupService.leaveIfMember` clears a client with `setClientInfo` empty, a server with `stopDistribution` + `setServerInfo` empty. A group server is found by `YxcGroupDirectory.serverOf`: exactly one device that claims the group id and lists the client, otherwise unknown (no guess). `YxcPlaybackRedirect` + `YxcPlayTargets` (in `PlayerService.createPlayerFor`, never on the main thread) replace a client by its server. After a leave the former input comes from the pre-join read, else from the monitor's last non-link input; with neither the device is left on the link input.
- **Settings (slice 4):** `YxcSettings` builds the sheet's items only from `getFeatures`/`getStatus` of the device (power, input, sound program, sleep timer, equalizer bands within the reported range, link control, link audio delay, link audio quality) with caps on options and band steps; `YxcSettingsWriter` sends one change at a time through `YxcClient`; text from the device is cleaned (`YxcNames.clean`). Not built: tone control, surround, subwoofer, dialogue level, pure direct, enhancer, renaming of groups or inputs, playback controls of the device's own sources, a virtual group device.

- **Volume of a device with group support (2026-11-02):** the generic UPnP volume control of such a device did not change the volume while it was in a group (owner's observation). `UpnpClient.getVolume/setVolume/getMute/setMute/hasActionGet*(device)` therefore go through `YxcVolumeAccess` for devices the monitor knows as YXC devices: the slider's percentage maps onto the device's own scale `0..max_volume` (from `getStatus`), writes are `main/setVolume?volume=<n>` and `main/setMute?enable=<bool>`, the latest value of a device wins while a write is on its way, reads are the zone status. Each device keeps its own volume (the group volume slider of Group 4 comes on top of this).
- **Group volume (2026-11-02):** a slider on the row of a group server (shown when it serves at least one resolved client) shows the **average** of the members' volumes in percent of each device's own scale (from the states the monitor keeps live) and, while dragged, moves every member by the same number of percentage points from where it was when the drag began, clamped to 0..100 per device (`YxcGroupVolume`); each member is written through `YxcVolumeAccess` (latest value wins). Dragging back restores each member's own level. About 1.5 s after the drag the row is rebound so the slider shows the real average. The slider is refreshed with the row's rebinds (power, input, group changes, end of a drag), not on every volume event.

## Pick and play (#305): platform facts (verified 2026-11-03)

Spec: `.claude/specs/2026-11-03-issue305-pick-and-play/`. Facts below were checked against the pinned versions (androidx.activity 1.8.0 and media3 1.11.1 from the Gradle cache, platform docs for the rest). Nothing here is measured on a device yet.

- **Contracts** (`androidx.activity.result.contract.ActivityResultContracts`, activity 1.8.0, `javap`): `OpenMultipleDocuments` is `ActivityResultContract<String[], List<Uri>>` (input: the MIME type filters), `OpenDocumentTree` is `ActivityResultContract<Uri, Uri>` (input: optional initial uri, result: the tree uri). The app does not use the Activity Result API anywhere yet; existing code uses `startActivityForResult` (`ServerListFragment`, `YaaccUpnpServerControlActivity` for `ACTION_OPEN_DOCUMENT_TREE`). Either is fine; the plan uses the contracts through the fragment because the buttons live in the server list fragment (to be checked in the implementation).
- **Grant lifetime** (source: https://developer.android.com/training/data-storage/shared/documents-files, "Persist permissions"): the grant of a picked file or tree "lasts until the user's device restarts" unless `takePersistableUriPermission` is called. It belongs to the app, not to the activity that received the result, so a rotation does not end it. This lowers the main risk of the spec; the persistable permission stays as a fallback only. The URIs below a picked tree are reached through the tree (`DocumentsContract.buildDocumentUriUsingTree`, `buildChildDocumentsUriUsingTree`), which the tree grant covers.
- **Access restrictions** (same page): on Android 11 and later `ACTION_OPEN_DOCUMENT_TREE` cannot grant the root of the internal storage, the root of reliable SD volumes and `Download`; single files cannot be picked in `Android/data` and `Android/obb`. The folder button therefore works for sub folders such as `Music`, `Movies`, `DCIM`; picking single files has no such restriction for `Download` files.
- **Listing a folder:** `DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))` for the folder itself; a query on that uri returns one row per child with `Document.COLUMN_DOCUMENT_ID`, `COLUMN_DISPLAY_NAME`, `COLUMN_MIME_TYPE`, `COLUMN_SIZE` (size may be null); a child is a directory when its mime type is `Document.MIME_TYPE_DIR`. The platform guide warns that iterating a large directory can be slow: the plan caps the rows read (500 media files, plus a cap on rows seen) and bounds the call with the existing provider gate.
- **ExoPlayer and `content://`:** `androidx.media3.datasource.DefaultDataSource` (media3 1.11.1) routes the `content` scheme to `ContentDataSource` (class present, `content`/`getContentDataSource` strings in `DefaultDataSource`). A picked `content:` URI can therefore be given to the local ExoPlayer directly while the grant is held. To be confirmed on a device (providers differ in seeking support).
- **Documents guide statement on virtual files:** with `CATEGORY_OPENABLE` virtual files are hidden; the plan uses the contracts' default (`CATEGORY_OPENABLE` for `OpenMultipleDocuments`), so only real files appear.


### As built (2026-11-03, reviewed: general review cycle 3 PASS, security review cycle 2 PASS)

- **Flow:** `ServerListFragment` (the card "Play from this device" with a phone icon and two buttons, `PickCardAdapter` in a `ConcatAdapter` directly after the servers) -> `LocalPickController` (`OpenMultipleDocuments` / `OpenDocumentTree` launchers, a static single worker thread) -> `PickedQueue.build` (up to three queues by type, name order, 500 files in all) -> `BrowserHost.pickReceivers` (the same picker, `GuardedPickedAction` and `ActionFlight` as browse actions) -> on confirm `UpnpClient.initializeQueuePlayers` -> `PlayerService.createQueuePlayers` (one player per kind and receiver, every player with its own items) -> `play()` for each.
- **Checks of what a provider says** (untrusted): `SharedContentPolicy.isAcceptableUri` and `OwnProviders.isOwnAuthority` for every picked uri and the tree; type through `SharedContentPolicy.chooseMimeType`, name through `DisplayName.clean`; picked files probed one bounded call each (`ProviderProbe`, `BoundedCall` with its own gate, 3 s per call, 20 s for all, at most 1000 uris); a folder is one bounded query (15 s, at most 5000 rows, `FolderListing.Row.capped`: id over 1024 chars dropped, name cut at 1024, type over 255 chars dropped); child uris are built on the checked tree uri (`buildDocumentUriUsingTree`), so a provider cannot redirect.
- **Shares:** `QueueShareWindow` + `Lease` per player, items found by identity; `QueueShareBinder` creates a share when `AbstractPlayer.loadItem(int)` loads an item (one at a time over all players, `BIND_LOCK`; `ShareRegistry.hasRoom` is checked before a file is opened; `ShareService.prepareFor` with the receiver's addresses resolved once at the confirm) and `registry.remove` + `unclaimed.released` removes it when the player moves on (`drop` on stop, `close` on destroy; neither waits for a bind in progress). This phone's audio and image players use the picked `content:` uri directly (`QueueItems.direct`). Queue items start with duration -1 (no next-item timer); when the share of an audio or video file is created `ContentDuration` reads its length with `MediaMetadataRetriever` (one bounded call per provider, 3 s, 1..24 h believed) and the item and its DIDL resource carry it, so a receiver shows the length and the player's timer starts the next item.
- **Invariants:** SI-1..SI-4, SI-11, SI-13, SI-14, SI-17, SI-18 hold as before (no `ShareLimits` change, no new component, permission or dependency). SI-12: the pick path has its own provider pool and gate (`pick-provider`), so "one call in flight per provider" holds per pool. SI-16: notices are fixed resource texts with numbers; `ShareLogRedactor` reduces a `content:` uri to its authority (the image viewer logs the uri of an image it cannot load). Exception: DEBUG lines of the players print the item title, which for a picked file is its cleaned name (the default log level is ERROR).
- **Known limitations:** a queue uses one registry slot per player (kinds times receivers, up to 8) and can use all slots; a `application/octet-stream` file of a folder is not played (no guessing from the extension); every failed share toasts (a queue of many missing files shows many); `stop()` and `pause()` are ignored while a share is being created (up to 20 s to open, 5 minutes for a copy); `LocalImagePlayer.setItems` puts all picked uris in one intent extra (a very long list of long uris could exceed the binder limit); a duration that cannot be read leaves the item at -1 (no timer; the receiver's transport state decides); `MediaMetadataRetriever` parses the picked file in native code (a file the user picked, bounded in time, not logged).
- **Not verified on a device:** the grant of the picked uris during a long background playback and after the app is moved to the background; ExoPlayer reading the picked `content:` uris directly (seeking depends on the provider); the video app on this phone with a share; a mixed folder on two receivers.


## 12. Net sources, presets and tuner: real answers of a device (2026-11-04, slice 5 step 0)

Measured with `capture.sh` and `source_test.sh` on one device that has a tuner (FM and DAB) and the network source block (the same type as device c). Station names and titles in the fixtures (`c_playInfo_net_radio*.json`, `c_tunerPlayInfo_*.json`, `c_presetInfo_net_empty.json`, `c_tunerPresetInfo_*.json`) are invented; the structure is as the device sent it.

- **Which play info belongs to the zone:** `netusb/getPlayInfo` and `tuner/getPlayInfo` both answer at any time, also for the source that is **not** selected (the tuner answer while net radio plays, the net answer while the tuner plays, last values kept). What to show is decided by the zone's input and the `play_info_type` of that input in `getFeatures` (`netusb`, `tuner`, `cd`, `none`; `net_radio`, `spotify`, ... are `netusb`, `tuner` is `tuner`, `aux` is `none`).
- **Net radio play info:** `input` `net_radio`, `playback` `play` / `stop`, `repeat` / `shuffle` `off`, `total_time` 0 (live stream; `play_time` counts up), **the station name is in `artist`** ("Name (City/Language)"), `album` empty, **the stream title is in `track`**, `albumart_url` a path on the device, `repeat_available` and `shuffle_available` **empty**, `attribute` 16785411.
- **`attribute` is a bit mask of what the source can do** (matches all four play infos seen: net radio 16785411 = bits 0, 1, 13, 24; Spotify 16777503 = bits 0-4, 8, 24; a media server 83886591 = bits 0-8, 24, 26): bit 0 play, bit 1 stop, bit 2 pause, bit 3 previous, bit 4 next, bits 5 to 7 shuffle related (only the server has them and a `shuffle_available`), bit 8 repeat (set exactly when `repeat_available` is not empty). **Evidence on the net radio source, which has only bits 0 and 1:** `setPlayback` `pause`, `next` and `previous` are answered with `response_code` 0 and **do nothing** (`playback` stays `play`, `track` and station stay), `play` after `stop` and `stop` work (`playback` `stop`, `play_time` 0). So yaacc offers a control only when its bit is set (the meaning of the bits is a reading of these captures, not documented).
- **Repeat and shuffle:** `setRepeat?mode=one|all|off` and `setShuffle?mode=on|off` answer `response_code` 4 on a source that lists no options; nothing changes. Offered only when `repeat_available` / `shuffle_available` list the value.
- **Net presets:** `netusb/getPresetInfo`: `preset_info` is a list of 40 `{input, text}` (position = preset number, empty slot = `input` `unknown`, `text` empty) and `func_list` `["clear", "move"]`; this device had no net presets (all empty). `recallPreset?zone=main&num=1` on an **empty slot is answered with 0 and does nothing**; `num=999` is answered with 4. So the device does not refuse an empty slot: yaacc recalls only a slot that the list it just read shows as not empty.
- **Tuner play info:** `band` (`fm` / `dab`), `auto_scan` (true while a seek runs), `fm` {`preset` (0 = none), `freq` in kHz (87550 = 87.55 MHz), `tuned`, `audio_mode`}, `rds` {`program_type`, `program_service` (can start with a blank), `radio_text_a`, `radio_text_b` (end with `_`)}, `dab` {`preset`, `id`, `status`, `freq`, `service_label` (padded with blanks to 16), `dls`, `ensemble_label`, `ch_label`, `bit_rate`, `quality`, ...}. The `dab` and `rds` blocks keep old values while the other band is selected.
- **Tuner presets:** `tuner/getPresetInfo?band=fm|dab` (the band is required): `preset_info` list of 40 `{band, number, hd_program, text}` (empty slot = `band` `unknown`); `number` is the frequency in kHz for FM and the service id for DAB; **position = preset number** (recall of 1 gave the first entry in both bands); `text` is padded with blanks. `tuner/recallPreset?zone=main&band=fm|dab&num=<n>` is answered with 0; recalling a DAB preset switches the band to `dab` while the zone input stays `tuner`.
- **Seek:** `tuner/setFreq?band=fm&tuning=auto_up|auto_down&num=0` is answered with 0 and **starts a scan**: `auto_scan` true, `fm.tuned` false, `fm.freq` moves in steps of 50 kHz on its own (87550 to 87600, then after `auto_down` to 107500, 106750, 106000 over the next seconds) until a station is found. The scan is not stopped by the app; it is shown as "scanning". `setFreq` with `band=dab` is answered with 4.
- **DAB service next / previous (measured with the band `dab`):** `tuner/setDabService?dir=next|previous` is answered with 0 and **switches the service**: `dab.service_label` changed to the next / previous service of the list ("TEST 1" to "TEST 2" and back (invented names)), `dab.dls` became empty (the new service sends its own text a moment later) and `dab.preset` went to 0 (no preset any more; `dab.id` kept its old value, so the service label, not the id, shows the current service). While the band was `fm` the same call is answered with 2 (refused). `tuner/setFreq` with `band=dab` is answered with 4 in both cases (DAB has no frequency seek). A DAB preset recall (`recallPreset?zone=main&band=dab&num=1`) sets `dab.preset` to 1 and the service of that entry.
- **Rules for yaacc that follow:** text from the play info is cleaned and capped (names are padded with blanks); the now playing text of a network source is the station (`artist`) and, if it differs, the stream title (`track`); of the tuner the FM frequency (`87.55 MHz`) with the RDS service / text, or the DAB service label with the `dls`; the preset lists are read right before they are shown and before a recall; a control is shown only if its attribute bit (and for repeat / shuffle the option list) allows it; seek is "scan up / down" with a scanning state and no repeated taps.
- **Tuner calls need the tuner input (measured 2026-11-04):** with the zone input `net_radio` selected, `tuner/setFreq?band=fm&tuning=auto_up|auto_down&num=0` and `tuner/setDabService?dir=next` were answered with 0 / 2 and changed **nothing** (`auto_scan` false, frequency unchanged; the DAB call answered 2 as in FM). Seek, DAB service and similar controls are therefore offered only while the zone input is the tuner (`play_info_type` `tuner`). A `tuner/recallPreset?zone=main&band=fm&num=2` was answered with 0 and set `fm.preset` 2 and `fm.freq` 88700 in the tuner play info while the input stayed `net_radio` in the data we have; whether the recall also switches the zone to the tuner is **open** (the status after the recall was not printed). FM preset list with two entries: `number` is the frequency in kHz (87550, 88700), `text` is padded with blanks (" TEST 1", "  TEST 2"), position = preset number.
- **Net radio favorites are not in `netusb/getPresetInfo` on this device (owner, 2026-11-04):** the list stays empty (40 x `unknown`) although two favorites are saved in the manufacturer's app. With `net_radio_type` `airable` the favorites live in the Airable service of the account, reached by browsing (`netusb/getListInfo`), not in the 40 preset slots. The device also lists `recent_info` (40 entries) in its `netusb` features: a "recently played" list may be the usable shortcut (open, to be captured: `netusb/getRecentInfo`, `netusb/recallRecentItem`).
- **Recently played (`netusb/getRecentInfo`, measured 2026-11-04):** `recent_info` is a list of 40 entries `{input, text, albumart_url, play_count, attribute}`, most recent first, empty slots `input` `unknown`. It holds the **network radio stations** (`input` `net_radio`, `text` "Name (City/Language)") **and every track the device played from a UPnP server** (`input` `server`, `text` "Title-(file name)", `albumart_url` an address on the LAN, `attribute` 30), also tracks that yaacc sent. Some texts start with blanks or " - ". Fixture `c_recentInfo.json` has the structure with invented content. **`netusb/recallRecentItem?zone=main&num=<n>` works (measured 2026-11-04):** answered with 0; the zone input changed from `tuner` to `net_radio` by itself and the station of entry 1 played (`artist` = its name, `track` = the stream title, `playback` `play`, `attribute` 16785411). So a recall of a recent entry switches the input; the number is the position in the list (1-based).
- **Does a tuner preset recall switch the input? (still open):** the test was started with the tuner already selected, so it did not answer. The row will not rely on it: before a tuner preset is recalled the zone input is set to the tuner if it is not (a call the app already makes), then the preset is recalled.
- **As built, step 7.1 (now playing line):** `YxcTunerInfo` (`tuner/getPlayInfo`), `YxcNowPlaying` (pure) and a second line in the row text (`YxcRowText`). The monitor reads the tuner play info together with the play info, only while the zone input is the tuner; the state holds the `YxcPlayInfo` and the `YxcTunerInfo`. The line is shown for the network source whose own `input` equals the zone input and whose `playback` is `play` or `pause`, and for the tuner (FM: `FM 87.55 MHz` with a trailing ellipsis while scanning, the RDS service and radio text; DAB: service label and dynamic label); nothing for a stopped source, standby, the link input or another source's old info. **A flag of the tuner is still not a reason to read** (it floods, section 10): the tuner line is refreshed with the input change, the power change and the 30 s read, and will be after our own tuner actions (step 7.2).
- **As built, step 7.2 (source controls):** `YxcSourceControls` (pure) decides what the source offers from the play info: the bits of `attribute` (play, stop, pause, previous, next; without a bit field only play, pause and stop), repeat / shuffle when the `*_available` lists are not empty, FM seek on the FM band, DAB service next / previous on the DAB band, nothing for standby, the link input or an input that is not the one the play info talks about. `YxcSourceActions` reads the zone status and the play info right before every control and sends it only if that fresh answer offers it (`NOT_AVAILABLE` otherwise; nothing is decided on an event or on the row's state). The calls are `netusb/setPlayback`, `setRepeat`, `setShuffle`, `tuner/setFreq?band=fm&tuning=auto_up|auto_down&num=0` and `tuner/setDabService?dir=next|previous` (arguments checked in `YxcClient`). On the row: icons for previous / next (or seek down / up on the FM band, previous / next station on DAB), repeat and shuffle only where offered; **while the device plays a source of its own and no yaacc player is playing on it, the row's play / pause / stop act on that source** (`netusb/setPlayback`) and the ones the source does not offer (pause on a net radio) are shown disabled; while yaacc plays on the device they stay UPnP. `YxcSourceController` serialises the calls per device (one in flight, taps within 300 ms dropped) and asks the monitor to read the device again right after and 1.5 s later (a seek scans for a while; the events of the tuner are not used).
- **Switching the tuner band (measured 2026-11-04):** `tuner/setBand?band=fm|dab` is answered with 0 and switches the band at once: the play info then says `band` `dab` (and `fm.tuned` false) or `fm` again; the zone input stays `tuner`; the last FM station is kept (`fm.freq`) and the DAB service too. The row offers it as a small "FM" / "DAB" button (it shows the band it switches to) next to the seek icons, only while the tuner is on the other band and `system/getFeatures` lists both (`tuner.func_list` with `fm` and `dab`). **As built:** `YxcSourceCapabilities` (bands, `tuner.preset.num`, `netusb.preset.num`, `recent_info`; at most 100 presets) is read once per device with the first full read and kept in the state; `YxcSourceControls` has `BAND_FM` / `BAND_DAB`; `YxcSourceActions` gets the capabilities along (a band switch is never sent without them).
- **FM step, scan and cancel (measured 2026-11-04, like the manufacturer's app: short tap steps, long tap scans):** `tuner/setFreq?band=fm&tuning=up|down&num=0` moves `fm.freq` by exactly one step (50 kHz: 103600, 103650, 103700, back to 103650) and, called while the tuner is on DAB, also switches it to FM; `tuning=auto_up|auto_down` scans by itself and stops on a station (the scan wraps around the band: 103650 up to 87600); **`tuning=cancel` stops a running scan** (`auto_scan` false, tuned to the frequency reached). **As built:** on the FM band the two seek icons step on a short tap (`STEP_UP` / `STEP_DOWN`) and scan on a long tap (`SEEK_UP` / `SEEK_DOWN`); while `auto_scan` is true a short tap stops the scan (`CANCEL_SCAN`). DAB keeps next / previous service on a tap.
- **Following a scan (owner's test, 2026-11-04):** the tuner's play info events are not used (they flood), so after a long tap the row would have stayed on the first frequency with "..." until the next 30 s read. `YxcSourceController` now reads the play info (`YxcMonitor.refreshPlayInfoNow`, no zone or group read) once a second while `auto_scan` is true, at most 60 times, after a scan started. The band is shown as a two-part switch "FM | DAB" (both names, the playing band selected); a tap on the other name switches (`BAND_FM` / `BAND_DAB`).
- **As built, step 7.3 (presets):** `YxcPresets` (pure) turns the three list shapes into entries: the network presets (`netusb/getPresetInfo`: non-empty slots, position = number), the recently played list (`netusb/getRecentInfo`: radio and streaming entries only, not `server` tracks; the number is the position in the device's own list) and the tuner presets per band (`tuner/getPresetInfo?band=fm|dab`: slots of that band; FM shows the frequency as a second text). Names are cleaned and capped, at most 100 slots of a list are looked at. `YxcPresetActions.load` reads the lists the device's capabilities name (`system/getFeatures`: preset counts, `recent_info`, bands); `YxcPresetActions.recall` reads the list again right before and recalls only if the entry is still there at the same position with the same name (`netusb/recallPreset`, `netusb/recallRecentItem`, `tuner/recallPreset`); a tuner preset first sets the zone input to the tuner when it is on another input. A star icon on the row of a switched-on device that lists presets opens `YxcPresetsSheet` (bottom sheet: Recently played, Presets, FM presets, DAB presets; one tap recalls and closes the sheet; the row follows by two reads).

- **As built, review of slice 5 (2026-11-04): who owns the device.** While yaacc plays on a device the zone input is the server input, which counts as a network source. So the row's play / pause / stop and the source icons act on the device's own source only when no yaacc player on it plays or is paused (`YxcSourceControls.rowMapsToOwnSource(state, yaaccActive)`, `available(state, yaaccActive)`; `ReceiverRowBinder.yaaccActive`); otherwise the UPnP transport keeps the buttons and the icons are hidden. A stopped player does not count. The presets star is still offered while yaacc plays (open suggestion of the security review: a recall changes the input).

### 12b. Device-level settings (slice 5, step 7.4; measured 2026-11-04)

- **Measured on a device that lists `dimmer` and `auto_power_standby`:** `system/getFuncStatus` answers `{"auto_power_standby":true,"dimmer":-1}`; `system/setDimmer?value=1` and `system/setAutoPowerStandby?enable=false` are answered with 0 and the next `getFuncStatus` shows the new values (`dimmer` 1, `auto_power_standby` false); `setDimmer?value=-1` and `enable=true` put them back. The ranges come from the system `range_step` entry `dimmer`: -1..2 (device a), 0..2 (b), -1..9 (c); -1 is the automatic mode where the range reaches it. `network_standby` is listed in the system functions of all three devices but `getFuncStatus` does not report it, so it is not offered.
- **As built:** the settings sheet gets two more items from `YxcSettings.build(..., YxcFeatures system, YxcFuncStatus funcStatus)`: **display brightness** (a slider over the range of the device, `-1` shown as "Auto") and **automatic standby** (a switch), each only when the system function list names it and the function status reports a value; `YxcSettingsWriter.setDimmer` checks the value against the range and the step before `system/setDimmer` is sent. **Not built:** the zone-level extras (tone control, dialogue level and lift, subwoofer volume, surround decoder, pure direct, enhancer, bass extension, extra bass, adaptive DRC, clear voice, 3D surround): none of the three devices lists one, so they could only be tested with invented data. They can follow the same model when a device that lists them is at hand.


## Snapcast (issue #319): behavior notes from the Snapcast sources

Verified 2026-11-10 by reading the Snapcast sources: the `develop` branch at `ea981ec` (2026-11-03, `github.com/snapcast/snapcast`, GPL v3; CMake project version 0.35.0, the changelog already has entries for 0.36.0, so a few details may be newer than the 0.35.0 tag). Written in our own words; nothing is copied. File names below are relative to that repository. Items marked **(rig)** were not run in the sandbox (no Docker daemon, building the sources was denied) and must be confirmed on the test rig (`tools/snapcast-rig/`) before code depends on them.

### Ports, transports, discovery
- Binary stream protocol: TCP 1704 (`tcp-streaming`, `server/server_settings.hpp`); JSON-RPC control: raw TCP 1705 (`tcp-control`), HTTP + WebSocket 1780 (`doc/json_rpc_api/control.md`). All bind to `::` by default, all published by default (`publish{true}`), `mdns_enabled{true}`.
- mDNS (`server/snapserver.cpp`, lines 450 to 473): `_snapcast._tcp` (stream port), `_snapcast-ctrl._tcp` (TCP control port), `_snapcast-http._tcp`, `_snapcast-https._tcp`. The service name is "Snapcast". The `_snapcast-https._tcp` service is the TLS variant of the HTTP/WebSocket interface, which YAACC does not use: the raw TCP control port (1705) and the TCP stream port that YAACC uses have no TLS and, unless the server enables authorization, no authentication. Published only if the matching service is enabled and its `publish` is true. No TXT records are published (`server/publishZeroConf/publish_avahi.cpp` passes none, `publish_bonjour.cpp` there registers with an empty TXT).
- Clients take their server from one URI (`tcp://host:1704`, `ws://`, or the default `tcp://_snapcast._tcp` = mDNS, which takes the first server found); the mDNS name is browsed again on each reconnect (`client/controller.cpp`, `client/snapclient.cpp`). The server has no message that tells a client to connect elsewhere (checked: the client handles only WireChunk, ServerSettings, CodecHeader, Error and Time from the server). `Server.DeleteClient` only forgets a disconnected client's configuration.

### JSON-RPC control
- Framing: JSON-RPC 2.0, one JSON object per line (ndjson) on the raw TCP socket. Requests, responses and notifications share the connection. Methods registered (`server/control_requests.cpp`): `Client.GetStatus/SetVolume/SetLatency/SetName`, `Group.GetStatus/SetMute/SetStream/SetClients/SetName`, `Stream.Control/SetProperty/AddStream/RemoveStream`, `Server.GetRPCVersion/GetStatus/DeleteClient/Authenticate`, `General.GetRPCCommands`.
- `Server.GetStatus` result: `{"server": {"groups": [...], "server": {"host": {arch, ip, mac, name, os}, "snapserver": {controlProtocolVersion, name, protocolVersion, version}}, "streams": [...]}}`. A group: `{id (UUID string), name, muted, stream_id, clients: [...]}`. A client: `{id, connected, config: {instance, latency, name, volume: {muted, percent}}, host: {...}, lastSeen: {sec, usec}, snapclient: {name, protocolVersion, version}}`. A stream: `{id, status ("idle"|"playing"|...), uri: {raw, scheme, host, path, query{...}, fragment}}`.
- Server identity: there is **no server UUID**. `server.host` carries `name`, and `mac`/`ip` that may be empty (the documented example has empty values). Our stable key is therefore the control endpoint plus the host name (design decision).
- Client ids are `hostID` (default the MAC address) with `#<instance>` appended for instance > 1.
- `Group.SetClients` sets the **exact** membership: clients of the group that are not in the list are removed and each gets a new group that inherits the stream; clients in the list are moved into the group (`server/control_requests.cpp`, `GroupSetClientsRequest`). It is the call for both "add" and "remove" and must be built from the full wanted list. A group without clients disappears from the status.
- `Stream.AddStream` params `{"streamUri": "<uri>"}`; result in the source is `{"id": "<stream id>"}` (`StreamAddRequest::execute`) while `doc/json_rpc_api/control.md` shows `{"stream_id": ...}`: **parse both** (**rig**). The call starts the stream and sends a full `Server.OnUpdate`. Name rules (`streamreader/stream_manager.cpp`): `name` must be non-empty and unique, else an exception (**rig**: the exact JSON-RPC error code). The documentation says only `pipe`, `file`, `tcp`, `alsa`, `jack` and `meta` are accepted; no scheme check was found in `StreamAddRequest` or `StreamManager::addStream` (**rig**: confirm which schemes a real server refuses); a `controlscript` must lie in the server's plug-in directory.
- `Stream.RemoveStream` params `{"id": "<stream id>"}`, answered with the same `id`/`stream_id` ambiguity as AddStream; it also closes the TCP source's listener (0.35.0 changelog); groups that used it lose their stream (`stream_id` becomes empty) and a full `Server.OnUpdate` follows. Our code must restore the previous stream ids of affected groups only for groups it moved itself.
- Notifications used: `Client.OnConnect/OnDisconnect/OnVolumeChanged/OnLatencyChanged/OnNameChanged`, `Group.OnMute/OnStreamChanged/OnNameChanged`, `Stream.OnUpdate/OnProperties`, `Server.OnUpdate` (full status after group or stream list changes).
- Authorization (Basic) is possible per server config; a server that requires it answers requests with an error until `Server.Authenticate` (out of scope, see `decisions.md`).

### Feeding a server with a TCP stream source
- URI: `tcp://<ip>:<port>?name=<name>[&mode=server|client][&sampleformat=48000:16:2][&codec=flac][&chunk_ms=20][&idle_threshold=100][&buffer_ms=50]` (`streamreader/tcp_stream.cpp`, `asio_stream.hpp`, `doc/configuration.md`). Default `mode=server`, default port 4953. The host must be a numeric IP address (`make_address`), not a host name. With `mode=server` the snapserver **listens** and the producer connects; only **one** connection is accepted at a time, the next one only after the previous is gone (re-accept after about 100 ms).
- The payload is read as raw PCM in chunks of `chunk_ms`; the TCP/Asio stream code does no container parsing (`asio_stream.hpp`, `do_read`), so a WAV header (as in the documented Mopidy `wavenc` example) would be played as 44 bytes of audio. We send raw little-endian PCM at the stream's `sampleformat` without a header.
- **The server paces its own reads**: after each chunk it waits until the chunk's real-time deadline (`nextTick_`), so TCP back pressure slows a producer that is ahead; stream time starts at the first read after connect (`first_`: `tvEncodedChunk_ = now - chunk duration`). If a read is late (producer underrun or stall), the server logs a resync and restarts the stream clock: an audible discontinuity. Consequences for `PcmFeeder`: keep a small queue (a few chunks), do not run far ahead (socket buffers add latency for pause/stop: bytes in flight / 192000 B/s at 48000:16:2), never underrun; pacing by deadline remains the producer's job so that its own buffer does not grow.
- State (`streamreader/pcm_stream.cpp`, `isSilent`; `asio_stream.hpp`): a chunk counts as silent when it is all zero bytes, **even with the default `silence_threshold_percent` 0** (an exact comparison with a zero chunk); after `idle_threshold` ms of silent chunks (default 100) the stream goes `idle`, the first non-silent chunk makes it `playing` again. So a pause that we fill with zeros, or digital silence between tracks, shows as `idle` while data keeps flowing; the UI must not treat `idle` as an error, and our own idle cleanup must be measured by our own activity (last audio written), not by the stream status. Connection errors make the stream accept again after 100 ms.
- Defaults (`server_settings.hpp`): `bufferMs` 1000, `streamChunkMs` 20, codec flac, sample format 48000:16:2, `plugin_dir` `/usr/share/snapserver/plug-ins`.

### Binary protocol (phase B), little endian (`common/message/*.hpp`, `doc/binary_protocol.md`)
- Base header 26 bytes: `type` u16, `id` u16, `refersTo` u16, `sent` {sec i32, usec i32}, `received` {sec i32, usec i32}, `size` u32 (size of the typed message that follows).
- Types in 0.35.0: Base 0, CodecHeader 1, WireChunk 2, ServerSettings 3, Time 4, Hello 5, ClientInfo 7, Error 8. **StreamTags (6) is commented out in the source: not needed.**
- Time: payload {latency.sec i32, latency.usec i32}; the offset formula is in `doc/binary_protocol.md` (client sends, server answers with `received` filled, client halves the round-trip difference). Codecs: pcm (RIFF WAVE header), flac, ogg, opus.

### Phase B protocol: implementation notes (Group 7, 2026-11-11)

Code: `de.yaacc.multiroom.protocol` (pure Java, no Android classes). Read from the Snapcast sources at `ea981ec` for **facts only** (written again in our own words, nothing copied) and from `doc/binary_protocol.md` at the same commit.

**Verified in the sources (not yet on the rig):**
- The server answers a Time request by copying the request id into `refersTo` and putting `received - sent` of the request into the latency field (`server/server.cpp`). The reference client then calls `setDiff(latency, response.received - response.sent)`, that is c2s = the latency field and s2c = its own receive time minus the server's send time, offset = (c2s - s2c) / 2, server time = client time + offset (`client/controller.cpp`, `client/time_provider.cpp`). `received` of the response is stamped by the receiver, so the client uses its own clock reading and the `sent` of the response header. The reference client keeps a median over 200 samples, clears it when the last sync is older than a minute, starts with 50 quick syncs after ServerSettings and then syncs every second (`TIME_SYNC_INTERVAL = 1s`).
- The client's playback delay in the reference client is `bufferMs - latency (from ServerSettings) - local player latency`, which is the same as the rule `play at timestamp + bufferMs - latency` of the design with the local player latency added to `latency`.
- An Error to a Hello is code 401 "Unauthorized" or 403 "Forbidden" with `refersTo` = the Hello id, after which the server stops the session; the reference client does not reconnect after it. `SnapcastConnection` treats both as permanent (`UNAUTHORIZED`).
- Hello, ServerSettings and ClientInfo are a u32 length and a JSON string; Time is two i32 (latency sec, usec); Error is u32 code and two length-prefixed strings; the size in the base header is the size of the typed message that follows (layouts above).

**What the code does (decisions in `decisions.md`, 2026-11-11 Group 7):**
- `SnapcastMessageCodec` (encode, decode of a whole frame), `FrameReader` (stream, caps, deadline), the message value classes, `PcmFormat` (RIFF WAVE, integer PCM only, 16/24/32 bit, `data` length not trusted), `FlacStreamInfo` ("fLaC", STREAMINFO of exactly 34 bytes first, later blocks must fit, 16/24 bit). Caps: 8 MiB for WireChunk and CodecHeader, 64 KiB for everything else including unknown types; a u32 above 2^31 - 1 is rejected as a negative size; the cap is checked before a body buffer is reserved, and the buffer grows with the bytes that arrive.
- `ClockOffsetFilter`: the last 16 samples, median of the half with the shortest round trips, jitter = median absolute deviation; refuses a round trip below 0 or above 500 ms; a sample more than 1 s from the estimate is held back, three agreeing ones in a row (within 100 ms) restart the window (server restart) and raise `epoch()`. Proven bound (tests): the error of the estimate is at most half the spread of the one-way delays (10 ms for delays between 5 and 25 ms), a constant asymmetry shows as half of it and never exceeds half the round trip; with 20 % of the samples delayed by 300 ms in one direction the error stays within 5 ms; a clock that runs 50 ppm fast is followed within 1 ms at one sample per second.
- `ChunkScheduler`: PLAY_AT / WAIT / DROP_LATE / DROP_TOO_FAR_AHEAD (timestamp more than 5 s after the corrected clock, which is "play time more than `bufferMs + 5 s` away" without the client latency) and DROP_QUEUE_FULL (an addition: the queue is bounded by 512 chunks and 4 MiB). Nothing is released before the codec header was accepted or while the clock is not synchronised.
- `SnapcastConnection`: Hello first (no `Auth`, MAC `00:00:00:00:00:00`), time sync starts when the settings arrive (16 requests 100 ms apart, then one per second), chunks before the codec header are dropped and counted, listeners run on a serial executor, the chunk backlog for a slow listener is bounded (256 chunks / 4 MiB), reconnect as in `SnapcastRpc` (5 failures, 1 s doubling to 30 s), `onLost` once per outage, `onGaveUp` once. Opt-in rig test: `SnapcastBinaryRigTest` (`-Dsnapcast.rig=127.0.0.1 [-Dsnapcast.rig.seconds=600] [-Dsnapcast.rig.expectChunks=true]`, report in `yaacc/build/snapcast-rig/offset-report.txt`).

**Open, to confirm on the rig (not verifiable here):**
- **rig** The real FLAC header of the rig's server: the code accepts "fLaC" + STREAMINFO (+ well-formed further blocks, with or without the last-block flag) and nothing else. If libFLAC in the server adds something unusual the codec header is refused as MALFORMED; the rig test prints the codec.
- **rig** That an idle pipe source sends no chunks (the test then reports 0 chunks unless `expectChunks` is set; feed noise first, see the rig README).
- **rig** Which clock the server stamps with: the code only needs an offset, any `int32` seconds value works (a wall clock gives about 1.8e15 microseconds, inside the 4e15 sanity bound of the filter).
- **rig** The offset spread over 10 minutes on loopback through Docker's port mapping (the test fails above 20 ms).

### Android client lag (context for the decision)
`badaix/snapdroid` (default branch, shallow clone 2026-11-09): last commit 2024-08-25 "Update Snapcast to v0.29.0" while the server is at 0.35.0.

### Additions from the source and the fixtures (2026-11-10)
- Control clients may receive notifications and responses as **JSON-RPC batches** (one JSON array per line, documented under "Batch"); the reader must accept both objects and arrays.
- `Group.OnMute` carries `{"id", "mute"}` (not `muted`); `Client.OnVolumeChanged` carries `{"id", "volume": {"muted", "percent"}}`; `Stream.OnUpdate` carries `{"id", "stream": {...}}`; `Client.OnConnect/OnDisconnect` carry the full client plus its `id`.
- Empty groups are removed from the server (`server/config.cpp`, `Config::remove`: a group goes away when its last client is removed). Disconnected clients keep their group entry (state is persisted by the server, **rig**), so a group stays listed while its clients are offline; the UI shows such members as disconnected.
- Test fixtures derived from the documentation and sources: `yaacc/src/test/resources/snapcast/` (see its README for what still needs a rig capture).
- Stream URI query values are percent-decoded by the server (`common/stream_uri.cpp`), so `%20` in a stream name works; whether `+` is decoded as a space was not checked: encode spaces as `%20` only.
- Underruns on the client side show up as the info log line "No chunks available" (`client/stream.cpp`); the line is logged about once a second whenever the client has nothing to play (also on an idle stream), so the rig README counts it only inside the run's time window.

### Error codes, layouts and tcp-source details (review cycle 1 fixes, 2026-11-10)
- JSON-RPC errors (`server/jsonrpcpp.hpp`): the `message` is a fixed title and the **real reason is in `error.data`**. Standard codes: -32601 method not found, -32602 invalid params (`data` = text such as "Stream with id 'x' not found"), -32603 internal error (`data` = the text of the exception; this is what a duplicate stream name or a failed bind (taken port) produces, because the exceptions are not `RequestException`s and are converted in `Server::onMessageReceived`), plus **401 "Unauthorized"** and **403 "Forbidden"** when authorization is on (`server/server.cpp`). The client must read both `message` and `data`.
- tcp source details (`streamreader/tcp_stream.cpp`): the listener is created in the constructor, so `Stream.AddStream` fails at once with -32603 when the port cannot be bound; the port comes from the URI or from `?port=`; `codec=` is the codec the server uses toward its clients (transport), the input stays raw PCM at `sampleformat`; `controlscript` must lie in the server's `plugin_dir`; no other restriction on the stream URI was found in the add-stream code.
- `StreamUri::toJson` puts host and port together in `uri.host` (for example `0.0.0.0:4953`, `common/stream_uri.cpp`).
- Binary messages (`common/message/*.hpp`, `doc/binary_protocol.md`): Hello = JSON {Arch, ClientName, HostName, ID, Instance, MAC, OS, SnapStreamProtocolVersion, Version, optional Auth{scheme,param}}; ServerSettings = JSON {bufferMs, latency, muted, volume}; CodecHeader = {codec string (u32 length + bytes), payload (u32 length + bytes)}; WireChunk = {timestamp sec i32, usec i32, payload (u32 length + bytes)}; ClientInfo = JSON {volume, muted}; Error = {code u32, message (u32 length + text), details (u32 length + text)}; strings are not null-terminated.
- mDNS resolution on Android: checked for task 3.1, see "Discovery on Android (task 3.1)" below; server identity details are as in the identity bullet above.
- Version note: these notes were read on the `develop` branch at commit `ea981ec5ec0805dfc9b4af3979090c325abf501d` and the rig builds exactly that commit (pinned by hash in the Dockerfile); the `v0.35.0` tag may differ from it. A change of the pin means re-checking the **rig** items.

### Security notes for the later groups (security review of Group 1, 2026-11-10)
- The documented scheme limit of `Stream.AddStream` is not visible in the sources; a `process://` source, if accepted, would run a program on the server for any unauthenticated control connection. Probe it once on the rig (rig README) and record the answer here.
- The HTTP/websocket control listener (port 1780) is not used by YAACC; the rig disables it because a web page in a browser can reach a loopback websocket.
- A hostile or spoofed server (mDNS is unauthenticated and publishes no TXT records) can send malformed JSON, very long lines, deeply nested JSON, large batches, or stream URIs that name other hosts; the rules for the control client and the feeder are in `design.md`, "Security rules".

### Rig run and feed spike (owner, 2026-11-10, `FeedSpike` against the rig built from commit `ea981ec`)
Both runs on the owner's machine, one snapserver and two snapclients in Docker, loopback (docker port mapping), `Stream.AddStream` created the stream `YAACC spike` as a tcp source on port 4953.
| | real-time paced, 300 s | burst, 60 s |
|---|---|---|
| chunks written | 15 000 (56 250 KiB) | 3 000 (11 250 KiB) |
| lead over real time | max 20 ms | **max 39 098 ms** |
| late chunks (> 20 ms) | 0 (worst 3.6 ms) | 0 (worst 0.2 ms) |
| slowest `write()` | 2.6 ms | **11 007.8 ms** (TCP back pressure) |
| status samples | 30 of 30 `playing` | 6 of 7 `playing` |
| clients connected (min) | 2 | 2 |
- What this settles: `Stream.AddStream` with a tcp source in server mode on `0.0.0.0:<port>` is accepted by a real snapserver, the producer can connect and feed it, the stream turns `playing` and stays so for five minutes, and two clients were connected throughout. A deadline-paced writer stays within one chunk of real time. The Phase A kill criterion for the server side of the feed is **not triggered**.
- Burst result: the data piled up for up to **39 s** between the producer and the server (docker's port forwarding plus the kernel socket buffers); the server read in real time as the source says, so a stop or seek would have acted that late. Consequence for the real feeder: pace by deadline **and** keep the send buffer small (`SO_SNDBUF` of a few chunks, `TCP_NODELAY`), do not rely on back pressure.
- Not yet reported: the client-side check (`docker compose logs -t --since <start+5s> --until <end> snapclient-1 snapclient-2 | grep -c "No chunks available"`, expect 0) and the server log line `State changed` (`idle => playing`), the raw `Stream.AddStream` answer (`id` or `stream_id`), and the `process://` probe from the rig README. The **rig** items in this document stay open until those are reported (the client check, the `State changed` lines and the `process://` probe were reported in the second round below; only the raw answer shape of `Stream.AddStream` is still open).

### Rig checks reported by the owner (2026-11-10, second round)
- **`process://` probe: refused.** `Stream.AddStream` with `process:///bin/true?name=probe` answers `-32603`, `message` "Internal error", `data` "Process stream executable must be located in '/usr/share/snapserver/sandbox'" (copied from the owner's terminal). So the server restricts process sources to a sandbox directory; an unauthenticated control connection cannot start arbitrary programs, only executables placed in that directory (empty in the rig image). This settles the question raised by the security review of Group 1. Other schemes were not probed; YAACC only ever creates `tcp` sources.
- **State transitions** (`docker compose logs snapserver | grep "State changed"`): `YAACC spike` goes `idle => playing` when the producer starts and `playing => idle` when it stops or stalls, as read in the source. The lines the owner pasted begin at 18:56 UTC; the 300 s paced run (18:20 to 18:25 UTC) is no longer in that log.
- **Client check, paced run: confirmed (fresh 60 s run, 19:37:56 to 19:38:56 UTC).** A snapclient logs "No chunks available" once a second while it has nothing to play and logs nothing else at info level, so a healthy run leaves a **gap** in that series. `snapclient-1` logged the line every second up to 19:37:56, then nothing until 19:38:56.463 (the end of the run), then every second again; the combined count of both clients inside the run window was 0 (`wc -l` also 0: the window is silent, not missing, because the series continues on both sides). So the clients received chunks for the whole run. The first attempt (300 s run) had an empty window because its logs were gone.
- Still open: the raw `Stream.AddStream` answer shape (`id` or `stream_id`); harmless, the parser accepts both.

### Discovery on Android (task 3.1, checked 2026-11-10 against the `android.net.nsd.NsdManager` reference, developer.android.com)
- Browse with `NsdManager.discoverServices("_snapcast-ctrl._tcp", NsdManager.PROTOCOL_DNS_SD, DiscoveryListener)`. `onServiceFound` can be called several times for one service and may report the type with a trailing dot, so the type is matched with `startsWith`. `onServiceLost` carries only the service name (no host), so the directory keeps name -> server for the "lost" event. The name is display text.
- Resolution: `resolveService(NsdServiceInfo, ResolveListener)` is deprecated from API 34 (replaced by `registerServiceInfoCallback`, which needs API 34 or the T extension 7) but is the one call that exists on minSdk 27 and still works; one resolution at a time (a second one fails with `FAILURE_ALREADY_ACTIVE`), so the app queues them (`SnapcastResolveQueue`). `NsdServiceInfo.getHost()` is an `InetAddress` (a number, no lookup) and `getPort()` the control port; the app passes that address to the connection.
- The platform's own sample for the legacy path holds a `WifiManager.MulticastLock` while the discovery runs (needs `CHANGE_WIFI_MULTICAST_STATE`, which the manifest has); the app holds it only between `start()` and `stop()` of the discovery, which follow the receivers page. Applications that target API 37 or higher need `ACCESS_LOCAL_NETWORK`; the app targets 33 (when the target is raised, this permission comes with it).
- A stop that is requested before `onDiscoveryStarted` (a page shown and hidden within a moment) may be answered with `onStopDiscoveryFailed` on some releases and leave the browse running, so `SnapcastNsd` holds such a stop back until the start is confirmed (`SnapcastBrowseHandle`, a handle per `discoverServices` call). Not verified on a device.
- A server that holds a selected group is remembered across a restart (endpoint only, `SnapcastSelectedServers`, preference `snapcast_selected_servers`) and listed again without any scan; it is subject to the normal grace once the discovery runs.
- Not verifiable in the sandbox (no device): the real behavior of `NsdManager` on the owner's phones (vendor quirks, IPv6 results, how fast a lost service is reported). Manual entry is the fallback.


### Snapcast feed tap (draft)

Status 2026-11-11: **written as a draft, since wired into the player in task 4.4 (see "Snapcast feed tap: wired into the player" below), not run on a device** (task 4.1 stays open until the owner's spike). The code is `PcmTapAudioProcessor` and `FeedTapChain` in `de.yaacc.multiroom.feed`; the PCM handling it relies on (`PcmFormatConverter`, `PcmFeeder`, `RealTimePacer`, `SnapcastStreams`) is tested on the JVM. The notes below are what was checked offline and what the on-device spike has to settle.

**Checked offline (Media3 1.11.1, `javap` on the jars in the Gradle cache, plus a scratch program that called `configure` and ran buffers through):**
- `androidx.media3.common.audio.AudioProcessor`: `configure(AudioFormat)` (throws `UnhandledAudioFormatException`), `isActive()`, `queueInput(ByteBuffer)`, `queueEndOfStream()`, `getOutput()`, `isEnded()`, `flush(StreamMetadata)`, `reset()`. The no-argument `flush()` is deprecated, and a processor must implement one of the two. `BaseAudioProcessor` offers the hooks `onConfigure`, `onQueueEndOfStream`, `onFlush(StreamMetadata)` (the no-argument `onFlush()` is deprecated), `onReset` and `replaceOutputBuffer(int)`; it is `isActive()` as soon as `onConfigure` returned a format. `AudioFormat(sampleRate, channelCount, encoding)` has public fields.
- `SonicAudioProcessor.setOutputSampleRateHz(int)` converts the rate; it accepts **16 bit and float only** (8, 24 and 32 bit throw `UnhandledAudioFormatException`), is inactive when nothing changes, and gave 1,920,000 output bytes for 1,764,000 input bytes at 44.1 to 48 kHz stereo (exact). It runs on the JVM. Its `flush()` overload is `flush(StreamMetadata)` only.
- `ToInt16PcmAudioProcessor` (in `common.audio`) converts 8, 24, 32 bit and float to 16 bit and is inactive for 16 bit. An inactive processor returns `AudioFormat.NOT_SET` from `configure`; the next stage must be configured with the last active format (as `AudioProcessingPipeline` does; the test driver does the same).
- `ChannelMixingMatrix.createForConstantPower(in, 2)` exists for 1 to 6 input channels and throws `UnsupportedOperationException` for 7 and 8; `ChannelMixingAudioProcessor` keeps a `SparseArray` (an Android class, not usable in a plain JVM test). That is why the channel fold-down is `PcmFormatConverter` (1 to 8 channels, normalised, tested), not the Media3 processor.
- `TeeAudioProcessor` (`exoplayer.audio`, `AudioBufferSink.flush(sampleRate, channelCount, encoding)` and `handleBuffer(ByteBuffer)`) passes the audio on unchanged; it can not mute the output, so the tap is an own `BaseAudioProcessor`.
- Wiring: `DefaultAudioSink.Builder#setAudioProcessors(AudioProcessor[])` (and `setAudioProcessorChain`); `DefaultRenderersFactory#buildAudioSink(Context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams)` is protected and can be overridden to return such a sink. `LocalMediaSessionPlayer` builds ExoPlayer with `new ExoPlayer.Builder(context).build()`; `SnapcastPlayer` overrides the factory method `createExoPlayer()` and passes such a `RenderersFactory` (task 4.4).

**The draft.** Chain: `ToInt16PcmAudioProcessor` (bit depth) -> `SonicAudioProcessor` with `setOutputSampleRateHz(48000)` (rate) -> `PcmTapAudioProcessor` (folds the channels to stereo, hands 16 bit PCM at the stream rate to a `PcmSink`, outputs zeros of the same length, or the unchanged audio with `playLocally = true`). A flush (seek, stop) calls `PcmSink.discard()`; a sink that drops data is counted (`getDroppedBuffers()`), playback goes on. `PcmFeeder` implements `PcmSink`.

**What the on-device spike must check (kill criterion of 4.1: if the tap can not be kept steady, Phase A goes back to the owner):**
1. *Where the processors sit.* Where `DefaultAudioSink` puts the processors of the builder relative to its own channel mapping, trimming, silence skipping and speed (Sonic) stages, and whether `enableFloatOutput` (default false) or high-resolution sources change what the tap receives. Log the `AudioFormat` that `configure` gets for a 44.1 kHz MP3, a 48 kHz FLAC, a 24 bit FLAC and a 5.1 file (no addresses or names in the log).
2. *Gapless track changes.* ExoPlayer should not flush the processors at a gapless transition; if it does, `discard()` throws away up to 8 queued chunks (160 ms) of the old track. Check what `TrimmingAudioProcessor` does at the end of a track (encoder padding), and whether the next track's first chunk follows the last without a hole in the stream. A pause between tracks is written as silence by the feeder.
3. *Back pressure against ExoPlayer's clock.* With the speaker muted the `AudioTrack` still consumes the zeros in real time and so paces the decoder; the tap's `queueInput` runs ahead of the played position by the `AudioTrack` buffer, which then comes on top of the 20 to 160 ms of feeder queue and the server's `bufferMs` (1000 ms) before anything is heard. Measure the delay from a tap on the screen to the sound, for play, pause, seek and stop; `PcmFeeder.write` must never block the playback thread for long (default wait 200 ms, then it drops and counts). A smaller `AudioTrack` buffer (`setAudioTrackBufferSizeProvider`) may be needed. A pause does not flush the processors: `SnapcastPlayer` calls `PcmFeeder.discard()` itself when ExoPlayer's `playWhenReady` turns false (task 4.4).
4. *Silence between tracks and at pauses.* The feeder writes up to 1 s of silence (50 chunks) and then waits without writing; the stream turns `idle` after 100 ms of zeros, which is harmless, but check that the clients resume cleanly at `idle -> playing` and after the feeder's re-anchor (a late first chunk makes the server resync once).
5. *Underruns in steady playback.* Count `getSilenceChunks()`, `getLateChunks()`, `getReanchors()` and `getDroppedBytes()` over the 30 minutes: all four should stay at 0 inside a track. If the producer is bursty (ExoPlayer hands over decoded buffers of tens to hundreds of milliseconds), the queue of 8 chunks (160 ms) may be too small; the queue can not grow beyond 8 by design, so the alternative is a smaller `AudioTrack` buffer or a larger decoder-side pacing.
6. *Rate conversion quality.* Sonic is a time-scale library; whether its plain rate change from 44.1 to 48 kHz sounds clean was not listened to. If it does not, the options are to ask the server for the source rate (`sampleformat=44100:16:2` is possible, but then the stream format differs per track and one stream needs a fixed rate, so this is a design change) or a better resampler (no new dependency without spec approval).
7. *Screen off and Doze.* CPU cost of Sonic and the fold-down, the two feeder threads, and which wake or Wi-Fi lock covers the feed while the screen is off (the app has a `WifiLock` in `NetworkDeviceListener` and wake locks in `Yaacc`; not checked whether they are held during a local music play).
8. *The speaker and the focus.* With silence on the speaker ExoPlayer still takes audio focus, shows the media notification and may duck other apps; decide whether that is wanted for a Snapcast-only play. The phone's own volume stays untouched; the player's volume is the group volume (task 4.4).
9. *Sample format of odd sources.* Mono (duplicated), 8 bit, float and the channel placement of 3 to 8 channels are covered by the unit tests of the converter (order as Media3 1.11.1 maps it: 6 = FL FR FC LFE BL BR, 7 = FL FR FC LFE BL BR BC, 8 = FL FR FC LFE BL BR SL SR); check one real file of each class that matters (a mono podcast, a 5.1 movie soundtrack).
10. *Start after a long pause.* After more than 1 s of silence the feeder sends nothing, and the first audio chunk may be read by the server with the old stream timestamp (the source resets its clock only after that late read), so the clients could drop up to 20 ms of the start. Writing one all-zero chunk when leaving the waiting state would absorb that; it was not added because the source does not prove it. Listen for a clipped start after a pause of more than a second on the rig.
11. *Thread priority.* The writer and watchdog threads run at default priority and the player does not change that; lift the writer to audio priority only if drop-outs show up under load.

### Snapcast feed tap: wired into the player (task 4.4)

Status 2026-11-11: **built, covered by JVM tests, not run on a device** (task 4.1 stays open until the owner's spike). `SnapcastPlayer` (`de.yaacc.player`) extends `LocalMediaSessionPlayer` and is created by `PlayerService` only when the experimental option "Play to Snapcast groups" is on (default off).

**Checked offline (Media3 1.11.1, `javap -c` on `DefaultAudioSink`):** the processors of `DefaultAudioSink.Builder#setAudioProcessors` end up in this order: `TrimmingAudioProcessor` (gapless padding), `ChannelMappingAudioProcessor` (only with an output channel map), then `ToInt16PcmAudioProcessor` (or `ToFloatPcmAudioProcessor` only when `enableFloatOutput` is on, default off), then **our chain** (`ToInt16`, `Sonic` for the rate, `PcmTapAudioProcessor`), then the sink's own `SilenceSkippingAudioProcessor` and `SonicAudioProcessor` (speed, inactive at 1.0). So the tap sees gapless-trimmed 16 bit audio at the file's rate; the sink's own conversions do not defeat it. A compressed pass-through (AC-3, E-AC-3, DTS, TrueHD to HDMI, USB or Bluetooth) or offload has no processor pipeline and would bypass the tap, so the sink is wrapped in `PcmOnlyAudioSink` (a `ForwardingAudioSink` that answers `supportsFormat`/`getFormatSupport`/`getFormatOffloadSupport` with "unsupported" for every format that is not `audio/raw`): the renderer then decodes the track to PCM, which goes through the tap. `DefaultAudioSink.Builder.setAudioCapabilities(DEFAULT_AUDIO_CAPABILITIES)` would **not** do this in 1.11.1: with `Builder(Context)` the builder hands `null` capabilities to the output provider (`javap -c` of `DefaultAudioSink$Builder.build`), which registers an `AudioCapabilitiesReceiver` and uses the device's real capabilities; the setting counts only for the deprecated builder without a context. Offload is never enabled. The processors are installed by a `DefaultRenderersFactory` whose `buildAudioSink(Context, boolean, boolean)` is overridden in `SnapcastPlayer#createExoPlayer`; ExoPlayer is built with `setWakeMode(WAKE_MODE_NETWORK)`.

**Flow.** `startItem`/`doResume` ask `SnapcastFeedController.start`; ExoPlayer plays only after `onReady` (slot of the server, control up, `ensure()`, `useFor(group)`, then the peer address of the control connection is read and the feed connects to it and the requested port). A pause of ExoPlayer (`onPlayWhenReadyChanged(false)`, from wherever) calls `PcmFeeder.discard()`; a seek relies on the flush of the processors (`PcmTapAudioProcessor.onFlush` discards). Stop drains (at most 250 ms plus 60 ms for the pacer's look-ahead), closes the feed on the controller's worker thread, restores the group's previous stream; destroying the player also removes the stream. After 10 minutes without audio the controller closes the feed and calls `removeIfIdle()` (retry after 1, 2, 4, 8, 10 minutes). The player's volume is the group volume (`SnapcastOperations.setGroupVolume` with the levels from the start of a gesture, calls closer than 2 s belong to one); the phone's own volume is never touched.

**Limits to know.** One feed per server at a time from this phone (a second group of the same server answers "busy" after a 3 s wait). If the control connection is lost the feed stops; the group stays on YAACC's stream until the next stop or idle check can reach the server (move it back with another controller). Music only (video or images to a group are refused with a toast). The audio goes **unencrypted** to the server's address on the ports 4953 to 4962; the marker in the stream name is an identifier visible to all controllers, not a secret.

### How to try the Snapcast player (device spike, task 4.1/4.4)

For the owner, on a device. Nothing here needs a code change. The user documentation of this is the chapter "Snapcast" of `docs/index.md`.

1. **Build** `./gradlew :yaacc:assembleDebug` and install the debug APK. Set the log level to "Debug" in the settings (the default "Error" hides the feed lines).
2. **Rig** `cd tools/snapcast-rig && docker compose up -d` (see its README: two snapclients with a null output; for something to hear, also run a real snapclient on a speaker that connects to the server, or a Snapcast server on a real host with real clients).
3. **Reach the server from the phone.** The rig listens on loopback only. USB phone: `adb reverse tcp:1705 tcp:1705` and `for p in $(seq 4953 4962); do adb reverse tcp:$p tcp:$p; done`, then add the server `127.0.0.1:1705` in Settings, "Snapcast (experimental)", "Snapcast servers" (an emulator reaches the host as `10.0.2.2`, see the rig README). A real server on the LAN: add its address the same way, or switch on "Find Snapcast servers" (off by default; the server needs Avahi or another mDNS announcer; the rig has none).
4. **Enable** Settings, "Snapcast (experimental)", "Play to Snapcast groups" (off by default). Open the receivers (top-level page, or the receivers tab of the classic layout): the groups appear as "<name> (Snapcast)", the first line of the row is the server's address. Choose one in the "Play on..." list (or select it in the classic layout).
5. **Play** a music file or a queue to it. Expected: a toast about the second of delay, the notification of the music player, the stream `YAACC <phone name> <8 hex>` in the Snapcast app/`Server.GetStatus` with the group on it, the clients logging chunks (rig) or playing (real speaker). The phone's speaker stays silent.
6. **Listen for**: clean start (no click, no clipped first 20 ms after a pause longer than a second, item 10 above); gapless track changes (item 2); no dropouts for 30 minutes with the screen off (items 5, 7); pause and stop audible about a second after the tap (item 3); seek; next/previous; play-mode repeat and shuffle; the end of the queue stops cleanly and the group gets its old stream back; phone call or another app taking the audio focus pauses and resumes (item 8); a rate of 44.1 kHz material sounds clean after Sonic (item 6); a mono file and a 5.1 file (item 9).
7. **Log lines** (tag `de.yaacc.player.SnapcastPlayer`, prefix `feed:`; they contain states and numbers only, never an address or a name): `feed ready`; `feed closed (stopped) chunks=… silence=… late=… reanchors=… dropped=… maxLeadMs=…` (inside a track `silence`, `late`, `reanchors` and `dropped` should stay at 0; a few `silence` chunks at the track change are expected); `feed closed (feed failed)`; `feed closed (control connection lost)`; `start failed: <KIND>`; `stream removed (idle)`; `group volume failed`. Kinds: `NO_CONTROL`, `REFUSED`, `UNAUTHORIZED`, `NO_FREE_PORT`, `CONFLICT`, `SERVER_FAILED`, `GROUP_GONE`, `CONNECT_FAILED`, `BUSY`, `FEED_FAILED`, `CONTROL_LOST`.
8. **Also try**: the option off again (the group gets the old "not available yet" toast and no player); video to a group (refused with a toast); stopping the snapserver while playing (the player stops with a toast); two groups of one server (the second says busy); pausing for more than 10 minutes and resuming from the notification (the feed opens again, the first moment is lost).
9. **Pass-through and channels**: play an AC-3 (or E-AC-3/DTS) file while the phone is connected to an HDMI, USB or Bluetooth sink that plays it directly (a TV, an AV receiver): the group must play it (decoded, stereo fold-down) and the phone's output must stay silent; play a 5.1 PCM/FLAC file too (item 9 of the draft list: channel placement) and check that it is not passed through either. Also: pause while the player is still starting (tap pause right after play): the music must not start; a track ExoPlayer can not read ends the play with the toast "Playing to the Snapcast group stopped because of an error" and the log line `ExoPlayer error: <code name>`.
10. **Report back**: whether it is steady for 30 minutes (kill criterion of 4.1), the counters above, the delay from tap to sound for play/pause/seek/stop, and anything from the open questions in `decisions.md` (Group 4, task 4.4).

- Device checklist addition (review of task 4.4, S6): on a phone **without a decoder** for a track's format (for example AC-3 on a phone that has none) the renderer reports the format unsupported, no audio track is selected, and the item "plays" silently for its length; there is no toast for this case (only `onPlayerError` shows one). Expect silence, and note which phone it was. A follow-up could toast on `onTracksChanged` when audio is present but no track is supported.


### Open checks for the owner (Phase A gate, task 6.2)

Everything in Groups 2 to 5 is proven by JVM tests and, for the control and the feed, by the rig and the feed spike; nothing below has been seen or heard on a phone. The user documentation (`docs/index.md`, chapter "Snapcast") says "not tested on a device with real speakers yet" until these are settled. Tick them off (or record the result in `decisions.md`) at the gate:
- [ ] **Gapless track changes** and the silence between tracks (draft list item 2 and 4); the counters `silence`, `late`, `reanchors`, `dropped` of the `feed closed` log line.
- [ ] **Delay** from a tap to the sound for play, pause, seek and stop (item 3); the documentation says "about a second".
- [ ] **30 minutes with the screen off**, steady (kill criterion of 4.1; items 5 and 7), the clip-free start after a pause of more than a second (item 10).
- [ ] **Rate conversion** of 44.1 kHz material (Sonic, item 6) and a mono file (item 9).
- [ ] **AC-3 / E-AC-3 / DTS and 5.1 PCM** on a sink that would pass them through (HDMI, USB, Bluetooth): decoded, folded to stereo, the phone silent; the documentation says so (checklist step 9).
- [ ] **No decoder for the format:** the item is silent for its length with no message (the documentation says so); note which phone it was.
- [ ] **Notification and audio focus** of the silent ExoPlayer for a Snapcast-only play (draft list item 8): wanted or not.
- [ ] **Pause while starting, a track that cannot be read, a lost control connection, removing the server while it plays** (checklist steps 8 and 9).
- [ ] **The 2 s live update** of the rows against a second controller (Snapweb) and `Group.SetClients` with a disconnected client on the owner's server.
- [ ] **mDNS discovery** with `NsdManager` on the owner's phones (switch on "Find Snapcast servers", a server with Avahi; the rig has none), including how fast a lost server leaves the list.
- [ ] **Screenshots** of the group row, the group sheet, the settings category and the servers dialog (for `docs/` and the README; none are referenced yet).
- [ ] **The static source fallback (see the next item) and a server that refuses `Stream.AddStream`:** only the tcp source was probed on the rig (accepted); `process://` was refused outside the sandbox directory; other schemes were not tried.

### Static source fallback: not built as the hint describes (found while writing the documentation, task 6.1; wording corrected after review cycle 1 of Group 6)

The text `snapcast_feed_static_source_hint` ("Add a TCP stream source to the server configuration instead (see the YAACC documentation).") promises a fallback that the code does not deliver:
- `SnapcastStreams.ensure()` adopts an existing stream only if its id and `name` are exactly `YAACC <phone name> <marker>` and it is a tcp source with `mode=server` and `sampleformat=48000:16:2`, bound to host `0.0.0.0` on a port of 4953 to 4962 (`isAdoptable`). A stream of that name with another shape is not adopted: it is removed and replaced when no group plays it, and the result is `CONFLICT` when a group plays it. The adoption rule is for finding the stream of an earlier run after a restart (tests such as `afterARestartTheStreamWithTheMarkerIsAdoptedAndNotAddedAgain`), not for a static source. The name changes with the setting "network name for local server".
- The marker is shown nowhere. The stream picker of the group sheet shows `YAACC <phone> <marker> (YAACC feed)` only after a stream of that name exists, so a user who is refused on the first contact cannot know the name.
- An adopted stream sets `currentId`, so it is removed by `shutdown()`: when the player is released (`runRelease`) or after 10 idle minutes (`removeIfIdle()`). A stop only gives the group back its previous stream. `removeLeftovers()` skips the stream it holds (`SnapcastStreams.java`, the `currentId` test in `removeLeftoversIn`), but it would remove a second marker-bearing tcp stream that no group plays, for example a static source written under an earlier phone name. Whether a snapserver accepts `Stream.RemoveStream` for a configured stream, and whether it returns at the next server restart, was not checked.
- Consequence for a user with a server that refuses runtime streams: a matching static source works for the first play, is removed when the player is released or after 10 idle minutes (if the server accepts the removal), and the next play meets the same refusal again.
- The hint is attached to every `REFUSED`, `NO_FREE_PORT`, `CONFLICT` and "another stream id" result; for the first two (every port 4953 to 4962 taken, a same-name stream in use) a static source is no help either.
- For reference, the static line that would be adopted (not a supported fallback): `source = tcp://0.0.0.0:4953?name=YAACC%20Yaacc%20<marker>&mode=server&sampleformat=48000:16:2` (`codec=flac` is the transport codec towards the clients and not checked by YAACC; the name percent-decoded must equal the stream name exactly).
Task 6.3 in `tasks.md` records the decision to make. Until then `docs/index.md` says plainly that a server that refuses runtime tcp streams cannot be played to.

### Snapcast UI (task 5.1 and 5.2)

Status 2026-11-11: **built, covered by JVM tests, screenshots pending** (the layouts and the look are checked by the owner on a device). Every decision is in a pure class of `de.yaacc.multiroom.ui` with a test; the Android classes in `de.yaacc.browser` and `de.yaacc.settings` only forward and show.

- **Row** (receivers section and device sheet, `ReceiverRowBinder` + `SnapcastRowBinder`): the two lines of text go into the field MusicCast rows use for their state (`yxc_text`): on the **first line the endpoint of the server** (security review S20; first because the row clips at the end, W2), on the second the facts: stream (24 characters on the row), "clients connected: n of m", group volume, "muted". Markers: "server unreachable" (the control connection is down; the group stays listed with its last known state, nothing is removed at once) and "stream unavailable" (the stream is not in the server's list, or `disabled`). The row's volume slider and mute box are the **group** volume and mute (`SnapcastControls`, start levels per gesture, coalesced, off the main thread); the settings button opens the group sheet; play/pause/stop act on the group's `SnapcastPlayer` like on the phone. The row state is read from the model in memory (`SnapcastReceivers.stateOf`), so a poll tick costs nothing; the sheet rebinds a Snapcast row only when its row state (`SnapcastRowText.Row`) differs from the last one.
- **Group sheet** (`SnapcastGroupSheet`, built in code like `YxcSettingsSheet`, no layout file): name, endpoint, group volume and mute, stream picker (`SnapcastStreamChoices`: current marked, `disabled` streams not selectable, YAACC's own feed `YAACC <phone> <marker>` marked), "Add client" (`SnapcastClientCandidates`: only clients of the same server in other groups; says when the other group would disappear), members (`SnapcastMemberRows`: name, connected flag, volume slider, mute, overflow menu Rename / Latency 0 to 10000 / Remove from group). Remove from group is `Group.SetClients` with the other members; the last member cannot be removed (an empty list would dissolve the group). The sheet is drawn again 250 ms after a change of the model and never while a finger is on a slider.
- **Display text.** Every name, id, host name and stream name of a server goes through `SnapcastDisplayText` before it is shown. It now also replaces zero width characters (U+200B to U+200D), U+2060 to U+2064, U+00AD, U+180E, the interlinear marks, U+FFFE/FFFF, tag characters and unpaired surrogates (security review S15, S20). A toast shows only a cleaned and shortened reason of a refusal (`SnapcastDisplayText.toastReason`, the rule of `SnapcastPlayerLogic.toastReason`, which delegates to it), at most one toast every 3 s.
- **Settings** (category "Snapcast (experimental)"): an explanation of the server requirement, the play option, `settings_snapcast_discovery` (**off by default**, security review S19) and the server list dialog. With discovery off, only typed-in and remembered servers are used; the setting is read live (`SnapcastDiscoveryGate`): switching it off stops the scan and removes the servers that only the scan knew (`SnapcastDirectory.discoveryStopped`). The dialog (`SnapcastServerListModel`): a host name or address with an optional port, no credentials (`@` gets its own message), at most 16, a duplicate is merged; "remove" (`SnapcastServerAdmin`) removes the server from the manual list, the remembered flag and the stored endpoint (S23) and the groups of that server from the stored selection. A server that is announced on the network cannot be removed.
- **Removing a server ends a running feed.** The receivers close the link of a server that leaves the directory even when a player retains it, and `SnapcastSession.close()` tells its listeners once that it is offline, so the feed controller closes the feed (CONTROL_LOST). The group on that server may stay on YAACC's idle stream until another controller moves it back.
- **Discovered ports.** An announced service on a port below 1024 is refused (S18); a typed-in server may use any port.
- **After the discovery is switched off, a late sighting is refused (S41).** `SnapcastDirectory` has a `browsing` flag under its lock: `discoveryStopped()` clears it, the first call of `SnapcastNsd.start` (`discoveryRestarted()`) sets it, and `serviceFound` lists nothing while it is clear, so a resolve that finishes after the switch was turned off cannot bring a server back. Typed-in and remembered servers do not pass through `serviceFound`.
- **A fresh status before a member change (S42).** *Add client* and *Remove from group* read a fresh `Server.GetStatus` within the rate limit of the model (`SnapcastOperations.freshStatusOrModel`, one per second and ten per minute) and fall back to the model; the candidate list of the picker stays a snapshot, and a removal of a client that already left is refused (`NOT_POSSIBLE`). A mute change still sends the cached volume.
- **Accepted or open, from the security review of Group 5.** S43 (accepted): a remembered server is contacted when the receivers are shown even with the discovery off, because a selected group lives there. S44 (open): a hostile server can make the sheet redraw about every 250 ms with up to 256 members; throttling and in-place slider updates are not built. S47: the user documentation says what the review asked for (unencrypted audio to whoever answers at the chosen address, marker is an identifier, the phone name is disclosed in the stream name).
- **Not verified here:** how any of it looks, touch behavior of the sliders and sheets, the dialogs, the preference screen, and the live update within 2 s on the rig.


### Snapcast client engine: what the code does and what is guessed (Group 8, tasks 8.1 and 8.2, 2026-11-12)

Code: `de.yaacc.multiroom.client`. The pure core (`SnapcastClientEngine`, `DriftController`, `PlaybackTracker`, `FrameCorrector`, `PcmConverter`, `PcmFrameDecoder`, `EngineHost`) is proven by JVM tests on a simulated output (`SimulatedSink`, a manual clock; the media frame number is written into every frame, so the tests read back what played and when). **Nothing below has been heard or measured on a device.** Verified against the Android documentation (android-docs, 2026-11-12): `AudioTrack.getTimestamp()` fills `framePosition` (low 32 bits, wrapping) and `nanoTime` (`CLOCK_MONOTONIC`, "presented or committed to be presented"); `AudioTrack.getPlaybackHeadPosition()` "is reset to zero by `flush()`, `reloadStaticData()` and `stop()`"; `flush()` is a no-op unless the track is paused or stopped; `getUnderrunCount()` is cumulative since creation (API 24); `write(..., WRITE_NON_BLOCKING)` may return a short count (API 23); MediaCodec FLAC `csd-0` is "fLaC" plus the STREAMINFO block, optionally more metadata blocks; raw decoder output is 16 bit PCM, or float only when the output format says `KEY_PCM_ENCODING`.

**How sync is held.** The sink is kept fed all the time (silence when no audio is due), so the output always has a position. The play time of stream sample `s` (counted from the timestamp of the first chunk after a start or resync) is `anchor + s/rate + bufferMs - (server latency + manual latency) - offset` on the client clock. The output's report (`frame f presented at t`) is extrapolated to the frame about to be written: `drift = predicted presentation - play time` (positive: late). Before audio first plays, after a gap, and after a step above 30 ms the engine pads silence or skips frames exactly (to 1 ms); while playing it drops or repeats single frames, at most one per 400 frames (0.25 percent), on the median of the last 7 drift measurements (dead band 2 ms, hysteresis). Late by more than 300 ms flushes the output once (at most every 500 ms). An epoch change of the clock filter drops the queues and the anchor but neither flushes nor re-creates the output; a connection loss flushes; an identical codec header is ignored; a different one restarts the decoder at most 12 times a minute (then the session ends with `HEADER_FLOOD`).

**Guesses about Android audio behaviour (each one is a candidate for the owner's report):**
1. *Latency.* The timestamp is taken to include the whole output pipeline (AudioTrack buffer, mixer, HAL), so no output latency is added. A Bluetooth (A2DP) route is expected to be missing from it; the manual latency field is for that. Whether the reported time is accurate to 1 to 2 ms on the owner's devices is the main open question of the kill criterion.
2. *Timestamp rate.* The documentation recommends polling `getTimestamp()` rarely (every 10 s to a minute once stable) and says short-term differences between reports are not meaningful. The sink polls at most every 20 ms and the engine uses a report at most every 50 ms (250 ms once aligned since Group 8h, 50 ms while a correction or a stall is being judged) and a median of 7 measurements, which is far more often than recommended. If a device's timestamp is jumpy or costly, the poll interval (`positionPollSlowMs`) and the window are the first knobs.
3. *Clock base.* The timestamp is on `CLOCK_MONOTONIC`, the engine clock is `elapsedRealtimeNanos` (counts in deep sleep). The sink converts by the age of the report (`System.nanoTime() - ts.nanoTime`), which stays right across sleep; a device whose `nanoTime` is on another base would show absurd positions that the tracker rejects (future, too old, more frames than written) and the session would end with `NO_PLAYBACK_POSITION` after 5 s. **Group 8f:** a timestamp whose age is above 2 s or in the future by more than 200 ms is now counted as a failed poll (it is on another clock base); after 40 such polls the sink uses the head position (`event trace`: "timestamp on another clock base", "position from head position now"), tested with a device model whose timestamp clock is 5 s and one hour off.
4. *After a flush.* The position is assumed to restart at 0 (documented for the head position); the head position right after the flush is read and subtracted in case a device does not reset it; a timestamp older than the flush is ignored by its time. A device where `flush()` after `pause()` does not drop the queued data would play old audio for up to 120 ms after a resync. **Group 8f:** the fall-back to the head position is a property of the device and is kept across flushes (it used to be forgotten at every flush, so a device without timestamps spent 2 s without a position after each one); it is tried again with the timestamp every 5 s.
5. *Stalls.* An underrun is expected to stall the position (not to run on at the nominal rate). The engine reads `getUnderrunCount()` (and notices when it is behind the output by more than 20 ms) and realigns; a device that counts underruns wrongly would cause needless flushes (they are rate limited, and counted in the status line). **Group 8f:** a report that is off the extrapolation of the previous one by more than 150 ms is held back until the next report confirms it (`positionOutlierMs`), and a flush for lateness above 300 ms needs 3 consecutive fresh reports that agree (`hardResyncReports`; the position is polled every 50 ms meanwhile). Before, one odd report made the engine think the output was far ahead, it stopped writing, the output ran dry, and the underrun flushed (reproduced in `DeviceContinuityTest`).
6. *No timestamps.* Some routes never give a timestamp. After 40 failed polls the sink falls back to the head position with the current time; that moves in steps of a buffer period, so the sync is then coarse (the status line says `head position (coarse)`). Whether any of the owner's devices need it is unknown. **Group 8f:** the head position is used only once it has moved (before, a bogus "frame 0 at now" report was fed to the engine, which then believed the output was playing).
7. *Buffer, threads, power.* The AudioTrack buffer is 400 ms (or the minimum), the engine writes at most 250 ms ahead (default since Group 8i, was 120), non-blocking, from a thread with `THREAD_PRIORITY_URGENT_AUDIO`. Screen off, Doze and Wi-Fi power saving can starve that thread or the connection. The service holds a partial wake lock (safety timeout 8 hours, renewed every 30 minutes, `LockPolicy`) and a Wi-Fi lock **only while the engine is playing, plus a grace period of 60 seconds after the start and after the last time it played** (connecting, a pause of the stream, a reconnect); then both are released and taken again when it plays again; a session that has not played for 15 minutes ends by itself (`SessionPowerPolicy`, status text "no audio for 15 minutes"). Only audio that has played for 5 seconds in a row counts as playing for the grace and the ceiling, so a server that connects and never plays audio, drops the connection again and again, or sends a second of audio now and then, cannot keep the CPU and the radio awake or the session alive beyond the ceiling. **What stays:** a server that really plays (a stream of silence counts as playing, as does a few seconds of audio every minute) keeps the locks and the session for as long as it likes, under the visible ongoing notification; the 15-minute ceiling applies to streams without audio only, and there is no cap on the total time the locks are held while audio plays; the price is that during a long pause of the stream the phone may sleep and the connection may need a reconnect. **The Wi-Fi lock is not a guarantee, and from Android 14 it does nothing with the screen off.** Checked against the platform documentation (2026-11-12): `WIFI_MODE_FULL_HIGH_PERF` keeps Wi-Fi out of power save also with the screen off, but is deprecated in API 34 and "automatically replaced" by `WIFI_MODE_FULL_LOW_LATENCY`, which "is only active when the screen is on" and "when the acquiring app is running in the foreground". The service therefore takes `FULL_HIGH_PERF` up to API 33 (works with the screen off) and `FULL_LOW_LATENCY` from API 34; **on API 34 and later no Wi-Fi lock exists that holds Wi-Fi out of power save with the screen off**, and the app does nothing more about it (the foreground service and the wake lock keep the process and the CPU running, nothing keeps the radio awake). If the owner's phone runs Android 14 or later, stutter or dropouts only with the screen off (and a growing `connection losses`, `underruns` or `flushes` count in the status) point at Wi-Fi power save; the phone's own settings (battery saver off, "Wi-Fi power saving" or adaptive connectivity off, the app unrestricted in battery settings) are the only remedy to try.
8. *Volume.* The server's volume is applied as a linear gain through `AudioTrack.setVolume` (like the software volume of the reference client); whether 50 percent sounds half as loud is for the ear.
9. *FLAC through MediaCodec.* The header is passed as `csd-0` unchanged; the decoder is made through `MediaCodecList.findDecoderForFormat`. It is assumed that a chunk holds whole FLAC frames (the encoder's write callback delivers frames), that the decoder does not hold samples back for long (if its output comes one chunk late the stream is still anchored at the first chunk, not at the first output), and that the decoded samples of a stream start at the timestamp of its first chunk. A client that joins a running FLAC stream may start up to one FLAC block (for example 4608 samples, 96 ms) off, because the encoder emits a frame after it has collected the block; **the pcm codec has no such question, so test pcm first (set `codec=pcm` for the stream) and flac second, and compare.** A flac stream has no per-chunk continuity check (the decoded size of a chunk does not follow its duration), so a chunk that never reaches the decoder would shift all later audio for good. The engine therefore re-anchors the stream by the timestamp of the next chunk when the scheduler drops a chunk (late, too far ahead, queue full), a chunk fails to decode or is too large, the decoded audio waiting is full, or the host queue lost a chunk; every hole is remembered (a sorted set of at most 64 positions) and acted on when the first chunk after it is processed, so several holes close together are all found (up to 64 pending at once; beyond that every chunk in the range is placed by its own timestamp, counted as `holeOverflows`); each recovery is counted as `holes re-anchored (flac)` in the status. **Known limit (review S10):** a recovery discards the decoded audio that was waiting (130 ms or more of valid audio) and places the next chunk by its timestamp. For a rare loss that is a short gap; on a link that loses many chunks it is much worse than pcm, which fills only the hole: measured on the simulated rig with chunks arriving 1.3 s late at every 10th position, flac plays about 52 s of audio in 2 minutes, pcm 108 s; at every 3rd or 2nd chunk flac is practically silent (8 s) where pcm plays 82 s and 63 s. Keeping the decoded audio before the hole was considered and not done: the next chunk would have to be placed by the gap rule of pcm, which assumes that the decoder's output is as continuous in time as its input, and the flac decoder buffers a block (for example 96 ms) at unknown moments, so that rule could insert or cut up to a block of audio at every hole; the present rule has the same assumption only once per recovery and loses audio instead of guessing. If the owner's flac run shows holes at all (`holes re-anchored (flac)` above 0 on a healthy link), compare with pcm before drawing conclusions. A chunk lost inside the connection (its own backlog limit) is not seen. After a recovery the first chunk is assumed to start at its timestamp, which is the same assumption as at the start.
10. *Decoder output.* 16 bit and float are understood; anything else ends the session with a typed reason. A 24 bit FLAC stream is therefore heard with 16 bit precision if the decoder converts, and at full precision (then rounded to 16 bit) if it gives float.
11. *Start threshold (found by the owner's "peep....peep" test, fixed in Group 8f, device behaviour still unverified).* A streaming `AudioTrack` plays only once its buffer holds a device-specific minimum (the whole buffer by default since Android 12; documented, `getStartThresholdInFrames`). The old engine wrote 60 ms and then nothing until the first position, which a track that waits for 400 ms never gives: it started only when the engine, after 40 failed polls, believed a bogus head position of 0, wrote more, the track started, the engine's extrapolation across the stall was 300 to 500 ms late, and the hard resync flushed the track: a short tone, then a pause, about once per second. Now: (a) the sink asks for a 40 ms threshold where the platform allows it (Android 12 and later, `setStartThresholdInFrames`); (b) the engine writes at least `startThresholdFrames()` before it waits for a position; (c) when there is no position 200 ms after the first write it keeps queueing silence until the output is full (the documentation's advice: fill until `write` returns a short count), so a threshold that is not reported (Android 11 and older) is found by filling. The silence is aligned over like the prefill; the server buffer (1 s by default) is longer than the 400 ms track. The engine gives up with `NO_PLAYBACK_POSITION` after 5 s without any position, as before.
12. *Long sessions.* Nothing in the engine depends on how long it has run: the timeline is judged by the gap between consecutive chunks (a gap above 3 s starts it anew), the anchor is moved forward in place by whole seconds every 2^34 frames (`timeline rebases`, no glitch), all frame counters are 64 bit (the platform's 32-bit frame position is unwrapped by the sink). The first version cut the stream every 10 minutes (review C1); the tests now run 3 hours of simulated playback.
13. *Residual risk of the FLAC path.* The FLAC bytes of the server are parsed by the platform's native decoder (out of process since Android 10, so a crash is a dead codec for the app and not a crash of the app, but it is native code reading hostile data). PCM is parsed in Java only. Next to "unencrypted" and "unauthenticated" this is the third property to know when the server is not yours. The loop that takes the decoded audio out of the codec is bounded (the first buffer over the limit ends it and the codec is made anew, at most 64 buffers and 150 ms per chunk, input chunks above 128 KiB are refused); that loop is tested through a fake codec (`CodecDrain`), the `MediaCodec` adapter around it is glue and is not.
14. *Known limits of the spike (not built, to be settled in Group 9):* (a) **no audio focus and no handling of `ACTION_AUDIO_BECOMING_NOISY`**: a phone call or another app does not pause the session, and pulling the headphones switches the sound to the speaker; (b) **no gain ramp**: volume, mute and the start of the stream change the gain in one step (`AudioTrack.setVolume` acts on audio that is already ahead in the output buffer, so a correct ramp has to be done on the samples; a server that toggles the volume at its settings rate can make clicks); (c) a second Start while the first session is still stopping shows a short message and is not queued; (d) the dialog warns only about IP literals outside the local ranges (a host name is not looked up on the main thread); the Hello, however, carries the generic host name "YAACC" instead of the name of the phone whenever the **resolved** address is outside the local ranges, whatever was typed (a dynamic DNS name, a form like `0x08.0x08.0x08.0x08`).
15. *`AudioTrack.getUnderrunCount()` rises when the deep-buffer output is kept too thin (Nothing A142, Android 16 / API 36; the first reading in Group 8g was wrong, corrected in Group 8i).* The platform log of the owner's phone: `createTrack_l ... mLatency = 528, mAfLatency = 128, frameCount = 19200, mSampleRate = 48000, mFlags = 00000008, mReqFrameCount = 19200, mNotificationFramesAct = 2048`. `mFlags` 0x8 is `AUDIO_OUTPUT_FLAG_DEEP_BUFFER` (DIRECT 1, PRIMARY 2, FAST 4, DEEP_BUFFER 8): the track runs on the deep-buffer output, whose mixer has 128 ms of latency of its own and wakes every 2048 frames (42.7 ms, the notification period). That is why the counter rose once per 2048 frames. With the engine keeping 120 ms queued (less than the 128 ms the mixer wants) the mixer ran short again and again: **real little breaks**, regular, each about one period long, with a position that keeps advancing in between (a stall of 42 ms is invisible to a stall test that looks at 250 ms), and every stall makes the stream 42 ms late, which the engine then compensated with `pad or skip while playing` (187 in 43 s, the "closed loop" of the 8h trace). Owner's runs: write-ahead 120 ms: 467 rises in 43 s, regular breaks; write-ahead 250 ms: 0 rises ("underruns 0"), no breaks. Shortened trace of the 120 run: `idle 74131-81847 ms: underrun new=1 every ~42.6 ms, total 1014 -> 1195 in 7.7 s` (the engine wrote silence at the same level); `active: after "aligned" the first rise caused flush reason=underrun` (the 8f build flushed on it: "peep....peep"). **Rule: the counter is real evidence that too little is queued; the write-ahead must stay above what the output's mixer needs (128 ms here, so at least 250 ms with margin).** Now: (a) the default write-ahead is **250 ms** (field of the test screen, 40 to 350); (b) the track is created with `PERFORMANCE_MODE_POWER_SAVING` on purpose (the deep-buffer path is what this phone picks anyway; the output details line shows the mode the platform gave, `getPerformanceMode`, API 29, the buffer size and the capacity, and the measured steady output ahead); a longer write-ahead derived from the platform's latency was not built (the latency of an `AudioTrack` is a hidden API, `getLatency`); (c) **adaptive write-ahead:** a rise of the counter that the position does not turn into a dry-out (stalled, or less than 10 ms queued) raises the write-ahead by 40 ms, at most once per 3 s, up to 350 ms, and it is never lowered in a session (status: `underrun counter of the platform: N rises; the output was dry A times (flush), the cushion was too thin T times; write-ahead W ms (asked W0 ms, raised R times)`, trace: `underrun counter judged`, `write-ahead raised to (ms)`); a rise that the position confirms is a real dry-out (flush and align again, as before) and raises the write-ahead too; (d) nothing is flushed for a rise alone. Model: `DeviceModel.mixerMinQueuedFrames` (an output whose mixer wants N frames queued, glitches for one period otherwise) with the two owner runs as fixtures (`ownersTraceAtWriteAhead120...`, `...250...`), the raising until the counter stops, the bound at 350 and the cooldown.
16. *Idle stream and the connection (owner's trace, same session).* The trace showed `decoder reset why=1; flush reason=connection_lost` at 81908 ms, 61 ms after the idle phase ended, and a reconnect at 90001 ms. The idle limit of the connection (10 s) is judged on frames of **any** type and the time answers (one request per second after the first 16) keep it alive, so a silent stream does not trigger it (`SnapcastConnectionTest.aStreamWithoutChunksButWithTimeAnswersIsNotDroppedByTheIdleLimit`: 39 s of idle stream with a 10 s limit). The other own limits are the handshake (10 s), the writer queue (the server does not read) and a slow message (10 s). Which of them, or whether the server closed the connection when the stream restarted, could not be told from the trace; since Group 8g the trace has `connection lost  why=<reason>  own limit=<idle limit | handshake limit | writer queue full | message too slow or read timeout | no (server or network)>`. If the reason is `server_closed` with `no (server or network)`, the server ended it (nothing of ours did).
17. *A pause of the source killed the sound on flac (found in the owner's two traces, fixed in Group 8h).* The source stopped for 9 s (first trace) and 7 s (second), the connection stayed up, the server sent the header again ("header ignored (same stream)") and the chunks came back with timestamps that continue on the server clock. The engine then believed the stream was late by 9.86 s and 7.62 s ("late over the hard limit" three times, hard resync, "align start drift +9.9 s"), skipped every chunk for the rest of the run (1.3 million frames, 27 s of audio) and never aligned again. **Cause:** a flac chunk is placed after the previous one by counting decoded frames (the decoder's output does not line up with the chunks, so the timestamps cannot be used one by one), and a pause is invisible to a frame count: the audio after it was due as much earlier as the pause lasted. PCM was safe because it compares each chunk's timestamp with the frame count (gaps up to 3 s are filled with silence, larger ones start the timeline anew). Reproduced in `DeviceRestartTest`: every flac pause of 1, 3, 10 and 60 s ended without sound (drift = pause minus 120 ms, so 880 ms for 1 s). **Fix:** (a) a flac chunk whose timestamp is more than `flacGapMs` from where the spacing of the stream puts it (300 ms in Group 8h, **3 s since Group 8j**, see guess 21: stalls shorter than that are corrected by the time guard) drops the timeline, and the chunk is placed by its own timestamp; (b) the engine never throws away more than 1 s in a row because it thinks it is late (`maxSkipMs`): beyond that it drops the timeline as well; (c) pcm timestamps that go back by more than 3 s do the same (before, such chunks were dropped as overlap until the old time came round). The first audio after a restart is about the server buffer after the chunk was recorded; the status line and the trace say it (`stream resumed: gap ms, action`, `first audio written after the resume`). Tested for 1, 3, 10 and 60 s, with the header again, with a reconnect, with a new clock epoch, and 3 hours of flac with a 10 s pause every 5 minutes.
18. *The clock estimate while it settles (Group 8h).* The filter calls itself synchronised after 3 samples and follows later samples with its median over 8 or more; on Wi-Fi the estimate can move by tens of milliseconds in the first seconds. The owner's trace aligned at 5.7 s with drift +3 us and showed +26.7 ms 0.6 s later, which is what such a move looks like (the trace has the `clock offset` events now to confirm it). Now the engine writes silence and aligns only when the filter has half its window (`OffsetSource.isSettled`, about 0.8 s after the settings). The corrections after that are single frames: at most 2 frames in a 20 ms block (0.2 percent), converging on a 26 ms step in about 12 s; a step of an exact pad or skip is made only when the median of the last seven measurements is over 50 ms (one noisy report, the age of a position jitters by tens of ms, used to cut the stream), or when the output is over 300 ms late. With `smoothCorrections` (on in the service) a dropped or repeated frame is spread over 32 frames by interpolation: no step in a sine larger than its own slope (a cut sample doubles it, a click for each correction).
19. *The restarted client got no chunk: the server had put it in a group of its own on an idle stream (owner's Server.GetStatus, Group 8i).* The Hello (id `yaacc-0123456789abcdef`), the settings, the header and the time sync all worked, the connection stayed up, and not one chunk came for 20 s; the status of the server at that moment: the phone alone in a **new group** on the stream `Default` (status `idle`, a pipe source), while the stream `YAACC spike` (tcp, port 4953, flac, chunk_ms 20, `playing`) had the three other groups. The server gives a client that connects (again) a group of its own on the default stream (or keeps the group it had there); chunks go to the clients of the group whose stream they come from. Nothing was wrong in the client protocol, and the probe "reconnect when no chunk comes" was not built (it would not help). **The spike now says it:** the status text has `server says: my client is connected, volume 100; my group <id> (1 client, not muted) is on stream 'Default' (idle)` and `server streams: ...` (read-only `Server.GetStatus` on the control port 1705 of the same address, every 5 s while no chunk has arrived, then every 30 s; the trace has a `server says` line when it changes), and, with no chunk and a group on an idle stream, `HINT: The server sends nothing: your group is on a stream that is idle ...`. The button **Move my group to a stream** (active when the server lists this client) asks which stream and then, after a confirmation that names what changes (the whole group, every client in it), sends `Group.SetStream`. For **Group 9**: the renderer integration has to make sure that the phone's client is in the group that YAACC feeds and that this group is on YAACC's stream: `Group.SetClients` sets the membership **exactly** (use the fresh-status rule, never drop other clients), `Group.SetStream` for the stream, and it must expect that a reconnecting client lands in a new group on the default stream and put it back.
20. *Why a session ends (Group 8i).* The service never ends silently now: the reason and the time are written to the preferences (`last session ended 12 min ago: ...` under the buttons of the test screen, also after the process was gone), the trace has `session ended reason=...`, and a notification says `Stopped: <reason>` for every end the user did not ask for. The reasons: user, bad address, name did not resolve, connection given up, server refused, engine failed, service error, **no audio for 15 minutes** (`SessionPowerPolicy`: locks only while it plays, an end after 15 minutes without audio), disabled in the settings, **system** (the service was destroyed with no stop from the user) and **app exit** (see below). What else could close it: (a) **YAACC's own shutdown (sleep) timer** (`Yaacc.startShutdownTimer`, the controller in `ShutdownTimerController`, started from the browser): when it runs out `Yaacc.exit()` stops the player and UPnP server services and calls `Runtime.exit(0)`, which also ends a running Snapcast client session; before this change that left no trace (now `app_exit`). If the owner ever started the timer in the YAACC menu, that is the "closes after a while". (b) **Foreground service timeouts of Android 15 and later** apply only to the types `dataSync` and `mediaProcessing` (6 hours in 24, `Service.onTimeout`) and `shortService`; the client service is `mediaPlayback`, which has no timeout (checked in the platform guide "Foreground service timeouts"). (c) The system can still kill a process (memory, battery optimisation of the vendor, "app standby"); the foreground notification and the 8-hour wake lock with its renewal make that unlikely, and a kill leaves `system` or nothing (no record can be written in a killed process; the next start of the dialog then shows the last record of a session that ended earlier). (d) The wake lock has a safety timeout of 8 hours, renewed every 30 minutes while it plays.
21. *A short stall of the source shifted the flac stream for good (review cycle 4, C1; Group 8j, rebuilt on the server's real stamping in Group 8k).* **What the server does** (Snapcast 0.35.0, read in the source, not captured from a server: `server/streamreader/pcm_stream.cpp` `PcmStream::chunkEncoded`, `asio_stream.hpp` `first_` and the resync after a slow read, `server/encoder/flac_encoder.cpp`): the PCM source is read in chunks (20 ms by default) and handed to the encoder; libFLAC emits a frame when a block is complete (1152 frames at compression level 0 to 2, the default is 2; 4096 frames at levels 3 to 8), so the encoder returns a chunk that holds every complete block (one, rarely two) or nothing, and the chunks *arrive* 20, 20, 20, 20, 40 ms apart. The **timestamp** of an encoded chunk is `tvEncodedChunk_`, a running clock that is advanced by the *encoded duration* (`encodedSamples_ / msRate`) after every chunk: flac chunks are stamped one block apart (24 ms for 1152 frames at 48 kHz) and **contiguous with the encoder's frames**. The clock is set from the wall clock at the first read and after a read that took longer than a chunk (a stall of the source). Over a stall the encoder keeps the frames it had collected, so the first chunk after it is stamped with the start of the pcm chunk just read, which is up to one block later than the first frame in that chunk. (An earlier version of this guess said the chunks are stamped with the pcm chunk that completed the block, spaced 20/20/20/20/40 ms, and cited the owner's traces. That was wrong: there is no such trace in the repository and the source says otherwise; 20/20/20/20/40 is the arrival spacing only. `DeviceRig.FlacStamping.COMPLETING_CHUNK` still has that model, labelled as not the server's rule, so that the engine is tested on it as well.) **What follows:** the skew of a chunk (its timestamp minus the position of its first frame in the encoder's stream) is **constant** in a steady stream, and a stall adds the stall (plus up to a block) to it. The engine places flac frames by counting what the decoder returns, which cannot see that, so the stream stayed shifted by a short stall (40 to 240 ms) or went silent (250 to 300 ms). **Group 8j** measured the skew against the *decoded* frame count; the review (cycle 5, W1) showed that a decoder that holds a block back, or whose latency steps, then looks like a stall of the source and the guard inserts silence that is never taken back (+24 ms for the rest of the hour, +85 ms with 4096 frame blocks). **Group 8k, root fix:** the position of a chunk is read from the FLAC frame header at its start (`protocol/FlacFrameHeader`; RFC 9639 section 9.1; sync code, blocking strategy, block size and sample rate codes with the "from the end of the header" variants, UTF-8 coded frame number (fixed block size: frame number times the block size) or sample number (variable), CRC-8). Every chunk starts at a frame boundary because libFLAC's write callback fills a fresh chunk after each encode call. That is the position of the audio the *encoder* was given; whatever the client's decoder does with it later does not matter. A chunk whose header does not read (bad CRC, junk, a different sample rate) is not used and counted; three in a row send the session back to counting decoded frames (status: `flac positions: from frame headers | from frame count`), **and then the guard is off for the session**: it is fed with exact positions only, never with counts (the 8j build did, and the owner's trace of it, 15 minutes of an album, shows four fills of about 26 ms: decoder holds taken for stalls; his A/B by ear preferred the 8i build without the guard). Without the guard (no readable headers, or switched off) the engine does what it did before it: a jump of the timestamps of 300 ms or more places the stream anew (`flacGapMs` 3 s is the limit only while the guard looks at the stream; the restart test with raw payloads, a pause of 1 s, showed the difference). Switch: the check box "FLAC stall guard" of the test screen (default on) = `ClientEngineConfig.flacTimeGuard`; the status says `flac stall guard: on (positions from frame headers) | off (switched off) | inactive (no frame header positions ...)`. `FlacTimeGuard` then judges the exact skew: after learning 48 chunks, the median of the last three excesses over the base beyond the learned band and **8 ms** (a third of a block of the default encoder) is a stall and is filled at once with that much silence (the same operation as the pcm gap fill, the amount less the band; every fill is logged with what the guard saw: skew over its base, band, chunks since the anchor, why, in the status line `last fill:` and in the trace), a smaller shift is filled after two windows of 12 chunks whose lowest excess stays above 4 ms (and above half the band). A skew **below** the base in five chunks in a row is the stream being later than its stamps (a fill that was wrong, a clock that went back): 12 ms or more (plus the band) places the stream anew by its stamps (`FLAC_REANCHOR`; nothing is skipped to undo anything), less lowers the base (a clock that is a little slow; Snapcast itself adds the duration in nanoseconds, `pcm_stream.cpp` line 349, so it does not drift). A fill above 3 s (`maxGapFillMs`), and a timestamp more than `flacGapMs` (3 s) from the spacing, also place the stream anew. When a stream is placed anew the frames the decoder still holds are known exactly from the positions (frames fed minus frames returned, `heldMicros`), so the anchor is the stamp of the first frame that comes out, not of the chunk just fed; a lost or failed chunk breaks that mapping until the decoder is reset, and then the anchor is the stamp of the chunk (one block of error at most). A clock epoch change now also resets the decoder (the block it held belongs to the old timeline; review S29). A livelock breaker in the skip path (more than `maxSkipMs` skipped in total without the lateness coming into tolerance drops the timeline and anchors by timestamp) is the safety net behind all of this. Switch: `ClientEngineConfig.flacTimeGuard(false)`. Status: `flac positions: ...`, `flac stream shifted against its timestamps ...: N times, M ms of silence inserted; placed anew because it was late: K times`; trace kinds `FLAC_SHIFT`, `FLAC_REANCHOR`, `FLAC_POSITIONS`. **Tested** on both stamping models of the rig (the server's rule and the old one), with decoders that hold nothing, one chunk, alternate between holding and a burst, hold random chunks (1 to 4 calls on 1 % and 10 % of the chunks, 1 to 3 h) or step their latency from 0 to a block at 60 s, with blocks of 576, 1152, 2304 and 4096 frames, 44.1 and 48 kHz, mono, stamp noise of 3, 8 and 15 ms and arrival jitter of 40 ms: no fill, no re-anchor, sync error unchanged to under 3 ms in 3 h; stalls of 20, 40, 60, 100, 150, 240, 250, 300, 400, 600, 1000, 3000 ms and 10 and 60 s (plain, header again, reconnect, epoch): sync error against what the other clients play (the stamps) under 3 to 5 ms and the audio continuous. The frame header parser is tested against frames of a conforming encoder (FFmpeg's flac encoder, not libFLAC: `src/test/resources/snapcast/flac/`) for 48, 44.1 and 96 kHz, mono and stereo, blocks of 16, 1152, 4096 and 4608 frames, frame numbers needing one to three bytes, and against hand-built headers for every block size and sample rate code, every length of the coded number, bad CRCs (every single bit flip), truncations and 300 000 random junk inputs. **Not verified without a capture from a real server:** that every chunk of a real server starts with a frame header (the source says so; a chunk of anything else falls back to counting), that the stamps are contiguous with the frame numbers (the guard learns the band and so tolerates a wandering, but an exact stamp is what makes 8 ms safe), the size of the arrival jitter, and what the platform decoder holds back. The status line `flac positions:` and the trace line `flac chunk positions` say what a device does. The trace has a second, small ring (the last 100 *important* events: flac fills with their numbers, re-anchors, stream resumes, flushes with their reason, hard resyncs, holes, headers, epoch changes, decoder resets, write-ahead raises, connection losses, pads or skips while playing with the sound more than 2 ms off) that is kept for the whole session and printed first in the copied trace ("important events"): the main ring of 600 events scrolls out in a minute or two on a busy stream (the owner's 15 minute trace had lost 9897 events).

### How to try the Snapcast client (device spike, task 8.1)

For the owner. This is a **listen test of the experimental phone-as-client**: nothing here is wired into the player or the renderer (that is Group 9). The sandbox could not play audio, so the sync quality is unverified; kill criterion: if sync cannot be held on your devices, Phase B stops and the reason is recorded.

1. **Build** `./gradlew :yaacc:assembleDebug`, install the APK. Switch on **Settings, "Snapcast (experimental)", "Snapcast client spike (listen test)"** (off by default); the entry **"Snapcast client listen test"** appears below it and opens the test screen. Turning the switch off again ends a running session.
2. **Rig** `cd tools/snapcast-rig && docker compose up -d`. The rig's snapclients play to `/dev/null`; for a comparison you need a client that makes sound and is known to be in sync: a **Raspberry Pi (or any PC) with a real `snapclient`** (`snapclient tcp://<rig host>:1704`, see the rig README "Connect a real snapclient" for the tunnel when the rig listens on loopback only). The best arrangement is the Pi on a speaker next to the phone's speaker (or both on headphones' two ears).
3. **Test 1 with the pcm codec.** In `tools/snapcast-rig/snapserver.conf` change `codec=flac` to `codec=pcm` in the `source =` line and `docker compose up -d --force-recreate snapserver`. (Test 2 below is flac again.)
4. **Feed something with an obvious beat.** A click once a second makes an offset easy to hear (the expression is untested here; any music with a sharp beat will do): `ffmpeg -re -f lavfi -i "aevalsrc='if(lt(mod(t,1),0.01),sin(2*PI*1000*t),0)':s=48000:c=stereo" -f s16le -ar 48000 -ac 2 - | docker compose exec -T snapserver bash -c 'cat > /tmp/snapfifo'`. Or music: `ffmpeg -re -i song.flac -f s16le -ar 48000 -ac 2 - | docker compose exec -T snapserver bash -c 'cat > /tmp/snapfifo'`. (The pipe source is 48000:16:2.)
5. **Reach the server from the phone.** USB phone: `adb reverse tcp:1704 tcp:1704` and use `localhost` as the address. A real server on the LAN: its address. The default port of the field is **1704** (the stream port), not 1705.
6. **Start.** Open the test screen, enter the address (it is filled from the first server of the Snapcast server list when nothing is remembered), leave the latency at 0, tap **Start**. A notification "Snapcast client (test)" with a Stop button appears. The status text should go: `service: connecting`, `running`; `engine: buffering`, `aligning`, then `playing, aligned` within a few seconds (the first sample waits for the server's buffer, about a second). `clock: synchronised` and a jitter below 1 ms on Wi-Fi is expected.
7. **Listen for** (compare with the Pi): (a) the offset between the two speakers at the start, after 1, 10 and 30 minutes (the click makes it audible; the kill criterion is "no audible offset after 10 minutes"); (b) whether the correction is audible (a crackle or a pitch wobble once a few seconds would be a failure of "inaudible"); (c) **screen off** for 10 minutes; (d) **Wi-Fi power saving** and the battery saver: up to Android 13 the Wi-Fi lock should prevent power save; **from Android 14 there is no screen-off Wi-Fi lock** (see guess 7), so note the Android version, and compare a run with the screen on and one with the screen off for stutter, `connection losses`, `underruns` and `flushes`; (e) **Doze**: leave the phone alone and unplugged for 30 minutes (`adb shell dumpsys deviceidle force-idle` forces it); (f) **switching networks** (Wi-Fi off, mobile on, Wi-Fi on): expect `reconnecting` in the notification and a resync after the return; (g) a **Bluetooth speaker**: expect an offset that the latency field must absorb; (h) phone call or another app taking the audio focus (the spike does not request focus; it keeps playing).
8. **Tune.** If the phone is consistently early or late against the Pi, type a manual latency in ms and tap **Apply latency** (positive plays earlier, like the latency of the server) and note the value. The same value should work after a restart of the session on the same route.
9. **Test 2 with flac.** Restore `codec=flac` in the rig config, recreate the server, start again. Compare the first-sample offset with the pcm run (guess 9 above), then the 10-minute offset. An error `this device has no decoder for the codec` or `the decoder refused the header` is a result, too.
10. **Stop** with the button (or the notification). Afterwards the phone must be quiet: `adb shell dumpsys power | grep -i snapcast` shows no wake lock, `adb shell dumpsys wifi | grep -i snapcast` no Wi-Fi lock, `adb shell dumpsys activity services de.yaacc` no `SnapcastClientService`.
11. **Numbers to report** (copy them from the status text; it is selectable): after 1 and after 10 minutes — `clock` offset and jitter, `output` ahead and position age and **source** (`timestamp` or `head position (coarse)`), `sync` drift, `corrections` dropped/repeated frames (about 1 frame per 400 at most), `steps` skipped/padded frames (should stay at the start-up values), `resyncs` (timeline, flushes, underruns, epoch changes: all should stay at 0 or very low), `dropped` counters, `decoder` starts/failures/headers ignored/connection losses, `elapsed`. Also: the device model and Android version, the route (speaker, wired, Bluetooth), the codec, and your listening verdict (offset against the Pi in ms if you can estimate it; audible correction yes/no).
12. **What the status words mean.** `aligned` means the first audio was placed on its time; `drift` positive means the phone is late; `ahead` is how much audio the phone has queued in the output (about the write-ahead, 250 ms by default; on a deep-buffer output a value near 120 ms or less is the thin cushion of guess 15); `flushes (suppressed)` counts rebuilds of the output queue because the phone was late by more than 300 ms or the output ran dry (a flush is audible as a short gap); `underruns` is the platform's count; `timeline` resyncs are gaps in the server's timestamps of more than 3 s (the stream starts anew); `timeline rebases` are harmless internal shifts after days; `holes re-anchored (flac)` counts chunks that were lost on the flac path; `chunks lost in the host queue` counts chunks that the audio thread could not take (it was busy).
13. **Security notes for the test.** The server and any controller see this phone's name (the UPnP server name), the program name "YAACC", the words "Android" and "unknown", and an id derived from a secret of this installation and the server's address (`yaacc-` and 16 hex characters; different for every server, never a hardware id). The MAC sent is `00:00:00:00:00:00`. Audio and control are unencrypted. The phone opens no listening socket; it connects only to the address you typed, resolved once.
14. **If it does not sound right: copy the trace (Group 8f).** Open the test screen, run the test for at least a minute, and tap **Copy trace** (next to the one-line summary under the buttons). The clipboard then holds one page of text: a summary line (device model and API level, codec, sample format, track buffer frames, start threshold, `getUnderrunCount`, whether `getTimestamp` ever worked and how old its last report was, flush counts), the whole status text (including **flushes by cause**: hard resync with the drift that triggered the last one, underrun, writer behind, header, connection lost; and the position reports taken and refused) and the **event trace**: the last 600 events (about 50 seconds of a busy run, more of a quiet one; the oldest are overwritten) with the time in ms since the engine started (header accepted or ignored, first write, `play()`, positions, the alignment start with the pad or skip, drift and queue levels once per second, late reports, flushes with their reason, underruns, odd or refused position reports, the switch to the head position, dropped chunks, holes, decoder resets). The trace has numbers and fixed words only. The status text in the same report also contains what the server told about this client, cleaned and cut (the 8 character start of the group id, the stream names and their states, in the `server says:` lines): do not paste it where those names must stay private. Paste it into the report (or mail it); it stays available after Stop until the next Start. A healthy run shows one `flush` at the start (header), `aligned` within a few seconds and no further `flush` lines. The status text says `[last value, not current: no audio is being placed]` next to the drift whenever the engine is not aligned (idle stream): the number is then a stale value. The line `underrun counter of the platform` shows how often the platform's counter rose, how often the position confirmed it and how often it did not.
15. **Write-ahead experiment (Group 8h).** The field "Write-ahead in ms" of the test screen (40 to 350, **default 250 since Group 8i**, applied at Start; the field shows the value that was applied) is how far ahead of the playback position the engine keeps the output filled; the status line shows it (`write-ahead N ms`). The owner's phone raises the platform's underrun counter 3 to 8 times a second while 97 to 140 ms are queued; to see whether the counter or the breaks go away with a deeper queue, try **250 to 300**. What to expect: (a) the queue in front of the speaker is deeper, so a stall of the engine thread up to that much is invisible (tested: a 150 ms stall dries out at 40 ms and is invisible at 300 ms); (b) a flush (resync, a real dry-out) costs a longer gap, because the output holds more audio that is thrown away (about the write-ahead plus the start-up); (c) the engine reacts slower to a change of the clock estimate or a pause of the source by about the same amount; (d) the track buffer is 400 ms, so 350 is the most that fits; the chunk release lead is raised with it. The breaks in a pure tone can also be the corrections themselves, so compare with the status line `corrections` (frames dropped or repeated in how many blocks, the most in one block).
16. **Reading the new status lines (Group 8h).** `writes: N, short S (frames missing M, worst W)`: a write that took fewer frames than offered (the output was full or took only whole bursts; the rest is queued again, nothing is lost); `longest gap between engine ticks`: the loop runs every 10 ms, so anything above 100 ms is the engine thread being kept away (it is the same number for a slow job, GC, or the CPU going to sleep); `longest tick / chunk / position poll`: where the time of a long gap went; `stream restarts after a pause`: how often the source stopped and came back, the gap, and how long the first audio took. Budget: any job on the engine thread must stay under the write-ahead minus about 20 ms (230 ms by default since Group 8i); tested with jobs of 60 ms (never a hole) and 200 ms (a hole every period, absorbed by a 300 ms write-ahead) at periods of 0.25, 0.5, 1 and 2 s. Reviewed and found no job of that size: the time sync runs on the connection thread and only hands the offset over (a stored number and a trace line), the status publication every 250 ms copies a few dozen fields, the watchdog, the power policy and the notification run on the main thread, the engine thread has `THREAD_PRIORITY_URGENT_AUDIO` set on itself (`audioThread` sets it in the body of the thread that runs the loop), the engine allocates a few KB per chunk (no GC pause of note on ART). If the next trace shows a regular long gap, the three `longest` numbers say which call it was.
17. **The server's view and the group button (Group 8i).** Under the buttons the test screen shows the last ending of a session; the status text has `connection:` (frames by type, bytes, the last chunk, first chunk after the connect, the server's settings) and `server says:` lines. When the stream is idle or the phone's group is on another stream the line `HINT:` says what to do; **Move my group to a stream** changes the server (Group.SetStream) and therefore asks first. The trace has `connect attempt`, `connected, Hello sent` (a number from the id, not the id), `server settings`, `first chunk of this connection`, `frames from the server`, `bytes from the server`, `connection lost` with its reason, `server says`, `session ended`.
