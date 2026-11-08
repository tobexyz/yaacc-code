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

## Cycle 3 — 2026-11-06
Reviewing: commit f901bd41 (fixes for the two cycle 2 Warnings and the cycle 2 Suggestions), OpenHome controller side.

### Critical
- none

### Warning
- [OhXml.java:46-58 `parse`, OhTrackListParser.java:40, OhSources.java:53] **Cycle 2 Warning 1 (a deeply nested answer) is not resolved on the real
  runtime.** The fix removes the recursive `getTextContent()`, but the DOM is still built by `DocumentBuilderFactory.newInstance()`, and on Android that
  is `org.apache.harmony.xml.parsers.DocumentBuilderImpl`, whose `parse(KXmlParser, DocumentImpl, Node, int)` calls itself for every child element
  ("Recursive descent", lines 296 and 322 in the AOSP libcore source, checked in the LineageOS mirror `lineage-20.0`; there is no depth check). The JDK parser used by the unit
  tests builds the DOM iteratively, so the tests cannot show it. A nested answer needs 7 bytes per level (`<a></a>`), so the 2 MiB limit allows about 290,000 levels, and a few
  thousand levels fit in 20 KB and are enough for the 1 MB stack of a non-main Android thread (this is derived from the source, not run on a device). `StackOverflowError` is an `Error`: `OhXml.parse` catches
  `Exception` only, `OpenHomePlayer.refresh`/`command` catch `RuntimeException` only, the `openhome-player` thread has no uncaught handler, so the default Android handler ends the app process.
  Reachable with one `ReadList` (or `SourceXml`) answer from any device that claims OpenHome; the app crash is the same effect as in cycle 2.
  Fix (do both, the first is the real one): (1) before building the DOM reject text whose element nesting is deeper than the formats need (a track list is 3 levels, a source list 3;
  a limit of 16-32 and a simple counter over `<`, `</` and `/>` is enough, no parser involved), (2) catch `StackOverflowError` in `OhXml.parse` and return null. Test: a *well-formed* 100,000-level document
  (the existing `aDeeplyNestedAnswerIsHarmless` closes none of its `<a>` tags, so the JDK parser rejects it as malformed and the test passes without proving anything) and one just over the depth limit,
  asserting `OhXml.parse` returns null whatever parser runs. Probe on the JDK: a well-formed 290,000-level, 2 MB answer parses and `text()` is fine (the part that was fixed works).
- [OhRemote.java:179-188 `queue`, OpenHomePlayer.java:166-172 `refresh`] **Cycle 2 Warning 2 is fixed for memory, not for traffic.** Memory is bounded: ids cut to 1000, only asked-for entries,
  Uri <= 4096, Metadata <= 16384, 2 MiB per answer, the cache stops at 4 M characters. But an entry that does not fit the 4 M budget is neither cached nor put in `unavailable`
  (`asked.remove(entry.getId())` runs in both cases), so it is asked for again on every poll; and because `OpenHomePlayer.ids` holds only the cached ids, `newIds.equals(ids)` is false for ever and `queue()` runs on
  every poll. Probe (fake `OhControl`, 1000 ids, each entry 4096 + 16000 characters, which the limits allow): 199 entries cached, then **17 `ReadList` calls per poll, about 16 MB
  per 2 s poll, for ever**, and the single worker is busy all the time so user commands wait behind it. A hostile device gets a sustained download out of the phone (mobile data, battery);
  a real library with about 1000 tracks and more than 4 KB of DIDL per track hits the same loop without any attack. Fix: add the ids that were read but not cached to `unavailable` too (or stop reading
  once `cachedLength` has reached the limit), and compare in `refresh` with the ids the device reported rather than with the ids that could be shown. Test: the 1000-entry device above, three `queue()` calls, assert `ReadList` calls on call 2 and 3 are 0.

### Suggestion
- [ClingOhControl.java:111-113, OpenHomePlayer.java:126] The fault text is only cleaned for the debug log; `new OhControlException(code, failure[0])` still carries the raw device text and
  `OpenHomePlayer.command` logs `e.getMessage()` at warn level, so the cycle 2 log forging/length remark is half done. Call `clean(...)` where the exception is built.
- [OhRemote.java:219-225 `insert`] When the device returns an id that is already in `known`, `known.put` replaces the entry but `cachedLength` is only increased; repeated answers inflate the counter until
  the cache accepts nothing and the traffic loop of Warning 2 starts by itself. Subtract the replaced entry (or skip `put` for a known id).
- [OhRemote.java:159-164] A queue of more than 1000 tracks is cut to the first 1000 without a message to the user; the player then shows a shorter queue than the device plays. Not a security matter, worth a log line.
- Carried over from cycle 2, unchanged and not rated: `HttpRequestSender.createResponse` reads a response without a size limit or overall deadline (the limits above only apply after it has been read); the mirrored items keep
  the device's `Uri` without `OhUriPolicy.isAcceptable` (nothing fetches them today); queue share lease for `OpenHomePlayer` to be noted in decisions.md.

### What was checked and is fine
- `OhXml.text` reads own text nodes only (JDK probe: 290,000 levels, 2 MB, one entry, no error); `parse` still refuses DOCTYPE/ENTITY and text over 2 MiB.
- `OhIds.decodeIdArray` checks the length before decoding (limit 80,064 characters); invalid arrays become `OhControlException(INVALID_ARGS)`; `idArray()` cuts to 1000; duplicates collapse in `wantedNow`.
- `OhTrackListParser.parse(xml, wanted)`: unrequested, duplicate, empty-Uri, over-long Uri/Metadata entries are dropped, the loop stops at `wanted.size()` accepted entries; the DOM cost of rejected entries is bounded by the 2 MiB.
- Cache budget accounting is correct for `queue`, `deleteId`, `deleteAll`; `unavailable` is bounded by `retainAll(wantedNow)`; `clampSeconds` (0..8,640,000) keeps `* 1000L` far from overflow and all users are `long`.
- `OpenHomePlayer` poll coalescing (`AtomicBoolean`, reset in `finally`) stops the pile-up of lambdas; `AbstractPlayer` index/state fields are volatile; `OhPlayableItems` skips DIDL parsing over 16 Ki characters.

### Tests
- [x] `ANDROID_HOME=/root/android-sdk ./gradlew :yaacc:testDebugUnitTest :yaacc:lintDebug`: BUILD SUCCESSFUL; 3112 tests, 0 failures, 0 errors, 0 skipped; lint 0 issues.
- [ ] No test would fail on the two Warnings above (the nesting test uses an unclosed document; no test polls an over-budget queue more than once).

### Verdict: FAIL

## Cycle 4 — 2026-11-08
Reviewing: Groups 1-7 as a whole, with the fixes for the two cycle 3 Warnings (commit 1e5eef74) and everything after cycle 3 (commits f901bd41..b2adf6c3: OpenHome controller
UI and settings sheet, reverse sync of the local player's edits into the queue, Radio facade, standby, OpenHome services on the root device, files picked on the phone,
drag to reorder, album art, device list log). General review cycle 7 is PASS. Method: source read of the diff and of the vendored Cling classes used on the path; a probe of
`OhXml.tooDeep`/`parse` compiled on its own with the JDK (not part of the repo, nothing in the repository was changed apart from this file); Gradle not run (orchestrator: green, 3198 tests, lint 0).

Threat model as before: any LAN host can call every OpenHome action without authentication (like AVTransport today); the controller side talks to a device the user chose, which may be hostile.

### Cycle 3 Warnings

1. **Nesting depth / unclosed document (OhXml): FIXED for every text the OpenHome code parses.** `OhXml.parse` (OhXml.java:45-69) now counts the element depth on the text
   before any parser sees it (`tooDeep`, OhXml.java:74, limit 16, skips comments, CDATA, processing instructions, `>` inside quoted attribute values, self-closing tags), refuses
   DOCTYPE/ENTITY and text over 2 MiB first, and catches `StackOverflowError` as a second line. It sits in front of both users (`OhTrackListParser`, `OhSources`). Probe
   (JDK, compiled from the current OhXml.java): well-formed 100,000 levels -> null; 16 levels -> document; 17 levels -> null; `>` in an attribute, tags inside comments/CDATA, 5,000
   self-closing tags -> not counted; 2 MB track list -> 49 ms; 500,000 `<!--` and 300,000 `<a x="` (quote tricks) -> 22 / 47 ms, so the counter is linear and cannot be used to burn CPU.
   Constructed attempts to under-count (stray quotes, unmatched end tags, odd comments) only lead past the first point where the Android parser itself throws, so nothing deeper is built.
   `OhXmlTest` now has the well-formed 100,000-level case (cycle 3 asked for it). One residual that is NOT part of the OhXml fix is listed as Suggestion 1 (the SOAP envelope).
2. **Polling an over-budget queue repeatedly (OhRemote / OpenHomePlayer): FIXED.** An entry that does not fit the 4 M budget now stays in `asked` and goes to `unavailable`
   (OhRemote.java:213-222), `unavailable` is cut to the current ids (`retainAll`) and is cleared by `deleteAll`, so the 1000-entry device of the cycle 3 probe gets its ReadLists once and none on
   the following `queue()` calls. `OpenHomePlayer.refresh` compares the ids the device reported with the ones it reported last time (`deviceIds`, OpenHomePlayer.java:194-196), not with the shown
   ones, so `queue()` is not called on every poll; if `queue()` throws, `deviceIds` is not advanced and the next poll tries again (one attempt per 2 s poll, bounded). A device that changes its
   ids on every poll still costs up to 20 ReadLists per poll; that needs the device to be hostile and only moves bytes the phone asked for, the same class as the unbounded read below (Suggestion 3).
   The cycle 3 Suggestions are also done: `insert` subtracts a replaced entry (OhRemote.java:255-258), the fault text is cleaned where the exception is built (ClingOhControl.java:132).
   Test: `OhRemoteTest.entriesThatDoNotFitTheCacheAreNotReadAgainOnEveryPoll` (1000 entries x 16,000 chars, ReadList count identical on call 2 and 3). No test covers the `deviceIds` comparison of the player itself (see Tests).

### Critical
- none

### Warning
- none

### Suggestion
1. [ClingOhControl.java:116 `invoke`, vendored `org/fourthline/cling/transport/impl/SOAPActionProcessorImpl.java:123-150` `readBody`] **Residual of cycle 3 Warning 1 one layer up (pre-existing, app-wide, not rated).**
   The SOAP envelope of every action answer (and request) is built with the platform `DocumentBuilderFactory` with no depth, size or DOCTYPE limit; the full body is also logged at verbose level.
   On Android that parser builds the DOM recursively (derived from the AOSP source as in cycle 3, not run on a device), so a hostile device can nest 10-20 KB of `<a>` inside the envelope instead of inside
   `TrackList`; the `StackOverflowError` leaves `ClingOhControl.invoke` (`readBody` catches `Exception` only), `OpenHomePlayer.refresh`/`command` catch `RuntimeException` only, and the process ends.
   The server side is better off: `YaaccUpnpServerProtocolRequestHandler.handle` catches `Throwable` and answers 500, so a nested request from a LAN host does not kill the renderer (request bodies are buffered
   without a limit there, pre-existing). It is rated as not new because AVTransport/ContentDirectory/RenderingControl answers of any device go through the same code in released versions, and #301
   adds no new parse path at this level. Cheap contained fix for #301: in `ClingOhControl.invoke` wrap `ActionCallback.run()` in `catch (StackOverflowError)` and throw `OhControlException(NO_ANSWER, ...)`;
   proper fix for the whole app: `OhXml.tooDeep` (or a SAX pre-scan) in `SOAPActionProcessorImpl.readBody` plus `disallow-doctype-decl`.
2. [OpenHomePlayer.java:720-742 `getIcon`, util/image/ImageDownloader.java:73-110, BitmapSampling.readAllBytes:50, AndroidOpenHomeHost.java:62-90] **Album art addresses.** Controller side is good:
   `getAlbumArt` takes the DIDL address of the device only if `OhUriPolicy.isAcceptable` (http/https with a host, no user info), so `file:`/`content:`/`jar:` from a device are never fetched
   (`anAlbumArtAddressThatIsNotHttpIsNotUsed`). What the fetch does with an http(s) address is the shared downloader, unchanged: `URL.getContent()` with no connect/read timeout, `readAllBytes` with no size limit
   (an `OutOfMemoryError` is not caught by `catch (Exception)` there), and `getIcon` starts a new download on the fixed content pool (default 10 threads, also used for thumbnails) on every call while the image is not
   cached - there is no in-flight or negative cache - so an art address that blackholes can occupy pool threads over time as the notification is rebuilt. The same pattern is in `AVTransportPlayer.getIcon`.
   Fix in the shared code: timeouts (10 s) and a size cap (for example 8 MiB) in `getUriAsStream`/`readAllBytes`, remember failed addresses for a while. On the renderer side the `albumArtURI` of a control point's
   DIDL is still not filtered (`toPlayableItem`; open since cycle 1, the phone shows or notifies it, nothing is sent back): apply the same `OhUriPolicy` there.
3. [HttpRequestSender.java:184 `createResponse`] Carried over unchanged from cycle 2/3: a response is read without size limit or overall deadline, so the OpenHome limits (2 MiB per answer text, 4 M cache) apply after
   the data is in memory. Same for the SOAP request body on the server side. Not rated (shared, pre-existing); a limit of 8-16 MiB in the shared sender and server consumer would make all the OpenHome limits effective.
4. [UpnpClient.java:546 `logDevicesOnce`] The friendly name and device type of every device in the registry are written to the log at info level without cleaning or length limit (log forging with line
   breaks, long strings). The default level is error, so it only matters when the user raises it. Use a cleaner like the one in `ClingOhControl.clean`/`MiniPlayerPresenter.cleanText` and cut to about 100 characters.
5. [OpenHomeRenderer.java:503-512 `takeOverFromPlayer`] The reorder loop assumes the player's list holds each item once. An item object that appears twice in the player's list gives `inQueue.get(i)` an
   `IndexOutOfBoundsException`; it is caught in `adoptPlayerEdits` and ends in `discardPlayer()`, so playback stops once, no exposure. Skip duplicates when building `order`. Only the local user can cause it.
6. Carried over from cycle 2/3, unchanged: no rate limit or cap on pending tasks of the renderer worker and no cap on GENA subscribers (now six OpenHome services incl. Radio, same as the other services);
   mirrored items of the controller keep the device's `Uri` without `OhUriPolicy` (nothing fetches or plays them; album art is gated, see 2); queue share lease for `OpenHomePlayer` to be noted in decisions.md.
   A queue of more than 1000 tracks is shown cut to 1000 without a message.

### What was checked and is fine
- **Renderer, new surface since cycle 3.** `Radio` (OhServiceDefs.radio, OhRendererCore.radioHandlers): every input action has a handler (no path into `standard()` that writes a state variable), `SetChannel` validates
  the address (`OhUriPolicy`, http/https, 4096) and the metadata length before it replaces anything, `SetId`/`Read`/`ReadList` only know the one channel, `ReadList` is bounded by `parseIdList(..., 1000)` and escaped by `OhEscape`;
  `SetChannel` has the same authority as `Playlist.DeleteAll` + `Insert`, no new capability. `SetStandby(true)` only stops playback (same as `Stop`), the queue stays; `Play`/`SeekId`/`SeekIndex` wake it.
  `OhQueue.move` checks both ids (800), keeps the id and the budget, and `id == afterId` is a no-op. Action argument validation unchanged and complete: `number()` -> 402, ranges for seek/volume, ui4 by Cling's datatypes.
- **Exposure on the root device.** The OpenHome services are added only when the renderer is enabled (`serverEnabled && rendererEnabled`, and in the nested layout only when `rendererEnabled`), once on the root and no longer on the
  embedded renderer (no duplicate exposure); services that fail to build leave the renderer without OpenHome instead of failing it. Nothing is offered when the renderer setting is off.
- **Reverse sync (`adoptPlayerEdits`/`takeOverFromPlayer`, `Host.queueUriOf/queueMetadataOf`, `AndroidOpenHomeHost`).** Runs on the one renderer worker; compares the player with what the renderer put in by identity, so an unsynced
  edit of a control point is not undone and not deleted (step 1 only deletes ids the renderer had put into the player). Items the user adds go through the same limits as a control point's `Insert`: `queueUriOf` is
  `OhUriPolicy.isAcceptable` (items with `file:`/`content:` addresses never enter the LAN-visible queue and leave the player again), `queue.insert` enforces 4096/16384/1000/4 M and a refusal removes the item from the player;
  a deleted track is not an insert anchor. A failure falls back to `discardPlayer()` (the player restarts from the queue), the queue is never left half-edited in a way a control point can exploit. Cost of the reorder loop
  is O(n^2) with n <= 1000, only after an edit. Tokens/IdArray event go out once (`queueEditedByPlayer` uses the delayed, coalesced event).
- **Controller fixes.** `ClingOhControl.codeOf` maps transport failures to `NO_ANSWER` (so the `unreachable` logic and the wake/prepare path are not fooled by a connection error, and a device cannot make a refusal look like a
  transport error: its fault code is only used for `moveTrack`'s 401 fallback and `PLAYLIST_FULL`); `OpenHomePlayer.command` runs `always` on every path (pending-edit counter cannot get stuck, a dropped post runs it too);
  `moveInPlayOrder` only reorders what the device reports and uses ids from the last read; `feedFiles` indexes `fileQueue` only through `fileIndexById` values the app assigned, so a device that reports any `Id` cannot
  cause an out-of-range access and can only advance the queue of files it was given for it; files of this phone are shared just in time (current and next) and `tracks()` drops everything that is not http(s).
- **Untrusted device data on the controller.** Source names, friendly names and fault texts shown with `setText` and `cleanText` (no HTML); `OpenHomeSettingsSheet` uses only indexes of the parsed list to switch; positions/durations clamped
  (0..8,640,000 s); volume through `OhVolume` clamps; `idArray` decodes with a length check first and cuts to 1000; `ReadList` answers keep only asked ids, dedupe, and the limits above; the cache budget and the `unavailable` set are bounded.
  Cover art embedded in a played file (`showEmbeddedArt`) is decoded with bounds first and sampled to 512 px on the content pool, `RuntimeException | OutOfMemoryError` caught.
- Renderer GENA events: `Info.Uri/Metadata` and `Radio.Uri/Metadata` are bounded by the queue limits; the events carry what the control points could read with `Read` anyway.
- Secrets: none in the diff; no new permissions, exported components or intents (`getNotificationIntent` uses the same immutable PendingIntent as before); no new dependency.

### Tests
- [x] Not run by me (instruction). Orchestrator: HEAD green, 3198 tests, lint 0. Probe of OhXml on the JDK run as described above (outside the repo).
- [x] Both cycle 3 Warnings have a test that would fail without the fix (`OhXmlTest.textsDeeperThanTheLimitAreRefusedWithoutBuildingADocument` with a well-formed 100,000-level text; `OhRemoteTest.entriesThatDoNotFitTheCacheAreNotReadAgainOnEveryPoll`).
- [ ] No test covers the player-level compare with `deviceIds` (a poll of a device whose queue is over the budget calls `queue()` once), a nested SOAP envelope, or a duplicated item in `takeOverFromPlayer`. Suggested, not blocking.

### Verdict: PASS

## Cycle 5 — 2026-11-08
Reviewing: commits ade16c7d (size, time and count limits for everything the network sends: the six suggestions of cycle 4) and 776af0ed (start and end of the renderer are never dropped, limits for images and album art relaxed), with decisions.md
(last three entries). Only this change set. Gradle not run; two things were verified outside the repo with a probe (httpclient5 5.3.1 / httpcore5 5.3.6 from the Gradle cache, program in the scratchpad).

### Critical
None.

### Warning
1. [ReceivingSubscribe.java:171-175 (new cap), :146 and :178 (duration taken as requested), cling `LocalGENASubscription.setSubscriptionDuration`, `ExpirationDetails.hasExpired`] **The subscription cap can be filled for good by any host of the network.**
   The requested `TIMEOUT` of a SUBSCRIBE or renewal is taken over without a limit: `Second-infinite` is `Integer.MAX_VALUE` (about 68 years) and `Second-0` is `UNLIMITED_AGE`, which `hasExpired` never reports as expired, so the
   maintenance in `LocalItems` never removes such a subscription. The only other way out is `EventDeliveryHealth` (3 failed deliveries in a row), and a subscriber whose callback answers 200, or which simply sees no event because the device
   is idle, stays. So about 30 SUBSCRIBE requests to one service (100 for all services) with `Second-infinite` and a callback that answers make every other control point get 503 for new subscriptions to that service (or to all) until the
   app is restarted; control points that need events (Linn Kazoo, BubbleUPnP, Home Assistant for OpenHome state) then show no state. Before the change the same host could only exhaust memory and traffic, so the cap is the right
   idea, but without a bound on the duration it turns a transient attack into a durable and very cheap lock-out of legitimate controllers, which is the scenario this review was asked to look for.
   Fix (small): clamp the requested timeout in `processNewSubscription` and `processRenewal` to a range such as 60..1800 s (`Second-0`, `infinite`, missing and larger values give 1800), so a hostile subscriber has to renew every 30 minutes and
   stays visible; optionally add a per-remote-address share (for example 10 subscriptions per `getInputMessage().getRemoteAddress()` / callback host) next to the global 100 and the 30 per service. A test for the clamp is a one-liner on `Second-infinite`.
2. [HttpRequestSender.java:170-190 `readBounded` in `createResponse`, called from `send` :101] **The size limit protects the memory, the 120 s deadline and the "stop at the limit" do not work for a body that keeps coming.**
   `readBounded` throws out of a try-with-resources on the entity stream. In httpclient5 5.3.1 `ResponseEntityProxy.streamClosed` calls `wrapped.close()` ("assumes that closing the stream will consume the remainder"), and httpcore5
   5.3.6 `ChunkedInputStream.close()` / `ContentLengthInputStream.close()` read and discard the rest of the body. So when the limit or the deadline is hit, the exception is only delivered after the rest of the answer has been drained.
   Probe: a server that sends an endless-style chunked body for 8 s, limit 1 MiB, deadline 2 s: `execute` threw "too large" after 8179 ms (that is, when the server stopped). With a body that never ends (or one byte every few seconds, under the 10 s socket
   timeout), the caller thread, and one of the 25 connections of the shared `PoolingHttpClientConnectionManager` (5 per route), is held for ever. The same `send` is used for descriptor retrieval of every device that is announced
   (`RetrieveRemoteDescriptors`), for all SOAP actions (`SendingAction`), and for the delivery of events to subscribers (`SendingEvent`): a hostile host that announces a device, or subscribes with up to 30 callbacks on different ports and
   answers the event POST with an endless chunked body, pins the pool until nobody can browse or control. Only an honest `Content-Length` above the limit is refused before streaming (that path does not drain). Memory is safe (the
   main point of cycle 4 item 3), threads and connections are not, and the decision text "read up to 32 MiB and 120 s" is not what happens.
   Fix (a few lines): do not close the entity stream on the failure path. Read without try-with-resources, close it only after a complete read, and on the limit / deadline exception abort the request (`request.abort()` / `HttpUriRequestBase.cancel()`)
   or just let the exception leave `execute`, whose `response.close()` discards the connection without draining. A test that serves a never-ending chunked body from a local server and asserts that `send` returns within the deadline plus
   one socket timeout.

### Suggestion
1. [AndroidOhThreads.java:60-69 `work`, BoundedWork.java:33] The cap of 2000 waiting tasks keeps the memory in bounds but is shared by all senders: a host that fills it makes the commands of real control points disappear with a 200 answer (the SOAP
   answer is sent before the worker runs). Before, they were delayed for minutes instead, so this is no new exposure (UPnP has no authentication; the same host can send Stop), and the dropped `Play`/`Pause` is repaired by the next action; the
   renderer's own `refreshNow` poll is not dropped (`every` is not counted). Optionally count per remote address, or give `Stop` a reserved slot. Not blocking.
2. [ClingOhControl.java:155, HttpRequestSender.MAX_RESPONSE_BYTES = 32 MiB] A 32 MiB SOAP answer is parsed by the DOM parser of Cling; an answer of tiny nested elements is far larger as a tree than as text, and `runGuarded` only catches
   `StackOverflowError`, not `OutOfMemoryError` (the image downloader does). A hostile device could still end the app by memory with an answer that is within the limit. Catch `OutOfMemoryError` next to `StackOverflowError` (NO_ANSWER), and
   use a lower limit (4-8 MiB) for everything that is not a ContentDirectory action. The nested-answer part of the old suggestion is closed.
3. [ImageDownloader.java:86 and :155-170] The cap of 64 MiB means up to about three times that in memory while reading (`ByteArrayOutputStream` growth plus `toByteArray`), 10 parallel pool threads for thumbnails; the `OutOfMemoryError` catch
   helps only for the thread that fails first. There is no total deadline (30 s read timeout per read, so a drip feed holds a pool thread; accepted in decisions.md). Suggest 16 MiB for thumbnails and album art (`retrieveImageWithCertainSize`
   with 512 px), the full size only for the viewer, and a deadline in `readAllBytes`.
4. [BrowseContentItemAdapter.java:774, :481 area, AVTransportPlayerWrapper.java:481] Still unfiltered: the `albumArtURI` of a ContentDirectory item or of an AVTransport renderer goes to the downloader with any scheme (`file:`, `jar:` read from this phone
   and shown as a thumbnail only; `http://127.0.0.1:...` is a blind GET). Use `AlbumArt.of` there too. Pre-existing, accepted.
5. [ReceivingSubscribe.java:171-175] Check and add are not atomic (accepted in decisions.md); the overshoot is bounded by the number of concurrent handler threads, so it is harmless.
6. [UpnpClient.java:333, 346, 360, 374, 387, 406] `getDisplayString()` of remote devices is still logged raw (verbose level only; the cleaned `logDevicesOnce` is info). `LogText.clean` removes ASCII control characters only (not U+0085, U+2028/2029); fine for logcat.
7. [OhPlayableItems.java:57-58] A device track whose address `OhUriPolicy` refuses (host with underscore such as `nas_01`, user info) now gets a null `Uri` in the mirror. Players only send `isSendable` items, `FileDownloader` and the share action in
   `BrowseContentItemAdapter` call `getUri().toString()` but are not reached from the OpenHome mirror; if a "share" or "download" action is ever added to the OpenHome queue screen, it must check for null.
8. [BoundedEntityConsumer.java:36-42] The body limit throws from `data()`; there is no overall deadline for a slow request body (the reactor's idle timeout applies), and a 413 answer is not sent (accepted in decisions.md).

### What was checked and is fine
- **Nested SOAP answer -> NO_ANSWER** (`ClingOhControl.runGuarded`): wraps the whole `ActionCallback.run()` (send, read, parse); the exception type is `OhControlException.NO_ANSWER`, so `unreachable` handling is unchanged; the other callers of Cling
  actions in the app are not covered (old remark, see Suggestion 2).
- **Image downloader**: http(s) uses `URLConnection` with 5 s connect / 30 s read timeouts and `readAllBytes(in, 64 MiB)` (exception before the buffer grows past the limit, `long` arithmetic, no overflow); the stream is closed in try-with-resources
  (Android's HttpURLConnection discards the rest); `OutOfMemoryError` and `Exception` are caught in the same try; other schemes keep the old `getContent()` behaviour. `OpenHomePlayer.artMayBeFetched`: synchronized, 32 addresses of at most 4096
  characters (`OhUriPolicy`) are kept, one attempt per address and minute; a device that cycles through more than 32 addresses only gets a retry earlier, no unbounded growth.
- **HttpRequestSender.readBounded**: refuses an honest `Content-Length` over the limit before reading, counts the decoded bytes (so a gzip bomb is cut at 32 MiB), `long` arithmetic, returns null for no entity as `EntityUtils` did (see Warning 2 for the close).
- **BoundedEntityConsumer** in both server request handlers (`YaaccUpnpServerProtocolRequestHandler`, `YaaccUpnpServerContentHttpHandler`): counts the bytes of the body that reach `data()`, chunked and with `Content-Length`; over 2 MiB it throws and the
  connection is closed by the reactor (the handlers' `Throwable` -> 500 path still applies to what gets through); a real SOAP / SUBSCRIBE request is a few KB. No other consumer of request bodies was left.
- **LogText.clean**: null-safe, cuts to 200 before replacing `\p{Cntrl}`, used for fault texts, image errors, the device list; the device list still prints the registry content at info only.
- **albumArtURI**: `AlbumArt.of` returns only http/https URIs (null scheme and empty are null); the OpenHome controller side (`getAlbumArt`) is stricter (`OhUriPolicy`); `AVTransportPlayer` and `LocalMediaSessionPlayer` use `AlbumArt.of`.
- **Duplicate item in `takeOverFromPlayer`**: identity set, the repeat is added to `refused` and removed from the player in descending index order, an unremovable one falls back to `discardPlayer()`; `order` has no duplicates, so the
  `IndexOutOfBounds` of cycle 4 cannot occur.
- **Lifecycle bypass** (`AndroidOhThreads.lifecycle`): called only from `OpenHomeRenderer.start()/shutdown()` (app side, not reachable from the network), not counted, so it cannot unbalance `BoundedWork`; a rejected execute after `close()` is logged
  and ignored. `work` pairs every `tryAcquire` with exactly one `done()` (finally in the task, or the `RejectedExecutionException` path); `BoundedWork` is a CAS loop, correct. The default `lifecycle` in the interface falls back to `work`. The cap of 2000 is above a
  full load (1000); the log line is rate limited (every hundredth drop).
- **Subscription counting**: `RegistryImpl.getLocalSubscriptionCount` is synchronized like the other registry methods and counts by identity of the `LocalService`; renewals are not counted again (separate branch before the check); refused
  subscriptions get 503 and are not registered; `Registry` interface change is compile-checked (HEAD is green).
- **OhPlayableItems / address policy**: the DIDL of the device is kept, but the mirror's `Uri` is only set for addresses `OhUriPolicy` accepts; sending back goes through `isSendable` as before.
- No secrets, no new permissions or exported components, no new dependency in the two commits; `CHANGELOG.md` and decisions.md edits only.

### Tests
- [x] Not run by me (instruction). Orchestrator: HEAD green, 3223 tests, lint 0.
- [x] New tests exist for: nested answer (`ClingOhControlTest`), bounded reads (`HttpRequestSenderBoundedReadTest`, `BitmapSamplingReadTest`), `BoundedEntityConsumerTest`, `ReceivingSubscribeLimitTest` (mocks the count, so it does not show that a duration is bounded),
  `BoundedWorkTest`, lifecycle under flood (`OpenHomeRendererTest`), `LogTextTest`, `AlbumArtTest`, `OhPlayableItemsTest`, duplicate (`OpenHomeRendererTest`).
- [ ] `HttpRequestSenderBoundedReadTest` reads from an in-memory entity; it does not run through httpclient5's streams, so it cannot show Warning 2 (the probe did). No test covers a `Second-infinite` / `Second-0` SUBSCRIBE (Warning 1).

### Verdict: FAIL
Two Warnings (1: cap can be held for good because the subscription timeout is unbounded; 2: `readBounded` drains the rest of the body when it closes the stream, so the deadline and the stop at the limit do not release the thread / connection). Both fixes are a few
lines. The six suggestions are otherwise closed as described; no Critical.
