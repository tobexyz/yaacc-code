# Design (draft for discussion): "This device"

## What exists (facts from the code, branch feat/issue305)
- **Browsing a real server:** `BrowseItemLoadTask` -> `UpnpClient.browseSync(...)` (UpnpClient.java ~623-660) runs a Cling `Browse` action on a `Device` and returns a `ContentDirectoryBrowseResult` (DIDL objects) to `BrowseContentItemAdapter` / `ContentListFragment`. Navigation state is `Navigator` / `Position` (device id + object id).
- **A fake device already exists for the receiver side:** `UpnpClient.LocalDummyDevice` (UpnpClient.java ~1713, UDN `LOCAL_UID`) is a `Device` that represents "this device" in the receiver list.
- **The local server browses SAF folders in-process:** `SafFolderBrowser` / `SafDirectoryLister` / `SafListingCache` / `SafListingSupport` (de.yaacc.upnp.server.contentdirectory) list, sort and page SAF folders and build DIDL items with short ids from `SAFCacheManager`; the item URLs point to the app's own HTTP server (`/saf/<id>/<name>`, `YaaccUpnpServerContentHttpHandler`), which is served constantly while the server runs. The SAF folders of the server are `settings_saf_tree_uris*` (`MediaPathFilter`).
- **One-file delivery:** `ShareRegistry` (random 16 byte key, entry with allowed receiver addresses, idle grace and absolute maximum, at most 8 active entries), `ProxyShareResponder` (the HTTP side: key check, address check, ranges via `ByteRangeResolver`), `ShareService`, `SharedItemFactory`, `ShareAddressResolver` (receiver id -> addresses, `LOCAL_UID` is handled), `ShareIntentHandler` (the intake and the receiver picker "Play on...").
- **Play paths:** `ContentItemPlayTask`, `PlayableItem` (the URI is taken from the DIDL `res` when the item is created), `AVTransportPlayer` (sends the URI to a remote renderer; next-URI prefetch), `LocalMediaSessionPlayer` (this phone).

## The solution space
### A. In-process "virtual content directory" + late binding of the URL (recommended starting point)
- A virtual provider device with a fixed id (e.g. `THIS_DEVICE_UID`, name "This device") appears in the server list. It is not a Cling device on the network.
- `UpnpClient.browseSync(...)` recognises the virtual device and answers from an in-process source instead of the control point: a thin class that reuses the SAF listing / sorting / paging / DIDL building of the local server's `SafFolderBrowser`, but over the owner's own list of picked folders, and without the HTTP URLs. The UI, adapters and navigator do not change ("browsing is the same as for real servers").
- The `res` URL of the items is a placeholder that is not fetchable (a private scheme such as `yaacc-local:<objectId>`). When the item is played on a receiver, the URL is bound late: a share is registered in `ShareRegistry` for exactly that file and the chosen receiver address(es), and the share URL replaces the placeholder in the `SetAVTransportURI` call. For this phone as the receiver the content URI is opened directly (no HTTP), as the share intake does.
- Pros: same UI and code path as real servers, nothing is published, reuse of tested parts. Cons: the late binding touches the play path (`PlayableItem` fixes its URI at creation, playlists of many items vs `ShareRegistry` limit of 8 active entries, next-URI prefetch).

### B. A hidden real Cling LocalDevice with a ContentDirectory on loopback/LAN, not announced
- Browse goes through the normal control point against our own HTTP server; the items would be served by the proxy handler.
- Pros: nearly no change in the browse code. Cons: opens a ContentDirectory endpoint (reachable from the LAN unless bound to loopback, then receivers cannot use it), needs the server to run, mixes with the "served constantly" behavior we want to avoid.

### C. A separate browse UI for local files (file-picker style)
- Pros: independent. Cons: not "the same as real servers" (decision 3), duplicate screens for sorting, paging, playlists.

Recommendation to discuss: A.

## Design questions for A
1. **Which folders does the entry show? (clarified 2026-11-02)** SAF can only list a folder after the owner granted it once with the system picker (the picker is Android's file browser; our own screens do the content browsing afterwards). On Android 11+ the picker refuses the root of the internal storage and folders like `Download` and `Android/data`; `Music`, `Movies`, `DCIM`, `Documents` and the root of an SD card / USB stick can be granted. Browsing the whole storage without a grant would need `MANAGE_EXTERNAL_STORAGE` ("all files access"): not proposed.
   - **Proposal (default): reuse the folders already granted for the local server** (`settings_saf_tree_uris*`). Nothing new to pick; the entry works even when the local server and its content provider are off, and delivery is one-shot. If no folder exists yet, the entry shows a hint and an "Add folder" button (system picker, one time). Caveat: a folder added there also lands in the server's list and is served by the server when it runs.
   - **Alternative: a separate list for this entry** (the server's list means "served constantly"): costs a second picker and shares Android's persisted grant budget (128, 512 since Android 11). Owner has not decided.
2. **Late binding in the play path:** a playlist of 50 files must not register 50 shares at once (limit 8). Register a share when the item is about to be sent to the receiver (current item and the next one for prefetch) and release it when the item is done. Needs a "URI resolver" on `PlayableItem` or in `AVTransportPlayer` that is called at send time. What is the least invasive hook?
3. **What does "once" mean?** A renderer asks for the same file several times (HEAD, ranges, seeking, a second connection). Proposal: the share lives while the item is current (idle grace after the last request, absolute maximum as today) and is dropped when the item ends or the player is closed, not after a single request.
4. **Receiver restriction:** use the existing "restrict the media proxy to the chosen receivers" behavior (address check by `ShareAddressResolver`)? Default for this entry: always restricted.
5. **Object ids:** the server uses short ids from `SAFCacheManager` (numbers mapped to document uris, persisted). The virtual source can reuse them so `Navigator` positions and the "back" stack behave the same. Ids must not be fetchable through any HTTP path.
6. **Metadata:** durations / mime types come from the same cache as the server (`SAFMetadata`); album art for SAF items: none, as for the server.
7. **Server list behaviour:** icon, sort position (first or last), long-press actions, what the entry shows when no folder was granted (a hint, not an error), what happens when a grant was revoked (the `[X]` marking like the server).
8. **Local playback (decision 4):** `LOCAL_UID` receiver: pass the SAF content uri to `LocalMediaSessionPlayer` (ExoPlayer can read `content://` through a `ContentDataSource`); no share needed. Check the current share intake for what it does for `LOCAL_UID` (ShareIntentHandler ~756).
9. **Tests:** pure parts (object-id mapping, which shares to hold for which items, release rules) as JVM tests; the in-process Browse with the SAF listing seams of the server tests; the play-path hook with the existing `PlayerService` / `AVTransportPlayer` tests where possible.

## Risks
- The late binding changes how `PlayableItem` gets its URI (touches all players). Mitigation: the placeholder is resolved in one place, everything else stays.
- Persisted SAF grant limit (see requirements).
- SAF providers that do not support what the share path needs (size unknown, no seeking): same as the server today.

## Is it worth it? (decision pending: this entry vs the improved share)
What the share intake does today (`ShareIntentHandler`, docs/index.md "Sharing to YAACC"): another app (gallery, files, music player) shares ONE file (`ACTION_SEND`, item 0 only, no `ACTION_SEND_MULTIPLE`) or a link; yaacc asks for the receivers ("Play on...") and plays it through a keyed, expiring, address-restricted share. Nothing is served afterwards.

| | Improved share (extend the intake) | "This device" entry |
|---|---|---|
| Browsing | in the other app (files, gallery, music player) | in yaacc, same screens as a server |
| Several files / a folder / an album | only if the intake learns `ACTION_SEND_MULTIPLE` (a list of files, queue); a whole folder cannot be shared | browse, "play all", "add to playlist", sorting, paging like a server |
| Setup | none | one-time folder grant (or reuse the server's) |
| Work | small (intake, queue of shares) | large (virtual browse source, late binding in the play path, server list entry) |
| Risk | low | touches `PlayableItem` / players, grant budget |
| Fits users who | pick files in another app and send them | live in yaacc and want to browse their own media like the server list |

Possible middle way: first extend the share intake (multiple files via `ACTION_SEND_MULTIPLE`, queue with just-in-time shares), which needs the same late-binding hook (question 2) and is the basis for the entry if it is built later.

## Next step
The owner decides whether the entry is wanted or the improved share is enough (see above). Until then nothing is implemented. If the entry is wanted: settle questions 1-3, then write tasks.md.
