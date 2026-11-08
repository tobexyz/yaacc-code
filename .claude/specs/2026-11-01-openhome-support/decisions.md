# Decisions: OpenHome support (#301)

## 2026-11-06 — Scope and roles (owner)
- **Both roles**: YAACC's renderer offers the OpenHome services, and a new `OpenHomePlayer` controls OpenHome renderers.
- **Controller**: the device owns the playlist; the app's playlist dialog shows and edits the device queue (no app-side copy that
  can drift). New class next to `AVTransportPlayer` (option A), no refactoring of the existing player.
- **Renderer**: no setting; the extra services are always offered together with AVTransport (backward compatible).
- **Source**: the controller switches the device to the Playlist source (and wakes it) automatically.
- **Fixtures**: the OpenHome service descriptions are copied into the test resources (MIT notice in `THIRD_PARTY.md`).
- **Order**: renderer and the controls to drive it first (the owner cannot run an OpenHome device on the notebook; the first test is
  YAACC against YAACC, 2026-11-07), then interop with real devices.

## 2026-11-06 — Facts read from ohPipeline (not from devices)
- Playlist has `Repeat` and `Shuffle` as booleans, **no repeat-one**. Decision: the renderer maps OpenHome repeat true to the app's
  repeat ALL; if the app's player is in repeat ONE, the renderer reports repeat true; the controller shows ONE as ALL for OpenHome
  receivers (the repeat button cycles off / all only).
- Default `TracksMax` 1000 in ohPipeline; the renderer uses 1000 as well. Errors: 800 id not found, 801 playlist full, 802 index not
  found, 803 seek failure.
- `Insert(AfterId=0)` inserts at the start, any other `AfterId` after that track; an unknown id is error 800, a full list 801 (verified in
  ohPipeline `TrackDatabase::Insert`). The `IdArray` is the track ids as big-endian 32 bit numbers one after the other (base64 in the XML,
  id 0 means "none"); `ReadList` answers `<TrackList><Entry><Id/><Uri/><Metadata/></Entry>...</TrackList>`, XML escaped. ohPipeline delays
  the `IdArray` event with a timer; so will the renderer (a burst of inserts is one event).

## 2026-11-06 — renderer threads and queue/player sync (review cycle 1)
**Context**: the review found player reads on the wrong thread (media3 throws off the main thread) and non-atomic queue and player edits.
**Decision**: the renderer does all its work on one serial worker thread (`OpenHomeRenderer.Threads`); commands from control points only hand work over. The player is only touched on the main thread (the worker waits for it, never the reverse; `createPlayer` runs on the worker because it waits for the player service). The player's list follows the queue by reconciling (`sync()`: remove what the queue lost, insert what is new) instead of applying index based edits, so concurrent, reordered or repeated notifications are harmless.
**Decision**: deleting the track that plays ends the player (it is shut down); the next `Play` creates a new one starting with the track behind the deleted one. Not verified against ohPipeline (?) — to check with a real device.
**Rationale**: ExoPlayer rules, simple and robust; a rare edit does not justify keeping a half-consistent player.

## 2026-11-06 — security review cycle 1 fixes
**Decision**: `ReadList` reads each id once, looks at no more text than `max` ids can take up, and answers at most 1 MiB; the queue has a total budget of 4 000 000 characters (error 801) and metadata at most 16 KiB per track; the renderer forces an audio MIME type whatever the metadata say. Loopback / link-local host filtering for track addresses is **not** done: a LAN device that can reach the renderer can already make it fetch any http(s) address with AVTransport; revisit if the real-device tests show a need.

## 2026-11-06 — security review cycle 3 (cap reached)
**Context**: security review cycle 3 failed with two warnings (nesting depth on Android's recursive DOM parser; endless re-reads when entries do not fit the cache budget). Three cycles are the limit of the workflow.
**Decision**: both were fixed after the cycle (nesting counted on the text before parsing, max 16 levels, `StackOverflowError` caught; entries that do not fit the budget count as unavailable, the poll compares the ids the device reported) with tests (`OhXmlTest`, `OhRemoteTest`), but **not re-reviewed**: the owner decides whether another cycle is wanted.

## 2026-11-08 — Closing the OpenHome player leaves the device playing
**Context**: Review cycle 4/5 noted that `OpenHomePlayer.onDestroy` ends the workers before the stop of the base class can be queued, so the device is not stopped.
**Decision**: Intended. The playlist and the playback belong to the device (it keeps its queue when the app is closed); leaving the player screen ends only the screen, the poll and the notification. The user stops the device with the stop button or the receiver row.
**Rationale**: Same as the reason for OpenHome in the first place (the playlist survives the app); an AVTransport player owned by the app stops with the app.

## 2026-11-08 — Edits at the renderer's own player are taken over before the queue is synced
**Context**: Edits in the playlist of the local music player (delete, move, add) were lost and could be undone by the next sync (user report).
**Decision**: `OpenHomeRenderer` compares the player's items (by identity) with what it put in, once a second and at the start of every `sync()`, and applies the difference to the queue: deletes, moves (id kept via `OhQueue.move`), adds (http/https only; others leave the player again). A known track that a control point deleted meanwhile is never re-inserted.
**Rationale**: One list must win; reconciling by place needs a list the renderer made itself.

## 2026-11-08 — Last warning of review cycle 6 fixed after the cycle cap
**Context**: Cycle 6 (third cycle of the controller group) found one Warning: in `takeOverFromPlayer` a known track deleted by a control point still served as the insert anchor, so a track the user added right behind it was refused and dropped from the player.
**Decision**: The anchor is only advanced over tracks that are still in the queue (one line plus a test). Fixed after the cap of three cycles, so this fix has not been reviewed again; the owner decides whether another cycle is wanted.

## 2026-11-08 — The six suggestions of security review cycle 4 done
**Context**: The owner asked for all six suggestions of cycle 4 (they do not block the verdict).
**Decision** (limits chosen so that no real use hits them):
1. `ClingOhControl.runGuarded`: a `StackOverflowError` while a device's SOAP answer is parsed is "no answer" (`OhControlException.NO_ANSWER`).
2. `ImageDownloader`: http(s) images are fetched with 5 s connect and 10 s read timeouts and at most 32 MiB; an `OutOfMemoryError` gives no image. `OpenHomePlayer.getIcon` starts a download of one address at most once a minute (32 addresses remembered). `AlbumArt.of` (renderer side, also the AVTransport renderer) only returns http(s) addresses.
3. `HttpRequestSender.readBounded`: answers are read up to 32 MiB and 120 s. The server's request bodies (`BoundedEntityConsumer`) are limited to 2 MiB.
4. `LogText.clean` for names and types of devices in the registry log and for fault texts.
5. A PlayableItem object that appears twice in the player counts once in the reverse sync; the repeat is taken out of the player.
6. At most 100 event subscribers per device and 30 per service (`SERVICE_UNAVAILABLE` above); `BoundedWork` drops tasks for the renderer's worker beyond 500 waiting ones; the mirror of a device's track keeps its address only if `OhUriPolicy` accepts it.
**Rationale**: The device is reachable by every host of the network and consumes data from every device on it; every input gets a size, a time or a count limit.

## 2026-11-08 — Review cycle 8 (hardening) answered
**Context**: The review of the hardening commit found one Warning: the cap on waiting tasks also dropped `start()` and `shutdown()` of the renderer.
**Decision**: Start and end go through `Threads.lifecycle`, which is never counted or dropped; the cap is 2000 (a full playlist load is 1000 tasks); the log of dropped tasks is one line per hundred. Of the suggestions: album art only needs `http`/`https` as scheme (hosts like `nas_01` are no valid `java.net.URI` hosts but real addresses); images up to 64 MiB and 30 s read timeout. Not done (low value): atomic check-and-add of subscribers, a 413 answer for long request bodies, a total deadline for images, cleaning the other log lines that print device names, import cleanup.
**Rationale**: The Warning loses the end of the renderer under load; the rest are limits chosen so real use does not meet them.
