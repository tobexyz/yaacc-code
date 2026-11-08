# Security Review: Video galleries (#318)

## Cycle 1 — 2026-11-08
Reviewing: `git diff origin/develop..HEAD` (branch feat/issue318), all groups. General review passed (review.md cycle 2).
Attack surface: the UPnP ContentDirectory is reachable by every host of the LAN without authentication; object ids, sort criteria,
StartingIndex and RequestedCount come from the network.

### Critical
- none

### Warning
- none

### Suggestion
- [VideoItemBrowser.java:49] `BrowseMetadata` of `410999<id>` does not apply the media-path filter (selection is `_id=?` only). Same as
  before this change (`git show origin/develop:.../VideoItemBrowser.java`: selection `_id=?`, no LIKE clause; the image item browsers
  `ImageAllItemBrowser`/`ImageByBucketNameItemBrowser` behave the same). Pre-existing, not made worse (the parent shown is now
  `430999` instead of `400999`, nothing else changed). Effect: a LAN host that enumerates the MediaStore `_id`s (small sequential integers)
  learns display name, size, duration, date and mime type of videos outside the configured media paths. The content itself stays protected:
  `YaaccUpnpServerContentHttpHandler.lookupContent` (line 520) selects `_id=? and (DATA like ...media paths...)`, so `/res/<id>/...`
  returns nothing for such a video. Cheap hardening: `browseMeta` passes `mediaPathSelection() + " AND _id=?"` with
  `mediaPathArguments()` + id, as `VideosByBucketNameFolderBrowser` does for the bucket. Only a test for "video outside the media paths has
  no metadata" would have to be added.
- [VideoBrowser.java:121-139, VideosByBucketNameFolderBrowser.java:56] The galleries query reads one row (id, name) per video and groups in
  memory, on every Browse that needs galleries, including `BrowseMetadata` of one gallery and every `BrowseDirectChildren` page of
  "Galleries". Work is O(number of videos) per request, memory O(number of videos) for the duration of the request (about two short
  strings per row, a few MB for 100k videos). The cache is per browser instance, i.e. per Browse (`findBrowserFor` creates a new browser
  per call), so it only avoids the repetition inside one request (count + page + metadata), not across requests. A host can repeat
  Browse cheaply. There is no rate limit on the ContentDirectory (existing; the image galleries do an N+1 query per gallery, which is
  worse). Acceptable for the expected size of a phone; if it ever matters, a short-lived cache keyed by the MediaStore generation/version
  would bound it.
- [YaaccContentDirectory.java:371 (`computedMaxResults`), VideoBrowser.java:79-84] `RequestedCount=0` means "everything" (`Long.MAX_VALUE`); `items()` then
  builds all videos as `Item`s in memory and one DIDL document. Pre-existing behaviour (the old loop did the same) and the
  `(int)` casts are safe (see below); the DIDL writer for >= 300 objects keeps the size/time reasonable. A server-side cap of
  RequestedCount (the UPnP spec allows the server to return fewer) would bound it for all browsers.
- [YaaccContentDirectory.java:364, :532] The raw `objectID` from the network is written to the debug log (`Browse: objectId: ...`, `unknown
  object id: ...`) without neutralising CR/LF. Pre-existing, debug level only, not new code. The new code logs only the MediaStore mime type
  (VideoBrowser.java:96), no title/path.
- [MediaPathFilter.java:18-31] The media-path filter is `DATA like '%<path>%'`: a substring match with unescaped `%`/`_` in the stored
  path (a path `/Movies` also matches `/Movies-private`, `/x/Movies`). With the filter on and no paths configured, `makeLikeClause(col, 0)`
  yields `col like ?` with no argument and the query throws (fail closed, Browse answers ACTION_FAILED). Pre-existing and identical in the
  HTTP handler, so list and content stay consistent; the new code only reuses it.
- [VideoBrowsersTest.java] No test sends hostile ids to the new routes: `450999` alone (empty bucket id), `450999' OR 1=1--`, a
  1000+ digit id, `410999abc`, `410999-1`. The code is safe for them (see below), a test would pin it (expected: empty list / NO_SUCH_OBJECT,
  and that the id is never part of the selection string, only of the argument list).
- [VideoGalleries / bucket names] The gallery name is `BUCKET_DISPLAY_NAME`, the leaf directory name only; the image galleries and the
  SAF folders expose the same kind of names to the same audience. A null/blank name becomes "(root folder)". If a platform version reports
  the volume id (e.g. an SD card UUID) as the bucket name of files in the root of a volume, it would be shown verbatim as it is for images;
  the new code cannot tell that apart from a directory name. No change needed, just be aware.

### What was checked and is fine
1. Injection.
   - `VideosByBucketNameFolderBrowser.java:44-52`: the selection is the constant string `<media path LIKE clause in parentheses> AND bucket_id=?`;
     the bucket id from the network (`myId.substring(prefix.length())`) only goes into the `selectionArgs` list (bound parameter). It is
     never concatenated into SQL. `VideoItemBrowser.java:49`: selection `_id=?`, id as argument. `VideoBrowser.mediaPathSelection()` builds
     `"(" + column + " like ? or ...)"` from the number of paths only; the path values are arguments (`%path%`).
   - Parsing: fixed-prefix `substring`, routing guarantees the prefix (`startsWith` / `equals`), so no `StringIndexOutOfBounds`. Empty id ->
     `bucket_id=''` -> no rows, `browseMeta` -> null -> `NO_SUCH_OBJECT` (YaaccContentDirectory.java:411-415). Negative ids work (tested).
     Non-numeric / SQL-looking ids are text parameters; SQLite compares them as text against an INTEGER column, nothing matches, nothing is
     executed. Very long ids: bound parameter, bounded by the HTTP request size of the UPnP stack; no regex, no recursion. The gallery
     metadata is looked up by `gallery.id.equals(bucketId)` in the list read under the media-path filter, not by the id.
   - Sort criteria: `SortSupport.toMediaStoreSortOrder` maps only whitelisted property names to fixed column names (VideoBrowser.java:71-72);
     unknown properties are skipped and validated by `SortSupport.validateSupported`. The `ORDER BY` string never contains client text.
   - Routing: new ids 430999 / 440999 / 450999 are not a prefix of any other id and no other id is a prefix of them; the `startsWith` for
     450999 is placed before the other branches but cannot capture them. Unknown ids still fall back to the root browser (existing).
2. Media paths. List (`items`), count (`count`), gallery list (`readGalleries`), gallery content and gallery count all use
   `mediaPathSelection()` (video DATA column) with `mediaPathArguments()`; the bucket condition is an additional AND outside the
   parentheses (an OR-precedence mistake is excluded, and there is a test). A guessed `450999<bucketId>` for a bucket outside the paths gives
   count 0, no items and `NO_SUCH_OBJECT` for the metadata. Content: `/res/<id>` is filtered by media path in the HTTP handler
   (`YaaccUpnpServerContentHttpHandler.java:520-524`), independent of the ContentDirectory, so a guessed item id cannot download a file
   outside the paths. Only the item metadata gap above remains (pre-existing).
3. Suffix scheme: `VideoGalleries.group` appends " (n)" to the display name only; the order is by bucket id so it does not depend on row
   order; no path, no volume name, no bucket id in titles. Bucket ids are in object ids (`450999<id>`) - hash of the path, not reversible
   to a path in practice, same as the image galleries.
4. Memory/time. `(int)` casts: `moveToPosition((int) min(max(0, first), Integer.MAX_VALUE))` cannot overflow or go negative and returns false
   for a position past the end (empty page); `VideoGalleries.page` clamps `start` to `[0, size]` and does not compute `start + max` when `max`
   is `Long.MAX_VALUE` (`max >= size - start` branch), so no overflow; negative values clamp to 0. `maxResults <= 0` returns an empty list.
   Cursors are closed with try-with-resources in `count`, `items` and `readGalleries`; a null cursor is handled. Paging in `items()` reads
   only the requested rows (`do/while` stops at `maxResults`), the count does a projection of `_ID` only.
5. XML / untrusted text. No XML is built by hand in the new code: titles (`DISPLAY_NAME`, `BUCKET_DISPLAY_NAME`) go through
   `createItem` / `new StorageFolder`, serialised by `DidlWriter` (escapes `& < >` in text and `"` in attributes, DidlWriter.java:227-250)
   or Cling `DIDLParser` (DOM). The resource URL is built from the numeric MediaStore `_ID` and a filename sanitised to
   `[a-zA-Z0-9._-]` (`ContentBrowser.getUriString`), the extension from the mime type map / mime subtype (a malformed mime string makes
   `MimeType.valueOf` throw and the Browse fail with ACTION_FAILED, not an injection).
6. Manifest / build: no change in `AndroidManifest.xml`, no new permission, no exported component, no new dependency, no gradle change.
   Only 7 `strings.xml` files (2 plain strings each: `all_videos`, `gallery_unnamed`), docs, changelog, spec files and tests.
7. Secrets / PII: none in the diff. No new logging of titles, paths or URLs (the old code logged name and URI at debug level for every
   video, the new code does not).

### Tests
- Not run (no Gradle, as instructed). State reported for HEAD: build, 3295 tests, lint green.
- Present and relevant: filter on -> every listing asks for the media paths in parentheses; bucket condition outside the OR group;
  negative bucket id; unknown gallery has no metadata; unknown video has no metadata/items; null mime/duration; paging/sort of galleries.
  Missing (Suggestion above): hostile/odd object ids; metadata of a video outside the media paths.

### Verdict: PASS
Zero Critical, zero Warning. Seven Suggestions, all pre-existing or hardening only; the first (media-path filter on `BrowseMetadata` of a
video item) is the one worth doing, since it is a one-line selection change in code this change already rewrote.

## Cycle 2 — 2026-11-08
Reviewing: `999025d7` (media paths on the metadata of images/videos, image galleries, album/artist/genre, `/thumb`, `/album`) and `2a193802`
(notes), plus an independent sweep of every code path that answers for a MediaStore id. Attack surface: ContentDirectory (SOAP) and the
content HTTP server, both without authentication, reachable by every host of the LAN; ids are consecutive numbers.
Not run: Gradle, device (reading only). State reported for HEAD: build, 3310 tests, lint green.

### Paths that read MediaStore (sweep, `grep MediaStore` over `yaacc/src/main/java`: 22 files, nothing outside `upnp/server`)
| Path (id the network sends) | Reads | Media-path filter |
|---|---|---|
| Browse `410999<id>` VideoItemBrowser meta/children | Video | yes (`_id=? AND (DATA like..)`) |
| Videos All / Galleries / Gallery `430999/440999/450999<b>` (list, count, meta, items) | Video | yes (all through `mediaPathSelection()`) |
| `310999<id>` ImageAllItemBrowser meta, `220999<id>` ImageByBucketNameItemBrowser meta | Images | yes (new) |
| `300999` Images All (count, items) | Images | yes |
| `200999` Image galleries: list, count | Images | yes; per-gallery child count (`getBucketNameFolderSize`) NO |
| `210999<b>` image gallery: meta, name, count, items | Images | yes (new, `bucketSelection()`) |
| `600999` All titles (count, items), `610999<id>` item meta | Audio | yes |
| `810999/820999` album meta, name, count, items; `820999<id>` item | Audio, Albums | count/items/item yes; meta gated by count > 0 (new); name only after the gate |
| `910999/920999` artist, `710999/720999` genre (same) | Audio, Artists, Genres, Genres.Members | same as album; genre API < R through `Genres.Members.DATA`; genre item meta API < R is dead code (`query(null..)`, fails closed) |
| `800999/900999/700999` album/artist/genre lists and counts | Albums/Artists/Genres tables | yes, an entry is listed only if >= 1 track in the paths (`getMusicTrackSize`) |
| `100999`, `400999`, `500999`, root `0` | none (constant counts) | n/a |
| `1000999/1100999` SAF browse | SAF, no MediaStore | own rules, see W3 |
| `1200999` live streams | capture services | not MediaStore |
| unknown id -> root browser | none | n/a |
| `getSearchCapabilities`, `getSortCapabilities`, `getSystemUpdateID` (no Search action exists) | none | n/a |
| HTTP `/res/<id>` (lookupContent) | Files | yes (since before) |
| HTTP `/thumb/<id>` | Images (+ `Thumbnails.getThumbnail`) | yes (new, before the thumbnail call, handler:700 vs 707) |
| HTTP `/album/<albumId>` (route is `album`, not `albumart`) | Audio, Albums, `loadThumbnail` | yes (new, handler:584, before BOTH the API <= P and the > P branch) |
| HTTP `/saf/<key>/<name>` | SAF | no check of the selected trees, see W3 |
| HTTP `/live/*` | capture | not MediaStore, only while a capture runs |
| legacy `/?id=<id>` (ImageAllItemBrowser still builds this URL) | none | 0 path segments -> 403, never served |
| `/proxy/<key>` ProxyShareResponder, ShareService | share registry (random key, not a MediaStore id) | n/a |
| YaaccUpnpServerService | only creates the ContentDirectory; no MediaStore access | n/a |

### Critical
- none

### Warning
- [ImagesByBucketNamesFolderBrowser.java:82-92, :126] `getBucketNameFolderSize` counts `BUCKET_ID=?` without the media-path condition and sets it as
  the child count of every gallery in the list (and nothing else overrides it). The bucket id comes from the filtered list, so this only
  differs for a bucket that holds files inside and outside the paths (the filter is a substring of the whole path, so a path that reaches
  into the file name, or buckets that share an id, e.g. case variants of a directory). Then the container announces the number of images
  that are NOT served and the count differs from `ImagesByBucketNameFolderBrowser.getSize` for the same gallery. Contradicts the decision
  that the paths apply to every answer; fix is the pattern already used for the gallery: `BUCKET_ID=? and (DATA like ..)` with the path args
  (a private helper shared with `ImagesByBucketNameFolderBrowser.bucketSelection()`), null cursor -> 0.
- [YaaccUpnpServerContentHttpHandler.java:242-246] Pre-existing, not in the last commits. The 404 page is built as
  `"<html><body>Resource with id " + contentId + albumId + thumbId + pathSegments.get(1) + " not found</body></html>"`, `text/html`, and
  `pathSegments.get(1)` is the percent-DECODED second path segment, unescaped. `GET /x/%3Cimg%20src%3Dx%20onerror%3D...%3E` (any unknown type,
  or `/saf/<junk>/<junk>`) is reflected HTML/script on the origin `http://<phone>:<port>`. No CSP, no `nosniff` (ServerHttpProcessor sets only
  Date). Impact: a page the victim opens in a browser on the LAN (the link only needs the phone's address) runs script in the origin of the
  content server and can read `/res/<id>` of everything in the media paths and send it out: the unauthenticated LAN-only server becomes
  readable for an attacker outside the LAN through the browser of any LAN user. Fix: do not echo the segment (fixed text "Resource not
  found"), or HTML-escape it; add `X-Content-Type-Options: nosniff`.
- [YaaccUpnpServerContentHttpHandler.java:725-840 (FIXME at :822-838), SafFolderBrowser.java:243, :332] Pre-existing, the owner carved out the
  SAF folders as having "own rules", but for access by id that rule is not enforced. `lookupSafContent`, `SafFolderBrowser.browseMeta`
  and `getSize`/browse map the numeric short id (sequential counter, persisted) to a document uri with `SAFCacheManager.getUriForShortId` and
  open it; the check against the selected trees is commented out (`FIXME`). Deselecting a tree (`TreeViewHolder.java:141`, `saveSelectedSafPathes`)
  keeps its ids in the map (`removeCachedUnder` only runs when the permission is lost) and the persisted permission stays, so
  `/saf/1100999<n>/<n>.ext` and Browse `1100999<n>` still serve and describe files of a tree the user no longer shares, to a host that counts
  through the short ids. Fix: resolve the tree of the uri and require that it is below one of `getSelectedSafPathes()` in both the handler and
  `SafFolderBrowser` (or drop the ids of a tree when it is deselected). The owner may accept it as a known gap in decisions.md; then it is
  a Warning carried, not forgotten.

### Suggestion
- [YaaccUpnpServerContentHttpHandler.java:584-600, :633-638] Cover of an album with tracks inside AND outside the paths: the check is "a track of the album is
  in the paths", but `Albums.ALBUM_ART` (API <= P) / `loadThumbnail(Albums/<id>)` (> P) is chosen by the platform from the whole album, so the
  picture can come from a track outside the paths. Embedded covers of one album are nearly always the same picture; leak is theoretical.
- [MediaPathFilter.java:18-31, filter on and empty list] `makeLikeClause(col, 0)` yields `col like ?` with no argument. Checked every user of
  the clause: `id=? and (col like ?)` has two placeholders and one argument, so the unbound one is NULL (no row) or the provider rejects the
  query; in both cases nothing is answered (fails closed, also for the new `isInMediaPaths`, the new `getSize == 0` gates and the thumbnail).
  Not verified on a device. Consequence is bad but not unsafe: Browse answers ACTION_FAILED (`YaaccContentDirectory.java:343/356` puts
  `ex.toString()` in the SOAP fault, the exception class and message of the provider reach the LAN) and the HTTP handler lets the
  RuntimeException of `isInMediaPaths`/`lookupContent` escape `handle()` (HttpCore answers 500; its body is usually the exception
  message, not verified). Cleaner: `makeLikeClause(col, 0)` returns `1=0`, so an empty list is an empty answer, and the SOAP fault carries a fixed text.
- [RootFolderBrowser.java:133-160, YaaccContentDirectory.findBrowserFor, handler lookupContent] The switches "serve images/videos/music" only
  hide the folder in the root listing. With "serve videos" off, `410999<id>`, `450999<b>` and `/res/<id>` (MediaStore.Files, any type) still
  answer for files in the media paths. Same family as the cycle 1 finding (what the user did not choose to share is reachable by counting).
  Owner decision: enforce the switch per id range, or document that the switches are only about the root view.
- [MediaPathFilter.java:18-31, :54-56] The filter is `DATA like '%<path>%'`: substring and unescaped `%`/`_` (a path `/Movies` also matches `/Movies-private`
  and `/x/Movies/..`). Since it is now the only control for every id, anchoring (`<path>/%` with escaped metacharacters) is worth doing. Not
  exploitable by the LAN host (cannot create files).
- [MusicArtistFolderBrowser.java:70] `getName` uses `myId.substring(myId.indexOf(PREFIX))`, i.e. `substring(0)`, the whole id with prefix, as
  `Artists._ID`: the title of an artist container is always empty. Pre-existing, not a security matter; found while checking (5).
- [MusicGenreItemBrowser.java:93] API < R: `getContentResolver().query(null, ...)` throws, genre item metadata fails (closed) on Android <= 10. Pre-existing.
- [MusicAlbumsFolderBrowser.java:140-148 and the artist/genre/image-gallery copies] The paging arithmetic gives `subList(-1, 0)` when the (filtered)
  list is empty (filter on, no music in the paths) and wraps to the last entry for a start behind the end. ACTION_FAILED / repeated page, no leak.
- [ImageAllItemBrowser.java:~120, ImageByBucketNameItemBrowser.java:~125] `browseItem` adds the result of `browseMeta` without a null check: BrowseDirectChildren of
  an image id outside the paths gives ACTION_FAILED (same for an id that does not exist, so no oracle). Videos handle null.
- [tests] No test with the API > P branch of `lookupAlbumArt` (the order of the gate is only visible in the code, which is right), none for the
  genre API < R branch, none for hostile ids on the new image/music routes (they are bound parameters: `id=? ..`, safe).

### What was checked and is fine
1. Injection: every id from the network (`substring` after a prefix the router guarantees) only goes into `selectionArgs`; `makeLikeClause` builds
   placeholders from the number of paths; sort orders are whitelisted columns (`SortSupport`). `Long.parseLong(genreId)` (API < R) with a
   hostile id gives NumberFormatException -> ACTION_FAILED, nothing executed. No new XML by hand.
2. New code, argument order: image items/galleries `[id|bucket, paths...]` for `x=? and (..)`; video item `[id, paths]` for `_id=? AND (..)`;
   video gallery `[paths..., bucket]` for `(..) AND bucket_id=?`; all-titles item `[paths..., id]` for `(..) and _id=?`; `isInMediaPaths`
   `[id, paths...]`. Every OR group is in parentheses, so no precedence hole. Filter off: one path `""` -> `%%` -> every row with a DATA
   (a row with NULL DATA is not answered for images/videos any more; `/res` never served it).
3. Null cursor: `isInMediaPaths`, `ImagesByBucketNameFolderBrowser.getSize/getName`, video `count/items` handle it. The album/artist/genre `getSize` do
   `cursor.getCount()` without a null check (NPE -> ACTION_FAILED, closed), pre-existing.
4. Genre branches: API >= R filters `Audio.Media.GENRE_ID` + DATA; API < R filters `Genres.Members.DATA` and the second query `_ID in (ids from the
   filtered members query)`, empty list returns early, so the unfiltered final query cannot add rows. `getSize == 0` gate before name/items for album, artist, genre.
5. `isInMediaPaths` runs before both album-art branches and before `getThumbnail`; the cover cache file `albumart<canonical id>.jpg` is only
   written after the gate and never read by another route; ids are canonical for the album, and parsed by `Long.parseLong` for the thumbnail (a
   spelling like `+7` matches the same row in SQLite, no disk cache is keyed by it).
6. A hidden id gets the same answer as a missing id on every ContentDirectory route (NO_SUCH_OBJECT, or the generic icon for thumb/cover; a missing
   id and an id outside the paths cannot be told apart by status). An album/artist/genre name is only shown for entries with a track in the paths.
7. (5) Legitimate use: listing and playing an album, artist, genre, image gallery, video gallery inside the paths takes the same queries as
   before plus the media path condition they already had for their items; names and counts of image galleries are now right (the title was the
   bucket id). No behaviour change for entries inside the paths except the NULL-DATA row noted in 2.
8. Routing: `/res`, `/thumb`, `/album` need the content server on, numeric ids, and `settings_local_server_chkbx`; all other URLs give 403/404; the legacy
   `/?id=` has no segments and gets 403.

### Tests
- Not run (no Gradle, as instructed). Reported state: 3310 tests, lint green. `MediaPathFilterBrowsersTest` (10) and `MediaPathServingTest`
  (4) pin the new filters, including "outside the paths" for item meta, gallery, album/artist/genre, thumbnail, cover.

### Verdict: FAIL
No Critical. Three Warnings: the first is inside the owner's decision (the child count of an image gallery ignores the paths), the other two are
pre-existing and outside the last commits (reflected HTML in the 404 page, SAF ids not tied to the selected trees). Every id of the MediaStore
now goes through the media-path filter on all routes except that count.

## Cycle 3 — 2026-11-08
Reviewing: `70acb03e` (W1 child count, W2 404 page), `4515d92d` (W3 first part, LIKE escaping), `7fb35166` (SAF selection per selected folder),
`c6417827` (notes), plus the whole branch `origin/develop..HEAD` once more. Attack surface unchanged: ContentDirectory (SOAP) and the content
HTTP server, no authentication, every host of the LAN; ids are consecutive numbers.
Not run: Gradle, device (reading only). State reported for HEAD: build, 3339 tests, lint green.

### Critical
- none

### Warning
- none

### Suggestion
- [MediaPathFilter.java:makeLikeClause/getMediaPathesForLikeClause, carried from cycle 2] The media path filter is still `DATA like '%<path>%' escape '\'`,
  a substring match of the whole path. The settings store absolute paths without a trailing slash (`TreeViewHolder.java:133-143`), so a
  selected `/storage/emulated/0/Pictures` also serves `/storage/emulated/0/Pictures2/..` and `/storage/emulated/0/Pictures-private/..`, and a
  directory `/x/storage/emulated/0/Pictures/..` created by another app. This is exactly the "starts like a selected one" class that W3 closed
  for the SAF trees, and it is the only control for MediaStore ids. Not new in this branch, not exploitable by the LAN host (it cannot create
  files), the escaping now makes the substring literal. Recommended next step: anchor it, per path `(DATA = ? or DATA like ? escape '\')` with `<path>` and `<escaped path>/%` and no `%` in front
  (the filter-off case then needs its own `1=1`; the DATA column holds absolute paths). Owner decision; I rate it Suggestion because it was accepted in cycle 2 and the SAF side (the part the owner chose to harden) is exact now.
- [MediaPathFilter.java:18-31, filter on and empty list, carried] `makeLikeClause(col, 0)` is `col like ? escape '\'` with no argument. Every user
  of the clause (31 call sites in 18 files under `upnp/server`, among them `lookupContent` and `isInMediaPaths` in the HTTP handler) puts it in
  `id=? and (..)`, `(..) and id=?`, `(..) AND bucket_id=?` or `(..)` alone, so the unbound `?` is NULL and `like NULL` is NULL: no row, nothing answered
  (fails closed). `getMediaPathes().size()` and `getMediaPathesForLikeClause()` read the preferences separately; if the setting changes in between
  there are more arguments than placeholders ("Too many bind arguments", exception, closed) or fewer (a NULL term in an OR, still a subset).
  Cleaner: `makeLikeClause(col, 0)` returns `1=0`.
- [YaaccContentDirectory.java:353-358 (`ex.toString()` in the ACTION_FAILED fault), carried] How a refusal of the `ESCAPE` clause by the Android media
  provider would show (cannot be tested here): `ContentResolver.query` throws `IllegalArgumentException("Invalid token ...")` (strict grammar) or an
  `SQLiteException`, no browser catches it (spot-checked VideoBrowser, ImagesByBucketNamesFolderBrowser, MusicAlbumFolderBrowser: no catch; the others use the same try-with-resources pattern), so Browse answers
  ACTION_FAILED with the exception class and message as the fault text (visible to the LAN host: the message names a token or the SQL, never
  a configured path, since paths are arguments), and the HTTP handler lets the RuntimeException of `isInMediaPaths`/`lookupContent` escape
  `handle()`; HttpCore answers 500 or closes the connection (its handling of an escaped RuntimeException was not verified), and no `ContentHolder` was built before the exception, so nothing is served.
  Both fail closed. To the user it looks like: every image/video/music listing and every `/res`, `/thumb`, `/album` request fails at once after the
  update on Android 11+; the thumbnail and cover routes do NOT fall back to the generic icon on an exception (only on "not in the media paths").
  I believe the grammar accepts it (`enforceStrictToken` blocks the clause keywords select/from/where/group/having/window/values/order/limit,
  every other SQLite keyword is allowed; the string literal `'\'` is not a token), but this is from memory of the AOSP source, not tested. Worth one
  manual check on an Android 11+ device before release. A fixed fault text instead of `ex.toString()` would also stop the provider's message
  reaching the LAN.
- [SAFCacheManager.java:759 (`preloadSafDurations`), carried as new observation] The duration preload walks `getSafPathes()` (every tree the app holds),
  not `getSelectedSafPathes()`. Nothing of it reaches the network (the short ids it creates and the durations it caches are only answered when
  the document is in a selected folder, checked above), but it reads files the user deselected, spends battery and I/O on them, and consumes
  numbers of the shared short id counter, so the gaps between the ids of a selected folder tell a LAN host how many files the deselected folders
  hold (a counting oracle of size only). Walk the selected folders only.
- [SafDocumentUris.java:99-104 and TreeViewHolder.java:131-141, design] A selection is inclusive: unticking a folder below a ticked folder (or below a
  whole selected tree) does not hide it, it is still below the other selected folder. That is what the media path filter does too, and the settings
  text should say so; a user who unticks "Private" under a ticked "Photos" otherwise assumes it is hidden.
- [SafDocumentUris.java:99-104] The `startsWith(folderId + "/")` rule trusts that a provider names the parent in the id. For the external storage
  and every path-like provider it is true, and it is only applied to uris the app itself got from the provider (never to client text). A provider
  with flat ids that contain `/` inside a different folder would be seen as inside; `..` in an id cannot occur because the ids come from the
  provider's listing, not from the client. Accepted trade-off, no action.
- [SafDocumentUris.java:97 and :124] The authority is compared with `equalsIgnoreCase`; an authority is case sensitive for `ContentResolver`. Harmless
  (both strings come from the app's own settings and cache), `equals` would be tighter.
- [SafFolderBrowser.java:245, :335, :375, :503; YaaccUpnpServerContentHttpHandler.java:771, :243] Strings of the client (short id, object id, path
  segment) go into the log at warn/error level for unknown ids (the default level is ERROR), so a probing host can fill and rotate logcat with its
  own text. Only logcat (no file), no markup context. Log the length or nothing, or log at debug like the refusals.
- [YaaccUpnpServerContentHttpHandler.java:259] The served content has `X-Content-Type-Options: nosniff` but no `Content-Security-Policy: sandbox`
  (`ProxyShareResponder.java:321` has it). A `.html` file in the media paths is served as `text/html` in the origin of the content server and could read
  other `/res/<id>`. It needs a hostile file on the phone first. Add the header for every type that is not audio/video/image.
- [tests] `SafDocumentUrisTest`/`SafSelectedTreesTest`/`SafServingTest` cover deselected tree, sibling folder, tree of another authority, whole tree,
  provider fallback and non-tree uris. No test with a `..` or `%2e%2e` document id and no test of a tree id that only starts like a selected one
  (`primary:DCIM` vs `primary:DCIM2`); both are right in the code (exact `equals` on the decoded tree id, `folderId + "/"` for the prefix).

### What was checked and is fine
1. W1 closed: `ImagesByBucketNamesFolderBrowser.getBucketNameFolderSize` (:86-99) counts `BUCKET_ID=? and (DATA like ..)` with `[bucket, paths...]`, null cursor 0, the
   same selection as `ImagesByBucketNameFolderBrowser.bucketSelection()` (so list count == gallery getSize). Video galleries: the count of a
   gallery in the list comes from the rows read with `mediaPathSelection()` (`VideoGalleries.group`), the count in the gallery from
   `(paths) AND bucket_id=?`; both through the same clause. Argument order checked for all five video selections.
2. W2 closed: `NOT_FOUND_PAGE` is a constant (`YaaccUpnpServerContentHttpHandler.java:678`), the 404 sets `nosniff` (:247). Grep over
   `upnp/server/http` and `share`: every body is a constant (`/health`, 403, `ProxyShareResponder` NOT_FOUND/FORBIDDEN/BUSY/RANGE/UPSTREAM_BODY,
   :1280 "Resource not found"); no header value is built from request text (`Content-Range`/`Cache-Control` are numbers, `ContentType.parse` gets the
   provider mime type, not the request); `ShareService` has no HTML or header output. The only request text left is in debug logs. If a RuntimeException escapes `handle()`, HttpCore's error text cannot contain request text either (the ids are `Long.parseLong`-checked first; not verified in the library).
3. W3 closed, ids of a deselected folder: `allowedUri` (SafFolderBrowser.java:280-287) runs in `browseMeta` (:243), `getSize` (:328), `browseContainer`
   (:372) and `browseItem` (:501); `lookupSafContent` (:775) checks before the document is opened; the old FIXME is gone. A short id that is not in
   the cache and one that is not selected give the same answer (null -> NO_SUCH_OBJECT / empty list / 0 / 404), no oracle.
4. W3, can a client still reach a document outside the selection: the client sends only the short id (a key of a map the app fills from provider listings
   and from its own settings; `SafShortIds.uriFor` is a lookup, `getOrCreateShortId` is only called with uris built by the app: selected folders, children
   of an allowed listing, parents in `parentIdOf`), never a uri or a document id, so `..`, percent-encoding and case tricks in the id cannot be injected. Still
   checked as if they could: `decode` is one UTF-8 pass (like `Uri`), malformed escapes stay as written, tree id compared with `equals` after decoding, so
   `primary:DCIM2` is not `primary:DCIM`, and `primary%3ADCIM` equals `primary:DCIM`; a sibling `Camera2` is not below `Camera` (`folderId + "/"`); the parent of
   the top of a selection is the SAF folder (`isSelectedFolder`, :256), and the parent folder itself has a short id that answers NO_SUCH_OBJECT unless selected;
   a different authority never matches; a uri that is no tree uri (picked file, plain tree uri without a document, `null`) is not selected (and the app only
   offers trees, `ACTION_OPEN_DOCUMENT_TREE`, no picked files); a whole selected tree is `folderId == treeId`. A tree-only selected entry
   (no `/document/`) is the whole tree, so `isChildDocument` is not called with a uri it would reject.
5. Fail closed: `SafSelection.contains` catches every exception of `isChildDocument` (SecurityException, IllegalArgumentException for a tree-only parent, remote
   errors) and answers false; `isInSelectedTrees` is null safe and has no backtracking pattern (`[^/?#]+`); an exception from the preferences escapes as
   ACTION_FAILED / 500 before anything is opened. Refusals are logged at debug only.
6. Short id map and persistence: ids are consecutive numbers from one counter; `SAFCacheManager` persistence restores id -> uri from its own store, a client
   cannot write to it; deselecting keeps ids (harmless now, they answer nothing), losing the permission still removes them (`removeCachedUnder`).
   `SafChangeRunner` watches selected folders only. The duration preload is the one reader of deselected folders (see Suggestion), not network-reachable.
7. LIKE escaping, every user: `makeLikeClause` always `like ? escape '\'`, every pattern from `getMediaPathesForLikeClause` = `%` + `escapeLike(path)` + `%`
   (`\` first, then `%`, `_`), so a path with `%`, `_` or `\` is literal and cannot widen the filter. A path is only ever an argument, never part of the SQL
   text (placeholders are generated from the count). In Java `'\\'` is the SQL literal `'\'`; SQLite takes no escapes inside a literal, so no injection through
   the clause. All 31 call sites pair the clause with the list from the same helper. Filter off: the set is `{""}`, the
   pattern `%%`, every row with a DATA matches (as before). `MusicGenreItemBrowser:82` compares `Genres.Members.RELATIVE_PATH` with absolute paths, so it
   never matches (dead code on API < R, closed).
8. Video galleries (the goal): list/count/meta/items of All videos, Galleries, one gallery and the video item all go through the media path clause; ids from the
   client are bound parameters; sort orders are the whitelist of `SortSupport`; names of galleries are directory names and are escaped by both DIDL writers
   (`DidlWriter` escapes `& < > "`, the DOM path by the library); routing of `430999/440999/450999` checked against all earlier `startsWith` branches, no
   prefix is swallowed; a gallery id outside the paths gives NO_SUCH_OBJECT (meta null) and an empty list; cycle 1 and 2 items (metadata of videos/images, thumbnail,
   cover, album/artist/genre names) re-read and still in place.
9. Routes `/res`, `/thumb`, `/album`: numeric id required before any query, `/saf` needs the key in the cache and the selection, `/live` is fixed, everything else
   403/404 with a constant body; no file path is built from the request.

### (5) Remaining unfiltered or unverified paths
- `Android 11+ MediaProvider` and the `ESCAPE` grammar: unverified on a device (see Suggestion, fails closed).
- The switches "serve images / videos / music" only hide the root folder, ids in the media paths are still answered (carried from cycle 2, owner decision).
- The media path filter is a substring match (Suggestion 1).
- Filter on with no media path configured: the query fails or returns nothing (closed); not tested on a device.
- Duration preload reads deselected SAF folders (not network-reachable).
- `MusicGenreItemBrowser` API < R `query(null, ..)` and the album art cover chosen from a whole album (cycle 2 Suggestions, unchanged).
- Content served with a hostile mime type has no CSP sandbox (Suggestion).
- Not covered by this branch: `ProxyShareResponder`/`ShareService` internals beyond the grep (random key, expiry, receiver check, `nosniff` + `sandbox`; unchanged here).

### Tests
- Not run (no Gradle, as instructed). Reported state: 3339 tests, build and lint green. Read: `MediaPathFilterTest` (7 cases for the escaping and the filter-off case),
  `SafDocumentUrisTest` (selection, encoding, authority, tree, provider fallback), `SafSelectedTreesTest` (deselected tree, sibling, below a selected folder, unknown id),
  `SafServingTest` (served, deselected not even opened), `MediaPathFilterBrowsersTest`, `MediaPathServingTest`, `VideoBrowsersTest`, `VideoGalleriesTest`.

### Verdict: PASS
No Critical, no Warning. The three Warnings of cycle 2 are closed (W1 `ImagesByBucketNamesFolderBrowser.java:86-99`, W2 `YaaccUpnpServerContentHttpHandler.java:247/678`, W3
`SafFolderBrowser.java:280-287` + `YaaccUpnpServerContentHttpHandler.java:775` with `SafDocumentUris.java:88-125`). The suggestions above do not block; the two I would not
ship without a look: the manual check of `ESCAPE` on an Android 11+ device, and the anchoring of the media paths.
