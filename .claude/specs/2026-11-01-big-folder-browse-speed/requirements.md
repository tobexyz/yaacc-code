# Browse of a big SAF folder is too slow for Home Assistant

## Problem Statement
Home Assistant shows `Server disconnected: UpnpConnectionTimeoutError('TimeoutError()')` when it browses a SAF folder with thousands of files;
the server logs `Broken pipe` (HA hung up before the answer was written).
Owner's log (13:08:59): `SAF browse 11009995661 list=5570 entries from cache in 0 ms, page 0-5569 in 6783 ms`.
The listing came from the cache (0 ms); the 6.8 s are spent building the 5570 items (about 1.2 ms per item) and the DIDL serialization comes on top.
The request asked for the whole folder (page 0-5569 = RequestedCount 0). At the same time the duration preload ran (`PRELOAD_FILE_SLOW: .thumbnails took 36693ms`).

## Goals
- A Browse of a folder with several thousand entries answers well under the time a control point waits (target: below 2 s with a warm listing, measured by the existing D line).
- The log tells where the time goes (listing / permission checks / metadata / item building).

## Non-Goals
- No change of the UPnP answers (same DIDL, same ids).
- No change to how often folders are re-listed (30 s cache, #299 change check).

## Hypotheses (to be MEASURED first, not assumed)
1. `createItem` is called per entry and each call does a permission check (`SafDirectoryLister.canRead` -> `checkCallingOrSelfUriPermission`, a Binder call) - the same answer for every child of one granted tree.
2. `SAFCacheManager.getMetadata` takes the global cache lock per entry and, on a cache miss, builds and serializes an entry (first Browse of a never-seen folder); it competes with the duration preload for that lock.
3. Per-entry string work (`replaceAll`, URI building, logging) and `toClingItem`/DIDL generation.

## Decisions (owner, 2026-11-01)
1. NO cap of the number of returned items below TotalMatches and NO virtual "More..." folders (confusing, strange client behavior). Always answer the complete list.
   Home Assistant (checked in its source) sends ONE Browse per folder with RequestedCount=0 and never pages; the library's default wait is 5 s.
   The only lever is speed on our side.
2. The slow Browse was NOT a repeat: the owner navigated from another folder into the large one (so the listing was cached by the size/meta calls before,
   the metadata cache was probably cold: one cache entry built per file).

## Scope
- Per-entry cost of `SafFolderBrowser.browseItem` / `browseContainer` / `createItem`, `SAFCacheManager.getMetadata`, the DIDL generation of the answer,
  and the contention with the duration preload.
- Out of scope: the first listing of a slow (network) provider; if it is slow by itself nothing here helps (noted for later: background listing).
