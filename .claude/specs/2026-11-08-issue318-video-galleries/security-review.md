# Security Review: video galleries (#318), summary

Condensed: the verdict of every cycle, the blocking findings (Critical and Warning) in one line each, and the suggestions that are still
open; the long texts and the notes of what was checked are in the git history of this file (the commits before the cleanup).
Attack surface: the ContentDirectory (SOAP) and the content HTTP server answer every host of the LAN without authentication; object ids
(consecutive numbers), sort criteria, StartingIndex and RequestedCount come from the network. All cycles read the code, none ran Gradle or a device.

## Cycle 1 — 2026-11-08 — PASS
No Critical, no Warning. The new browsers bind every id as an argument, sort orders are the whitelist of `SortSupport`, names are escaped by the DIDL writers,
paging cannot overflow.

## Cycle 2 — 2026-11-08 — FAIL
No Critical. Warnings (an independent sweep of every code path that answers for a MediaStore id):
- The child count of an image gallery in the list ignored the media paths: same selection as the gallery itself.
- The 404 page of the content server repeated a decoded path segment as HTML and had no `nosniff` (old problem): fixed text, `X-Content-Type-Options: nosniff`.
- SAF ids of a deselected tree were still described and served (the FIXME in `lookupSafContent`): a document is only answered when it is in a selected folder.

## Cycle 3 — 2026-11-08 — PASS
No Critical, no Warning. The three Warnings are closed (child count, 404 page, SAF selection per selected folder), the LIKE escaping covers all 31 call sites (a `%`, `_` or `\` in a path is literal and only an argument, never SQL text).

## Cycle 4 — image gallery list — 2026-11-08 — PASS
No Critical, no Warning. One query per Browse (the old code ran one per gallery), the filter is on the single query, the bucket id is only compared, never put into SQL.

## Cycle 5 — Images folder paging — 2026-11-08 — PASS
No Critical, no Warning. `MediaGalleries.page` clamps in `long` before the cast: `Long.MIN_VALUE`, `Long.MAX_VALUE`, negative and 0 cannot throw or leak.

## Suggestions still open (not blocking, none new in this branch's behaviour)
- The media path filter is a substring match: a selected `/storage/emulated/0/Pictures` also matches `.../Pictures2/...`. Anchor it (`DATA = ?` or `DATA like '<path>/%'`); owner decision.
- The filter on with no media path configured: `makeLikeClause(col, 0)` is `col like ?` with no argument (no row, fails closed); `1=0` would be cleaner.
- A failed query answers ACTION_FAILED with `ex.toString()` of the provider in the SOAP fault; a fixed text would keep it from the LAN.
- Served files have no CSP `sandbox` (a hostile mime type).
- A SAF selection is inclusive (unticking a folder below a ticked one does not hide it): the settings text could say so.
- The duration preload walks every granted SAF tree, not only the selected ones (nothing reaches the network, but the short id gaps count the files of deselected folders).
- The authority of a SAF uri is compared with `equalsIgnoreCase` (`equals` is tighter); strings of the client reach the log at warn level for unknown short ids.
- The cover of an album with tracks inside and outside the media paths may come from a track outside (theoretical).
- `readGalleries` keeps one small array per image until the Browse ends (about 15-20 MB for 100,000 photos); folding the rows while reading would make it constant. `BrowseMetadata` of one gallery scans all image rows of the media paths.
- Tests for `..` and prefix-like tree ids in `SafDocumentUris`, and for the metadata of a gallery outside the media paths with the filter on.
- The switches "serve images / videos / music" only hide the root folder.
- The `ESCAPE` clause on Android 11+ was the one point that could not be checked here; the owner checked it on a device (folders with `_` in the name are listed correctly).
