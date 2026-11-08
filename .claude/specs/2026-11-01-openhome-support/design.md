# OpenHome support: design (issue #301)

Spec: `requirements.md`. Decisions: `decisions.md`. Verified facts come from the service descriptions and sources of ohPipeline
(`yaacc/src/test/resources/openhome/spec/`); everything marked (?) is to be confirmed with a real device.

## Packages
- `de.yaacc.openhome` (plain Java, no Android classes, unit tested on the JVM): the model both roles share.
  - `OhIds`: `IdArray` codec (list of ids <-> big-endian 32 bit numbers, base64 text).
  - `OhQueue`: the play queue: ordered entries `(id, uri, metadata)`, ids from a counter (1, 2, 3 ... never reused, 0 = none),
    `insert(afterId, uri, metadata)`, `delete(id)`, `deleteAll()`, `readList`, `idArray()`, `idArrayToken()` (changes with every
    edit), `tracksMax`. Errors are exceptions that carry the OpenHome code (800 id not found, 801 full, 802 index, 803 seek).
  - `OhTransportState` (`Playing`, `Paused`, `Stopped`, `Buffering`), `OhVolume` (percent <-> steps), `OhSourceXml` (the product's
    source list), `OhTrackList` (`ReadList` XML), `OhEscape`.
- `de.yaacc.upnp.server.openhome`: the renderer (Cling local services) and the coordinator that drives the player.
- `de.yaacc.player` / `de.yaacc.upnp.callback.openhome`: the controller (`OpenHomePlayer`, action and subscription callbacks).

## Renderer role
- `createMediaRendererServices()` returns AVTransport, RenderingControl **and** Product (version 2), Playlist (1), Info (1),
  Time (1), Volume (2). Service types `urn:av-openhome-org:service:<Name>:<Version>`, ids `urn:av-openhome-org:serviceId:<Name>` (?).
  The services' actions and state variables are exactly those of the OpenHome descriptions (a test compares them with the
  fixtures); balance, fade and similar are answered with fixed values.
- `OpenHomeRenderer` (one per app process) owns the `OhQueue` and one long-lived music player (`LocalMediaSessionPlayer`). The
  queue and the player's item list are kept in step: ids map to `PlayableItem`s; every queue edit is applied to the player, and
  the end of an item moves the `Id` on.
- Events (GENA, through the state variables' `sendEvents`): `TransportState`, `Repeat`, `Shuffle`, `Id`, `IdArray` (delayed, bursts
  collapse), `TracksMax`, `ProtocolInfo`; Info: `Uri`, `Metadata`, `Duration` and the counters; Time: `Seconds` (at most once per
  second), `Duration`; Volume: `Volume`, `Mute`; Product: `Standby`, `SourceIndex`, `SourceXml`, names.
- Player changes needed (small, tested): `insertItems(index, items)` (ExoPlayer `addMediaItems(index, ...)`, `PlayOrder.insert`),
  `playItemAt(index)`. Repeat: OpenHome repeat true is the app's repeat ALL; false is OFF.
- Product: standby is always false (the phone is the device), one source (name and system name `Playlist`, type `Playlist`,
  visible). Setting the source or standby is accepted and changes nothing.
- Security: the renderer is reachable by every device on the network, as `SetAVTransportURI` is today. `Insert` checks the number
  of tracks (`TracksMax`), the length of address and metadata, and accepts only http(s) addresses; the metadata is parsed by the
  same DIDL parser as for AVTransport. Eventing frequency is limited. The existing receiver checks stay.

## Controller role (`OpenHomePlayer`)
- Chosen by `PlayerService.createPlayer` when the receiver offers the OpenHome Playlist and Product services and the per-device
  choice is not AVTransport (default OpenHome). `AVTransportPlayer` is not changed.
- Before queue operations: Product `Standby` (wake with `SetStandby(false)`), `SetSourceBySystemName("Playlist")` (?) or
  `SetSourceIndex` of the Playlist source from `SourceXml`.
- Play: `DeleteAll`, `Insert` the items one after the other (`AfterId` of the previous result), `SeekIndex(0)` / `Play`. Play all and
  add to playlist: `Insert` at the end. `getItems()` is the device queue: `IdArray` -> ids, `ReadList` in batches for the ones not
  known yet. The playlist dialog's edits become `DeleteId` (and `Move` where the device offers Playlist 2).
- State: subscriptions to Playlist, Info, Time, Volume, Product; a read of all values as a fallback every 30 s and after own actions
  (events may not reach the phone). The row text and the mini player use `Info` (track) and `Time` (position).
- Play mode: `Repeat`/`Shuffle` booleans; the app's repeat ONE shows as ALL.
- Volume: `Volume` of 0 to `VolumeMax` mapped to percent; mute.

## Testing
- Renderer: JVM tests through the service harness (real SOAP path): every action of the descriptions, the events, the fixtures
  comparison, hostile input (huge metadata, 1001st track, unknown ids, ids that are not numbers).
- Controller: tests against a fake OpenHome device built on the renderer classes, then manual tests YAACC against YAACC.
- `capture/` records the device description, the service descriptions and the events of a real device (and of the YAACC renderer).

## Risks
- `AbstractPlayer` insertion with shuffle (play order) is the delicate part; covered by `PlayOrderTest` additions.
- Third-party control points may need other service versions than offered here (?). Found only by testing.
- The 1000-track limit and `ReadList` batches keep memory and traffic bounded.
