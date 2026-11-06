# Security Review: OpenHome support (#301)

## Cycle 1 — 2026-11-06
Reviewing: Groups 2-4 (OpenHome renderer, branch feat/issue301, up to commit 22db10c3)

Scope: `yaacc/src/main/java/de/yaacc/openhome/` (OhQueue, OhIds, OhUriPolicy, OhEscape, OhTrackList, OhError, OhSourceXml, OhVolume),
`yaacc/src/main/java/de/yaacc/upnp/server/openhome/` (OhLocalService, OhRendererCore, OhServiceState, OpenHomeRenderer, AndroidOpenHomeHost,
AndroidOhThreads, OhCall, OhServiceDef(s)), the player changes (PlayOrder, AbstractPlayer, LocalMediaSessionPlayer, ExoPlaylistSync),
`UpnpClient.initializeLocalPlayer`, `YaaccUpnpServerService.createOpenHomeServices`.
Not reviewed: the untracked controller files of Group 5 (`OhControl`, `OhRemote`, `OhXml`, `OhSources`, `OhTrackListParser`, ...), they are not part of this gate.

Threat model: any device on the LAN can call every OpenHome action without authentication (like AVTransport/RenderingControl today). Findings are
rated by what is new compared to that existing surface.

### Critical
- none

### Warning
- [OhRendererCore.java:369 `ReadList`, OhIds.java:91-96 `parseIdList`, OhQueue.java:159 `readList`, OhTrackList.java:27 `toXml`] **Remote
  out-of-memory by amplification (new; AVTransport has nothing like it).** `ReadList` accepts up to 1000 ids but does not remove duplicates, and the answer
  has no size limit. One `Insert` with 64 KiB of `&` as Metadata (allowed: limit is 65536 chars), then one `ReadList` with `IdList` = the new id repeated
  1000 times (about 2 KB request) makes `OhTrackList.toXml` build an answer of **327,680,000 chars** (measured with the compiled classes: 1000 entries,
  1 entry stored; `&` becomes `&amp;`, five times larger), which Cling then escapes a second time into the SOAP envelope and encodes to bytes. Without
  `&`, 1000 x (4096 + 65536) = 70 M chars are still possible with distinct entries. The app has no `largeHeap`; the process dies with an
  `OutOfMemoryError` (the whole app: UI, content server, players). The work also runs on the HTTP I/O thread while the Playlist service lock is
  held, so a few parallel requests stall every other request of the embedded HTTP server. Fix: (1) `readList` returns each id once; (2) bound the answer,
  for example stop adding entries when the escaped text would exceed about 1 MiB and answer with what fits (or error 402 for an `IdList` that is longer
  than the limit allows); (3) cap the IdList text before `split(" ")` (the split of a 10 MB string of `1 ` allocates 5 M strings before `max` applies;
  read it with an index loop instead); (4) add a test with the sequence above that asserts a bounded answer size.
- [OhQueue.java:36-38,96-117 `insert`, AndroidOpenHomeHost.java:65-92 `toPlayableItem`, OpenHomeRenderer.java `startFrom`/`reconcile`] **The queue is bounded
  per entry but not in total (new).** 1000 tracks x (4096 + 65536) chars = about 70 M chars kept in the queue (70 MB with compact strings, 140 MB when the
  attacker uses any character above U+00FF), and every entry is parsed with `DIDLParser` and kept again as a `PlayableItem`/DIDL `Item` object graph
  in the player's list when somebody (the same attacker) calls `Play` (1000 SAX parses of up to 64 KiB on the worker, plus the object graphs). A LAN
  device needs about 70 MB of upload to push the app towards its heap limit (192-512 MB on typical devices, no `largeHeap`), and
  the memory stays until `DeleteAll`. DIDL for one track is 1-3 KiB in practice. Fix: lower `MAX_METADATA_LENGTH` (for example 16 KiB or less, check
  what the real controllers send) and add a total budget to `OhQueue` (for example 4 MiB of uri + metadata chars over all entries; error 801 "playlist
  full" when `insert` would exceed it); test: 1000 inserts of the maximum size end with 801 before the budget, `delete` gives the budget back.

### Suggestion
- [OhUriPolicy.java / AndroidOpenHomeHost.java:65-92] **What the phone fetches (rated: not new, and tighter than AVTransport).** `OhUriPolicy` allows
  http/https with a host, no user info, no blank/control character, at most 4096 chars. That is a clear gain over `YaaccAVTransportService.setAVTransportURI`,
  which accepts any parseable URI including `file:` and `content:`. What the policy does not do, and what stays the same as for AVTransport: the host is
  not restricted (loopback, the app's own HTTP server, link-local, other LAN hosts and other apps' local servers can be named), so the phone issues a
  blind GET from its network position (ExoPlayer decodes the answer as audio, nothing is returned to the sender). media3 follows redirects only
  http<->http (`DefaultHttpDataSource` default), and `media3-exoplayer-hls/dash` are not in the build, so a manifest cannot pull `file:`/`content:`
  segments through the http address. Consider rejecting loopback and link-local hosts (cheap, no legitimate OpenHome use) and say in decisions.md that
  LAN hosts are accepted by design.
- [AndroidOpenHomeHost.java:80-90, LocalMediaSessionPlayer.java `toMediaItem`, PlayerService.createPlayerForLocalDevice] The metadata is not covered by
  the scheme policy: (a) the `albumArtURI` of the DIDL goes unchecked to `MediaMetadata.setArtworkUri` (media3's notification loads it; may be `file:`/
  `content:`), (b) the MIME type of the first `res` decides which player `createPlayerForLocalDevice` creates, so a LAN device can make the renderer start
  the video or image player (a full-screen activity) although the renderer advertises audio only in `ProtocolInfo`; the music player slot is also taken from
  whatever plays on the phone (`createMusicPlayer` shuts the existing `LocalMediaSessionPlayer` down). All of this is the same through AVTransport today.
  For OpenHome it is easy to close: in `toPlayableItem` ignore `albumArtURI` unless http(s) (or drop the DIDL item and keep only title/artist/album), and
  force an `audio/*` MIME type (default `audio/mpeg`).
- [OhLocalService.java:144-150 `standard`] An action without a handler and with one input and no output writes the argument into its related state variable.
  Today every such action has a handler (Playlist, Volume, Product setters are all in the handler tables), so it is not reachable, but a future service
  version that adds a setter without a handler would let any LAN device overwrite device state. Make the default for a missing handler "answer from state
  for getters, fail with 501/401 for everything else" or add a test that fails when an input-only action has no handler.
- [OhRendererCore.java:290-296 `number`] A missing argument gives `NullPointerException` and so error 501 (`ACTION_FAILED`) with a warning log instead of 402. No security effect (the
  text stays in the log, which is at level E by default), but `number(null)` should throw `OhError.invalid`. The `Insert`/`Read` handlers are fine for a
  missing Uri/Metadata (402 / empty).
- [AndroidOhThreads.java:41-60 `work`] The worker queue is unbounded and there is no rate limit per control point. A flood of `Play`/`Next`/`Insert` is cheap to
  accept, but each task may wait up to 5 s per player call on the main thread, so the backlog (and `sync()` main-thread round trips) can grow and keep the UI thread busy.
  Same class of problem as AVTransport; consider coalescing (one pending `sync()` flag like `idArrayPending`) and a ceiling on pending tasks.
- [OhRendererCore.java `buildServices`, OhServiceDef evented variables] More services mean more places for GENA subscriptions, and `Time.Seconds` is a 1 Hz
  event, `Info.Metadata` up to 64 KiB, `IdArray` up to 5.3 KB at 6/s. Cling takes any number of subscribers and any callback URL (pre-existing for AVTransport/
  RenderingControl: callback SSRF and reflection). The delta is the larger event volume per subscription. A cap on subscriptions per service/remote host
  would help all services; not specific to #301.
- [OhIds.java:56-77 `decodeIdArray`, controller side] The base64 text is decoded before the length check; a hostile device can make the controller allocate a
  large array. Check `base64.length()` against `MAX_LIST * 4 / 3 + padding` first. (Controller code, noted for Group 5.)

### What was checked and is fine
- **XML in `ReadList`**: `OhEscape.xml` escapes `& < > " '` and drops characters XML 1.0 forbids; Uri and Metadata are escaped once inside `TrackList` and Cling
  escapes the whole value again for the SOAP body, so a Metadata of `</Entry><Entry>...` stays text. A track Uri cannot carry markup anyway (`java.net.URI`
  refuses `< > "` and blanks). `Read` returns the raw values, Cling escapes them. Covered by `OhSmallClassesTest.theTrackListIsTheXmlOfTheReadListAnswer`.
- **DIDL parsing of untrusted text (XXE / billion laughs)**: `DIDLParser.parse` first rewrites every `&` that is not `&amp;` to `&amp;` and uses the hardened
  `org.seamless.xml.SAXParser` (external general and parameter entities off; `disallow-doctype-decl` where the platform knows it). Because no entity
  reference other than `&amp;` and no character reference survives the rewrite, a DOCTYPE with internal entities cannot be expanded; the input is at most
  64 KiB. A parse failure gives an item that plays the queue's address and logs only the exception class name. The same parser is used for AVTransport,
  where the metadata size is not bounded, so OpenHome is not worse.
- **Input parsing and ranges**: ids are unsigned 32 bit and never 0 (`OhIds`), `number()` turns every non-number into 402, `SeekSecondAbsolute` is `ui4` and
  `*1000L` cannot overflow, `SeekSecondRelative` is range-checked, `SeekIndex` checks `0..Integer.MAX_VALUE`, `Volume` is clamped to 0..100, Cling's datatypes
  (`ui4`, `i4`, `boolean`) reject the rest before the handler. `IdArrayChanged`/`IdArray` tokens are plain numbers. Uri length (4096) and metadata length (65536) are
  checked before anything else uses them; the Uri is validated before it is stored; `insert` checks 800 before 801 as ohPipeline does.
- **Queue limits and ids**: 1000 tracks (801 beyond), ids are never reused while alive (`nextId` skips live ids, wraps at 2^32-1), all queue operations are `synchronized`.
  The IdArray event is debounced (150 ms), the `Time` and `Volume` events only fire on a change, and the poll is a fixed 1 Hz.
- **Information leakage**: error texts are fixed ("address", "metadata", "Id not found", ...) and never echo the Uri or Metadata; unexpected exceptions go to the
  control point as the generic 501 text, the details stay in the log. The new code logs only fixed strings and exception class names, no Uri or metadata;
  the log level is E by default, so the W-level lines with stack traces are off. (The existing `YaaccUpnpServerProtocolRequestHandler` still builds a verbose
  line with the whole SOAP body, including the Insert Uri, at level V; pre-existing, only for a user who chose V.) `Info.Uri`/`Info.Metadata` and `Read`/`ReadList`
  show the queue to every device on the LAN, as `GetPositionInfo`/`GetMediaInfo` show the track of AVTransport; the queue only holds what controllers inserted.
- **Volume/Mute/Standby**: `SetVolume`, `VolumeInc/Dec`, `SetMute` end in `SystemVolume` exactly like RenderingControl (any LAN device can already change them;
  nothing new except a second entry point). `SetStandby` and balance/fade are accepted and change nothing (the phone is not put to sleep), so there is no new
  denial of service there. `SetSourceIndex*` accepts only the one source.
- **Error codes**: `OhError` maps to 402/800-803; everything else is 501. Handlers do not wait for the player, so a Cling request thread is held only for the short
  queue work (service lock timeout 5000 ms, the same `LOCK_TIMEOUT` as the other services).
- **Threads and timeouts**: one worker thread, the player is only touched on the main thread, `OhCall` cancels calls that time out (5 s) and restores the interrupt
  flag, `sync()` recovers by discarding the player, the poll task is guarded and cancelled on shutdown, the renderer is shut down when building the services fails.
  No new permission, dependency or manifest entry; no secrets; `org.json` is a test-only change.

### Rating against the existing AVTransport surface
| Item | AVTransport today | OpenHome renderer |
|---|---|---|
| Unauthenticated control from the LAN | yes | same |
| Track address schemes | any URI (`file:`, `content:` ...) | http/https only (better) |
| Size of metadata/URI | unbounded | 64 KiB / 4096 per track (better per item), but 1000 tracks and no total (W2) |
| Amplification of stored data into one answer | none | `ReadList` duplicates (W1, new) |
| DIDL parse of untrusted text | yes, same parser | same |
| Volume / mute | RenderingControl | same effect, second entry point |
| Player kind chosen by metadata MIME | yes | same (suggestion) |
| Subscriptions / event volume | LastChange | five more services, 1 Hz `Time` (suggestion) |

### Tests
- [x] `ANDROID_HOME=/root/android-sdk ./gradlew :yaacc:testDebugUnitTest :yaacc:lintDebug` BUILD SUCCESSFUL (second run; the first run failed with
  `java.io.EOFException` in the test worker while another Gradle process was using the same build directory); 3086 tests, 0 failures, 0 errors, 0 skipped; lint 0 issues
  (`grep -c "<issue " yaacc/build/reports/lint-results-debug.xml` = 0). The tree also contains untracked Group 5 files, which compiled and passed.
- [ ] No test covers an oversized `ReadList` answer or a total-size budget of the queue (add with the fixes).

### Verdict: FAIL

## Cycle 2 — 2026-11-06
Reviewing: commit b7cb6c32 (fixes of cycle 1) and the controller side that cycle 1 did not cover: `openhome/{OhControl, OhControlException, OhXml, OhSources,
OhTrackListParser, OhRemote}`, `upnp/callback/openhome/{ClingOhControl, OhPlayableItems}`, `player/{OpenHomePlayer, OpenHomeChoice}`, the change in
`PlayerService.createPlayer` and `AbstractPlayer.adoptDeviceState`.

Threat model for the controller part: the remote device (the one the user selected, or any device that announces the OpenHome services; the app prefers
OpenHome for audio on such a device) answers every call. Its answers are untrusted. Method: reading plus probes against the compiled sources
(`javac` of `de.yaacc.openhome`, JDK 21 / Xerces; the Android DOM of libcore recurses the same way).

### Cycle 1 findings: resolved
- **W1 (ReadList amplification) resolved.** Measured: an `IdList` with 100,000 copies of one id parses to 1 id (`parseIdList` dedupes; the text looked at is cut to
  `max*11+11` = 11,011 chars, so the `split` is bounded); 243 maximum-size entries (16,384 `&` each, the worst case) give an answer of 983,915 chars (cap 1 MiB
  before escaping), 1,770,935 chars after the second escaping by Cling (factor 1.8 for `&amp;`; for tag-only entries 63,916 -> 111,928, factor 1.75); no
  wrap-around of the arithmetic (`long` sums, `int` lengths below 2^31). The check `xml.length() + one.length() > maxLength` also bounds the per-entry work.
- **W2 (no total budget) resolved.** Measured: 16 KiB metadata entries stop with 801 after 243 inserts; 100,000 insert/delete cycles leave the budget at 0
  (`delete` subtracts exactly what `insert` added, entries are immutable, `deleteAll` resets to 0); after `deleteAll` the same 244 inserts fit again. A failed
  insert changes nothing (the budget check is before every mutation, all methods `synchronized`). The id counter wraps correctly (4294967295 -> 1) and skips
  live ids. `number(null)` gives 402; `toPlayableItem` forces an audio type (the suggestion of cycle 1 is taken).

### Critical
- none

### Warning
- [OhXml.java:72-75 `text`, OhTrackListParser.java:45-47, OhSources.java:62-64] **Remote crash of the app by deeply nested XML in a device's answer
  (new, controller side).** `OhXml.text` calls `Element.getTextContent()`, which is recursive over the whole subtree. The parse accepts 4 MiB of text, so a
  `TrackList` / `SourceList` such as `<TrackList><Entry><Id><a><a><a>...` nested 10,000 levels (70 KB; 5,000 levels still pass, 10,000 / 20,000 / 50,000 / 500,000 fail) parses fine and
  then throws `StackOverflowError` (reproduced with `OhTrackListParser.parse` on a thread with a 1 MB stack, the size of the `openhome-player` worker). That is an `Error`: `OhXml.parse` (`catch Exception`),
  `OhPlayableItems`, `OpenHomePlayer.command/refresh` (`catch RuntimeException`) do not catch it, the executor thread dies with an uncaught exception and
  Android's default handler ends the whole app. It happens on the 2 s poll right after the user plays on the device, with no further action. Fix: do not
  recurse; read only the direct text and CDATA children of the element (loop over `getChildNodes()`), or parse with SAX/StAX and a depth limit (for example 8),
  and let `OhXml.parse` also catch `StackOverflowError`/`RuntimeException`. Add a test with a deeply nested `TrackList` and `SourceList` (must return an
  empty list, not throw).
- [OhRemote.java:158-180 `queue`, OhTrackListParser.java:38-52, OhXml.java:36 `MAX_LENGTH`, OhPlayableItems.java:43-46 `fromTrack`, OpenHomePlayer.java:147-160
  `rebuildItems`, OhIds.java:33 `MAX_LIST`] **Remote memory exhaustion by a device's queue (new, controller side; the renderer got limits in cycle 1, the
  controller has none).** The controller trusts the device on four numbers: the `IdArray` (up to `MAX_LIST` = 10,000 ids, although a playlist has 1000), the
  answer of each `ReadList` (up to 4 Mi chars, 50 ids asked per call), the size of each entry in it (the parser keeps any `Uri`/`Metadata` of any length, no
  per-entry or total budget, entries the controller did not ask for are accepted: a 4 MB answer carries 97,830 distinct unrequested entries or one entry with 3.9 M
  chars of metadata, measured), and the DIDL it parses (`DIDLParser.parse` of up to 4 Mi chars per track, no length check). All of it is kept: `known`
  (all entries of one `queue()` call are in the map until the final `retainAll`), then `itemsById` (a `PlayableItem` with the whole DIDL object graph per track,
  kept again), so the device can hold 200 batches x 4 Mi chars = about 800 M chars (1.6 GB as UTF-16) in the app, or ask for 4 M-char DOMs again and again.
  `OutOfMemoryError` is not a `RuntimeException`, so the effect is the same app crash as above. A device that lists 10,000 ids but answers `ReadList` with an
  empty list also makes every poll (2 s) read 200 batches again, because `ids` (the ids read) never equals the device's `IdArray` and `missing` is
  not deduped or negatively cached (network and battery amplification, also from a faulty device). Fix, as in the renderer: (1) lower `MAX_LIST` to the
  `TracksMax` the device reports, capped at about 1000 (more ids: take the first 1000 or refuse), (2) in `OhTrackListParser` (or `queue()`) keep only entries whose id was asked for,
  at most the batch size, with `Uri` <= 4096 chars and `Metadata` <= 64 Ki chars (skip the entry otherwise), plus a total budget over `known` (for example
  the 4 M chars of `OhQueue.MAX_TOTAL_LENGTH`), (3) lower `OhXml.MAX_LENGTH` to what 50 entries need (for example 1 MiB; a DOM is several times larger than the
  text), (4) dedupe `missing` and remember ids the device did not deliver for this `IdArray`, (5) catch `OutOfMemoryError`/`Error` at the top of the worker
  tasks only if you want the app to survive, but the limits are the real fix. Tests: a fake `OhControl` that returns a 3 MB entry, 100,000 ids, 10,000 ids with empty answers.

### Suggestion
- [OhIds.java:56-77 `decodeIdArray`] Still decodes before it checks the length (cycle 1 note, not done): 40 M `A` characters are decoded in 176 ms and allocate the
  30 MB array before "too long" is thrown. In the app the text has already been decoded and re-encoded by Cling's `bin.base64` datatype and exists three times.
  Check `base64.length() > MAX_LIST * 4 / 3 + 4` first (cheap; it is the documented intent of the cycle 1 remark).
- [HttpRequestSender.java:184 `createResponse`, shared by all remote calls] `EntityUtils.toByteArray` reads a response without a size limit, and the 10 s socket
  timeout is per read, so a device that sends a byte every 9 s keeps a call (and the single `openhome-player` worker thread) busy for ever, or streams
  gigabytes. This is not new (AVTransport, ContentDirectory behave the same) and the user chose the device, so it is not rated here, but the 4 Mi / 1 MiB limits of the OpenHome code
  are only effective if the sender stops reading at a limit (for example 8 MiB for SOAP responses) and has an overall deadline for `ClingOhControl` calls
  (the control point can use `RequestConfig.setResponseTimeout` plus a watchdog). `prepare()` itself is bounded (20 wake attempts x 0.5 s plus the calls).
- [OpenHomePlayer.java:77-84 poll, `post`] Every 2 s a `refresh` is queued although the previous one may still run: with a slow or hanging device the queue
  of the single worker grows (a small lambda per tick, about 40 KB/h) and user commands wait behind it. Coalesce with one pending flag (like `idArrayPending` in the
  renderer). `refresh` also needs about 10 sequential SOAP calls per tick; skipping the optional ones while `unreachable` would calm a dead device.
- [OhPlayableItems.java:54-60 `fromTrack`, OpenHomePlayer.java `rebuildItems`] The mirrored items keep the device's `Uri` as it is (`Uri.parse`, no scheme
  check; `file:`, `content:` and `javascript:` are possible) and the DIDL `albumArtURI`/MIME of the device. Today nothing fetches them: `startItem`/`loadItem` do nothing,
  `getAlbumArt()` of the base class returns null, the notification shows the title, `shareKeysOf` reads only the path. The scheme policy and the audio MIME
  type were added to the renderer only; apply `OhUriPolicy.isAcceptable` when mirroring (otherwise keep the item without URI) so that a later feature that plays or
  copies a mirrored item (play on this phone, move to another player) cannot be fed a `file:` or `content:` address by a device. The queue share lease
  (`PlayerService` `setShareLease`) now also applies to `OpenHomePlayer`: `UpnpClient.shareKeysOf` reads the proxy keys from the device-reported list, so a device can
  extend the lifetime of a share whose key it already knows; no new access, but note it in decisions.md.
- [OpenHomePlayer.java:96-103, ClingOhControl.java:112, OhControlException] The text of a SOAP fault (`invocation.getFailure().getMessage()`) comes from the device
  and goes unshortened into `YaaccLogger.w/d` (log forging by newlines, very long strings, and into the shared log). Truncate to about 200 characters and replace
  control characters before it is logged or used as exception message.
- [OhRemote.java:262-270, 296-312 `readState`; OpenHomePlayer `getCurrentPosition`] `Time.Seconds`/`Duration` from the device are taken as `long` without a range:
  `seconds * 1000L` wraps for a hostile value and gives a negative position. No crash found (`format` and the `Long.parseLong` in `updateMetadataInternal` cope), but
  clamp both to 0..(10 days) in `readState`. `OhVolume` is safe (division by zero and overflow excluded by `max <= 0` and the clamps).
- [AbstractPlayer.adoptDeviceState] The Javadoc says "player's own thread only"; the `OpenHomePlayer` worker calls it, so `currentIndex`, `paused`,
  the notification and `playOrder` are written from a background thread while the screen reads them (they are not volatile). Not a security finding, but a data race
  on state that a hostile device drives (index/list changes each poll). Consider posting the adoption to the main handler.

### What was checked and is fine
- **XML parsing of device answers**: `OhXml.parse` refuses any text containing `<!doctype` or `<!entity` (case-insensitive, so no internal entities, no external
  DTD, no billion laughs; without a DOCTYPE only the five predefined entities and character references exist), sets `disallow-doctype-decl` where the parser knows it,
  is not namespace aware, has no XInclude, reads from a `StringReader` (no encoding tricks, no network access). A 4 MB document of empty elements needs 24 MB of DOM
  on the JDK (about 6 x the text), so the cap is generous but not exploitable alone (see the warning for the sum). The SAX parser of the DIDL (`DIDLParser`) is the
  hardened Seamless one reviewed in cycle 1; a parse failure only gives an item that shows its address, a `res` without protocol info is caught by the
  `catch (Exception)` in `fromTrack`.
- **`IdArray` decoding**: strict base64 (MIME decoder, exception -> `IllegalArgumentException`), multiple of 4 bytes, no id 0, at most 10,000 ids; `OhRemote.idArray` lets the
  exception go to `refresh`, which logs only the class (an `IllegalArgumentException` is a `RuntimeException`: caught, no crash, the poll goes on).
- **Loops that depend on the device**: `prepare()` waits at most 20 x 500 ms and then goes on; batches are `missing.size()/50` (bounded by the ids, see the warning); the
  `unreachable` flag after three missing answers; the poll never spins faster than every 2 s; interruption restores the flag. `OhRemote.number` falls back on bad numbers;
  `tracksMax()` is not used for a loop.
- **What the app sends**: `OpenHomePlayer.tracks` sends only http(s) addresses accepted by `OhUriPolicy` (no `file:`/`content:` of the phone), the metadata is the
  DIDL the app generates; `ClingOhControl` rejects unknown actions/arguments before sending and converts every value through the Cling datatype. The control URL
  is taken from the device description like for AVTransport (pre-existing, no new request path).
- **Player choice**: `OpenHomeChoice.decide` is OpenHome only for audio, only when `Playlist` and `Product` are offered, and the user can switch a device back to
  AVTransport (stored per UDN in the default preferences, a bool per key). `PlayerService.createPlayer` shuts the old `OpenHomePlayer` of the device down before creating a new one.
  A device that claims OpenHome and does not behave only loses its own player (no other player, no file of the phone).

### Tests
- [x] `ANDROID_HOME=/root/android-sdk ./gradlew :yaacc:testDebugUnitTest :yaacc:lintDebug`: BUILD SUCCESSFUL; 3107 tests, 0 failures, 0 errors, 0 skipped; lint 0 issues (`grep -c "<issue " yaacc/build/reports/lint-results-debug.xml` = 0).
- [ ] No test covers a deeply nested answer, a device with 10,000 ids, an oversized entry or an unrequested entry (`OhRemoteTest.hostileTrackListsAreIgnored` covers a DOCTYPE only).

### Verdict: FAIL
