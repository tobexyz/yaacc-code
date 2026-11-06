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
