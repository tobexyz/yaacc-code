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
