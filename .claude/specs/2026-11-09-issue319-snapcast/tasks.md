# Tasks: Snapcast support (#319)

Plan: `.claude/specs/2026-11-09-issue319-snapcast/requirements.md` and `design.md`. Decisions: `decisions.md`.
Rules: spec first, tests first for the pure parts; all new strings in values + de/es/fr/nl/pt/zh; license headers with a closing ` */`; neutral package `de.yaacc.multiroom`; commits as tobexyz with `(#319)`; reviewer then security-reviewer after each group (sequential, max 3 cycles); no Gradle while a reviewer runs; no PR unless asked; the owner tests on real devices and the rig (no audio tests in the sandbox).

**Phase A (Groups 1-6): source + controls. Phase B (Groups 7-10): the phone as a client; starts only after the owner's gate at the end of Group 6.**

## Group 1: Research and rig (P0), no production code
Status 2026-11-10: gates passed (review cycle 3 PASS, security review cycle 2 PASS). The owner ran the rig and the feed spike on 2026-11-10 (tasks 1.2 and 1.5 done; the kill criterion was not triggered). Settled by the owner's second round: the client-log check (gap in the idle series) and the `process://` probe (refused, sandbox directory). Still open: the raw `AddStream` answer shape (the parser accepts both) and the remaining **rig** items in `docs/tech.md`.
- [x] 1.1 (done 2026-11-10, `docs/tech.md` section "Snapcast (issue #319)"; items marked **rig** still need the rig) Verify protocol and control behavior from the Snapcast sources and the rig and write it to `docs/tech.md` (own words, version 0.35.0) | `docs/tech.md`
  - **Accept**: notes cover JSON-RPC framing and error codes, the `Server.GetStatus` shape, every notification we use, the `Stream.AddStream` tcp URI (bind address, port choice, `codec`, `sampleformat`, raw PCM vs WAV header, which security limits apply), `Group.SetClients` semantics, mDNS service names and TXT, server identity fields, ServerSettings/Time/WireChunk layouts for Phase B; each item has a source (file/line in the Snapcast repo) or "verified on the rig".
  - **Verify**: a grep shows every `(verify)` marker of `design.md` resolved in `docs/tech.md`; nothing copied verbatim.
  - **Constraints**: Snapcast is GPL v3: no code copied; list the license.
- [x] 1.2 (built and run by the owner on 2026-11-10: snapserver and two snapclients up, the spike ran against it; `docker compose config` and the image pins were exercised by that run) Docker rig | `tools/snapcast-rig/` (`docker-compose.yml`, `snapserver.conf`, README)
  - **Accept**: `docker compose up` starts one snapserver (control 1705, stream 1704, a pipe source) and two snapclients with a null/file audio backend; the README says how to see the streams and how the owner connects a real client.
  - **Verify**: `docker compose config` validates; (owner or CI) `Server.GetStatus` over `nc localhost 1705` lists two clients.
  - **Constraints**: images pinned by tag (no `latest`); only local ports; non-interactive; never commit audio files.
- [x] 1.3 (done 2026-11-10: derived from docs and sources, see the README there; real captures follow from the rig) Fixtures | `yaacc/src/test/resources/snapcast/` (`status_*.json`, notifications, errors)
  - **Accept**: `Server.GetStatus` with 0/1/3 groups, a disconnected client, a stream list, each notification we use, an AddStream error, an unauthorized error; names/IPs/MACs scrubbed (192.0.2.x).
  - **Verify**: a grep for real MAC/IP patterns finds nothing.
- [x] 1.4 (done 2026-11-10: GO for the synthetic device, see design.md "Inventory result"; the owner's review of the go/no-go is still pending) Inventory: where a receiver must be a Cling `Device` | `.claude/specs/2026-11-09-issue319-snapcast/design.md` (section update)
  - **Accept**: a list of every place that uses a receiver's services or registry (`UpnpClient.getDevice`, `RendererStatus`, `OpenHomeChoice`, `ShareAddressResolver`, `DeviceSessionGrouper`, volume, `DeviceSheetAdapter`, pick controllers) with the change needed for a synthetic device; a go/no-go for "synthetic device" vs "`ReceiverTarget` seam".
  - **Verify**: reviewed by the owner before Group 3 starts.
- [x] 1.5 (owner's run 2026-11-10, results in `docs/tech.md` "Rig run and feed spike": paced 300 s OK, 30/30 `playing`, 0 late chunks; burst lead 39 s. The client-log check passed on a fresh 60 s run, see tech.md) Spike (throw-away, plain Java, not committed to `main` sources): feed a tone as raw PCM into a tcp source created by `Stream.AddStream` on the rig | `tools/snapcast-rig/spike/`
  - **Accept**: the stream shows `playing`, the rig clients log chunks, no underruns for 5 minutes at real-time pacing; a burst write is shown to misbehave (documents why pacing is needed).
  - **Verify**: run output attached to `docs/tech.md`; **kill criterion**: if AddStream is refused or the feed cannot be kept steady, stop and decide with the owner (static source fallback).

## Group 2: Control library (P1, pure Java, tests first)
- [x] 2.1 Models and parsing: `SnapcastServerStatus`, `SnapcastGroup`, `SnapcastClient`, `SnapcastStream`, volume, errors | `yaacc/src/main/java/de/yaacc/multiroom/control/`, tests `SnapcastModelTest`
  - **Accept**: all fixtures parse; unknown fields are ignored; missing optional fields give defaults; no exception escapes on malformed JSON (a typed error instead).
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests 'de.yaacc.multiroom.control.*'` green.
  - **Constraints (security)**: the limits of design.md "Security rules" apply; tests with hostile input (nesting 10 000 deep, a 2 MiB line, 100 000-element batch, huge strings, out-of-range numbers) must pass without an `Error` or unbounded memory.
- [x] 2.2 `SnapcastRpc` (ndjson over TCP, ids, pending map, 10 s call deadline and 5 s read timeout, notification listeners on a serial executor, reconnect with backoff) | same package, tests with a loopback fake server (the literal `127.0.0.1`, see `decisions.md`)
  - **Accept**: request/response match by id; a notification between two responses is delivered; a lost connection fails pending calls and reconnects; line length capped (1 MiB) and a flood does not grow memory; listeners are called off the socket thread.
  - **Verify**: `SnapcastRpcTest` green, including the cap and the reconnect cases.
  - **Constraints (security)**: byte-level line cap, overall deadline per call, depth pre-scan before `org.json`, rate-limited re-read, reconnect ceiling (design.md "Security rules").
- [x] 2.3 `SnapcastModelUpdater`: patch the model from notifications, re-read on doubt | same package
  - **Accept**: each notification of the fixtures produces the same state as a fresh `Server.GetStatus`; an unknown notification triggers a re-read.
  - **Verify**: `SnapcastModelUpdaterTest`.
- [x] 2.4 Operations: `SnapcastOperations` (volume, mute, latency, name, group stream, group clients, group volume with the relative algorithm, add/remove stream) | same package
  - **Accept**: the calls and parameters equal the documented JSON; the group volume keeps relative levels and clamps at 0-100 (table tests like the MusicCast group volume).
  - **Verify**: `SnapcastOperationsTest`.
  - **Constraints**: Phase A rig test (opt-in, `-Dsnapcast.rig=localhost`, forwarded to the test JVM by `yaacc/build.gradle`): changes made through the library are seen by a second connection. (Written; the run waits for the owner's rig.)

## Fix Group 2: Address review cycle 1 of Group 2 (findings W1 to W6 in `review.md`, "Cycle 1 (Group 2)")
- [x] F2.1 Ordering of a status answer and notifications (W1) | `de.yaacc.multiroom.control` (`SnapcastRpc`, `SnapcastModelUpdater`)
  - **Accept**: a `Server.GetStatus` (or `Group.SetClients`) answer followed directly by a notification always ends in the newer state; lines are applied in the order received.
  - **Verify**: a new test reproduces the reviewer's probe (answer with volume 70, `Client.OnVolumeChanged` 74 right behind it) for at least 1000 rounds without a stale result.
- [x] F2.2 JSON pre-scan matches Android's `org.json` literal rule (W2) | `SnapcastJson`
  - **Accept**: a `"` outside a string opens a string only directly after `{`, `[`, `,` or `:` (whitespace allowed); otherwise the line is rejected as malformed; the two bypass lines of the review are rejected.
  - **Verify**: scan-level tests with `[a",` + 100 000 x `[` + `"]` and `[a",[],[],...,b"]` (300 000 elements).
- [x] F2.3 Unknown notifications are not queued; the notification queue is bounded by bytes as well (W3) | `SnapcastRpc`
  - **Accept**: params of methods outside `SnapcastModelUpdater.isKnown` are dropped at the reader (only a "re-read needed" flag is set); the queue has a byte budget (default 4 MiB) and overflow sets the flag instead of growing.
  - **Verify**: a test with a blocked listener and 1 MB notifications shows bounded memory (queue byte counter) and a re-read request.
- [x] F2.4 A silent peer is detected (W4) | `SnapcastRpc`
  - **Accept**: a call that times out with nothing received since its request was written drops the connection and starts the reconnect cycle; a forced reconnect is possible from CONNECTED.
  - **Verify**: tests for both; the old test that asserted "stays CONNECTED" is changed accordingly.
- [x] F2.5 The rig switch works as documented (W5) | `yaacc/build.gradle` (forward the system property `snapcast.rig` to the test JVM), `SnapcastRigTest`
  - **Accept**: `-Dsnapcast.rig=<host>` on the Gradle command line reaches the test; without it the test is skipped; the Javadoc, the rig README and task 2.4 name the same switch.
  - **Verify**: with `-Dsnapcast.rig=127.0.0.1` and no rig the rig tests are attempted (they fail or are aborted by a connection error, not skipped); without the property 3 are skipped.
  - **Constraints**: no new dependency; do not change dependency versions.
- [x] F2.6 Test: a server that stops reading (W6) | `SnapcastRpcTest`
  - **Accept**: callers return at their deadline and the connection is dropped when the peer does not read.
  - **Verify**: the new test passes without real sleeps longer than 2 s.
- [x] F2.7 Suggestions taken: local model patch after our own calls (the server does not notify the caller's connection), `setGroupVolume` takes start levels and current levels separately, peer address accessor on `SnapcastRpc` for Group 4, null/empty checks in `setGroupClients`, no address text in exception causes or `toString()`, remove unused code and test leftovers; record the Group 2 choices in `decisions.md`.
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --offline --tests 'de.yaacc.multiroom.*'` green; full unit tests and lint without new issues.

## Fix Group 2b: Address review cycle 2 of Group 2 (finding W7 in `review.md`, "Cycle 2 (Group 2)")
- [x] F2.8 Lifecycle events of `reconnect()` / `sessionEnded()` keep their order (W7) | `SnapcastRpc`
  - **Accept**: `onDisconnected` (and in `sessionEnded` also `onGaveUp`) is enqueued before the next attempt can enqueue `onConnected`; events alternate disconnected, connected; after the last `reconnect()` the last event is "connected".
  - **Verify**: a test calling `reconnect()` several hundred times against a loopback server asserts the alternation and the last event (the reviewer's probe delivered `[C, D]` in 14 of 2000 rounds on the old code).
- [x] F2.9 Suggestions of cycle 2: Javadoc for Group 3 (dedicated listener executor, `callOrdered` and every `SnapcastOperations` change or `getStatus` throws `IllegalStateException` inside a delivery, the re-read requester runs on the listener thread and must hand off to a worker, read a fresh status on `onConnected` and repeat a failed re-read, `reconnect()`/`retry()` reset the failure count, one slow call of 10 s without other traffic drops the connection); a rejected drain on an external executor fails ordered callers as CLOSED at once; drop the params of `Stream.OnProperties` at the reader; a test that one notification is always accepted when the byte budget is smaller than the line.
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --offline --tests 'de.yaacc.multiroom.*' --rerun` green 3 times; full gate and lint without new issues.

## Fix Group 2c: Address the security review of Group 2 (W1 in `security-review.md`, "Cycle 1 (Group 2)")
- [x] F2.10 Server error text is display text: the title of a server error is printable ASCII, at most 64 characters; the data has control characters, line and paragraph separators, bidi controls and the C1 range replaced; `describe()` Javadoc says "error row, never a log" (W1) | `SnapcastJson.displayText/errorTitle`, `SnapcastMessage.parseError`, `SnapcastException`
  - **Verify**: `SnapcastModelTest.serverErrorTextCarriesNoControlCharactersAndTheMessageStaysShort` (failed before the fix).
- [x] F2.11 Suggestions taken: `SnapcastRpc.Config` refuses limits below 1 (S3, a read timeout of 0 would mean infinite); the `addStream` Javadoc says any scheme may be accepted, callers build only tcp sources, and the control connection has no TLS and no authentication (S9). Not taken, recorded for later (owner: the group named; the rest are accepted residual risks of a LAN-only control channel): S1 (byte budget counts line bytes, not the parsed tree), S2 (no rate budget for valid lines), S4 (host name resolution without a timeout in `SocketTransport`: Group 3 resolves the address itself and passes an `InetAddress`), S5 to S8, S10 (a differential test with a ported Android tokener), S11 (Group 4 must not log the peer address and must take the address and the feed connection in one step).
  - **Verify**: `SnapcastRpcTest.aLimitOfZeroOrLessIsRefusedByTheConfig`; full gate 3618 tests, 0 failures, lint unchanged.

## Group 3: Discovery and the receiver model (P1)
Status 2026-11-10: built, full gate green (3772 tests, 0 failures, lint unchanged); review gates pending. Not verifiable in the sandbox: `SnapcastNsd` (NsdManager, multicast lock) and the 2 s row update on the rig; both wait for the owner's device.
- [x] 3.1 `SnapcastDiscovery` (NsdManager for `_snapcast-ctrl._tcp`, manual servers from preferences, merge by server key) with a testable pure core | `de.yaacc.multiroom.discovery`, tests for the merge/key logic
  - **Accept**: two sightings of one server give one entry; a lost service is removed after the live-update grace; manual servers survive restarts; nothing is scanned outside the receivers page lifetime.
  - **Verify**: `SnapcastDirectoryTest`.
  - **Constraints**: multicast lock only while needed; no extra permission beyond what NsdManager needs.
  - **Constraints (security)**: only loopback/private/link-local/unique-local addresses, at most 16 servers, lazy connect, strict manual entry validation; names and ids from servers are display text only.
- [x] 3.2 `SnapcastGroupDevice` + `SnapcastDirectory` integrated into `UpnpClient` lists/lookups as decided in 1.4 | `de.yaacc.multiroom`, `de.yaacc.upnp.UpnpClient`
  - **Accept**: a group appears in `getDevicesProvidingAvTransportService()` and resolves by `getDevice(id)`; it is never in Cling's registry (never advertised); a group that vanishes is dropped from the list and from stored receiver selections without a crash.
  - **Verify**: `SnapcastGroupDeviceTest`, `UpnpClientReceiversTest` (new), existing receiver tests unchanged.
- [x] 3.3 (unit part done; the 2 s check on the rig is the owner's) Live updates: `SnapcastLiveSource` for `ReceiverLiveUpdates` (control notifications, coalesced 300 ms, lifetime like MusicCast) | `de.yaacc.browser` / `de.yaacc.multiroom`
  - **Accept**: rows follow server changes from a second controller within 2 s on the rig; stops when the page is hidden.
  - **Verify**: `ReceiverLiveUpdatesSnapcastTest` (fake target/source).

## Fix Group 3: Address review cycle 1 of Group 3 (W1 to W3 in `review.md`, "Cycle 1 (Group 3)")
- [x] F3.1 Guard until Group 4: `PlayerService.createPlayer` returns null with a toast "not available yet" for a `SnapcastGroupDevice` (W1); one string in 7 locales; a test. Group 4 replaces the guard with the real branch.
- [x] F3.2 A stored selection of a Snapcast group survives a cold start (W2): keep an id whose server is still listed (manual servers; discovered servers that are listed) but has no status yet (`SnapcastGroupSource.isPending(id)` or equivalent, used by `getReceiverDevices()` so the id is not removed), wake the connection on page start; for a discovered-only server remember the endpoint of a server that holds a selected group in the preferences (plain endpoint, no credentials) so it is known after a restart, or document the loss. Test: stored selection, no status yet, id kept; status arrives, group listed; server removed, id dropped.
- [x] F3.3 Stable device identity across refreshes (W3): one `SnapcastGroupDevice` per id is reused while name, offline flag, stream and client count are unchanged, so `DeviceDiffCallback.areItemsTheSame` and `DeviceSheetAdapter` see the same item. Test: repeated snapshots give identical instances; a change gives a new one.
- [x] F3.4 Suggestions: reject leading zeros in IPv4 parts of manual entries (`010.0.0.1`); `SnapcastDisplayText` must not cut a surrogate pair; `groups()` stops building after 64 devices; `SnapcastSessionTest.sessionThreadsAlive` checks only threads of its own session; take the narrow races of the review (tick after reconnect, directory snapshot taken outside the lock, `SnapcastNsd` stop right after start, old resolve clearing a new queue's entry) if they can be fixed with a test; record the ones not taken in `decisions.md`.

## Fix Group 3b: Address review cycle 2 of Group 3 (W4, W5 in `review.md`, "Cycle 2 (Group 3)")
- [x] F3.5 Flaky test (W4): `SnapcastSessionTest.aFailedReadIsRepeated` awaits `getLastErrorKind() == null` instead of asserting right after the status appears; check the other assertions of that class that follow a status for the same pattern; run the class 10 times under CPU load.
- [x] F3.6 A pending Snapcast id must not cost three looks at every play start (W5): `UpnpClient.countMissingReceivers` / `awaitReceiverDevices` count a pending Snapcast id as missing only for the first look, or only while its link has not failed (choose the simpler rule and record it in decisions.md); a user who selected only an offline Snapcast group gets a clear "no receiver available" instead of a 5 s wait with SSDP searches. Test with a fake source and the policy's sleeper (no real sleeps).
- [x] F3.7 Suggestions of cycle 2 that are cheap: one toast per `createPlayer` batch instead of one per group; the `setRememberedServers` Javadoc matches the code (`makeRoomLocked(false)` may evict a lost entry); `SnapcastNsd.start` calls `discoveryRestarted()` before returning early when the NSD service is null; a test of the `createPlayer` wiring if it can be done without a device (mock `UpnpClient`); record the rest in decisions.md.

## Group 4: Feed and the player (P1, spike first)
Carried over from the security review of Group 3 (`security-review.md` S17 to S26): S25 key players, leases and preferences by an opaque handle, never by the receiver id, and never log `getPeerAddress`; the feed host is the control connection's peer address only. For Group 5: S19 the discovery switch (task 5.2) must ship with discovery, or the hub defaults to manual servers only, because discovery, the multicast lock and connections to every announced private server start for every user who opens the receivers tab; S20 show the endpoint of the server in the group row or sheet and strip invisible/format characters from names, so a hostile server cannot imitate a legitimate group; S23 "remove server" also clears the remembered flag; S18 optionally refuse ports below 1024 for discovered services.
- [ ] 4.1 Spike on a device: Media3 tap -> PCM at the stream format -> `PcmFeeder` -> rig | `de.yaacc.multiroom.feed` (draft)
  - **Accept**: 30 minutes of music from a UPnP server through YAACC audible on both rig clients, screen off, track changes without a gap longer than the server buffer; owner confirms by ear.
  - **Verify**: owner's report; **kill criterion**: if the tap cannot be kept steady (underruns, drift) the spec stops here for Phase A and goes back to the owner.
- [ ] 4.2 `PcmFeeder` + `RealTimePacer` (pure pacing math, injectable clock) | `de.yaacc.multiroom.feed`, tests
  - **Accept**: never more than the allowed look-ahead; after a stall it does not burst (drops/pads silence by rule); format conversion (rate, channels, 16 bit) is bit-exact where no conversion is needed.
  - **Verify**: `RealTimePacerTest`, `PcmFormatConverterTest`.
  - **Constraints (security)**: the feed connects only to the control connection's peer address and the port YAACC requested; never to a host or port from a server reply.
- [ ] 4.3 `SnapcastStreams` (ensure/add, SetStream with restore, idle cleanup, name marker, crash recovery of leftover YAACC streams) | `de.yaacc.multiroom.feed`, tests with the fake RPC server
  - **Accept**: no second stream for one phone; the previous group stream is restored on stop; only streams carrying the YAACC marker are ever removed; an AddStream error gives a typed result for the UI (static source hint).
  - **Verify**: `SnapcastStreamsTest`.
  - **Constraints (security)**: random per-install name marker, percent-encoded name (`a&mode=client` test), remove/restore only what YAACC recorded and only while still on its stream.
- [ ] 4.4 `SnapcastPlayer` (queue, play/pause/stop/next/previous/seek, state reporting to the existing player UI, volume = group volume) and `PlayerService.createPlayer` wiring | `de.yaacc.player`
  - **Accept**: the same behaviors as the local music player for a queue, plus the buffer delay noted in the UI; one player per group; sharing/Play-on flow picks a Snapcast group like any receiver.
  - **Verify**: `SnapcastPlayerTest` (fake feeder), existing player tests green.

## Group 5: UI (P2)
- [ ] 5.1 Group rows and live status in the receivers section / sheet; members with volume, mute, latency, name; stream picker; add/remove client | `de.yaacc.browser`, layouts, `values*/strings.xml`
  - **Constraints (security)**: names, host names, ids and stream URIs from a server are display text only: control characters, line separators and bidi controls are replaced before they are shown (the library truncates but does not filter them; `SnapcastJson.displayText` is the helper), and they are never used as identity or logged (security review of Group 2, S14).
  - **Accept**: a group shows name, stream, client count, volume; a member's slider moves the server; "stream unavailable"/"server unreachable" rows are explained; unreachable servers are marked, not removed immediately.
  - **Verify**: `SnapcastRowTextTest`, `SnapcastMemberRowsTest`, resource tests (`ServerRootResourcesTest` style); screenshots checked by the owner.
- [ ] 5.2 Settings: Snapcast servers (manual add/remove, discovery on/off), explanation of the server requirement | `de.yaacc.settings`, strings in 7 locales
  - **Accept**: a manual address is validated (host[:port]); no credentials stored.
  - **Verify**: `SnapcastServerListTest`, string completeness test.

## Group 6: Documentation and Phase A gate (P2)
- [ ] 6.1 Update documentation | `README.md`, `docs/index.md`, `docs/tech.md`, `CHANGELOG.md`, `fastlane/metadata/android/en-US/` (only if released)
  - **Accept**: features, requirements (a snapserver, how to enable the TCP source or let YAACC add it), the buffer delay, the not-affiliated notice, the Docker rig; no stale references.
  - **Verify**: `grep -r 'TODO\|FIXME\|PLACEHOLDER' README.md docs/ || true` shows nothing plan-related.
  - **Constraints**: do not document Phase B features before they exist.
- [ ] 6.2 Gate: owner tests Phase A on devices, decides whether Phase B starts | `decisions.md`

## Group 7 (Phase B): Binary protocol and time sync (P1, pure Java, tests first)
- [ ] 7.1 Message codec: Base, Hello, ServerSettings, CodecHeader, WireChunk, Time, ClientInfo, Error (little endian) | `de.yaacc.multiroom.protocol`, tests with byte fixtures
  - **Accept**: round trip of every message; truncated/oversized messages give a typed error; payload size caps (e.g. 8 MiB) hold; unknown types are skipped by size.
  - **Verify**: `SnapcastMessageCodecTest`.
- [ ] 7.2 `SnapcastConnection` (TCP 1704: Hello, settings, header, chunks, time sync loop, reconnect) | same package, loopback fake server tests, opt-in rig test
  - **Accept**: against the rig a Java client receives chunks for 10 minutes and its offset converges (jitter reported); no chunk is played before the codec header; server loss is reported once.
  - **Verify**: `SnapcastConnectionTest`; rig test output.
- [ ] 7.3 `ClockOffsetFilter` and `ChunkScheduler` (pure) | same package
  - **Accept**: a simulated asymmetric/jittery network keeps the offset within a stated bound; late chunks are dropped, early chunks wait.
  - **Verify**: `ClockOffsetFilterTest`, `ChunkSchedulerTest`.

## Group 8 (Phase B): Audio engine (P1, spike first)
- [ ] 8.1 Spike: `AudioTrack` engine playing rig chunks (PCM, then FLAC via `MediaCodec`) in sync with a Raspberry Pi/rig client | `de.yaacc.multiroom.client` (draft)
  - **Accept**: owner hears no offset against another client after 10 minutes; drift correction is inaudible.
  - **Verify**: owner's report; **kill criterion**: if sync cannot be held on the owner's devices, stop Phase B and record why.
- [ ] 8.2 `SnapcastClientEngine` with latency setting (manual per device), buffer handling, foreground service/wake and Wi-Fi lock only while playing | `de.yaacc.multiroom.client`, `AndroidManifest.xml` (service)
  - **Accept**: survives screen off; stops cleanly; no wake lock when idle.
  - **Verify**: `SnapcastClientEngineTest` (pure parts), lint clean.

## Group 9 (Phase B): Renderer integration (P2)
- [ ] 9.1 Output "Snapcast group" for the YAACC renderer: the renderer feeds the group and the phone plays as a member; renderer volume/mute map to the client volume; the direct local output is off | `de.yaacc.upnp.server`, `de.yaacc.player`
  - **Accept**: a UPnP controller playing to the YAACC renderer is heard on the group in sync, including on the phone; volume from the controller moves the phone's client volume on the server.
  - **Verify**: `SnapcastRendererOutputTest` (pure mapping), owner's device test.

## Group 10 (Phase B): Documentation
- [ ] 10.1 Update documentation for Phase B | `README.md`, `docs/`, `CHANGELOG.md`
  - **Accept**: no stale references; requirements and limits (sync, doze) described.
  - **Verify**: the placeholder grep as in 6.1.
