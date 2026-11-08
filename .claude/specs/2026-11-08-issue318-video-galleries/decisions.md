# Decisions: video galleries (#318)

## 2026-11-08 — Layout as the images, one id per video
The folder "Videos" holds "All videos" and "Galleries" (as "Images"), the issue asks for exactly that. The items keep their id; a bookmark
of a video item still resolves, a bookmark of the old "Videos" listing shows the two containers now.

## 2026-11-08 — Same-name galleries get a counter, no path
Two buckets with the same name (internal storage and an SD card) are told apart by " (2)". A path would show the storage layout of the
phone to every host of the network; images show both under the same name.

## 2026-11-08 — Problems found in the image browsers, not part of this change
- (fixed later in this branch, see the last entry) `ImagesByBucketNamesFolderBrowser.browseContainer` cuts the page after the rows (one per image), then removes duplicates: paging through galleries with many images skips or repeats galleries.
- `ImagesByBucketNameFolderBrowser.getName` returns the bucket id (first column of the projection) instead of the bucket name; `getSize` passes the prefix with the id, so the count of a gallery is wrong; the items of a gallery ignore the media paths.
- `ImagesFolderBrowser.browseContainer` computes its page with an `end` that ignores `start`.

## 2026-11-08 — The media paths apply to every answer of the server (owner's decision after the security review)
The security review of the galleries found that the metadata of a video item (and, by the same pattern, of image items) was answered for ids
outside the media paths; a look at the other browsers and the content handler found more: the content (list, count, name) of an image gallery,
the name of an album, artist or genre, the thumbnail `/thumb/<id>` and the album cover `/albumart/<id>`. All of them filter now:
- items (video, image, image of a gallery): the media path condition in the selection of the metadata query;
- image gallery: the same for count, name and items (the name is the directory name, the count is right now: both were wrong);
- album, artist, genre: `browseMeta` answers "no such object" when no track of it is in the media paths;
- `/thumb/<id>` and `/albumart/<id>`: served only when the image, or a track of the album, is in the media paths (else the default / generic icon).
The files (`/res/<id>`) were filtered before. Not changed: the SAF folders (own rules), the filter on and no media path configured (the query fails, as before).

## 2026-11-08 — Security review cycle 2 answered
- W1 (child count of an image gallery in the list ignored the media paths): fixed, same selection as the gallery itself.
- W2 (the 404 page of the content server repeated a decoded path segment as HTML, no `nosniff`): fixed, the page is a fixed text and the server sends `X-Content-Type-Options: nosniff` (old problem, found in the sweep).
- W3 (SAF ids of a deselected tree are still described and served; the short ids are not checked against the selected trees): not part of this change, for the owner to decide.

## 2026-11-08 — W3 (SAF) and the escaping of the media paths done (owner's decisions)
- A document is only described, listed, counted or served when its uri is in a selected SAF tree (`SafDocumentUris.isInSelectedTrees`): `SafFolderBrowser.allowedUri` for metadata, size and both listings, the check in `lookupSafContent` for `/saf/<key>/<name>`. The old FIXME there is gone. A deselected tree keeps its short ids, they answer "no such object" now.
- The media path LIKE conditions use `escape '\'` and the patterns come from `MediaPathFilter.escapeLike` (`%`, `_` and `\` in a path are literal). The strict SQL grammar of the media provider (Android 11 and later) allows the `ESCAPE` keyword (every SQLite keyword except the ones that start another clause); this could not be run on a device here.
- Not done: the empty media path list with the filter on (the query has one `?` and no argument, so it fails), the switches "serve images / videos / music" that only hide the root folder.

## 2026-11-08 — The SAF selection is checked per selected folder, not per granted tree (review cycle 4)
The settings store the document uri of every ticked folder (a whole tree, or a folder below it). Matching the tree id alone would have kept the whole
granted tree visible once one folder of it is selected. A document is in the selection now when it is a selected folder, below one (its id continues
the folder's id with `/`, as the ids of the external storage are paths), or when the whole tree is selected; for providers whose ids are no paths
`DocumentsContract.isChildDocument` decides (`SafSelection`, fails closed on an error). The top of a selection has the SAF folder as parent in
the metadata, so a client does not navigate up into a folder that is not shown. Refusals are logged at debug level (a probing client must not fill the log).

## 2026-11-08 — The list of image galleries is grouped, sorted and paged like the video galleries (owner's report)
The owner saw the same server twice: the YAACC app listed one image gallery ("Camera"), Home Assistant nine. Cause: the list cut its page
after the rows of the images (the app asks for 50 at a time, the first 50 images were all of "Camera"; Home Assistant asks for everything), and
sorted case-sensitively in SQL. `ImagesByBucketNamesFolderBrowser` now reads the rows once, groups them with `MediaGalleries` (renamed from
`VideoGalleries`; same names, counts and " (2)" counter as the videos), sorts by title without regard to the case and pages the galleries;
the total is the number of galleries, the child count comes from the grouping (no query per gallery). The metadata of one gallery takes its name and
count from the same list, so name and count agree with the list. Visible change for images: same-named directories get " (2)", files in the
root of a volume are in "(root folder)", the order ignores the case. `ImagesFolderBrowser` (fixed two containers) is not touched.

## 2026-11-08 — `ImagesFolderBrowser` pages with `MediaGalleries.page` (owner asked to fix it too)
Its own arithmetic clamped `start` to the last entry, so a page behind the end (`first >= 2`) returned "Galleries" again instead of nothing (the only defect; the count was
handled right). It uses the paging of the Videos folder now. The two folders were also built with the page arguments of the caller
for their metadata; they get `0, 1`, as in the Videos folder (the arguments are not used by them).
The sort by title ignores the case now, as in the Videos folder (`MediaGalleries::titleKey`).
