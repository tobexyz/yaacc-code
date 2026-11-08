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
