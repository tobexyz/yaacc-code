# Tasks: Sendspin support (#325)

Plan: `requirements.md`, `design.md` and `usecases.md` and `controller.md` (the controller client: plan K1 to K8) (what YAACC can be in a Sendspin network and which use cases need which role) in this directory. Decisions: `decisions.md` (entries marked CONFIRM must be decided by the owner before the matching group starts).
Rules: spec first, tests first for the pure parts; all new strings in values + de/es/fr/nl/pt/zh; license headers with a closing ` */`; package `de.yaacc.sendspin.*` (decision CONFIRM); no Sendspin logo, no product naming (requirements, Constraints); commits as tobexyz with the issue number (#325), no trailers; a general review, then a security review after each group (sequential, max 3 cycles); no Gradle while a reviewer runs; no PR unless asked; the owner tests audio on real devices and the rig (no audio tests in the sandbox).

**Stages: P (proof of concept) = Groups 1 to 5, ends with the owner's go / reduce / stop. Phase 2 (server) = Groups 6 and 7. Phase 3 (client) = Groups 8 to 10. Phase 4 (extras) = Group 11. The owner decides at the end of each stage; nothing after Group 5 starts without it.**

## Status (2026-11-24, end of the first working session)

Done and verified (JVM tests, and the rig for the protocol parts; no listening test, no device):
- **Group 1**: protocol notes in `docs/tech.md`; crypto inventory (decisions.md); the places a receiver touches (below); the rig (`tools/sendspin-rig/`, local run verified, Docker image never built).
- **Group 2 (as production code, not a throw-away spike)**: the handshake with the real reference player (ChaChaPoly chosen by the player; the AESGCM suite is verified against the Python vectors only), hello, activate, time, state, 40 s of PCM, seek, pause, resume, volume. 2.2 (pairing PSK) is **not** done.
- **Group 3**: WebSocket (both sides), Noise `KKpsk2` with both suites, PSK ids, Sentinel fallback, identity, the codec of the PoC messages with hostile-input guards, the secure channel with fragmentation on receive. 3.4 (pairing token and store) and 3.5 (Keystore) are **not** done. 3.2: sending fragments is not needed yet.
- **Group 4, protocol part**: 4.1 `PcmStreamer`, 4.2 `HubConnection` (admission rules, activation, time, state, commands, goodbye reasons). 104 Sendspin tests (4 of them rig tests that need a running player and skip otherwise); the full suite of the app has 6809 tests, none failing.

App integration, first slice (second working session, 2026-11-24; JVM tests and resource processing and lint only, **never run on a device**):
- **4.3 (third working session: mDNS added)**: the synthetic `SendspinReceiverDevice`, the hub singleton with one connection per player, players added **by address** (at most 16, stored), players found by **mDNS** `_sendspin._tcp` (`SendspinNsd`, `SendspinDirectory`, started by the discovery gate while a receivers page is visible, a switch on by default that works only while Sendspin is on), selected players remembered across a restart, a live source that refreshes the list, the receiver-list hooks in `UpnpClient`. The announcement format was checked against the reference player; the `NsdManager` part itself has **never run** (it can not on the JVM). **No discovery report** like FCast's.
- **4.4**: `SendspinPlayer` (ExoPlayer with the PCM tap into the streamer, pause = `stream/end`, seek = `stream/clear`, volume and mute are the player's) and the `PlayerService` branch (music only, one player per receiver, made only when the stream rate is known).
- **4.5 partly**: the row (`SendspinRowBinder`, `SendspinRowText`), the branches in `ReceiverRowBinder`, `RendererStatusMonitor` and `ReceiverVolumeLiveUpdater`, the settings (switch **off by default**, add and remove dialog), 35 strings in seven locales, docs and CHANGELOG. **Approval is by playing to the player**; there is no list of approvals with a revoke button yet, no gear on the card. mDNS: only the TXT and the format of the announcement are verified, not the Android resolution.
- Lint: 8 errors and 6 warnings, none in the new files (not compared with the baseline of develop). 6837 unit tests, none failing.
- Pairing (fourth working session, 2026-11-24): token codec with the spec's vector, pairing store and policy, re-handshake in the open channel, the server side of the Pairing PSK flow in `HubConnection` (phases `PAIRING`, `PAIRING_FAILED`, failures), the dialog of a card (tap), the lists of paired and approved players with remove buttons, strings in seven locales, docs. Verified: `RehandshakeTest` (6), `HubPairingTest` (10), `SendspinPairingsTest`, and the rig test against the reference player with unpaired access off (paired, then the second connection under the long-term PSK, 2026-11-24). Never run on a device.
- Controller (fifth working session, 2026-11-24): `controller.md` (plan K1 to K11): the client's handshake, the controller session, the card, the artwork role, the search of servers; **open: K8 pairing as a client, K10 the security review (postponed), K11 a thumbnail on the card**.
- Not done: 4.6 (the owner listens), the identity key and the PSKs in the Keystore (3.5, they are in the app-private preferences), pairing with a code (Group 11), a discovery report, names in the paired list.

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
- [x] 2.2 (done as production code and the rig test `HubRigTest`, 2026-11-24) The same under a pairing PSK from a token, then the re-handshake to a long-term PSK and a reconnect under it | same folder
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
- [x] 3.4 Identity, pairing token, pairing records (done: `PairingToken`, `SendspinPairings`, `SendspinPolicy`; "forget all" is the two lists with a remove button, not one button) | `de.yaacc.sendspin.secure`, tests `PairingTokenTest` (the spec's reference vector), `SendspinPairingsTest`, `SendspinKeysTest`
  - **Accept**: the token is decoded leniently and rejected strictly as specified; the server's record is `client_id` -> long-term PSK; approvals per `client_id` are discarded on a pairing; "forget all" wipes everything.
- [x] ~~3.5 Key storage wrapped by the Android Keystore (AES-GCM key; the Keystore has no X25519 on API 27) with a faked wrapping interface in JVM tests~~ **dropped by the owner on 2026-11-27** | `de.yaacc.sendspin.secure`
  - **Status 2026-11-27**: decided not to build: the identity key and the PSKs stay in the app's private preferences (backup excluded). The owner: the app is no banking or private-communication app; the encryption of the transport protects the privacy that the use case needs
  - **Accept**: identity keys and PSKs are never stored in the clear; backup excluded (`allowBackup` rules checked); nothing secret in logs; CONFIRM (key storage).

## Group 4: P1, play to one player, unpaired with approval (P)
- [x] 4.1 Chunking and pacing from the PCM tap: 48 kHz 16-bit stereo, 20 ms chunks, server-clock timestamps, fixed lead; `stream/start|clear|end` rules for play, seek, track jump, pause and stop | `de.yaacc.sendspin.hub`, tests with a simulated clock and a simulated player buffer
  - **Accept**: never more than the player's `buffer_capacity` ahead; track changes continue the timeline; seek and jump send `stream/clear`; pause sends `stream/end`; the first timestamp respects the player's `min_buffer_ms + output_delay_ms`. (verify: how `RealTimePacer` and the slot model of `de.yaacc.multiroom.feed` fit; if not, own pacing behind the same tap.)
  - **Verify**: `ChunkPacingTest`, `StreamLifecycleTest`.
- [x] 4.2 `HubConnection`: server-initiated connect, handshake, hello, activate, `server/time`, goodbye handling and reconnect rules (`another_server`: stop and show; `shutdown`/`restart`: retry with backoff; `concurrent_attempt`: retry later; `pairing_required`/`unpaired`: stop and ask) | `de.yaacc.sendspin.hub`, tests with a fake player peer (in-memory transport)
  - **Accept**: each goodbye reason causes its specified behavior; unapproved players stay at empty `activities`; the server declares `'playback'` only while it streams; limits for connections and memory.
  - **Verify**: `HubConnectionTest`; opt-in rig test.
- [x] 4.3 Discovery and directory: `NsdManager` `_sendspin._tcp` plus manual `host:port`, one entry per `client_id`, a synthetic `SendspinReceiverDevice` (id `sendspin:<client_id>`), scanning only while a receiver page is open | `de.yaacc.sendspin.hub`, `de.yaacc.sendspin.discovery`
  - **Status 2026-11-27**: built (`SendspinDirectory`, `SendspinNsd`, manual players, tests `SendspinDiscoveryTest`); **not built: the discovery report with a *Copy report* button** that the Snapcast discovery has (see 11.12)
  - **Accept**: lost services are dropped after the live-update grace; manual entries survive restarts; the discovery report like the other protocols' (what is browsed, what was seen), with a *Copy report* button.
  - **Verify**: `SendspinDirectoryTest`.
- [x] 4.4 `SendspinPlayer` (queue, play, pause, stop, next, previous, seek, volume, mute) and the `PlayerService.createPlayer` wiring | `de.yaacc.player`, tests with a fake hub
  - **Status 2026-11-27**: built as `SendspinPlayer` / `SendspinPlayerLogic` (`de.yaacc.player`), wired in `PlayerService`
  - **Accept**: the same behaviors as the other protocol players for a queue; one player per receiver; the places of task 1.3 done.
  - **Verify**: `SendspinPlayerTest`, existing player tests green.
- [x] 4.5 Receiver row, status text, operator approval (a list with revoke), settings (manual players, discovery switch) | `de.yaacc.browser`, `de.yaacc.settings`, strings in 7 locales
  - **Status 2026-11-27**: built (`SendspinRowText` and its tests, rows, approvals list, settings, strings in 7 locales)
  - **Accept**: the row says available / playing / needs pairing / in use by another server / not approved, in words; "approved" and "neither paired nor approved" are always distinguishable; defaults per `decisions.md`.
  - **Verify**: `SendspinRowTextTest`, string completeness test.
- [ ] 4.6 The owner listens: music from a UPnP server through YAACC to a player for 30 minutes (and two players if available) | rig run
  - **Status 2026-11-27**: not recorded as asked: the owner played to a Kodi addon (legacy protocol, 2026-11-24); no 30 minute report and no two-player run
  - **Accept**: the owner's report in `decisions.md`.

## Group 5: P2, Pairing PSK and the PoC gate (P)
- [x] 5.1 Pairing PSK flow on the server (token paste, key check, re-handshake, pair-init/pair-finalize, record, re-handshake, reconnect with the long-term PSK) and the credential-mismatch signal (Sentinel fallback: surface it and offer re-pairing) | `de.yaacc.sendspin.hub`, `de.yaacc.sendspin.secure`
  - **Accept**: a reference player pairs; the record persists; after a restart the player is used without pairing; a player that lost its record is shown as "re-pair".
  - **Verify**: `HubPairingTest`; rig test.
- [x] 5.2 (the lists show the first 8 characters of the id, not names: the app does not remember a player's name by id) Dialogs: paste token with clear errors, paired and approved list with revoke | `de.yaacc.settings`, `de.yaacc.browser`
  - **Accept**: nothing sensitive in logs or in the dialog beyond what the user pasted; string completeness test.
- [x] 5.3 Documentation of the PoC (README, `docs/index.md`, `docs/tech.md`, CHANGELOG), the trademark notice, the rig | docs
  - **Status 2026-11-27**: docs in `docs/index.md`, `docs/tech.md`, `CHANGELOG.md`, `THIRD_PARTY.md`, the trademark notice in `docs/index.md`, the rig README; the README feature list got a Sendspin line on 2026-11-27
  - **Accept**: facts only ("plays to Sendspin players; pairing PSK; not affiliated"); limits stated (no groups yet, sync as heard, unpaired sessions can be impersonated on the LAN as the protocol says); no stale references.
- [x] 5.4 **Gate**: the owner answers Q1 to Q5 with the evidence and decides go (Phase 2, Phase 3), reduce, or stop | `decisions.md`
  - **Status 2026-11-27**: passed in practice: the owner asked for each following step; the Q1 to Q5 list was not answered as such, the evidence is in `decisions.md`

## Group 6: Phase 2, hub with several players and groups (P1, after the gate)
- [ ] 6.1 `HubGroup` and membership rules (solo group on `client/leave`/unavailable, previous group, `group/update`, ids of 43 base64url characters) | `de.yaacc.sendspin.hub`, tests
  - **Status 2026-11-27**: not done as written: groups are the app's own (`SendspinGroups`, `groups.md`); the protocol's group rules (`client/leave`, solo group on unavailable, previous group) are not implemented for players; the player connection carries one fixed group id
  - **Accept**: a client is in exactly one group; no member left in an empty group; movement rules follow the spec.
- [x] 6.2 `HubScheduler` with the spec's `buffer_capacity` accounting, a common send-ahead over a group, late joiners, format change without a gap | tests with simulated player buffers
  - **Status 2026-11-27**: built as `PcmStreamer` (capacity accounting, common send-ahead over a group, per-member stuck-write abort); tests `PcmStreamerTest`, `PcmStreamerGroupTest`
  - **Accept**: never exceeds a member's `buffer_capacity`; late joiners get future timestamps only.
- [x] 6.3 Controls: volume, mute, output delay commands, `GroupVolume` (the spec's algorithm: delta, clamp, redistribute, round once, one command per player), group mute | tests with the spec's tables
  - **Status 2026-11-27**: built: volume, mute, output delay commands; `GroupVolume` follows the spec's delta, clamp and redistribute rule (2026-11-26); group mute
  - **Accept**: commands only for `supported_commands`; table tests reproduce the spec's behavior.
- [x] 6.4 Fragmentation of large messages, the server side of the WebSocket (needed for client-initiated mode), the full codec of the core and player roles | `de.yaacc.sendspin.session`, tests with the spec's malformed sequences
  - **Status 2026-11-27**: built: fragmentation in `SecureChannel`, the server side of the WebSocket (`WsHandshake`, `InboundServer`), the codec; tests `SecureChannelTest`, `WsConnectionTest`, `WsHandshakeTest`, `InboundServerTest`
  - **Accept**: the listed malformed fragment sequences close the connection; size caps hold.
- [x] 6.5 Takeover: another server displaces a player (`another_server`): the row shows it as in use, the group continues without it | tests
  - **Status 2026-11-27**: built: another server's takeover ends the connection with `ANOTHER_SERVER` and the row shows it as in use

## Group 7: Phase 2, server UI and gate (P2)
- [x] 7.1 Link sheet, group slider = group volume, member menu (output delay), unlink, "Play on" a member plays on its group (MusicCast pattern) | `de.yaacc.browser`, tests `SendspinLinkCandidatesTest`, `SendspinGroupDirectoryTest`, `SendspinPlayTargetsTest`
  - **Status 2026-11-27**: built (`SendspinLinkSheet`, group slider, "Play on" a member plays on its group); the phone itself can be a member; the test names differ from the plan
  - **Accept**: candidates with disabled reasons (unavailable, taken by another server, not approved or paired, group full); link and unlink work against the rig and the fake hub; takeover is shown, not an error.
- [x] 7.2 Live status of the rows (within 2 s, coalesced at 300 ms) | `de.yaacc.browser`
  - **Status 2026-11-27**: rows follow the app's refresh plan (about every 2 s); no separate measurement
- [x] 7.3 Documentation update | `README.md`, `docs/`, `CHANGELOG.md` (only what exists)
  - **Status 2026-11-27**: docs and changelog are current
- [ ] 7.4 Gate: the owner tests Phase 2 on devices and decides whether Phase 3 starts | `decisions.md`
  - **Status 2026-11-27**: the owner's device test is still to come

## Group 8: Phase 3, player engine spike (P1, needs the owner's go)
- [x] 8.1 Check how generic the engine of `de.yaacc.multiroom.client` is (`AudioTrackSink`, `DriftController`, `FrameCorrector`, `ChunkScheduler`, `MediaCodecFlacDecoder`, the service and its policies) and what must change to take the Sendspin time filter as its time source | `design.md` (section update)
  - **Status 2026-11-27**: done: `SendspinAudioEngine` adapts the Snapcast client engine (`EngineHost`, `AudioTrackSink`, decoders)
- [x] 8.2 The Sendspin time filter (the spec's parameters) and the `client/time` burst driver | `de.yaacc.sendspin.session`, tests `TimeFilterTest`, `ClockSyncDriverTest`
  - **Status 2026-11-27**: built as `ServerClock` and the offset estimator of the client package (tests `ServerClockTest`, `PlayerSessionTest`); the hub answers `server/time`
  - **Accept**: converges on a simulated link with jitter and drift; discards measurements with `max_error <= 0` and a non-increasing `time_added`; bursts of at least 8 messages, a burst at least every 10 s; the two conversion functions invert each other except rounding.
- [ ] 8.3 Spike: the engine against the reference server (PCM), measure sync on the owner's devices | `de.yaacc.sendspin.speaker` (draft)
  - **Status 2026-11-27**: not done: sync was never measured or listened to on the owner's devices; only the rig and simulated outputs
  - **Accept**: the owner hears no offset after 10 minutes; drift correction inaudible; the measured error stated (target +-1 ms in steady state; the acceptable level is set with the owner after the first numbers). **Kill criterion for Phase 3:** sync cannot be held on the owner's devices -> stop Phase 3 and record why.
- [x] 8.4 `PlayerEndpoint` with `EndpointDriver` (in-process hub or remote) and the spec's admission rules (activity ranking, last-playback server tie-break, displacement and rejection goodbyes, pairing attempt protection, provisional timeout, caps) | tests `PlayerAdmissionTest`, `EndpointDriverTest`
  - **Status 2026-11-27**: built as `PlayerArbiter` and `SendspinPlayerHost` (activity ranking, last-playback tie-break, displacement, provisional timeout, caps); tests `PlayerArbiterTest`, `SendspinPlayerHostTest`

## Group 9: Phase 3, listener and service (P1)
- [x] 9.1 Network listener (WebSocket server, Noise responder, hello/activate, pairing PSK as the client, unpaired setting, token display data, mDNS registration, `Origin` rejected) | `de.yaacc.sendspin.speaker`
  - **Status 2026-11-27**: built (`SendspinPlayerService`, `InboundServer`, `PlayerSession`, `OwnPhoneAsMemberTest`, `PlayerRigTest`); **not tried with Music Assistant or on a phone**
  - **Accept**: the reference server and Music Assistant connect, pair with the displayed token and play; limits hold under a flood test; advertised only while enabled; stops cleanly.
  - **Verify**: `PlayerListenerTest` (fake peer), rig test; security review focus.
- [x] 9.2 Foreground service and locks | `de.yaacc.player`, `AndroidManifest.xml`
  - **Status 2026-11-27**: built (foreground service, locks only while a stream plays, notification with a switch-off button); the owner's device test is pending
  - **Accept**: survives screen off; locks only while a stream plays; notification with stop; lint clean; owner's device test.

## Group 10: Phase 3, client UI and gate (P2)
- [x] 10.1 Settings and screen: "use this phone as a Sendspin player" (off by default), name, pairing token (copy), unpaired toggle with a warning, paired servers with remove, latency offset | `de.yaacc.settings`, strings in 7 locales
  - **Status 2026-11-27**: built (switch, token with QR code, one unpaired setting, paired devices with *Add device*); **no latency offset field** in the phone's own UI (a server's output delay command works)
- [x] 10.2 The phone's own speaker as a member of the hub's group (in-process) and the takeover display | `de.yaacc.browser`
  - **Status 2026-11-27**: built: the phone's own player is a member of a group over the loopback address (2026-11-26); the card says whose group it plays in
- [x] 10.3 Documentation for Phase 3 (requirements and limits: sync, doze, security; the opt-in listener) | `README.md`, `docs/`, `CHANGELOG.md`
  - **Status 2026-11-27**: docs and changelog are current
- [x] 10.4 Gate: the owner decides about Phase 4 | `decisions.md`
  - **Status 2026-11-27**: in practice: the owner asks for the Phase 4 items one by one

## Group 11: Extras (Phase 4, each item needs its own owner go)
- [ ] 11.1 Code-based pairing (dynamic and static codes) with CPace | `de.yaacc.sendspin.secure`
  - **Accept**: the draft's test vectors pass; interoperates with the reference; **kill criterion**: no confidence in correctness -> do not ship (Pairing PSK only).
- [x] 11.2 Controller, metadata and artwork roles (the phone as a controller of other servers, now playing in the player UI)
  - **Status 2026-11-27**: built (K1 to K8, K12, artwork; and the server side, K15)
- [ ] 11.3 FLAC encoding on the hub, Opus, the `source` role
- [x] 11.4 Client-initiated connection mode of the hub (advertise `_sendspin-server._tcp`, accept inbound sockets), opt-in and off by default, with the legacy unencrypted wire (decisions, 2026-11-24)
  - **Accept (all)**: tests first for pure parts, general then security review, documentation update.
- [x] 11.6 **Linking (groups, S7)**: design and decisions in `groups.md`; built (timeline, model, hub, app player, link sheet, card, group volume); open: a measurement with two players in the rig and a listening test on real players.
- [ ] 11.5 **Roles of the phone as a client (owner's idea, 2026-11-24; P5 in `usecases.md`)**: a setting for the roles the phone offers a server (speaker = player, now playing = metadata, cover display = artwork, visualizer), a screen for each (a full-screen cover, a light or colour show), the roles listed in the phone's `client/hello`. Needs the phone as a player (P1 to P4) first; read the visualizer role of the specification before the design. Not started.
- [x] 11.7 **One card per YAACC phone (K14)**: installation id in the TXT record of the player and the server announcement, merge in the receiver list, *Remote control* entry on the card. Needs K15 to be useful.
- [x] 11.8 **The controller role of the YAACC server (K15)**: spec in `controller-server.md` (decisions answered 2026-11-26: one policy for all roles, artwork in the first version, standard controller role for any controller); then then the server side of `client/command`, `server/state` (controller, metadata, artwork) mapped to the app's playback; tests against the client side (K1-K7) and the reference controller.
- [ ] 11.9 **Remote linking, future enhancement (K16)**: to be discussed with the owner; not in the protocol.
- [x] 11.10 **Groups are easier to read (K17)**: names of the members on the leader's card, "plays with" on the member's card (also this phone), members under their leader.
- [x] 11.11 **K11, cover on the card** (done 2026-11-27: the soft background view of the card, as the UPnP cover; also on the merged card of a YAACC phone; not tried on a phone; only the album channel)
- [ ] 11.12 Discovery report with a *Copy report* button for Sendspin (what is browsed, what was seen), like the Snapcast one
- [ ] 11.13 K10: general review, then the security review of the client side and of the new server surface (controller role, loopback connection, cover fetching); postponed by the owner
- [ ] 11.14 Opus for the phone as a player (FLAC is built)
- [ ] 11.15 Merge the duplicated pairing and clock code of `ControllerSession` and `PlayerSession`
- [ ] 11.16 Real device checks that nothing here replaces: the sound path and FLAC decoding, sync between players, the phone as a group member, K14 and K15 on two phones, K15 against another controller, Music Assistant as a server to the phone's player

