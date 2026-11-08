# Decisions: video galleries (#318)

## 2026-11-08 — Layout as the images, one id per video
The folder "Videos" holds "All videos" and "Galleries" (as "Images"), the issue asks for exactly that. The items keep their id; a bookmark
of a video item still resolves, a bookmark of the old "Videos" listing shows the two containers now.

## 2026-11-08 — Same-name galleries get a counter, no path
Two buckets with the same name (internal storage and an SD card) are told apart by " (2)". A path would show the storage layout of the
phone to every host of the network; images show both under the same name.

## 2026-11-08 — Problems found in the image browsers, not part of this change
- `ImagesByBucketNamesFolderBrowser.browseContainer` cuts the page after the rows (one per image), then removes duplicates: paging through galleries with many images skips or repeats galleries.
- `ImagesByBucketNameFolderBrowser.getName` returns the bucket id (first column of the projection) instead of the bucket name; `getSize` passes the prefix with the id, so the count of a gallery is wrong; the items of a gallery ignore the media paths.
- `ImagesFolderBrowser.browseContainer` computes its page with an `end` that ignores `start`.
