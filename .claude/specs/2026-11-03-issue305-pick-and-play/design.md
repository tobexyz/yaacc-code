# Design: pick and play (#305)

## Facts (from the code, branch feat/issue305 after the merge of develop)
- Single file today: foreign app -> exported `LauncherActivity` (forward with nonce) -> `ShareIntentHandler.handleSharedFile` -> `PendingShare` -> `ReceiverPickController` ("Play on...") -> `confirm` -> `ShareService.prepare` (open descriptor, `ShareRegistry.registerOrEvict`, proxy URL) -> `SharedItemFactory.create` -> `UpnpClient.initializePlayersWithPlayableItems` -> `PlayerService.createPlayer`.
- `PlayableItem` gets its URI at creation (`SharedItemFactory`) and the same URL is in the DIDL `Res`; all players of one action share the same `PlayableItem` objects (`PlayerService.createPlayerFor`).
- Release is per player today: `ShareLifetimeListener` releases keys when a player is removed. Nothing releases an item that finished while the player stays; `stop()` at the end of a list keeps the player.
- `AVTransportPlayer` has no `SetNextAVTransportURI` and no prefetch: advancing is `onItemEnded` -> `advance` -> `play` -> `loadItem(idx)` -> `loadItem(prev, cur)` -> `startItem` -> `proceedWithSetURI` (`SetAVTransportURI(playableItem.getUri())`, DIDL from `playableItem.getItem()`). Send time is a natural just-in-time point.
- `LocalMediaSessionPlayer` (audio on this phone) adds ALL items to ExoPlayer up front (`toMediaItem(item)` with `item.getUri()`); video on this phone starts an external app (`MultiContentPlayer`, `setDataAndType`); images use `LocalImagePlayer`.
- `ShareRegistry`: 16 random bytes key, at most 8 active, `registerOrEvict` evicts only released entries; `remove` closes the source at once; `discardShareOf(item)` = `remove` + `unclaimed.released`.
- Pick results of the system picker are not foreign input: the user picks inside our own activity, so the launcher forward, the nonce and the one-pending-share dance of the intake do not apply. Names, types and sizes of the providers are still untrusted text.

## Approach
### 1. Picking (new, browser layer)
- Two `ActivityResultContracts` in the browser activities (new layout: buttons in the server list header next to the live stream buttons; classic: the same two buttons in the server tab): `OpenMultipleDocuments` with the MIME filter `audio/*`, `video/*`, `image/*` and `OpenDocumentTree`. Exact contract and intent extras are verified against the androidx.activity / platform docs first (task 1) and written to `docs/tech.md`.
- Result URIs are accepted only if `content:` scheme, not an own provider (`OwnProviders.isOwnAuthority`, fail closed), length-capped (`SharedContentPolicy.MAX_URI_LENGTH`). No `file:`.
- A new pure class `PickedQueue` takes the picked entries (uri, display name, MIME as chosen by `SharedContentPolicy.chooseMimeType`, size if known) and splits them by type into up to three queues (audio, video, image), each sorted by name (files from the picker: its order within the type); one cap of 500 files over all queues (audio first, then video, then image); non-media files are skipped; notice texts are fixed resources with counts.
- Folder listing: `DocumentsContract.buildChildDocumentsUriUsingTree` + one bounded query (`BoundedCall`, `ProviderCallGate`, same budgets as `ProviderProbe`) for document id, display name, mime type, size; no recursion; directories skipped. Runs on the `fileExecutor`, never on the main thread; if the listing is cut by the time budget the notice says so.
- The grant is temporary (no `takePersistableUriPermission`). Its lifetime is until the device restarts and it belongs to the app, not to the activity (docs/tech.md "Pick and play (#305): platform facts"), so rotation does not end it; the persistable permission stays a fallback only.

### 2. Handing over (reuse of the browse "Play on..." path)
- The queues are offered as ONE picked action to `ReceiverPickController` as a picked action (same `PickedAction` / `GuardedPickedAction` / `ActionFlight` as browse actions, so rotation discard, cancel and busy rules are the existing ones). The queue holds only URI texts, names and MIME types: no descriptor, no copy, no key until the receivers are confirmed (SI-11 spirit).
- On confirm one player per non-empty queue is created on the chosen receivers and all are started directly (`initializePlayersWithPlayableItems` per type, then `play()`); the chosen receiver ids are captured once (like `prepareAndPlayFile` does); the allowed addresses come from `ShareAddressResolver.resolve(upnpClient, ctx, ids)` at that moment and are not recomputed later (the stored selection is cleared or restored by the after-start hook).

### 3. Late binding in the play path (the core change, as built; see decisions.md)
- Every player of a queue has its OWN list of placeholder `PlayableItem`s (cleaned name, MIME, DIDL class, a placeholder url, no key) and its own `QueueShareWindow` with one `Lease`. The window finds an item by identity (the playlist may be edited: remove, move, `addToPlaylist`); an item that is not part of the queue needs no share.
- `QueueShareWindow`: `acquire(index)` counts a holder and binds the share on the first holder, `release(index)` releases it with the last one. Binding (`QueueShareBinder`: room check, `ShareService.prepareFor` with the addresses resolved at the confirm, copy url and DIDL item onto the placeholder, claim the key) runs outside every lock, so closing a player never waits for it; a share that arrives after the close is released at once.
  - Release: `registry.remove(key)` + `unclaimed.released(key)` (tight bound, not the 10 minute grace; `DeleteOnCloseSource` deletes a copy). The old url stays on the item and answers 404.
  - A player holds only the share of its current item, so a queue needs at most one share per player (types x receivers) against the registry limit of 8; a registry that is full is detected before a file is opened (`ShareRegistry.hasRoom`).
- `Lease.drop()` (stop, end of the list: reusable) and `Lease.close()` (the player is destroyed: final; a move after it is no failure of an item).
- Hook: `AbstractPlayer.loadItem(int)` calls `lease.moveToItems(current, null)` before the item is loaded; `stop()` drops, `onDestroy()` closes. An item that cannot be shared is skipped; after three in a row the player stops and says so once. Players without a lease (browse items, the single-file intake) are unchanged.
- `ShareKeyTracker` / `ShareLifetimeListener` keep working: release is idempotent, the key is claimed at the bind.
- `AVTransportPlayer`: `selectBestResourceForDevice` and `modifyProxyUrlWithDeviceId` run after the bind and see the final URL.
- This phone as receiver:
  - Audio (`LocalMediaSessionPlayer`): ExoPlayer builds all media items up front, so a lazy URL does not fit. Proposed: the placeholder resolves to the picked `content:` URI directly (ExoPlayer reads it through its content data source with our own grant, no proxy, no share), which also removes the need for shares on this phone. Verified in task 1 (grant lifetime, `ContentDataSource` on the exact URIs); fallback: a `ResolvingDataSource` that binds a share on open.
  - Images: `LocalImagePlayer` with the `content:` URI directly (verify how it loads today; fallback: share per image through the window).
  - Video: an external app cannot read our grant; use a share per item through the window (one at a time) as the intake does today.

### 4. UI and texts
- Two icon buttons (own icons, no brand assets) with content descriptions "Play files from this device" and "Play a folder from this device"; toasts with fixed resource texts and counts only (SI-16: no names, URIs or keys in logs or toasts); strings in values + de/es/fr/nl/pt/zh.
- Receiver picker title for a queue: the folder name or "N files" with the existing name cleaner (SI-19).

### 5. Alternatives considered
- Raise the 8 active shares and register the whole queue: touches `ShareLimits` (security review rule), holds N descriptors / copies, exposes N files for the whole playlist time, and a queue of running shares cannot evict itself. Rejected.
- Release with the 10 minute grace (`registry.release`) instead of `remove`: leaves a finished file fetchable. Rejected for items of a queue; `remove` is used.
- Virtual "This device" server (previous spec): rejected by the owner (decision 1).

## Risks
- Starting all three types at once: video on this phone opens an external app and takes the screen; a receiver that cannot play a type (a speaker and a video) fails for that player only and the failure is reported per player, the other players keep running. Filtering by what the receiver announces (sink protocol info) is not part of this step.
- **Grant lifetime of the picker result:** documented as until the device restarts (app-bound); to be confirmed on a device for a long background playback. If it ever ends too early, hold the permission with `takePersistableUriPermission` for the lifetime of the queue and release it when the queue ends.
- Touching `AbstractPlayer` affects every player; the window is optional (null for all existing paths) and has its own tests plus the existing playlist tests (`AbstractPlayerPlaylistTest`, `AbstractPlayerPlayModeTest`, `ExoPlaylistSyncTest`, `ShareLifetimeListenerTest`).
- Several receivers advance independently; reference counts per index cover it, tested with two fake players.
- Providers may report no size or no seeking: same copy fallback and caps as the intake (SI-13), one copy at a time; a queue of non-seekable files copies one file at a time just in time.

## Docs to keep consistent
docs/index.md "Sharing to YAACC" (add a section "Play files from this device"), docs/tech.md "Sharing local files" (as built: queue, window, SI additions), CHANGELOG, README feature list, store description.
