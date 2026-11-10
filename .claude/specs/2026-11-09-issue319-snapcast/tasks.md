# Tasks: Snapcast support (#319)

Plan: `.claude/specs/2026-11-09-issue319-snapcast/requirements.md` and `design.md`. Decisions: `decisions.md`.
Rules: spec first, tests first for the pure parts; all new strings in values + de/es/fr/nl/pt/zh; license headers with a closing ` */`; neutral package `de.yaacc.multiroom`; commits as tobexyz with `(#319)`; reviewer then security-reviewer after each group (sequential, max 3 cycles); no Gradle while a reviewer runs; no PR unless asked; the owner tests on real devices and the rig (no audio tests in the sandbox).

**Phase A (Groups 1-6): source + controls. Phase B (Groups 7-10): the phone as a client; starts only after the owner's gate at the end of Group 6.**

## Group 1: Research and rig (P0), no production code
- [x] 1.1 (done 2026-11-10, `docs/tech.md` section "Snapcast (issue #319)"; items marked **rig** still need the rig) Verify protocol and control behavior from the Snapcast sources and the rig and write it to `docs/tech.md` (own words, version 0.35.0) | `docs/tech.md`
  - **Accept**: notes cover JSON-RPC framing and error codes, the `Server.GetStatus` shape, every notification we use, the `Stream.AddStream` tcp URI (bind address, port choice, `codec`, `sampleformat`, raw PCM vs WAV header, which security limits apply), `Group.SetClients` semantics, mDNS service names and TXT, server identity fields, ServerSettings/Time/WireChunk layouts for Phase B; each item has a source (file/line in the Snapcast repo) or "verified on the rig".
  - **Verify**: a grep shows every `(verify)` marker of `design.md` resolved in `docs/tech.md`; nothing copied verbatim.
  - **Constraints**: Snapcast is GPL v3: no code copied; list the license.
- [ ] 1.2 (drafted 2026-11-10, **not built or run**: no Docker in the sandbox, the owner cannot test at the moment) Docker rig | `tools/snapcast-rig/` (`docker-compose.yml`, `snapserver.conf`, README)
  - **Accept**: `docker compose up` starts one snapserver (control 1705, stream 1704, a pipe source) and two snapclients with a null/file audio backend; the README says how to see the streams and how the owner connects a real client.
  - **Verify**: `docker compose config` validates; (owner or CI) `Server.GetStatus` over `nc localhost 1705` lists two clients.
  - **Constraints**: images pinned by tag (no `latest`); only local ports; non-interactive; never commit audio files.
- [x] 1.3 (done 2026-11-10: derived from docs and sources, see the README there; real captures follow from the rig) Fixtures | `yaacc/src/test/resources/snapcast/` (`status_*.json`, notifications, errors)
  - **Accept**: `Server.GetStatus` with 0/1/3 groups, a disconnected client, a stream list, each notification we use, an AddStream error, an unauthorized error; names/IPs/MACs scrubbed (192.0.2.x).
  - **Verify**: a grep for real MAC/IP patterns finds nothing.
- [x] 1.4 (done 2026-11-10: GO for the synthetic device, see design.md "Inventory result"; the owner's review of the go/no-go is still pending) Inventory: where a receiver must be a Cling `Device` | `.claude/specs/2026-11-09-issue319-snapcast/design.md` (section update)
  - **Accept**: a list of every place that uses a receiver's services or registry (`UpnpClient.getDevice`, `RendererStatus`, `OpenHomeChoice`, `ShareAddressResolver`, `DeviceSessionGrouper`, volume, `DeviceSheetAdapter`, pick controllers) with the change needed for a synthetic device; a go/no-go for "synthetic device" vs "`ReceiverTarget` seam".
  - **Verify**: reviewed by the owner before Group 3 starts.
- [ ] 1.5 (spike program drafted as `tools/snapcast-rig/spike/FeedSpike.java`, only run against a fake server; **waits for the owner's rig run**) Spike (throw-away, plain Java, not committed to `main` sources): feed a tone as raw PCM into a tcp source created by `Stream.AddStream` on the rig | `tools/snapcast-rig/spike/`
  - **Accept**: the stream shows `playing`, the rig clients log chunks, no underruns for 5 minutes at real-time pacing; a burst write is shown to misbehave (documents why pacing is needed).
  - **Verify**: run output attached to `docs/tech.md`; **kill criterion**: if AddStream is refused or the feed cannot be kept steady, stop and decide with the owner (static source fallback).

## Group 2: Control library (P1, pure Java, tests first)
- [ ] 2.1 Models and parsing: `SnapcastServerStatus`, `SnapcastGroup`, `SnapcastClient`, `SnapcastStream`, volume, errors | `yaacc/src/main/java/de/yaacc/multiroom/control/`, tests `SnapcastModelTest`
  - **Accept**: all fixtures parse; unknown fields are ignored; missing optional fields give defaults; no exception escapes on malformed JSON (a typed error instead).
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests 'de.yaacc.multiroom.control.*'` green.
  - **Constraints (security)**: the limits of design.md "Security rules" apply; tests with hostile input (nesting 10 000 deep, a 2 MiB line, 100 000-element batch, huge strings, out-of-range numbers) must pass without an `Error` or unbounded memory.
- [ ] 2.2 `SnapcastRpc` (ndjson over TCP, ids, pending map, 5 s timeout, notification listeners, reconnect with backoff, serial worker) | same package, tests with a loopback fake server (`setCanonicalHostName("localhost")` as in the existing fake servers)
  - **Accept**: request/response match by id; a notification between two responses is delivered; a lost connection fails pending calls and reconnects; line length capped (1 MiB) and a flood does not grow memory; listeners are called off the socket thread.
  - **Verify**: `SnapcastRpcTest` green, including the cap and the reconnect cases.
  - **Constraints (security)**: byte-level line cap, overall deadline per call, depth pre-scan before `org.json`, rate-limited re-read, reconnect ceiling (design.md "Security rules").
- [ ] 2.3 `SnapcastModelUpdater`: patch the model from notifications, re-read on doubt | same package
  - **Accept**: each notification of the fixtures produces the same state as a fresh `Server.GetStatus`; an unknown notification triggers a re-read.
  - **Verify**: `SnapcastModelUpdaterTest`.
- [ ] 2.4 Operations: `SnapcastOperations` (volume, mute, latency, name, group stream, group clients, group volume with the relative algorithm, add/remove stream) | same package
  - **Accept**: the calls and parameters equal the documented JSON; the group volume keeps relative levels and clamps at 0-100 (table tests like the MusicCast group volume).
  - **Verify**: `SnapcastOperationsTest`.
  - **Constraints**: Phase A rig test (opt-in, `-Dsnapcast.rig=localhost`): changes made through the library are seen by a second connection.

## Group 3: Discovery and the receiver model (P1)
- [ ] 3.1 `SnapcastDiscovery` (NsdManager for `_snapcast-ctrl._tcp`, manual servers from preferences, merge by server key) with a testable pure core | `de.yaacc.multiroom.discovery`, tests for the merge/key logic
  - **Accept**: two sightings of one server give one entry; a lost service is removed after the live-update grace; manual servers survive restarts; nothing is scanned outside the receivers page lifetime.
  - **Verify**: `SnapcastDirectoryTest`.
  - **Constraints**: multicast lock only while needed; no extra permission beyond what NsdManager needs.
  - **Constraints (security)**: only loopback/private/link-local/unique-local addresses, at most 16 servers, lazy connect, strict manual entry validation; names and ids from servers are display text only.
- [ ] 3.2 `SnapcastGroupDevice` + `SnapcastDirectory` integrated into `UpnpClient` lists/lookups as decided in 1.4 | `de.yaacc.multiroom`, `de.yaacc.upnp.UpnpClient`
  - **Accept**: a group appears in `getDevicesProvidingAvTransportService()` and resolves by `getDevice(id)`; it is never in Cling's registry (never advertised); a group that vanishes is dropped from the list and from stored receiver selections without a crash.
  - **Verify**: `SnapcastGroupDeviceTest`, `UpnpClientReceiversTest` (new), existing receiver tests unchanged.
- [ ] 3.3 Live updates: `SnapcastLiveSource` for `ReceiverLiveUpdates` (control notifications, coalesced 300 ms, lifetime like MusicCast) | `de.yaacc.browser` / `de.yaacc.multiroom`
  - **Accept**: rows follow server changes from a second controller within 2 s on the rig; stops when the page is hidden.
  - **Verify**: `ReceiverLiveUpdatesSnapcastTest` (fake target/source).

## Group 4: Feed and the player (P1, spike first)
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
