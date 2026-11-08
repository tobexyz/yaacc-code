# Issue #299: new files and folders in a shared SAF folder

(Written after the implementation was drafted; the process rule "spec first" applies from the next issue on.)

## Problem Statement
A user adds a SAF folder (for example Downloads). Later a torrent client writes a new folder with media files into it.
Control points keep showing the old content until the user removes and re-adds the folder.

## Cause
The server lists SAF folders live (`SafListingCache`, TTL 30 s), so a Browse sees new content. Control points cache Browse
results until the ContentDirectory `SystemUpdateID` changes. It changed only when the list of shared folders changed.

## Goals
- New files and sub folders inside a shared SAF folder reach control points without any manual action.
- Low cost: no recursive walks, no work when no SAF folder is shared.

## Non-Goals
- The Android MediaStore (media scanner, "media store rescan" button): not part of this issue (confirmed by the owner).
- ContainerUpdateIDs (per container events).
- Ticking or unticking a folder in the server tree view does not change `SystemUpdateID` (separate gap, see open questions).

## Success Metrics
- After copying a folder into a shared SAF folder, a caching control point refreshes within about 45 s.
- No thread and no provider query when no SAF folder is selected.

## Open Questions (for the owner)
1. Check interval 30 s and minimum interval between two `SystemUpdateID` bumps 15 s: acceptable?
2. Folders a control point browsed in the last 15 min are watched in addition to the selected roots, at most 16: acceptable?
3. Bump `SystemUpdateID` also when a folder is ticked or unticked in the server tree view?
