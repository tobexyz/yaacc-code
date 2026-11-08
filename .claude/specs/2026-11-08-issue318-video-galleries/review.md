# Review: video galleries (#318)

## Cycle 1 — 2026-11-08
Reviewing: Groups 1-3 (`git diff origin/develop..HEAD`, 4 commits: spec, tests first, implementation, docs)

Checked against design and MediaStore API: BUCKET_ID / BUCKET_DISPLAY_NAME of `MediaStore.Video.Media`, DATA LIKE filter incl. the
"filter off" case (`""` -> `%%`), `moveToPosition` paging, sort order mapping, negative bucket ids, null bucket names, cursor
closing (all try-with-resources), `Long.MAX_VALUE` for maxResults (no `start + max` overflow in `VideoGalleries.page`, loop compares
`int < long`), routing order in `findBrowserFor` (no new prefix shadows or is shadowed; `450999` and `410999` are disjoint, the new
test over all prefixed ids guards that), image browsers (untouched, `git diff` shows no change), other users of the video ids
(grep for `VIDEOS_FOLDER`, `VideosFolderBrowser`, `VIDEO_PREFIX`, `ContentDirectoryIDs` outside the package: only `RootFolderBrowser`,
which only calls `browseMeta` and now needs no query for the child count; the HTTP handler uses only SAF ids), SystemUpdateID (no
MediaStore based container update id exists for images or music either, nothing to keep for videos), search (none in the content
directory), regressions for clients (old `VIDEOS_FOLDER` browse returns 2 containers, item ids unchanged, BrowseMetadata of a video
item answers with the parent `430999`; `VideoItemBrowser.browseItem` no longer adds `null` for an unknown id, an improvement).
No Critical finding.

### Critical
- none

### Warning
- [.claude/specs/2026-11-08-issue318-video-galleries/design.md:20 and tasks.md:12-13] The spec names a support class `VideoRows` ("the
  projection, the conversion of a cursor row into an item, shared by the three item listings and `VideoItemBrowser`"). It does not
  exist; the implementation is the abstract base class `VideoBrowser` (query helpers `count`, `items`, `galleries`, media path
  selection). Fix: update the design (class list) and tasks 2.1/2.2 to `VideoBrowser`, so the spec matches the code.
- [docs/index.md:425-430] The paragraph says that *Images* and *Videos* both offer the galleries and then states three things for both:
  directories with the same name are told apart by a number, files in the volume root go into a gallery named *(root folder)*, and the
  media paths apply to the galleries. This is only true for videos. `decisions.md` itself records that the image galleries show two
  same-named directories under the same name, `ImagesByBucketNameFolderBrowser` ignores the media paths for the items of a gallery
  and has no name for a bucket without one. Fix: limit the three statements to the video galleries (or say "Videos" explicitly), and
  mention that the text of the root gallery follows the app language (the German one is *(Hauptordner)*). The sentence "Before this was
  added, *Videos* held the list of all videos" is migration information that belongs in the CHANGELOG (already there), not in the
  manual.
- [yaacc/src/test/java/de/yaacc/upnp/server/contentdirectory/VideoBrowsersTest.java:104] The media path requirement ("filters the list of the
  galleries and the content of a gallery alike") is tested only with the filter off (one argument `%%`). The case that can go wrong is
  the filter on with two or more paths: `(_data like ? or _data like ? ) AND bucket_id=?` needs the parentheses so that the bucket
  condition binds to the whole OR group, and the arguments must be in the order of the placeholders (paths first, bucket id last).
  Nothing in the tests would fail if `mediaPathSelection()` lost its parentheses or the bucket id were put first in
  `VideosByBucketNameFolderBrowser.selectionArgs`. Fix: add a test with the preference filter on and a set of two paths (return
  `true` for the filter key and a `Set` for the media path key) for the gallery items, `getSize` and the gallery list; assert the
  selection starts with `(`, the `AND bucket_id=?` follows the closing parenthesis and the last argument is the bucket id.

### Suggestion
- [VideosByBucketNamesFolderBrowser.java:56-69] Two full scans of the videos for one BrowseDirectChildren of "Galleries" (`getSize` and
  `browseContainer` each call `galleries()`), and one more per BrowseMetadata of a gallery (`VideosByBucketNameFolderBrowser.browseMeta`).
  Acceptable for the sizes of a phone (two columns per row), but a browser instance lives for one Browse (see
  `YaaccContentDirectory.browse`: "one browser for all steps"), so the grouped result could be kept in a field of the
  browser and the second scan saved.
- [VideosByBucketNamesFolderBrowser.java:67 and VideosFolderBrowser.java:69] The title sort key (`toLowerCase(Locale.ROOT)`, null safe) is
  written twice; `VideosFolderBrowser` could use the same helper (for example a package-private static in `VideoGalleries`).
  Also the sort of the galleries has no tie-breaker: two names that differ only in case ("Camera", "camera") keep the order of the rows,
  and the query has no sort order, so a client that pages could in theory see an unstable order. Comparing by name, then by bucket id
  (`Gallery.id`) before building the containers would make it deterministic.
- [VideoGalleries.java:73-78] A real directory named like a generated name ("Camera (2)") gets the same title as the suffixed one. Only
  titles collide (the ids differ), so it is cosmetic; skipping used names while counting would remove it. `BUCKET_ID_ORDER` is declared
  after its first use in the file; moving it above `group` reads better.
- [VideoItemBrowser.java:52] `orderby` is passed to a query that returns one row; passing `NO_SORT`/an empty array is clearer. No test for
  the unknown item (browseMeta returns null) or for a video without mime type / duration (both handled in `VideoBrowser.item`).
- [VideoBrowsersTest.java] No test that a cursor is closed (`verify(cursor).close()`), and `MediaCursors` answers every query with all
  rows regardless of the selection, so the tests check the selection text and the arguments, not the filtering; that is fine, but
  keep it in mind when reading `aGalleryListsTheVideosOfItsBucketInTheMediaPaths`.
- [VideoBrowser.java:46-48 / MediaPathFilter.makeLikeClause] With the filter on and an empty list of media paths the selection has one `?`
  and zero arguments (the query throws). Not new (the old videos listing and the images behave the same), so out of scope here;
  noting it because the Videos root now runs the galleries query as well.
- [VideoGalleryResourcesTest.java:54] The "not the English text" check skips `values-fr` without a reason; harmless.

### Tests
- [x] All tests passing (reported by the orchestrator: `assembleDebug testDebugUnitTest lintDebug` green, 3289 tests, lint 0; Gradle not run in this review)
- [ ] Coverage adequate for the change (all behaviors of the design are covered except the media path filter being on with several paths, see the third Warning)

Tests fail without the fix: routing, ids and browser tests reference classes and constants that do not exist before the change
(test-first commit), and the behavior tests encode the bugs the design avoids (page cut after grouping, same-name suffix independent
of row order, negative bucket ids as numbers, "All videos" as the parent of an item).

### Verdict: FAIL
No Critical findings; three Warnings (spec out of date, docs overstate what the image galleries do, missing media-path-filter-on test).
All three are small and need no production code change except none; fixing them gives PASS.

## Cycle 2 — 2026-11-08
Reviewing: the fixes of cycle 1 (`git show HEAD`, eb9405a8) and the code they touch.

Checked: the three Warnings of cycle 1, the cache of the galleries (lifetime of a browser), the new sort helpers and the comparator
composition, the new tests against the code they claim to guard, regressions in the callers (`VideosFolderBrowser`, `RootFolderBrowser`,
`VideoItemBrowser`). Gradle not run in this review.

- Warning 1 (spec names): fixed. design.md:20 and tasks.md 2.1/2.2 name `VideoBrowser`; `VideoRows` appears nowhere in the spec or code.
- Warning 2 (docs): fixed. docs/index.md:425-430 now limits the suffix, "(root folder)" (with "in the language of the app", the German text is
  "(Hauptordner)", verified in values-de) and the media paths to the *Videos* galleries. The migration sentence is gone (it is in the CHANGELOG).
  What it claims matches the code: `VideoGalleries.group` (counter by bucket id order), `gallery_unnamed`, `mediaPathSelection()` in the
  gallery list and in the gallery content.
- Warning 3 (filter on): fixed. `filterOn` stubs `getBoolean` -> true and `getStringSet` -> two paths. The tests assert the selection starts with
  `(_data like ? or _data like ? )` (matches `MediaPathFilter.makeLikeClause`, whose second `like ? ` carries the trailing space), that the
  first `)` comes before `AND bucket_id=?` and that the bucket id is the last of three arguments. A set has no order, so the paths are compared
  with `containsAll`, correct. They cover `getSize` and `browseItem` of a gallery, and `browseItem`/`getSize` of all videos and the galleries list.
  The mutation check reported in the task (no parentheses) fails both: plausible, `startsWith("(...")` is the first assertion in both tests.
  Ordering of the bucket id (put first) would fail `assertEquals("7", args.get(2))`.
- One read of the galleries: the claim "a browser lives for one Browse" holds. `YaaccContentDirectory.browse` calls `findBrowserFor(objectID)`
  once (line 411), which builds a new browser per call (no caching of instances anywhere; the only other creators are
  `VideosFolderBrowser`/`RootFolderBrowser`, which create a fresh browser per use), and `getSize`, `browseChildren` and `browseMeta` of that Browse
  use that instance. The cached list is never modified (`browseContainer` copies into a new list before sorting), so sharing it is safe; a Browse
  does not run concurrently on one instance. The cache sits in `VideoBrowser`, so the other video browsers carry an unused null field (harmless).
- Sort helpers: `titleKey` is the former key (null safe, `Locale.ROOT`). `inTitleOrder` = key, then raw title (upper case first), then id; the ids
  are never null for the containers built here. `SortSupport.toComparator(orderby, accessors, tieBreaker).thenComparing(tieBreaker)`:
  with no/unmapped criteria the first comparator is already the tie-breaker (second one is a no-op), with `-dc:title` the key is reversed and ties
  fall back to ascending title/id (deterministic), with `dc:date` (not mapped for containers) the default applies. No behavior change
  for `VideosFolderBrowser` (same key; its default stays the fixed order "All videos", "Galleries").
- `BUCKET_ID_ORDER` moved above its first use; no change in behavior (static initialization order was fine before as well, being used only at call time).

### Critical
- none

### Warning
- none

### Suggestion
- [VideoBrowsersTest.java, `theGalleriesAreReadOnceForTheCountThePageAndTheMetadataOfOneBrowse`] The name promises "the metadata" but the test
  covers `getSize` + `browseContainer` of the galleries list only. The metadata case (`VideosByBucketNameFolderBrowser.browseMeta`, which also uses
  the cache) is not covered; add `browseMeta` of a gallery after `getSize` on the same instance and assert one query, or shorten the name.
- [VideoBrowsersTest.java, `galleriesThatDifferOnlyInTheCaseAreInAStableOrder`] The comment says "upper case first, then by id"; the data
  ("Movies"/"movies") is decided by the title step already, so the id step of `inTitleOrder` (two equal titles, which `group` makes impossible
  by the counter suffix) is never exercised. Harmless, a unit test of `inTitleOrder` with two equal titles would cover it.
- [VideoBrowser.java:108] The field `galleries` sits between two methods; the other fields of the class are at the top. Style only.
- Still open from cycle 1 and out of scope: the empty media path list with the filter on (one `?`, zero arguments) and the missing
  `verify(cursor).close()` test.

### Tests
- [x] All tests passing (reported by the orchestrator: build, 3295 tests, lint green at HEAD; Gradle not run in this review)
- [x] Coverage adequate for the change (media path filter on, null mime type/duration, unknown video, single read, case-only names are covered)

### Verdict: PASS
Zero Critical, zero Warning; the three Warnings of cycle 1 are fixed and the changes introduce no regression.

## Cycle 3 - media paths for every id - 2026-11-08
Reviewing: the last commit (999025d7), the security fix "the media paths apply to everything the server answers for an id" (decisions.md, last entry).

Read: the diff of all main and test files, `ContentBrowser`/`MediaPathFilter`/`VideoBrowser`, all music and image browsers, `YaaccContentDirectory.browse`/`findBrowserFor`, the HTTP handler (`/res`, `/album`, `/thumb`, SAF, live) and the three test classes. Gradle not run (the state at HEAD is reported green: build, 3310 tests, lint).

Selections and arguments (checked one by one):
- `ImageAllItemBrowser`, `ImageByBucketNameItemBrowser`: `_id=? and (_data like ? [or ...])`, args = id, then `%path%` for each path. Correct.
- `VideoItemBrowser`: `_id=? AND (...)`, args = id + `mediaPathArguments()`. Correct.
- `ImagesByBucketNameFolderBrowser`: `bucketSelection()`/`bucketSelectionArgs()` give `bucket_id=? and (...)`, args = bucket id (substring after the 6-character prefix, which is what the container ids `210999<bucket>` carry), then the paths. Used for getSize, getName and browseItem alike, so the count, the name and the list agree. `getSize` no longer passes the prefix with the id (the old count was wrong), `getName` reads `BUCKET_DISPLAY_NAME` (was the bucket id), null cursor handled in getSize.
- Filter off: `getMediaPathes()` is `{""}`, so one `_data like '%%'`, one argument: placeholders and arguments match in all new queries.
- `isInMediaPaths` (handler): `<idColumn>=? and (<dataColumn> like ? ...)`, id first. For `/thumb` the id is validated by `Long.parseLong` before; a spelling like `007` or `+7` compares equal to the integer id in SQLite or fails closed. For `/album` the canonical id is used. A null cursor answers false (generic icon / default image).
- Album art: the check sits after the canonical-id step and before the `SDK_INT <= P` branch, so it covers both branches. Thumbnail: before `getThumbnail`.
- Music containers: `browseMeta` answers null (NO_SUCH_OBJECT in `YaaccContentDirectory.browse`) when `getSize == 0`. With tracks in the paths the path is unchanged (the same `getSize`/`getName`/`browseItem` calls as before plus one extra count). The genre `getSize` (API >= R: `Audio.Media.GENRE_ID`, below: `Genres.Members` URI of the genre) was already filtered and is unchanged; both branches use id first, then the paths. BrowseDirectChildren of an out-of-path album/artist/genre/gallery already listed nothing (browseItem filtered) and totalMatches is now 0 for the gallery.

Other id-based endpoints: `/res/<id>` (filtered before, Files table with `_data`), `/album/<id>` and `/thumb/<id>` (now), `/saf/...` (own rules, out of scope), `/live/...` (no id), `/proxy/<key>` (random key). The legacy `/?id=` URL (built by `ImageAllItemBrowser`) has one path segment or none and is answered 403 by `handle` (`pathSegments.size() < 2`): nothing to filter there. The ContentDirectory has no Search action (only `getSearchCapabilities`). Item browsers of music (`MusicAllTitle`, `MusicAlbum`, `MusicArtist`, `MusicGenre` item browsers) were filtered already; `MusicAlbums/Artists/Genres` lists keep only entries with tracks in the paths; `ImagesAll`, `ImagesByBucketNames` lists and counts filter by `_data`.

### Critical
- none

### Warning
- none

### Suggestion
- [ImageAllItemBrowser.java:132-136, ImageByBucketNameItemBrowser.java (browseItem)] `browseItem` adds the result of `browseMeta` without a null check (`VideoItemBrowser.browseItem` has it). For an id outside the paths (as for an unknown id, as before) BrowseDirectChildren answers an empty DIDL with NumberReturned 1 / TotalMatches 1; `DIDLContent.addObject(null)` ignores the null, so nothing leaks and nothing crashes, the answer is only the same as for a missing id. Add the null check and let `getSize` return 0 when the item is missing, for a clean answer.
- [YaaccUpnpServerContentHttpHandler.java:665-672, 681-703 and the browsers] Filter on with no media path (`makeLikeClause(col, 0)` gives one `?`, zero arguments): the query throws. That was out of scope and "as before" for `/res` and the listings; it is new for `/thumb`, `/albumart` and the metadata of images and videos, which answered before. Practically unreachable (a client cannot browse to such an id when every listing fails), but an empty set should mean "nothing allowed" (`1=0`) in `MediaPathFilter.makeLikeClause` / `isInMediaPaths`; it would close the open point of cycle 1 as well.
- [MusicArtistFolderBrowser.java getName] `myId.substring(myId.indexOf(PREFIX))` is the whole id (indexOf is 0), so the lookup `Artists._ID = '910999<id>'` finds nothing and the name is empty. Existing bug, not changed here, the same kind the commit fixed for the image gallery name. `ImagesByBucketNamesFolderBrowser.getBucketNameFolderSize` counts a bucket without the media path condition (list child count vs filtered `getSize` of the gallery: equal in practice, a bucket is one directory).
- [MusicAlbumFolderBrowser/MusicArtistFolderBrowser/MusicGenreFolderBrowser/ImagesByBucketNameFolderBrowser.browseMeta] `getSize` runs twice (check, then the constructor argument), and the music `getSize` methods still call `cursor.getCount()` without a null check (before the commit the same NPE). Keep the first result in a local variable; use `cursor == null ? 0 : ...` as the gallery browser now does.
- [ImagesByBucketNameFolderBrowser.getName] `BUCKET_DISPLAY_NAME` can be null (files in the root of a volume); the title would be null. `VideoBrowser` uses `gallery_unnamed` for this; the same fallback fits here.
- [MediaPathServingTest.theCoverOfAnAlbumWithoutATrackInTheMediaPathsIsTheGenericIconAndNotCached] The mocked cursor has no `isAfterLast` stub. Without the fix (the album table is queried) the legacy loop `while (!cursor.isAfterLast())` never ends: the test would hang instead of failing. Stub `isAfterLast` to true. The handler tests run with the filter off (`getBoolean(null, true)` on the mock returns false): add one with the filter on and two paths to check the order of the LIKE arguments after the id (the browser tests do).
- [MediaPathFilterBrowsersTest.aGalleryOfImagesHasItsNameAndTheCountOfItsImagesInTheMediaPaths] The mock returns the same one-column cursor for any projection, so it does not notice a projection with the bucket id as first column again; assert the projection passed to `query` (`bucket_display_name`). The positive paths of the music containers (a MusicAlbum with tracks) are not covered by a unit test; they are unchanged apart from the count check.

### Tests
- [x] All tests passing (reported by the orchestrator: build, 3310 tests, lint green at HEAD; Gradle not run in this review)
- [x] Coverage adequate for the change: tests fail without the fix. Removing `isInMediaPaths` in `lookupThumbnail` fails `theThumbnailOfAnImageOutsideTheMediaPathsIsNotServed` (content is the bitmap, `getThumbnail` is called). Removing the media path clause from the image metadata queries fails `assertFiltered` (selection and argument order). Removing `getSize == 0` in the three music containers or the gallery fails the `...IsNoSuchObject` tests. The album art check is covered by one test on the API <= P branch (the API > P branch cannot run on the JVM; the check precedes both by reading). `anImageOutsideTheMediaPathsHasNoMetadata` alone would pass without the fix (empty cursor); the positive tests next to it carry the filter assertion.

### Verdict: PASS
Zero Critical, zero Warning; all selections have the id first and the media path arguments after it, filter off (`%%`) keeps working, the positive paths are unchanged, and the id-based endpoints that answer media data are all filtered now. The Suggestions do not block.

## Cycle 4 - SAF trees and LIKE escaping - 2026-11-08
Reviewing: HEAD (4515d92d: W3 of security cycle 2, escaping of the media paths) and HEAD~1 (70acb03e: child count of an image gallery, 404 page). Gradle not run (state at HEAD reported green: build, 3330 tests, lint).

Checked and in order:
- Stored selected strings: not plain tree uris. `YaaccUpnpServerControlActivity` (466-476) stores `DocumentFile.fromTreeUri(...).getUri()`, i.e. `content://auth/tree/<id>/document/<id>` (the granted folder is the document whose id equals the tree id); `TreeViewHolder` stores the `documentUri` of any row, so a sub folder of a granted tree is stored as `tree/<tree id>/document/<sub folder id>`. `TREE_BASED_URI` accepts both shapes (optional `/document/<id>`), so the real format matches. Short ids only come from `SafFolderBrowser` (`getOrCreateShortId`) and the persisted cache keys (`saf_cache_<document uri>`, same shape); `getUriForShortId` has two callers only (`allowedUri`, `lookupSafContent`). Share/picked files do not use the SAF short ids or the `/saf/` route (grep of ShareIntentHandler, ShareRegistry, QueueShareBinder: no use), so nothing legitimate is refused. `browseContainer` already skipped uris without `/tree/`.
- Matching: authority case-insensitive, tree id compared decoded and for equality (`primary:Music2` is not `primary:Music`, `%3A` = `:`), anchored regex, null / garbage / non tree uri -> false. `decode` bounds are right. Both HTTP and all four browse paths (meta, size, container, item) go through the check; the old FIXME block is gone.
- LIKE: `like ? escape '\'` is valid SQLite; every user of `makeLikeClause`/`getMediaPathesForLikeClause` (all browsers, `/res`, `isInMediaPaths`, genre members) uses the pair, no own LIKE exists, arguments are only used with this clause. Filter off: `%%` still matches everything. A path that already holds a backslash is escaped (`\\`) and matched literally (before: literal too, SQLite has no default escape). Case-insensitivity is unchanged. The strict grammar of the media provider (Android 11+) is stated allowed (ESCAPE is a SQLite keyword, the literal `'\'` has no backslash escaping in the tokenizer); I could not fetch the AOSP tokenizer from here and the owner could not run a device: see Suggestion.
- Tests: `SafSelectedTreesTest` and `SafServingTest` fail without the check (the deselected id would be queried / the `DocumentFile` opened; the `never()` verifications). `MediaPathFilterTest` has an escape-aware matcher; `aPathWithAnUnderscoreDoesNotMatchADirectoryThatDiffersAtThatPlace`, the `%` and backslash tests fail without `escapeLike`, the clause test fails without `escape`.

### Critical
- none

### Warning
- [SafDocumentUris.java:80-90 (isInSelectedTrees), used by SafFolderBrowser.java:278 and YaaccUpnpServerContentHttpHandler.java:775] The check compares authority and tree id only, never the document. The selected set holds document uris of sub folders (TreeViewHolder checkbox), so with only the sub folder `primary:DCIM/Camera` selected the whole granted tree `primary:DCIM` counts as selected: `browseMeta` of the sub folder returns the parent id (`parentIdOf`, the short id of `primary:DCIM`), and that id, like every sibling id still in the cache, is described, listed and served. The same for a user who selected `A` and `B` below a granted root and deselects `B`: the root is not selected, `A` keeps the tree "selected", the short ids of `B` still answer, which is exactly the W3 scenario. Not a regression (before: no check at all, and nobody who selects the granted root or the whole tree is affected), but the guarantee in CHANGELOG.md ("when you deselect a folder ... no longer described, listed or sent, also not to a client that still knows their ids"), docs/index.md (431-433), the decision log and the Javadoc ("A document is only ... in a selected SAF tree") holds for whole trees only. Fix: also require, for path like ids, that the document id equals a selected document id or starts with it plus `/` (and fall back to the tree level for opaque ids, or accept `findPath` from the provider), or state the tree granularity in the CHANGELOG/docs/decisions and say that a sub folder selection serves its granted tree. Either way add a test with a selected sub folder document uri.

### Suggestion
- [SafDocumentUrisTest.java (isInSelectedTrees tests), SafSelectedTreesTest.java:62, SafServingTest.java:62] All tests select a plain tree uri (`.../tree/primary%3AMusic`), a shape the app never stores; the stored form is `.../tree/<id>/document/<id>` (granted root) or `.../document/<sub folder>`. The regex handles them, but nothing pins it: add a test with a selected `tree/x/document/x` and one with a sub folder document uri (also for the Warning above).
- [MediaPathFilter.java:25, 29] `escape '\'` in the strict-grammar selection of the media provider is unverified on a device. If the tokenizer refused it, every media query would throw `IllegalArgumentException` (all browsers, `/res`). The risk is low, but a smoke test on Android 11+ (browse Music with the filter on) before the release is worth the minute; the decision log already says it was not run.
- [YaaccUpnpServerContentHttpHandler.java:~680-684] `NOT_FOUND_PAGE` was inserted between the Javadoc of `lookupThumbnail` (with `@param idStr`, `@return`) and the method: that Javadoc now sits on the constant, the constant has two Javadocs and the method none. Move the constant above the Javadoc.
- [SafFolderBrowser.java:278-285] `allowedUri` logs a warning per refused call; a client that probes ids can fill the log (the file logger is on a phone). Debug level for the refusal would do.
- [decisions.md / CHANGELOG.md] Open from earlier cycles and unchanged: filter on with no media path (one `?`, no argument) still fails the query; the switches "serve images / videos / music" only hide the root folder. The decision log lists both; fine as "not done".
- HEAD~1: `getBucketNameFolderSize` now uses the same condition as the gallery list (id first, then the paths), null cursor handled; the test checks selection and arguments. The 404 page and `nosniff` are right (`nosniff` on the content path too); the test only checks the constant, not that the handler uses it.

### Tests
- [x] All tests passing (reported by the orchestrator: build, 3330 tests, lint green at HEAD; Gradle not run in this review)
- [ ] Coverage adequate for the change: the whole-tree cases and the LIKE escaping are covered and fail without the fix; the stored selection format and the sub folder selection are not covered (Warning and first Suggestion).

### Verdict: FAIL
One Warning (the check is per granted tree, not per selected folder; docs and changelog claim more). Zero Critical. The LIKE escaping and the 404/gallery-count changes are correct.

## Cycle 5 - SAF per selected folder - 2026-11-08
Reviewing: HEAD (7fb35166: the SAF selection is checked per selected folder). Gradle not run (state at HEAD reported green: build, 3339 tests, lint).

Checked and in order:
- `SafDocumentUris.isInSelectedTrees(uri, selected, ChildCheck)`: authority (case-insensitive) and decoded tree id must match before anything else; then, per selected folder: whole tree (`folderId == treeId`, also a plain `tree/<id>` uri), the folder itself, `documentId.startsWith(folderId + "/")` (so `Camera2` is not in `Camera`), else the provider. Null / garbage / non tree uris, null collection and null entries give false; a null `check` is skipped; the lambda of `SafSelection` catches every `Exception` and answers false (fails closed). The short ids come from the cache, not from the client, so a `..` in an id is not reachable. The provider is only asked when the id test failed, so path like ids (external storage) cost no IPC for the allowed case.
- `SafSelection.contains`: `DocumentsContract.isChildDocument(ContentResolver, parent, child)` is called with the selected uri as parent and the document as child (order right, the interface parameters are named parent/child); available since API 21 (minSdk 27); `FileNotFoundException` (checked) / `SecurityException` / anything else are caught. The provider enforces the tree permission on the parent uri, which is a tree based uri of a granted tree. The semantic is "descendant", which is what is needed. It uses `MediaPathFilter.getSelectedSafPathes(context)`, the same source as `ContentBrowser.getSelectedSafPathes()`.
- `SafFolderBrowser.browseMeta`: the top of a selection (`isSelectedFolder`: same authority, tree id and document id as a selected entry, a plain tree uri included) gets the SAF folder as parent. For a tree root that is what `parentIdOf` returned already (`isTreeRoot`), so nothing changes there; for a folder below a granted tree it replaces the id of the unselected folder around it. Everything below a top gets `parentIdOf` as before, and the parent of a direct child is the selected folder itself (path like ids: `id.substring(0, lastIndexOf('/'))`; opaque ids: `findPath`), so no code path produces the id of an unselected parent: the only caller of `parentIdOf` is `browseMeta`, and a listing (`SafDirectoryLister.list`, `childFolders`, `buildItems`) holds the children only, with the listed folder as parent id. Even if a client got the id of the unselected parent, `allowedUri` refuses it (the parent is neither a selected folder nor below one). A selected file (checkbox on a file) is its own "selected folder" and its parent is the SAF folder, as in `browseItem(SAF_FOLDER)`.
- `allowedUri` is used by `browseMeta`, `getSize`, `browseContainer`, `browseItem`; `lookupSafContent` has the same check before opening the file. No other caller of `getUriForShortId`. The refusal in `allowedUri` and `lookupSafContent` logs at debug level.
- `NOT_FOUND_PAGE` now sits above the Javadoc of `lookupThumbnail`, which is on its method again.
- Tests: `SafDocumentUrisTest` uses the stored forms (`tree/x/document/x`, `tree/x/document/x%2Fsub`, plain tree uri) and covers parent / sibling / same-prefix sibling / tree root with only a sub folder selected, A and B with B deselected, several folders, provider check (asked with the selected uri as parent, not asked for whole tree and path like child, false without or with a refusing check), non tree inputs, `isSelectedFolder`. `SafSelectedTreesTest` (selection = whole Music tree + `DCIM/Camera`): a sibling in the DCIM tree is `NO_SUCH_OBJECT` with no query, a document below `Camera` is looked up. `SafServingTest`: a file below `Camera` is served, a `Screenshots` file of the same tree is not. Reducing `isInSelectedTrees` to the tree level (return true when the tree id matches) fails `theRestOfTheGrantedTreeIsNotSelectedWhenOnlyAFolderBelowItIs`, `aDeselectedFolderOfATreeThatStaysSelectedIsNotSelected`, `aSiblingOfASelectedFolderIsNoSuchObjectThoughTheTreeIsGranted` and `aFileBelowASelectedFolderIsServedAndASiblingFolderOfTheSameTreeIsNot`. In the two mock based tests the `SafSelection` lambda reaches `DocumentsContract.isChildDocument` for the sibling; on the JVM the Android stub answers false or throws, and both end in false, so the tests are valid but do not exercise the provider branch.
- Statements: CHANGELOG.md:42 and docs/index.md:431-433 ("a folder you deselect ... no longer listed or sent, also not to a client that remembers its ids") match the code now; decisions.md last entry matches (per selected folder, provider for non path ids, top of a selection has the SAF folder as parent, debug log).

### Critical
- none

### Warning
- none

### Suggestion
- [SafFolderBrowser.java:256-257] The new parent for the top of a selection is not pinned by a test: `SafSelectedTreesTest.aDocumentBelowASelectedFolderIsLookedUp` stops at `queryDocument` (mock returns null), so reverting the ternary to `parentIdOf(...)` keeps everything green. Only `isSelectedFolder` is unit tested. A browseMeta test with `queryDocument` returning a folder entry for `DCIM/Camera` and an assert on `getParentID()` equal to `ContentDirectoryIDs.SAF_FOLDER.getId()` would close it. Not a leak either way (the unselected parent is refused by `allowedUri`), only a client that navigates up gets "no such object".
- [SafSelection.java:33-43] The adapter to the provider has no test (argument order, `false` on exception). A small seam (inject the `ChildCheck`/the `isChildDocument` call) would allow a test that a throwing provider gives false. It is right by reading.
- [SafFolderBrowser.java:245, 335, 375, 502] The refusal is logged at debug in `allowedUri`, but every caller then logs again at warn / error ("Meta: short ID not found", "Short ID not found", `e(...)` in `browseContainer` and `browseItem`), so a client that probes ids still writes one W/E line per probe, which was the point of the debug level. Lower these to debug as well (the same lines serve a really unknown id, which is the same client behaviour).
- [SafFolderBrowser.browseMeta / browseContainer(SAF_FOLDER)] Nested selections (A and A/B both ticked): B is listed below A (parent A) and at the top of the SAF folder, but `browseMeta(B)` answers the SAF folder as parent. Harmless, only the navigation up from B differs from the listing.
- [docs/tech.md:554-556] The description of the parent in `browseMeta` still says "the SAF folder for a granted tree (document id = tree id)"; add "and for the top of a selection". [SafDocumentUris.java:87] `isInSelectedTrees` keeps the name "trees" though it checks folders (renaming is churn, a Javadoc line is enough, it exists).
- Open from earlier cycles and unchanged: `escape '\'` on a device (Android 11+ strict grammar), filter on with no media path.

### Tests
- [x] All tests passing (reported by the orchestrator: build, 3339 tests, lint green at HEAD; Gradle not run in this review)
- [x] Coverage adequate for the change: the folder-level check is covered with the stored forms and fails when reduced to the tree level (four tests, see above); the parent of the top of a selection and the provider adapter are covered by reading only (Suggestions).

### Verdict: PASS
Zero Critical, zero Warning. The cycle 4 Warning is closed: the selection is checked per selected folder in the metadata, size, both listings and the `/saf/` content, the provider decides for ids that are no paths and any error fails closed, and docs, changelog and decision log match the code.

## Cycle 6 - Image galleries list (owner's report: app 1 gallery, Home Assistant 9) - 2026-11-08
Reviewing: Group 4 (uncommitted: `git diff HEAD` and the new `ImageGalleryListTest`). Gradle not run (reported by the orchestrator: assembleDebug, 3346 tests, 0 failures, lint 0 issues).

Checked and in order:
- Cause and fix: the old list cut the page by rows of images (the app's first 50 rows were all "Camera"), sorted in SQL case-sensitively and issued one count query per gallery. `ImagesByBucketNamesFolderBrowser` now reads the rows once per browser (cached in `galleries`), groups with `MediaGalleries.group`, sorts, then pages galleries with `MediaGalleries.page`; `getSize` is `galleries().size()`, so the total and the pages come from the same list. Rows with a null bucket id are skipped (they could never match `bucket_id=?` in the gallery anyway).
- Paging: `page(all, first, max)` clamps `first` into `[0, size]`, `end = max >= size - start ? size : start + max` (no `start + max` overflow for `Long.MAX_VALUE`, `max` < `size - start` fits an int), a first index beyond the end and `max == 0` give an empty list, a negative `max` gives empty (`Math.max(0, max)`), a negative `first` starts at 0. `MediaGalleriesTest.aPageIsCutFromTheList` covers these (including 99, 5, `Long.MAX_VALUE`); `ImageGalleryListTest` covers it through the browser (`list(7, 3)` is empty, pages 0/3/6 add up to the full list).
- Sort: ascending default is `inTitleOrder()` (lower-case key, then the title with upper case first, then the id); with a client sort `SortSupport.toComparator(...)` on `dc:title` uses the lower-case key, `.thenComparing(tieBreaker)` keeps the order deterministic for equal keys (the tie-breaker is ascending also for a descending sort, so a page boundary can never repeat or skip a gallery). An unknown sort property falls back to the default. Descending is tested (`Slack, musiccastImages, DCIM, Camera`).
- Media path filter: the single query of `readGalleries` carries `makeLikeClause`/`getMediaPathesForLikeClause` (escaped LIKE), so list, total and counts only see images in the media paths. `ImagesByBucketNameFolderBrowser.browseMeta` takes name and count from the same list and answers null when the gallery is not in it (outside the media paths, unknown id); `getSize` and `browseItem` keep `bucketSelection()` (id first, then the paths). Nothing reads the media store without the filter. `ImagesFolderBrowser` calls `browseMeta` of the galleries folder, which now goes through `getSize` -> the list.
- Naming consistency: list and meta use the same `group()` (same " (2)" counter in the order of the bucket ids, same "(root folder)" text), so the name in the gallery equals the name in the list (`theNameInTheGalleryIsTheNameInTheList`); the bucket prefix `210999` is cut the same way in `browseMeta` and `bucketSelectionArgs`.
- Dead code / imports: `getName` and `getBucketNameFolderSize` are gone; `ImagesByBucketNamesFolderBrowser` imports are all used (`SuppressLint` on `readGalleries`, `Cursor`, `MediaStore`, `Comparator`, `HashMap`, `Map`, `Function`, `R`; `YaaccLogger`, `HashSet`, `LinkedHashMap`, `Set` were removed); `ImagesByBucketNameFolderBrowser` imports all still used (`Cursor`, `MediaStore`, `YaaccLogger`, `Photo`, `MimeType` by `browseItem`/`getSize`). The unused `@SuppressLint("Range")` on `getSize` was removed with the cursor code.
- Rename: no `VideoGalleries` left in `yaacc/src` (main, test), `docs/`, `README`, `CHANGELOG.md`. Remaining hits are in the spec folder only: `review.md`, `security-review.md` (historic, allowed), `design.md:18,21`, `tasks.md:9,12` (the historic Group 1/2 entries), and `decisions.md:47`/`tasks.md:22` (which name the rename on purpose). `VideoRoutingTest` still routes `200999` / `2109991` to the image browsers.
- Docs: `docs/index.md` (same-name counter and "(root folder)" now for both kinds, media paths for the images in a gallery, sorting without regard to the case, paged list equals the full list), `CHANGELOG.md` (cause, fix, what changes for images, one query) and `decisions.md` (new entry, and the earlier "problem found" bullet marked fixed) match the code. The second bullet of that earlier entry (`getName` returned the bucket id, wrong count) was fixed in cycle 3/4 and the line is unchanged; not wrong, only older text.
- Tests: `ImageGalleryListTest` (7 tests) uses a fake cursor that ignores the selection and the paging arguments, so a page cut in SQL or by rows cannot pass: `aPageHoldsGalleriesNotRowsOfImages` (120 rows of "Camera" first, page of 50), pages-add-up, total, case-insensitive sort asc/desc, counts, same names, name in gallery equals name in list. That 6 of 7 fail against the old code is plausible by reading (the old code paged rows, sorted in SQL, counted per query, and had no " (2)"). `MediaPathFilterBrowsersTest` was adapted correctly: one query, no count query per gallery (`assertEquals(1, selections.size())`), the escaped LIKE clause and the media paths as arguments are asserted for both the list and the meta; the outside-the-paths test still pins null / 0 / empty.

### Critical
- none

### Warning
- none

### Suggestion
- [MediaGalleries.java:32, 38, 61, 63] The class Javadoc was widened to videos and images, but the field/param docs still say "the number of videos", "one per video", "per video". Say "media" (or "files") so the shared class reads right.
- [design.md:18, 21] The design still describes `VideoGalleries` only (Group 2 history). One line "renamed to `MediaGalleries` and shared with the image galleries (Group 4)" would keep the spec and the code in step; not needed for the code.
- [ImagesByBucketNamesFolderBrowser.readGalleries] Every page request reads one row per image (a library of tens of thousands of photos is an array per row, per request, and the app asks for 50 galleries at a time). Same approach as the video galleries and cheaper than before (no query per gallery); a `GROUP BY` through the query bundle (API 26+) would avoid the rows, but is not worth the churn now.
- [ImagesByBucketNameFolderBrowser.browseMeta] Constructs a new `ImagesByBucketNamesFolderBrowser` to reuse `galleries()`; fine and package private, but `VideoBrowser` shares this through inheritance. If a third kind appears, move the reading into `MediaGalleries` or a common base.
- [ImageGalleryListTest] No test for a null bucket id row, for equal lower-case names that differ in case ("camera" / "Camera") in a page boundary, or for the filter on in the list test (covered in `MediaPathFilterBrowsersTest`). Optional.
- Open from earlier cycles and unchanged: `escape '\'` on a device (Android 11+ strict grammar), filter on with no media path.

### Tests
- [x] All tests passing (reported by the orchestrator: build, 3346 tests, 0 failures, lint 0 issues; Gradle not run in this review)
- [x] Coverage adequate for the change: paging (first beyond the end, `Long.MAX_VALUE`, pages add up), total, sort asc/desc, counts, same names, name/count from the same list and the media path filter on list and meta are covered and fail against the row-paged implementation.

### Verdict: PASS
Zero Critical, zero Warning. The list pages galleries, not rows; total, pages, counts and the metadata of one gallery come from the same grouped list under the same media path filter; no dead code or stale references in source, tests or user docs.

## Cycle 7 - Images folder paging (Group 5) - 2026-11-08
Reviewing: Group 5 (uncommitted: `git diff HEAD` and the new `ImagesFolderBrowserTest`). Gradle run for the three affected classes only: `ImagesFolderBrowserTest` 5/5, `VideoBrowsersTest` 24/24, `ImageGalleryListTest` 7/7, 0 failures.

Checked and in order:
- Paging: `ImagesFolderBrowser.browseContainer` ends in `MediaGalleries.page(result, firstResult, maxResults)`, same call as `VideosFolderBrowser`. Sort happens before paging. By reading `page`: (0, MAX) and (1, MAX) give everything / the second; (2, n), (2, MAX), (MAX, 1) clamp `start` to 2 and give an empty list; max 0 gives an empty list (also at start 2, where `0 >= 0` takes `end = size` = `start`); no `start + max` overflow; no `(int)` of a client number except after `max < size - start`.
- Sort + page: descending `dc:title` gives ["Galleries", "All images"], page (1, 1) of it gives "All images"; no sort keeps the fixed order (the no-op comparator relies on the stable sort).
- Children ignore the page arguments: `ImagesAllFolderBrowser.browseMeta` and `ImagesByBucketNamesFolderBrowser.browseMeta` only call `getSize(contentDirectory, myId)` (a count query / the grouped list); `firstResult`, `maxResults` and `orderby` are not read. `(0, 1)` is therefore the same as before, and equal to the Videos folder.
- Tests: with the old arithmetic `aPagePastTheEndIsEmptyNotTheLastFolderAgain` fails (first 2 gave `start = 1`, "Galleries" again) and the other four pass (order, `(0,1)`, `(1,1)`, `(1,MAX)`, `(0,0)`, descending sort and `(1,1)` of it all agreed with the old code), which matches the task note. The tests use the real browser classes with a mocked resolver, so they also pin that the metadata of the two children builds with an empty media store. They are useful as a pin for the shared `page` through this browser.
- CHANGELOG line: "a client that asked for entries after the two folders got the last folder a second time. It uses the same paging as *Videos* now" is true.

### Critical
- none

### Warning
- [decisions.md, last entry] "`(int) maxResults` could wrap for counts above 2^31" is not true of the old code: it reached `start + (int) maxResults` only when `maxResults <= size - 1` (= 1), a larger value took `end = result.size()` first. Nothing wrapped, and `aHugeCountIsNoSmallNumber` passes on the old code (the task says four of five did, consistently). Remove the cast sentence from the decision (the only defect was the page behind the end); the test may stay as a guard but its comment "(int) of it is 1" describes a bug that did not exist. The log is there so nobody re-litigates a decision on a false reason.

### Suggestion
- [ImagesFolderBrowser.java:70] The accessor is `DIDLObject::getTitle`, `VideosFolderBrowser` uses `MediaGalleries::titleKey` (without regard to the case, as the galleries list and docs/index.md say). With the two English titles it makes no difference; a translation that starts with a lower case letter would sort after upper case ones. Use `titleKey` for both kinds to keep them one behaviour.
- [ImagesFolderBrowser.java:67-73] The comment above the no-op comparator names "By bucket name"; the folder is called "Galleries". Cosmetic.

### Tests
- [x] All tests passing (the three affected classes run here: 5 + 24 + 7, 0 failures; the whole suite not run, as agreed)
- [x] Coverage adequate for the change: past the end, `Long.MAX_VALUE` first and max, max 0, sort ascending/descending and paging after the sort are covered; the past-the-end test fails on the old code.

### Verdict: FAIL
Code and tests are correct (zero Critical), but one Warning: the decision log gives a defect (int cast wrap) that the old code did not have. Delete that sentence and the verdict is PASS; no code change needed.

## Cycle 8 - Images folder paging, fixes of cycle 7 - 2026-11-08
Reviewing: the Warning and the Suggestions of cycle 7. The three affected classes run again: `ImagesFolderBrowserTest` 5/5, `VideoBrowsersTest` 24/24, `ImageGalleryListTest` 7/7, 0 failures (whole suite not run).

- Warning closed: the decisions.md entry now names the page behind the end as the only defect ("the count was handled right"); the cast sentence is gone. The test is `aHugeCountGivesBothFolders`, without the comment about the cast.
- Suggestions taken: the sort accessor is `MediaGalleries::titleKey` (as `VideosFolderBrowser`; a line in decisions.md says so), the comment says "Galleries".
- No new finding: the diff of `ImagesFolderBrowser` is the two `(0, 1)` calls, the accessor, the comment and `MediaGalleries.page`; `DIDLObject` is still used by the map/comparator types, so the imports stand.

### Critical
- none

### Warning
- none

### Suggestion
- none

### Tests
- [x] All tests passing (the three affected classes)
- [x] Coverage adequate for the change

### Verdict: PASS
