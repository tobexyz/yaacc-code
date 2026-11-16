# Tasks: Sendspin support (server and client)

Plan: `.claude/specs/2026-11-09-sendspin/requirements.md` and `design.md`. Decisions: `decisions.md` (entries marked CONFIRM must be decided by the owner before the matching group starts).
Rules: spec first, tests first for the pure parts; all new strings in values + de/es/fr/nl/pt/zh; license headers with a closing ` */`; neutral packages `de.yaacc.multiroom.*`; no Sendspin logo or product naming (requirements, Constraints); commits as tobexyz with the issue number; reviewer then security-reviewer after each group (sequential, max 3 cycles); no Gradle while a reviewer runs; no PR unless asked; the owner tests audio on real devices and the rig (no audio tests in the sandbox).

**Phases: 1 = Groups 1-2 (foundations), 2 = Groups 3-6 (server), 3 = Groups 7-10 (client), 4 = Group 11 (extras). The owner decides at the end of each phase.**

## Group 1: Research, rig, feasibility spike (P0)
- [ ] 1.1 Verify the protocol details we rely on and write them to `docs/tech.md` (own words, wire version 1) | `docs/tech.md`
  - **Accept**: notes cover handshake order, `client/init`/`server/init`/`noise/handshake` fields, Noise pattern details (prologue, payloads, counters, re-handshake), PSK ids and the Sentinel constants, `server/hello`/`client/hello`/`server/activate`/`client/state`/`stream/*`/`group/update`/`client/goodbye` fields we use, binary IDs and the audio chunk layout, fragmentation, admission rules, pairing PSK flow and token encoding, player and sync rules, and which spec sections are still moving (the spec repository is active); each item cites the spec section; resolves every (verify) marker of `design.md` that does not need the rig.
  - **Verify**: `grep -c "(verify)" .claude/specs/2026-11-09-sendspin/design.md` shrinks accordingly; nothing copied verbatim; license terms recorded.
  - **Constraints**: no code from Apache-2.0 reference implementations is copied; compare behavior only.
- [ ] 1.2 Crypto feasibility spike (JVM only, throw-away): Noise `KKpsk2` handshake in Java against the reference player, then a Pairing PSK exchange | `tools/sendspin-rig/spike/` (not production code)
  - **Accept**: a Java program completes `client/init`..`server/hello`/`client/hello`/`server/activate` with the reference player under the Sentinel PSK and under a pairing PSK; both suites work; uses only library primitives (Bouncy Castle lightweight API, decision CONFIRM).
  - **Verify**: run log attached to `docs/tech.md`. **Kill criterion**: if interoperable Noise cannot be reached in reasonable effort, stop and decide with the owner (Sendspin is dropped or reduced).
  - **Constraints**: pinned dependency version (7-day rule); keys and PSKs are generated for the spike, never committed.
- [ ] 1.3 Docker rig | `tools/sendspin-rig/` (`docker-compose.yml`, README)
  - **Accept**: reference player(s) and a reference server run in containers with a null/file audio backend, ports documented; instructions to attach a real player; images pinned by tag.
  - **Verify**: `docker compose config` validates; the README commands list the discovered players.
  - **Constraints**: local ports only; non-interactive; no audio files committed.
- [ ] 1.4 Inventory (shared with the Snapcast spec, task 1.4): where a receiver must be a Cling `Device`; decide synthetic device vs. a `ReceiverTarget` seam, and whether a Sendspin player is merged with its UPnP renderer | `.claude/specs/2026-11-09-sendspin/design.md` (section update)
  - **Accept**: a list of the places and the change each needs; a go/no-go reviewed by the owner.
- [ ] 1.5 Spike: ExoPlayer with a capture `AudioSink` (PCM out, back pressure), seek, gapless, queue (shared with the Snapcast spec, task 4.1) | `de.yaacc.multiroom.feed` (draft)
  - **Accept**: a queue of three tracks produces continuous PCM with correct timestamps; seek and track jump are detectable; decoding is not forced to real time.
  - **Verify**: owner's device run; **kill criterion**: if not feasible, the fallback is a headless decode with an own queue (decision with the owner).

## Group 2: Foundations (P1, pure Java, tests first) - Phase 1
- [ ] 2.1 WebSocket subset (client + server) | `de.yaacc.multiroom.session`, tests `WsFramingTest`, `WsHandshakeTest`, `WsConnectionTest`
  - **Accept**: handshake (accept key, `Origin` rejection switch), text/binary frames up to the cap, masking rules (client frames masked, server frames not), ping/pong, close with codes, fragmented messages, protocol violations close with 1002/1009; idle and handshake timeouts; no unbounded buffers.
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests 'de.yaacc.multiroom.session.Ws*'` green; opt-in rig test against the reference.
  - **Constraints**: CONFIRM (in-house vs library); security review focus.
- [ ] 2.2 Message codec and fragmentation | same package, tests with fixtures from the spec's examples
  - **Accept**: every message of the core and player role round-trips; unknown `type`/fields/IDs are ignored as specified; out-of-range values and the listed malformed fragment sequences close the connection; size caps hold.
  - **Verify**: `SessionCodecTest`, `FragmentationTest`.
- [ ] 2.3 Time filter and clock sync driver | same package, tests with simulated clocks
  - **Accept**: converges within the spec's expectations on a simulated link with jitter and drift; handles discarded measurements (`max_error <= 0`, non-increasing `time_added`); bursts of at least 8 messages, burst start at most 10 s apart; conversion functions invert each other except rounding.
  - **Verify**: `TimeFilterTest`, `ClockSyncDriverTest`.
- [ ] 2.4 Noise `KKpsk2` + PSK handling | `de.yaacc.multiroom.secure`, tests `NoiseHandshakeTest` (published vectors), `PskIdTest` (Sentinel constants from the spec), `SentinelFallbackTest`, `RehandshakeTest`
  - **Accept**: handshake with both suites matches vectors and interoperates with the reference (rig test); wrong PSK/identity fails silently as specified; counters reject replays; re-handshake swaps keys.
  - **Verify**: the tests above green; rig test output.
  - **Constraints**: no own curve/cipher code; keys zeroed after use where the library allows; CONFIRM (library).
- [ ] 2.5 Identity, pairing token, pairing store | `de.yaacc.multiroom.secure`, tests `PairingTokenTest` (the spec's reference vector), `PairingStoreTest`, `IdentityTest`
  - **Accept**: token encode/decode lenient/strict as specified; records (client side at least 5, eviction rule, never evict an open connection); approvals per `client_id` are discarded on pairing; the file-backed store wraps secrets with a Keystore key (instrumented/pure split: the wrapping interface is faked in JVM tests); "forget all" wipes everything.
  - **Verify**: the tests above green; lint clean.
  - **Constraints**: CONFIRM (key storage); no secrets in logs, tests or fixtures.

## Group 3: Hub core - connections, pairing, groups (P1) - Phase 2
- [ ] 3.1 Discovery and directory (NsdManager `_sendspin._tcp`, manual list, merge by `client_id`) | `de.yaacc.multiroom.hub`, tests for the pure merge/key logic
  - **Accept**: one entry per `client_id`; lost services are dropped after the live-update grace; manual entries survive restarts; scanning only while the receivers page is visible (multicast lock).
  - **Verify**: `SendspinDirectoryTest`.
- [ ] 3.2 `HubConnection` state machine (server-initiated connect, handshake, hello, activate, goodbye handling, reconnect rules, backoff) | same package, tests with a fake player peer (in-memory transport)
  - **Accept**: every `client/goodbye` reason causes the specified reconnect/stop behavior; displaced/rejected connections are shown as such; unapproved players stay at empty `activities`; caps for players and memory.
  - **Verify**: `HubConnectionTest`; opt-in rig test with the reference player.
- [ ] 3.3 Pairing PSK flow (server side) and unpaired approval | same package + `de.yaacc.multiroom.secure`
  - **Accept**: token paste pairs a reference player; records persist; approval is per `client_id`, revocable, never implied for `source`; "paired/approved/neither" is always distinguishable.
  - **Verify**: `HubPairingTest`; rig test.
- [ ] 3.4 `HubGroup` and membership rules (solo group on `client/leave`/unavailable, previous group, `group/update`, ids) | same package, tests
  - **Accept**: a client is in exactly one group; group id format is 43 base64url chars; movement rules follow the spec; no member left in an empty group.
  - **Verify**: `HubGroupTest`.
- [ ] 3.5 Controls: volume/mute/output delay commands, `GroupVolume` (spec algorithm), group mute | same package, tests
  - **Accept**: table tests reproduce the spec's delta/clamp/redistribute behavior, rounding once, one command per player; commands only for `supported_commands`.
  - **Verify**: `GroupVolumeTest`, `HubControlsTest`.

## Group 4: Streaming and the player integration (P1) - Phase 2
- [ ] 4.1 `HubScheduler`: send-ahead, `buffer_capacity` accounting, chunking 15-150 ms, late joiners, `stream/start|clear|end` rules | `de.yaacc.multiroom.hub`, tests with a simulated player buffer
  - **Accept**: never exceeds `buffer_capacity`; first timestamp respects `min_buffer_ms + output_delay_ms` of the slowest member; seek/jump uses `stream/clear`, pause uses `stream/end`, track transitions send nothing; format change sends `stream/start` without a gap on the timeline.
  - **Verify**: `HubSchedulerTest`.
- [ ] 4.2 PCM capture sink to chunks (format conversion, Sonic resampling, channel/bit-depth conversion) | `de.yaacc.multiroom.feed` (shared with Snapcast)
  - **Accept**: bit-exact where no conversion is needed; resampling quality acceptable by ear; timeline continuous across tracks.
  - **Verify**: `PcmConverterTest`, owner's ear check.
- [ ] 4.3 `SendspinPlayer` (queue, play/pause/stop/next/previous/seek, state to the existing player UI, group volume) and `PlayerService.createPlayer` wiring | `de.yaacc.player`
  - **Accept**: the same behaviors as the local music player for a queue; one player per group; "Play on" a member plays on its group.
  - **Verify**: `SendspinPlayerTest` (fake hub), existing player tests green.
- [ ] 4.4 End-to-end with the rig: play 5 minutes of music from a UPnP server through YAACC to two reference players, in sync | rig run
  - **Accept**: owner confirms by ear; the reference players report timing within their own tolerance.
  - **Verify**: owner's report. **Kill criterion for Phase 2**: not in sync or not steady -> stop and decide with the owner.

## Group 5: Server UI (P2) - Phase 2
- [ ] 5.1 Receiver rows and live status (`SendspinRowText`, availability reasons, group role), live updates via the connections | `de.yaacc.browser`, strings in 7 locales
  - **Accept**: rows follow player changes within 2 s; "needs approval", "in use by another server", "unpaired" are explained; coalesced at 300 ms.
  - **Verify**: `SendspinRowTextTest`, `ReceiverLiveUpdatesSendspinTest`, resource tests.
- [ ] 5.2 Link sheet, group slider, member menu (output delay), unlink, takeover display (MusicCast pattern) | `de.yaacc.browser`
  - **Accept**: candidates with disabled reasons; link/unlink work against the rig and the fake hub; group slider = group volume; takeover shown, not an error.
  - **Verify**: `SendspinLinkCandidatesTest`, `SendspinGroupDirectoryTest`, `SendspinPlayTargetsTest`.
- [ ] 5.3 Pairing and approval dialogs, paired/approved list with revoke, settings (manual players, discovery on/off) | `de.yaacc.settings`, `de.yaacc.browser`
  - **Accept**: token paste with clear errors; approve/revoke; nothing sensitive in logs or screenshots of the dialog.
  - **Verify**: `SendspinPairingDialogTest` (pure parts), string completeness test.

## Group 6: Documentation and Phase 2 gate (P2)
- [ ] 6.1 Update documentation | `README.md`, `docs/index.md`, `docs/tech.md`, `CHANGELOG.md`
  - **Accept**: features, the protocol statement ("implements the Sendspin protocol except code-based pairing", factual wording only), trademark notice, rig, security notes; no stale references.
  - **Verify**: `grep -r 'TODO\|FIXME\|PLACEHOLDER' README.md docs/ || true` shows nothing plan-related.
  - **Constraints**: do not document Phase 3/4 features before they exist.
- [ ] 6.2 Gate: owner tests Phase 2 on devices and decides whether Phase 3 starts | `decisions.md`

## Group 7: Player engine spike (P1) - Phase 3 (needs the owner's go)
- [ ] 7.1 Spike: `AudioTrack` engine with the time filter against the reference server (PCM), measure sync | `de.yaacc.multiroom.speaker` (draft)
  - **Accept**: against a reference player on the rig the owner hears no offset after 10 minutes; drift correction inaudible; measured error stated (target +-1 ms, acceptable level to be set with the owner after the first numbers).
  - **Verify**: measurement log plus owner's ears. **Kill criterion for Phase 3**: if sync cannot be held on the owner's devices, stop Phase 3 and record why.
- [ ] 7.2 `PlayerEngine` (decode PCM, FLAC via `MediaCodec`, chunk scheduling, correction, `stream/clear`, format change, lead-time and buffer measurements, manual latency offset) | `de.yaacc.multiroom.speaker`, tests for scheduler/correction math
  - **Accept**: pure parts tested with simulated clocks; underruns are reported and recovered with one rare resync; no pitch change.
  - **Verify**: `ChunkSchedulerTest`, `DriftCorrectionTest`.

## Group 8: Player endpoint and listener (P1) - Phase 3
- [ ] 8.1 `PlayerEndpoint` with `EndpointDriver` (in-process hub, remote) and the spec's admission rules | `de.yaacc.multiroom.speaker`, tests from the "Multiple servers" tables
  - **Accept**: activity ranking, last-playback server tie-break, displacement and rejection goodbyes, pairing attempt protection, provisional timeout, caps.
  - **Verify**: `PlayerAdmissionTest`, `EndpointDriverTest`.
- [ ] 8.2 Network listener (WebSocket server, Noise responder, hello/activate, pairing PSK as the client, unpaired setting, token display data, mDNS registration) | `de.yaacc.multiroom.speaker`
  - **Accept**: the reference server and Music Assistant connect, pair with the displayed token and play; `Origin` rejected; limits hold under a flood test; stops cleanly; advertised only while enabled.
  - **Verify**: `PlayerListenerTest` (fake peer), rig test; security review focus.
- [ ] 8.3 Foreground service and locks | `de.yaacc.player`, `AndroidManifest.xml`
  - **Accept**: survives screen off; locks only while a stream plays; notification with stop.
  - **Verify**: lint clean; owner's device test.

## Group 9: Client UI and settings (P2) - Phase 3
- [ ] 9.1 Settings and screen: enable "use this phone as a Sendspin player" (off by default), name, pairing token (copy), unpaired toggle with warning, paired servers list with remove, latency offset | `de.yaacc.settings`, strings in 7 locales
  - **Accept**: nothing listens until enabled; turning it off closes connections and unregisters mDNS; token never shown in logs.
  - **Verify**: `PlayerSettingsTest` (pure parts), string completeness test.
- [ ] 9.2 Local speaker as a member of the hub's group (in-process) and takeover display | `de.yaacc.browser`
  - **Accept**: the phone's own speaker appears as a member of its own group; a remote server taking it over is shown and the group continues.
  - **Verify**: `LocalEndpointRowTest`.

## Group 10: Documentation and Phase 3 gate (P2)
- [ ] 10.1 Update documentation for Phase 3 | `README.md`, `docs/`, `CHANGELOG.md`
  - **Accept**: requirements and limits (sync, doze, security), the opt-in listener; no stale references.
  - **Verify**: the placeholder grep as in 6.1.
- [ ] 10.2 Gate: owner decides about Phase 4 | `decisions.md`

## Group 11: Extras (Phase 4, each item needs its own owner go)
- [ ] 11.1 Code-based pairing (dynamic and static codes) with CPace | `de.yaacc.multiroom.secure`
  - **Accept**: the draft's test vectors pass; interoperates with the reference; **kill criterion**: if no confidence in correctness, do not ship (keep Pairing PSK only).
- [ ] 11.2 Controller, metadata and artwork roles (phone as controller of other servers, now playing in the player UI) | `de.yaacc.multiroom.session`, `de.yaacc.browser`
- [ ] 11.3 FLAC encoding on the hub and Opus; `source` role
- [ ] 11.4 Client-initiated connection mode of the hub (advertise `_sendspin-server._tcp`, accept inbound sockets) if not already done in Group 3
  - **Accept (all)**: tests first for pure parts, reviewer then security-reviewer, documentation update.
