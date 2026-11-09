# Design: Sendspin support (server and client)

Requirements: `requirements.md`. Decisions and open choices: `decisions.md`. Normative source: `github.com/Sendspin/spec` at `c281aeb` (2026-11-09), `README.md`; section names are the spec's. Items marked (verify) are assumptions that Group 1 of `tasks.md` must settle before code depends on them.

## Context
- The spec's **server** orchestrates players and groups, generates streams, manages pairing; a **client** has roles (`player`, `controller`, `metadata`, `artwork`, `source`, ...). A client is in exactly one group; the server owns group membership; a new server can displace the one that currently holds a player's `'playback'` activity (spec "Multiple servers").
- YAACC already is a media controller/server with a queue, a local player and a receivers list with MusicCast linking (`de.yaacc.yxc`, `YxcGroupDirectory`, `YxcLinkCandidates`, `YxcPlayTargets.resolve`, the link sheet). Receivers are Cling `Device` objects; a synthetic non-registered `Device` has a precedent (`UpnpClient.getLocalDummyDevice()`).
- Shared parts with the Snapcast spec: synthetic receiver, PCM capture sink, NsdManager helper (decisions.md).

## Decision
Four phases, each releasable, one protocol stack shared by both roles:
1. **Foundations** (pure Java, no UI): WebSocket, Noise, messages, time filter, identity and pairing store.
2. **Server ("hub")**: discover, pair/approve, play to and link Sendspin players from YAACC.
3. **Client ("speaker")**: the phone as a player, with a single in-process endpoint shared with the server role.
4. **Extras**: controller/metadata/artwork roles, code-based pairing (CPace), FLAC encoding and Opus, source role.

Rejected: a bridge that publishes Sendspin players as UPnP renderers (a second large design, kept possible); a server-only first release without the client (the client carries the main risk but also completes the MusicCast-like picture, so it is gated, not dropped).

## Technical Approach

### Packages
`de.yaacc.multiroom.session` (WebSocket, framing, message codec, time filter, connection state machines), `.secure` (Noise `KKpsk2`, identities, pairing records, tokens, key store), `.hub` (server role: discovery, connections, groups, scheduler, controls), `.speaker` (player role: listener, engine). Android glue in `de.yaacc.browser`, `de.yaacc.player`, `de.yaacc.settings` as for `yxc`. Pure parts have no Android imports.

### Session layer
- **WebSocket** (RFC 6455 subset, decision pending): client handshake (with `Sec-WebSocket-Key`), server handshake (rejects `Origin` header per spec for server-initiated endpoints), frame codec (masking, fragmentation, ping/pong, close), message size cap, idle timeout. One class pair shared by both roles.
- **Connection handshake** (spec "Communication"): `client/init` (cleartext, text frame) -> `server/init` + Noise message 1 -> Noise message 2 -> encrypted `server/hello`, `client/hello`, `server/activate`. The server is always the Noise initiator, whichever side opened the socket. Timeouts 30 s per step; silent failures close without a reply, as the spec defines.
- **Messages**: JSON `{type, payload}` records (platform `org.json` in production, test-only `org.json:json` in JVM tests, as `yxc`), binary IDs (0 JSON, 1 fragment, 4-7 player, 12-15 source), fragmentation for messages above 65518 bytes, ignore rules for unknown types/fields/IDs, out-of-range values close the connection. Immutable value objects per message.
- **Time filter**: the two-dimensional Kalman filter with the spec's parameters (`process_std_dev` 0, `drift_process_std_dev` 1e-11, forget factor 2, cutoff 3, `min_samples` 100, drift threshold 2, `max_error_scale` 0.5) and the conversion formulas. Pure class with injectable microsecond clock; unit tests with simulated offset/drift/jitter and with the spec's formulas as table tests. The reference C++ filter (separate repo, license to be checked) is used only to compare outputs, not copied.
- **Clock sync bursts**: at least 8 `client/time` messages per burst, each after the previous reply, a burst at least every 10 s, the lowest `max_error` measurement feeds the filter (spec). The server role answers `server/time` with the four timestamps (server receive and transmit time taken as late/early as the spec requires).

### Secure layer
- **Noise `KKpsk2`** with `25519_ChaChaPoly_SHA256` and `25519_AESGCM_SHA256`; server = initiator, client = responder; prologue = exact bytes of `client/init` + `server/init`; message 1 payload `{psk_id, psk_category}`, message 2 payload `{}`; transport counters, re-handshake in transport mode (needed to promote a pairing session). Written as a small state machine over library primitives (decision: Bouncy Castle lightweight API), verified against the Noise test vectors and against the reference implementation on the rig (a handshake with `sendspin-python-cli`).
- **PSKs** (spec "Pre-Shared Key"): `psk_id = base64url(SHA-256("sendspin-psk-id-v1" || PSK))`; categories long-term, pairing, Sentinel (published constant); Sentinel fallback on a lookup miss; credential-mismatch signal handling.
- **Identity**: Curve25519 key pair per YAACC installation and role (server identity, player identity); `server_id`/`client_id` = base64url public key (43 chars). Private keys from `SecureRandom`; stored wrapped by a Keystore key (decision); never logged.
- **Pairing PSK flow** (Phase 2): the player shows a token `SP:0…` (version 0: 32-byte client key + 32-byte pairing PSK, base32 with `2->9` transliteration, QR alphanumeric alphabet); the server operator pastes it; the server checks the decoded client key against the `client_id` on the connection, re-handshakes with the pairing PSK, runs `pair-init`/`pair-finalize`, persists the long-term PSK with the `client_id`, re-handshakes to it. Pairing records on the player: at least 5, eviction rules from the spec. Token decoding is lenient with input as the spec requires and strict on malformed data.
- **Unpaired access** (Phase 2 server side, Phase 3 player side): per-`client_id` operator approval on the server (never implied for the `source` role); on the player a setting, default off (decision).
- **Phase 4**: dynamic and static pairing codes with CPace (X25519/SHA-512, draft-irtf-cfrg-cpace-21) with the draft's test vectors, commit-and-reveal binding, wrapping, rounds; kill criterion in tasks.

### Server role ("hub")
- **Discovery**: `NsdManager` for `_sendspin._tcp` (server-initiated mode, recommended by the spec), manual `host:port` list; client-initiated mode (advertise `_sendspin-server._tcp` and accept inbound WebSocket connections) is required for conformance and is a Phase 2 stretch with its own task; a service that disappears is dropped after the live-update grace.
- **Connection manager**: one connection per player, serial worker per connection, reconnect rules from `client/goodbye` reasons (`another_server`: do not reconnect, show as available; `shutdown`/`restart`: reconnect; `concurrent_attempt`: retry later with backoff; `unauthorized`/`pairing_required`/`unpaired`: stop, ask the user), backoff on repeated handshake failures. Limit of connected players (default 32), memory caps per connection.
- **Activation and roles**: the server declares `activities: ['playback']` only while its group plays (and drops it when the purpose ends, so other servers can take the player), activates `player@v1` only when approved or paired, never activates a role the client did not list.
- **Groups**: a `HubGroup` has an id (32 random bytes, base64url, sent in `group/update`), a name, the member clients, one queue and one stream timeline. The leader is the group's controlling row in the UI; the others are members. A client is in exactly one group; `client/leave`, `available: false` and disconnects move it to a solo group per the spec; `switch` from a controller client cycles groups (Phase 4).
- **Streaming**: per player, `stream/start` with a format the player lists (PCM 16-bit 44.1/48 kHz first; FLAC and Opus later), audio chunks of 15-150 ms with server-clock timestamps; the first timestamp at least `max(min_buffer_ms + output_delay_ms)` ahead (common send-ahead = the maximum over the group); fill each player up to `buffer_capacity` using the spec's accounting rules; `stream/clear` for seek and track jump (with `from_timestamp` when possible), `stream/end` on pause/stop, `stream/start` again on resume; track changes continue the timeline (gapless). Players that join a playing group get future timestamps only.
- **Audio source**: ExoPlayer with a capture `AudioSink` (shared with the Snapcast spec) so the queue, seek, gapless and decoding of YAACC stay as they are; PCM is converted to the stream format (sample rate via Media3's Sonic, channels, bit depth) and chunked. The capture sink applies back pressure from the per-player buffer accounting, so decoding runs ahead of real time only as far as the players' buffers allow. (verify: ExoPlayer clock behavior with a non-real-time sink; fall back to a headless decode + own queue if gapless or seek break.)
- **Controls**: `server/command` `volume`, `mute`, `set_output_delay` to players that list them in `supported_commands`; **group volume** with the spec's algorithm (delta, clamp, redistribute, round once, one command per player), group mute (true only when all mute-capable players are muted); state back through `client/state` and `group/update`.
- **Receiver model**: each Sendspin client is a receiver row (synthetic `Device` with id `sendspin:<client_id>`, friendly name from `client/hello`, TXT name before that), showing state (available / in use by another server / needs approval / unpaired), volume, mute, group role (leader, member, solo) like `YxcRowText`. Merging with a UPnP renderer of the same device (IP or `device_info.mac_address`) is optional and decided in Group 1.4.
- **Link UI (MusicCast pattern)**: a link picker lists candidates with disabled reasons (unavailable, taken by another server, not approved/paired, group full); "link" adds the player to this group's stream (connect, approve, `server/activate`, `stream/start` on the group's timeline); "unlink" moves it to a solo group (stream ends); "Play on" a member plays on its group (as `YxcPlayTargets`); the group slider = group volume; a member's slider = its volume; output delay in the member's detail menu.
- **Takeover**: when another server displaces a player (`client/goodbye` `another_server`), the row shows it as available/in use and the group continues without it.

### Client role ("speaker")
- **Endpoint**: one `PlayerEndpoint` per phone, with state (volume, mute, output delay, formats, buffer capacity), that exactly one "driver" controls at a time: either YAACC's own hub (through an in-process adapter, no sockets, no Noise) or a remote server (through the network listener). The spec's admission rules (`playback` activity ranking, last-playback server wins ties) decide who may displace whom; the in-process hub participates as a server with its own `server_id`. Result: a YAACC phone can lead a group with its own speaker as a member, or be taken over by Music Assistant or another YAACC, and take itself back.
- **Listener** (opt-in setting): WebSocket server on a free port (preferred 8928), `/sendspin` path, mDNS registration `_sendspin._tcp` with TXT `path` and `name` via `NsdManager`; rejects upgrade requests with an `Origin` header; admits one connection at a time (more only as the spec allows); provisional connections dropped after 30 s without `server/activate`; limits on message size, fragment buffers and time; stops advertising when disabled or when the app has no reason to run (like the UPnP server service lifecycle).
- **Hello**: roles `player@v1` (Phase 3); `supported_formats` (PCM 16-bit 44.1/48 kHz stereo first, FLAC when the decoder is verified), `buffer_capacity` (a few MB), `supported_pair_methods` (Pairing PSK), `unpaired_access` (setting), `device_info` (product "YAACC", software version, MAC only if obtainable - Android 11+ hides it; omit if not).
- **Player engine** (spike first, own gate): decode chunks (PCM direct; FLAC via `MediaCodec` with the STREAMINFO from `codec_header`); schedule each chunk at `compute_client_time(timestamp) - output_delay_ms` using the converged time filter, pace into an `AudioTrack` (streaming mode) using `getTimestamp()` to map frames to the local clock; correct drift by dropping/duplicating single frames (spec: at most 0.5 % speed deviation, accuracy target +-1 ms in steady state, one-shot resync rare); send `client/state` only after the filter has converged; report `required_lead_time_ms` and `min_buffer_ms` from measurements; handle `stream/clear` (with and without `from_timestamp`) and format changes without gaps where possible. Honest limit: Android output latency varies per device, so a manual latency offset is part of the design.
- **Service**: a foreground service with wake and Wi-Fi lock only while a stream plays; notification with the group name and a stop action.
- **Pairing token display**: the player screen shows the token (copy/share); the user pastes it into Music Assistant (or any server). Pairing records can be listed and removed.

### Phase 4 extras
- Controller role (phone controls another server's group: play/pause/next/seek/volume/switch) and metadata/artwork roles (now playing in YAACC's player UI); code-based pairing with CPace; FLAC encoding (hub) and Opus (patent note from the spec); the `source` role (the phone streams a local input into a server, never played locally).

## Interfaces & Contracts
- `WsConnection` (text/binary send, listener, close, limits), `WsListener`/`WsConnector` factories injectable for tests (loopback fake peers, as the existing fake servers with `setCanonicalHostName("localhost")`).
- `SessionCodec` (JSON + binary + fragmentation), `NoiseSession` (handshake + transport, re-handshake), `TimeFilter`, `PairingStore` (keys, records, approvals; interface + file-backed implementation), `PairingToken` (encode/decode).
- `HubConnection` (state machine per player), `HubGroup`, `HubScheduler`, `GroupVolume`, `SendspinDirectory` (players, merged ids, rows), `PlayerEndpoint` with `EndpointDriver` (in-process or remote), `PlayerEngine`.
- Persistence: server/player identities (wrapped), pairing records, approvals, manual player list, settings (all in app-private storage).

## Test strategy
- Pure JVM, tests first: WebSocket framing and handshake, Noise handshake vectors, message codec and fragmentation (malformed sequences from the spec), time filter simulations, token encode/decode with the spec's reference vector, scheduler/accounting tables, group volume tables from the spec's algorithm, admission/arbitration tables ("Multiple servers"), pairing flow with a fake peer.
- Rig (opt-in, Docker): reference player(s) and a reference server (`sendspin-python-cli`/`aiosendspin`, pinned image tags); integration tests: handshake, pairing PSK, play 5 minutes of PCM, link/unlink, takeover, for both roles.
- Manual: owner listens (sync between two players and the phone, takeover, pause/seek/track change).
- Gate per group: unit tests, lint, assemble; reviewer then security-reviewer (the listener, the crypto use, the pairing store and the token handling are the focus).

## Risks (most serious first)
1. **Cryptography and pairing in Java** (Noise correctness, key handling, CPace): mitigation = library primitives only, test vectors, interoperability with the reference on the rig, security review; spike with a kill criterion before any UI.
2. **Player sync on Android** (+-1 ms target vs. device output latency, doze, Wi-Fi power save): spike with the owner's devices, manual latency offset, kill criterion for Phase 3.
3. **Receivers are Cling `Device`s in many places** (shared with Snapcast): inventory first; fall back to a small receiver seam.
4. **Ecosystem risk**: young protocol (technical preview in Music Assistant), spec may change: pin to wire `version` 1, track the spec repository, keep each phase releasable.
5. **Listening socket on a phone** (attack surface): off by default, hardened, reviewed.
6. **ExoPlayer with a capture sink** may not give seek/gapless with back pressure: spike; fallback headless decode.

## Open Items
- A UPnP/OpenHome facade per Sendspin group for third-party controllers.
- Merging a Sendspin player with its UPnP renderer row.
- QR scanning of pairing tokens (needs a camera permission and a decoder dependency).
- Behavior with several YAACC servers on one LAN fighting over players (rule: the spec's arbitration; UI hint).
