# Design: video galleries (#318)

## Layout (mirrors the images)
```
Videos (400999)                         2 containers
  All videos (430999)                   items: every video of the media paths (what "Videos" held before)
  Galleries (440999)                    containers: one per bucket
    <bucket name> (450999 + bucket id)  items: the videos of the bucket
```
Video items keep their id `410999 + MediaStore id` in every folder; only the parent of an item in "All videos" is now 430999 (a
gallery lists them with its own id as parent). One id per video makes controllers and play queues see the same object everywhere.

## Classes (package `upnp.server.contentdirectory`)
- `ContentDirectoryIDs`: `VIDEOS_ALL_FOLDER`, `VIDEOS_BY_BUCKET_NAMES_FOLDER`, `VIDEOS_BY_BUCKET_NAME_PREFIX`.
- `VideosFolderBrowser`: now the two containers (size 2), as `ImagesFolderBrowser`.
- `VideosAllFolderBrowser`: the old item listing of `VideosFolderBrowser`.
- `VideosByBucketNamesFolderBrowser`: the galleries. One query over the media paths with `BUCKET_ID` and `BUCKET_DISPLAY_NAME`, grouped in
  Java (`VideoGalleries`), then sorted and paged: the page is cut after grouping, not after the rows.
- `VideosByBucketNameFolderBrowser`: the videos of one bucket (media paths and bucket id in the selection).
- `VideoBrowser` (abstract base of the four video browsers that read the media store): the projection, the media path selection, the query with paging and sort order, the conversion of a cursor row into an item, the grouped galleries (read once per browser).
- `VideoGalleries` (pure): grouping of the rows to galleries (first seen order, count per bucket), unique names (a second gallery
  named like another one gets " (2)", " (3)" ... in bucket id order), a name for a bucket without one, paging of a list.
- `YaaccContentDirectory.findBrowserFor`: three routes.

## Paging, sort, counts
- Items: `moveToPosition(firstResult)` then up to `maxResults` rows; sort order from `SortSupport.toMediaStoreSortOrder` (title, date added).
- Galleries: sorted in memory by `SortSupport.toComparator` (title, default ascending, case-insensitive), then `subList`.
- `getSize` of a gallery = its rows (for the child count); of "Galleries" = the number of distinct buckets.

## Risks
- A bucket id can be negative (an int hash): ids are only compared as text, the prefix match is on the fixed prefix.
- A bucket without a display name (files in the root of a volume): shown with a text from the strings.
- Two directories with the same name on different volumes: told apart by the suffix (no path is shown to the network).
