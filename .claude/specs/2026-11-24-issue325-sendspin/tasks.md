# Tasks: Sendspin support (#325)

Plan: `requirements.md` and `design.md` in this directory. Decisions: `decisions.md` (entries marked CONFIRM must be decided by the owner before the matching group starts).
Rules: spec first, tests first for the pure parts; all new strings in values + de/es/fr/nl/pt/zh; license headers with a closing ` */`; package `de.yaacc.sendspin.*` (decision CONFIRM); no Sendspin logo, no product naming (requirements, Constraints); commits as tobexyz with the issue number (#325), no trailers; a general review, then a security review after each group (sequential, max 3 cycles); no Gradle while a reviewer runs; no PR unless asked; the owner tests audio on real devices and the rig (no audio tests in the sandbox).

**Stages: P (proof of concept) = Groups 1 to 5, ends with the owner's go / reduce / stop. Phase 2 (server) = Groups 6 and 7. Phase 3 (client) = Groups 8 to 10. Phase 4 (extras) = Group 11. The owner decides at the end of each stage; nothing after Group 5 starts without it.**

## Status (2026-11-24, end of the first working session)

Done and verified (JVM tests, and the rig for the protocol parts; no listening test, no device):
- **Group 1**: protocol notes in `docs/tech.md`; crypto inventory (decisions.md); the places a receiver touches (below); the rig (`tools/sendspin-rig/`, local run verified, Docker image never built).
- **Group 2 (as production code, not a throw-away spike)**: the handshake with the real reference player (ChaChaPoly chosen by the player; the AESGCM suite is verified against the Python vectors only), hello, activate, time, state, 40 s of PCM, seek, pause, resume, volume. 2.2 (pairing PSK) is **not** done.
- **Group 3**: WebSocket (both sides), Noise `KKpsk2` with both suites, PSK ids, Sentinel fallback, identity, the codec of the PoC messages with hostile-input guards, the secure channel with fragmentation on receive. 3.4 (pairing token and store) and 3.5 (Keystore) are **not** done. 3.2: sending fragments is not needed yet.
- **Group 4, protocol part**: 4.1 `PcmStreamer`, 4.2 `HubConnection` (admission rules, activation, time, state, commands, goodbye reasons). 104 Sendspin tests (4 of them rig tests that need a running player and skip otherwise); the full suite of the app has 6809 tests, none failing.

App integration, first slice (second working session, 2026-11-24; JVM tests and resource processing and lint only, **never run on a device**):
- **4.3 partly**: the synthetic `SendspinReceiverDevice`, the hub singleton with one connection per player, players added **by address** (at most 16, stored), the receiver-list hooks in `UpnpClient`; **no mDNS discovery**, no discovery report.
- **4.4**: `SendspinPlayer` (ExoPlayer with the PCM tap into the streamer, pause = `stream/end`, seek = `stream/clear`, volume and mute are the player's) and the `PlayerService` branch (music only, one player per receiver, made only when the stream rate is known).
- **4.5 partly**: the row (`SendspinRowBinder`, `SendspinRowText`), the branches in `ReceiverRowBinder`, `RendererStatusMonitor` and `ReceiverVolumeLiveUpdater`, the settings (switch **off by default**, add and remove dialog), 35 strings in seven locales, docs and CHANGELOG. **Approval is by playing to the player**; there is no list of approvals with a revoke button yet, no gear on the card.
- Lint: 8 errors and 6 warnings, none in the new files (not compared with the baseline of develop). 6837 unit tests, none failing.
- Not done: 4.6 (the owner listens), the identity key in the Keystore (3.5, it is in the app-private preferences), the Pairing PSK (Group 5), live row updates when a player's name arrives (the list shows the address until the list is shown again), approvals list, mDNS.

Places a Sendspin receiver touches (task 1.3, read on 2026-11-24 by following FCast and Snapcast): `UpnpClient` (the receiver list at the `fcastReceivers` hooks, `findDevice` by id, the selection change), `PlayerService.createPlayer` (an `instanceof` branch, one player per receiver) and a player in `de.yaacc.player` (the shape of `SnapcastPlayer`: ExoPlayer built with `FeedTapChain` and a `PcmSink`; ExoPlayer is created lazily after the service connects, so the tap's sample rate must be known from the receiver device, which means the hub keeps a connection per known player and exposes the format), `ReceiverRowBinder` (branches in bind, bindStatus, bindPower, the transport action, `logName`), `RendererStatusMonitor` (excluded for non-UPnP receivers), `ReceiverVolumeLiveUpdater`, a row class next to `FCastRowBinder`, a settings entry with an add-by-address dialog, strings in 7 locales, and the tests that enumerate receiver kinds.

## Group 1: Checks before code (P0 prerequisites)
- [x] 1.1 Protocol notes in `docs/tech.md`, in our own words, wire version 1, spec at `2cfe197` | `docs/tech.md`
  - **Accept**: notes cover the handshake order, `client/init`/`server/init`/`noise/handshake` fields, the Noise pattern details (prologue, payloads, counters, re-handshake), PSK ids and the Sentinel constants, the fields we use of `server/hello`/`client/hello`/`server/activate`/`client/state`/`server/time`/`stream/*`/`server/command`/`client/goodbye`, binary ids and the audio chunk layout, the player role messages, the pairing PSK flow and the token encoding, unpaired access and operator approval; each item cites the spec section; resolves every (verify) marker of `design.md` that does not need the rig.
  - **Verify**: `grep -c "(verify)" .claude/specs/2026-11-24-issue325-sendspin/design.md` shrinks accordingly; nothing copied verbatim; license terms recorded.
  - **Constraints**: no code from the Apache-2.0 reference implementations is copied; compare behavior only.
- [x] 1.2 Crypto inventory: what the platform has on API 27 (X25519, ChaCha20-Poly1305, AES-GCM with 12-byte nonces, HMAC-SHA256/512, SHA-256/512) confirmed on a device or from the platform sources, and the library candidates measured (Bouncy Castle lightweight `X25519`/`ChaCha20Poly1305` only, Tink's subtle classes): version, license, age (7-day rule), added size of the debug APK and with the project's minification setting (`docs/adr/0001-no-r8-proguard-minification.md`) | `docs/tech.md`, `decisions.md`
  - **Accept**: a table of need / platform / library; a recommendation for the CONFIRM entry "Cryptography library"; the owner confirms the choice before task 2.1.
- [x] 1.3 Inventory of the places a Sendspin receiver touches (synthetic `Device`, `PlayerService.createPlayer`, `ReceiverRowBinder` and its siblings, `ReceiverVolumeLiveUpdater`, `RendererStatusMonitor`, selection and persistence of selected receivers, notification and lock-screen paths, settings, tests that enumerate receiver kinds) by reading how FCast and Snapcast hook in | `design.md` (Context, section update)
  - **Accept**: a list of the places and the change each needs; no code.
- [x] 1.4 Test rig and players: which Sendspin players and servers the owner can use (a real device or the reference command line player) and which of them accept unpaired access; a Docker rig for the reference player(s) and a reference server, images pinned by tag, null/file audio backend | `tools/sendspin-rig/` (`docker-compose.yml`, README)
  - **Accept**: the owner's answer is recorded in `decisions.md`; `docker compose config` validates; the README commands list the discovered players; local ports only; no audio files committed.
  - **Constraints**: the names and the options of the reference implementations are verified from their own repositories at this step (they were not reachable when this spec was written).

## Group 2: P0 spike, throw-away (P)
- [x] 2.1 Java program: WebSocket client, `client/init`..`server/hello`/`client/hello`/`server/activate` with the reference player under the Sentinel PSK, both cipher suites, `server/time` answers, `client/state` read | `tools/sendspin-rig/spike/` (not production code)
  - **Accept**: the run log shows the full sequence with a reference player; uses only library primitives of the choice of 1.2; keys and PSKs are generated for the spike, never committed.
  - **Verify**: run log attached to `docs/tech.md`. **Kill criterion (decision with the owner, not mine to take alone):** no interoperable Noise handshake with reasonable effort -> stop, or reduce to a mirror-only path.
- [ ] 2.2 The same under a pairing PSK from a token, then the re-handshake to a long-term PSK and a reconnect under it | same folder
  - **Accept**: log of pairing, restart of the spike, reconnect with the long-term PSK, no pairing needed the second time.
- [x] 2.3 Send audio: a generated sine as PCM chunks with server-clock timestamps and a fixed lead; the owner listens through the reference player | same folder
  - **Accept**: a steady tone for 5 minutes, no gaps; the player's own log shows no late chunks (where it reports them).

## Group 3: Foundations (P, pure Java, tests first)
- [x] 3.1 WebSocket subset (client) | `de.yaacc.sendspin.session`, tests `WsFramingTest`, `WsHandshakeTest`, `WsConnectionTest`
  - **Accept**: handshake (accept key check, no `Origin` header on outgoing upgrade), text/binary frames up to the cap, client masking, ping/pong, close with codes, protocol violations close with 1002/1009; idle and handshake timeouts; no unbounded buffers. The server side (the player role's listener) and fragmentation of large messages are Group 6 and Group 9.
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests 'de.yaacc.sendspin.session.Ws*'` green; opt-in rig test.
  - **Constraints**: CONFIRM (in-house vs library); security review focus.
- [x] 3.2 Message codec for the PoC messages (core and player role, small messages) | same package, tests with fixtures from the spec's examples
  - **Accept**: every message of the PoC round-trips; unknown `type`/fields/ids are ignored as specified; out-of-range values close the connection; size caps hold.
  - **Verify**: `SessionCodecTest`.
- [x] 3.3 Noise `KKpsk2` and PSK handling | `de.yaacc.sendspin.secure`, tests `NoiseHandshakeTest` (published vectors), `PskIdTest` (Sentinel constants from the spec), `SentinelFallbackTest`, `RehandshakeTest`
  - **Accept**: handshake with both suites matches the vectors and interoperates with the reference (rig test); a wrong PSK or identity fails silently as specified; counters reject replays; re-handshake swaps keys.
  - **Constraints**: no own curve or cipher code; keys zeroed after use where the library allows; CONFIRM (library).
- [ ] 3.4 Identity, pairing token, pairing records | `de.yaacc.sendspin.secure`, tests `PairingTokenTest` (the spec's reference vector), `PairingStoreTest`, `IdentityTest`
  - **Accept**: the token is decoded leniently and rejected strictly as specified; the server's record is `client_id` -> long-term PSK; approvals per `client_id` are discarded on a pairing; "forget all" wipes everything.
- [ ] 3.5 Key storage wrapped by the Android Keystore (AES-GCM key; the Keystore has no X25519 on API 27) with a faked wrapping interface in JVM tests | `de.yaacc.sendspin.secure`
  - **Accept**: identity keys and PSKs are never stored in the clear; backup excluded (`allowBackup` rules checked); nothing secret in logs; CONFIRM (key storage).

## Group 4: P1, play to one player, unpaired with approval (P)
- [x] 4.1 Chunking and pacing from the PCM tap: 48 kHz 16-bit stereo, 20 ms chunks, server-clock timestamps, fixed lead; `stream/start|clear|end` rules for play, seek, track jump, pause and stop | `de.yaacc.sendspin.hub`, tests with a simulated clock and a simulated player buffer
  - **Accept**: never more than the player's `buffer_capacity` ahead; track changes continue the timeline; seek and jump send `stream/clear`; pause sends `stream/end`; the first timestamp respects the player's `min_buffer_ms + output_delay_ms`. (verify: how `RealTimePacer` and the slot model of `de.yaacc.multiroom.feed` fit; if not, own pacing behind the same tap.)
  - **Verify**: `ChunkPacingTest`, `StreamLifecycleTest`.
- [x] 4.2 `HubConnection`: server-initiated connect, handshake, hello, activate, `server/time`, goodbye handling and reconnect rules (`another_server`: stop and show; `shutdown`/`restart`: retry with backoff; `concurrent_attempt`: retry later; `pairing_required`/`unpaired`: stop and ask) | `de.yaacc.sendspin.hub`, tests with a fake player peer (in-memory transport)
  - **Accept**: each goodbye reason causes its specified behavior; unapproved players stay at empty `activities`; the server declares `'playback'` only while it streams; limits for connections and memory.
  - **Verify**: `HubConnectionTest`; opt-in rig test.
- [ ] 4.3 Discovery and directory: `NsdManager` `_sendspin._tcp` plus manual `host:port`, one entry per `client_id`, a synthetic `SendspinReceiverDevice` (id `sendspin:<client_id>`), scanning only while a receiver page is open | `de.yaacc.sendspin.hub`, `de.yaacc.sendspin.discovery`
  - **Accept**: lost services are dropped after the live-update grace; manual entries survive restarts; the discovery report like the other protocols' (what is browsed, what was seen), with a *Copy report* button.
  - **Verify**: `SendspinDirectoryTest`.
- [ ] 4.4 `SendspinPlayer` (queue, play, pause, stop, next, previous, seek, volume, mute) and the `PlayerService.createPlayer` wiring | `de.yaacc.player`, tests with a fake hub
  - **Accept**: the same behaviors as the other protocol players for a queue; one player per receiver; the places of task 1.3 done.
  - **Verify**: `SendspinPlayerTest`, existing player tests green.
- [ ] 4.5 Receiver row, status text, operator approval (a list with revoke), settings (manual players, discovery switch) | `de.yaacc.browser`, `de.yaacc.settings`, strings in 7 locales
  - **Accept**: the row says available / playing / needs pairing / in use by another server / not approved, in words; "approved" and "neither paired nor approved" are always distinguishable; defaults per `decisions.md`.
  - **Verify**: `SendspinRowTextTest`, string completeness test.
- [ ] 4.6 The owner listens: music from a UPnP server through YAACC to a player for 30 minutes (and two players if available) | rig run
  - **Accept**: the owner's report in `decisions.md`.

## Group 5: P2, Pairing PSK and the PoC gate (P)
- [ ] 5.1 Pairing PSK flow on the server (token paste, key check, re-handshake, pair-init/pair-finalize, record, re-handshake, reconnect with the long-term PSK) and the credential-mismatch signal (Sentinel fallback: surface it and offer re-pairing) | `de.yaacc.sendspin.hub`, `de.yaacc.sendspin.secure`
  - **Accept**: a reference player pairs; the record persists; after a restart the player is used without pairing; a player that lost its record is shown as "re-pair".
  - **Verify**: `HubPairingTest`; rig test.
- [ ] 5.2 Dialogs: paste token with clear errors, paired and approved list with revoke | `de.yaacc.settings`, `de.yaacc.browser`
  - **Accept**: nothing sensitive in logs or in the dialog beyond what the user pasted; string completeness test.
- [ ] 5.3 Documentation of the PoC (README, `docs/index.md`, `docs/tech.md`, CHANGELOG), the trademark notice, the rig | docs
  - **Accept**: facts only ("plays to Sendspin players; pairing PSK; not affiliated"); limits stated (no groups yet, sync as heard, unpaired sessions can be impersonated on the LAN as the protocol says); no stale references.
- [ ] 5.4 **Gate**: the owner answers Q1 to Q5 with the evidence and decides go (Phase 2, Phase 3), reduce, or stop | `decisions.md`

## Group 6: Phase 2, hub with several players and groups (P1, after the gate)
- [ ] 6.1 `HubGroup` and membership rules (solo group on `client/leave`/unavailable, previous group, `group/update`, ids of 43 base64url characters) | `de.yaacc.sendspin.hub`, tests
  - **Accept**: a client is in exactly one group; no member left in an empty group; movement rules follow the spec.
- [ ] 6.2 `HubScheduler` with the spec's `buffer_capacity` accounting, a common send-ahead over a group, late joiners, format change without a gap | tests with simulated player buffers
  - **Accept**: never exceeds a member's `buffer_capacity`; late joiners get future timestamps only.
- [ ] 6.3 Controls: volume, mute, output delay commands, `GroupVolume` (the spec's algorithm: delta, clamp, redistribute, round once, one command per player), group mute | tests with the spec's tables
  - **Accept**: commands only for `supported_commands`; table tests reproduce the spec's behavior.
- [ ] 6.4 Fragmentation of large messages, the server side of the WebSocket (needed for client-initiated mode), the full codec of the core and player roles | `de.yaacc.sendspin.session`, tests with the spec's malformed sequences
  - **Accept**: the listed malformed fragment sequences close the connection; size caps hold.
- [ ] 6.5 Takeover: another server displaces a player (`another_server`): the row shows it as in use, the group continues without it | tests

## Group 7: Phase 2, server UI and gate (P2)
- [ ] 7.1 Link sheet, group slider = group volume, member menu (output delay), unlink, "Play on" a member plays on its group (MusicCast pattern) | `de.yaacc.browser`, tests `SendspinLinkCandidatesTest`, `SendspinGroupDirectoryTest`, `SendspinPlayTargetsTest`
  - **Accept**: candidates with disabled reasons (unavailable, taken by another server, not approved or paired, group full); link and unlink work against the rig and the fake hub; takeover is shown, not an error.
- [ ] 7.2 Live status of the rows (within 2 s, coalesced at 300 ms) | `de.yaacc.browser`
- [ ] 7.3 Documentation update | `README.md`, `docs/`, `CHANGELOG.md` (only what exists)
- [ ] 7.4 Gate: the owner tests Phase 2 on devices and decides whether Phase 3 starts | `decisions.md`

## Group 8: Phase 3, player engine spike (P1, needs the owner's go)
- [ ] 8.1 Check how generic the engine of `de.yaacc.multiroom.client` is (`AudioTrackSink`, `DriftController`, `FrameCorrector`, `ChunkScheduler`, `MediaCodecFlacDecoder`, the service and its policies) and what must change to take the Sendspin time filter as its time source | `design.md` (section update)
- [ ] 8.2 The Sendspin time filter (the spec's parameters) and the `client/time` burst driver | `de.yaacc.sendspin.session`, tests `TimeFilterTest`, `ClockSyncDriverTest`
  - **Accept**: converges on a simulated link with jitter and drift; discards measurements with `max_error <= 0` and a non-increasing `time_added`; bursts of at least 8 messages, a burst at least every 10 s; the two conversion functions invert each other except rounding.
- [ ] 8.3 Spike: the engine against the reference server (PCM), measure sync on the owner's devices | `de.yaacc.sendspin.speaker` (draft)
  - **Accept**: the owner hears no offset after 10 minutes; drift correction inaudible; the measured error stated (target +-1 ms in steady state; the acceptable level is set with the owner after the first numbers). **Kill criterion for Phase 3:** sync cannot be held on the owner's devices -> stop Phase 3 and record why.
- [ ] 8.4 `PlayerEndpoint` with `EndpointDriver` (in-process hub or remote) and the spec's admission rules (activity ranking, last-playback server tie-break, displacement and rejection goodbyes, pairing attempt protection, provisional timeout, caps) | tests `PlayerAdmissionTest`, `EndpointDriverTest`

## Group 9: Phase 3, listener and service (P1)
- [ ] 9.1 Network listener (WebSocket server, Noise responder, hello/activate, pairing PSK as the client, unpaired setting, token display data, mDNS registration, `Origin` rejected) | `de.yaacc.sendspin.speaker`
  - **Accept**: the reference server and Music Assistant connect, pair with the displayed token and play; limits hold under a flood test; advertised only while enabled; stops cleanly.
  - **Verify**: `PlayerListenerTest` (fake peer), rig test; security review focus.
- [ ] 9.2 Foreground service and locks | `de.yaacc.player`, `AndroidManifest.xml`
  - **Accept**: survives screen off; locks only while a stream plays; notification with stop; lint clean; owner's device test.

## Group 10: Phase 3, client UI and gate (P2)
- [ ] 10.1 Settings and screen: "use this phone as a Sendspin player" (off by default), name, pairing token (copy), unpaired toggle with a warning, paired servers with remove, latency offset | `de.yaacc.settings`, strings in 7 locales
- [ ] 10.2 The phone's own speaker as a member of the hub's group (in-process) and the takeover display | `de.yaacc.browser`
- [ ] 10.3 Documentation for Phase 3 (requirements and limits: sync, doze, security; the opt-in listener) | `README.md`, `docs/`, `CHANGELOG.md`
- [ ] 10.4 Gate: the owner decides about Phase 4 | `decisions.md`

## Group 11: Extras (Phase 4, each item needs its own owner go)
- [ ] 11.1 Code-based pairing (dynamic and static codes) with CPace | `de.yaacc.sendspin.secure`
  - **Accept**: the draft's test vectors pass; interoperates with the reference; **kill criterion**: no confidence in correctness -> do not ship (Pairing PSK only).
- [ ] 11.2 Controller, metadata and artwork roles (the phone as a controller of other servers, now playing in the player UI)
- [ ] 11.3 FLAC encoding on the hub, Opus, the `source` role
- [ ] 11.4 Client-initiated connection mode of the hub (advertise `_sendspin-server._tcp`, accept inbound sockets) if not already done in Group 6
  - **Accept (all)**: tests first for pure parts, general then security review, documentation update.
