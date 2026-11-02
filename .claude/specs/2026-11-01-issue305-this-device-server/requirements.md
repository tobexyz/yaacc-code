# Issue #305: server type "this device"

Issue (owner): "add an entry 'this device' to the server list. Allow to pick local files and pass them only once to a receiver without serving it constantly like the server does."

Status: **ON HOLD, draft for discussion. Nothing is implemented.** The owner decides first whether the entry is a good enhancement or whether the improved share intake is enough (see "Is it worth it?" in design.md). The base is feat/issue299 with feat/issue296 merged in.

## Problem Statement
To play a file of the phone on a receiver today there are two ways: the local UPnP server (the shared folders are listed and served to the whole network as long as the server and the content provider are on), or the share intake (one file, shared to yaacc from another app). Neither lets the owner browse folders of the phone like a server and send single items to a receiver without serving them constantly.

## Decisions (owner, 2026-11-01)
1. The entry is NEW, next to the local server entry. It does not replace it (other devices on the network still use the local server).
2. Sources: only folders picked with the Storage Access Framework (SAF). NOT the media store.
3. Browsing the content works exactly like browsing a real server (same screens, sorting, paging, navigation, play / play all / add to playlist / "Play on...").
4. It also works with this phone as the receiver (local playback), like the share intake.
5. (2026-11-02) The owner does not want a "pick folders" feature as such: the content is browsed in the app's own screens like a server. SAF needs a one-time grant of a folder with the system picker before the app can list it (the system picker is a file browser, not our content browsing); that cannot be avoided. Open: whether the entry reuses the folders already granted for the local server (default proposal) or has its own list.

## Goals
- A server list entry "This device" (identity fixed, not announced on the network, no content directory is published, no port is opened for it).
- The content under it are SAF folders that were granted once with the system picker (the proposal: the ones already granted for the local server, so there is nothing new to pick; if none exists the entry shows a hint and an "Add folder" button that opens the system picker).
- Playing an item on a receiver hands the receiver one keyed, expiring, address-restricted share of that file (the existing share machinery) and nothing else is reachable. When playback ends or the share expires the file is not served any more.
- Same browse experience as a real server, including big folders (the Browse speed work of #299 applies: no network round trip, but keep the paging and the per-folder cost low).

## Non-Goals
- No media store browsing, no MediaStore scan.
- No change to the local UPnP server and its SAF folders (they stay "served constantly").
- No network exposure: no SSDP announcement, no ContentDirectory service on the LAN for this entry.

## Success Metrics
- Browse, sort, page and open sub folders of a picked SAF folder from the "This device" entry with the same UI as a real server.
- A file played on a receiver is fetched through a share URL (random key); after the playback the URL answers 404/403; another host cannot fetch it; the local server setting is not needed.
- No new permission; unit tests for the pure parts; the existing tests stay green.

## Constraints
- Android limits the persisted SAF grants of an app (128 on older versions, 512 since Android 11); the folders of the local server and of this entry share that budget.
- Existing rules: license headers, tests for pure logic, 0 lint findings, commits carry `(#305)`, English.

## Open Questions (to discuss, see design.md)
See the list at the end of design.md.
