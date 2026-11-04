# Issue #305: pick files or a folder on this device and play them on receivers

Issue (owner): "add an entry 'this device' to the server list. Allow to pick local files and pass them only once to a receiver without serving it constantly like the server does."

Status: DRAFT. Supersedes `2026-11-01-issue305-this-device-server` (the virtual "This device" server entry), see decision 1. Nothing is implemented.

## Decisions (owner, 2026-11-03)
1. **No server entry and no browsing of local content.** Browsing is only for real servers. It is enough to pick one or more files, or one folder, with the system picker (Storage Access Framework); after the pick the receiver picker "Play on..." opens. (Supersedes the entry design; the "own list of folders" answer for the entry is void.)
2. A share lives while its item is current (idle grace and maximum as today); shares are created just in time for the current item (the next item was dropped later, see decisions.md) and released when the item is done.
3. First the improved share (queue of several files with just-in-time shares), the entry is not built.

## Decisions (owner, 2026-11-03, answers to the open points)
4. Folder: only the folder itself, no sub folders.
5. Folder size means the NUMBER of files, not their bytes: at most 500 files are queued (a safety limit for the listing, the queue and the memory); a notice says when files were left out. (Owner asked what it means; recommendation 500 kept unless he objects.)
6. Mixed types: only media files (audio, video, image) are queued, everything else is skipped. They are sorted by type, each type sorted by name, and each type goes to its own player: up to three players (music, video, image show) are started directly at the same time on the chosen receivers.

## Problem Statement
Playing a phone file on a receiver today needs the local UPnP server (folders served to the whole network while it runs) or the share intake from another app, which accepts exactly one file. There is no way to say "play these files / this folder on that speaker" from inside yaacc without serving them constantly.

## Goals
- Two actions in the app: "Play files from this device" (system file picker, several files allowed, audio / video / image) and "Play a folder from this device" (system folder picker).
- After the pick the existing receiver picker "Play on..." opens; confirming starts one queue on the chosen receivers (this phone included).
- Each file is reachable by a receiver only while it is the current item: a keyed, expiring, address-restricted share (existing machinery) is created just in time and released when the item is done. No more than the existing 8 active shares are ever needed.
- The local UPnP server, its folders and settings are not involved and need not run.

## Non-Goals
- No virtual server, no browsing, sorting or paging of local content, no persisted folder list, no MediaStore.
- No `ACTION_SEND_MULTIPLE` from other apps in this step (the queue and the just-in-time shares are built so it can follow, but the manifest filter, the launcher forward of N items and the nonce rules for it are a separate decision).
- No sub folder recursion (pending decision above), no change of `ShareLimits` (8 active shares, grace, maximum).

## Success Metrics
- Pick 20 audio files, choose a UPnP speaker: they play one after the other; at any time at most the current file of each player has an active share; after the last file none is left; a receiver that was not chosen, or a request without the key, gets 403/404.
- Pick a folder of 600 media files: 500 are queued, the notice says so. Pick a folder with songs, a video and photos: the music player, the video player and the image show start together on the chosen receivers.
- This phone as receiver plays the same queue.
- Existing share, player and registry tests stay green; 0 lint findings.

## Constraints
- Rules: license headers, tests for pure logic, commits carry `(#305)`, English, trademark care (nominative names only).
- Security invariants SI-1..SI-19 of docs/tech.md "Sharing local files" stay valid: keyed shares, addresses fixed at confirm, restriction default on, bounded provider calls, no key/URI/name in logs, no new permission, no new exported component.
