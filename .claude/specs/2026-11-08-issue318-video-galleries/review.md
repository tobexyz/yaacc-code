# Review: video galleries (#318), summary

Condensed: the verdict of every cycle and the blocking findings (Critical and Warning) in one line each; the long texts, the
suggestions and the notes of what was checked are in the git history of this file (the commits before the cleanup).
Cycles 1 and 2 reviewed the video galleries, cycle 3 the media paths for every id, cycles 4 and 5 the SAF selection and the LIKE
escaping, cycles 6 to 8 the image gallery list and the Images folder. Gate at the end: `assembleDebug testDebugUnitTest lintDebug`
green, 3352 tests, 0 failures, lint 0 issues.

## Cycle 1 — 2026-11-08 — FAIL
No Critical. Warnings:
- The spec named a support class `VideoRows` that does not exist (the code has `VideoBrowser`): names in `design.md` / `tasks.md` corrected.
- `docs/index.md` claimed for the Images what only the Videos did (same-name counter, root folder name, media paths in the galleries): the paragraph says what each does.
- `VideoBrowsersTest` had no test with the media path filter on, so the requirement "media paths filter the galleries" was untested: tests with the filter on added (list, count, gallery, item).

## Cycle 2 — 2026-11-08 — PASS
No Critical, no Warning (stable order of equal titles, one read of the galleries per Browse and odd rows were taken from the suggestions).

## Cycle 3 — media paths for every id — 2026-11-08 — PASS
No Critical, no Warning. Reviewed the metadata of images and videos, the image galleries, album / artist / genre and the thumbnail and cover routes
answering only for ids in the media paths.

## Cycle 4 — SAF trees and LIKE escaping — 2026-11-08 — FAIL
No Critical. Warning:
- `SafDocumentUris.isInSelectedTrees` compared authority and tree id only, so one ticked folder kept the whole granted tree visible: the check is per selected folder now (the folder itself, below it, or the whole tree selected; `DocumentsContract.isChildDocument` for providers whose ids are no paths; fails closed).

## Cycle 5 — SAF per selected folder — 2026-11-08 — PASS
No Critical, no Warning.

## Cycle 6 — image galleries list (owner's report: the app shows 1 gallery, Home Assistant 9) — 2026-11-08 — PASS
No Critical, no Warning. The list is grouped, sorted without regard to the case and paged by galleries; counts and the name of one gallery come from the same list; the rename `VideoGalleries` to `MediaGalleries` left no stale reference.

## Cycle 7 — Images folder paging — 2026-11-08 — FAIL
No Critical. Warning:
- `decisions.md` claimed that `(int) maxResults` could wrap in the old paging of `ImagesFolderBrowser`; it could not (the only defect was a page behind the end returning the last folder again): the sentence is removed.

## Cycle 8 — Images folder paging, fixes of cycle 7 — 2026-11-08 — PASS
No Critical, no Warning, no Suggestion.
