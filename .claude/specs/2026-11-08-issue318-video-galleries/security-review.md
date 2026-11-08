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
