# Design: Snapcast support (#319)

Requirements: `requirements.md`. Decisions: `decisions.md`. Research and background: `notes.md`.
Facts below marked (src) are checked in the Snapcast sources (`develop` at `ea981ec`, CMake version 0.35.0: `doc/binary_protocol.md`, `doc/json_rpc_api/control.md`, `doc/configuration.md`, `server/`, `client/`); items that are not settled are marked "to check" with the task that settles them (the rig-dependent ones are listed as **rig** in `docs/tech.md` and wait for the owner's rig run; the on-device ones belong to the spikes in Groups 4 and 8).

## Context
YAACC lists receivers as Cling `Device` objects (`UpnpClient.getDevicesProvidingAvTransportService()`, ids = UDN) and creates a player per device (`PlayerService.createPlayer`: `AVTransportPlayer`, `OpenHomePlayer`, local players). A phone-local pseudo device already exists (`UpnpClient.getLocalDummyDevice()`, `LOCAL_UID`), so a synthetic `Device` that is not in Cling's registry is an established pattern. A Snapcast group is not a UPnP device.

## Decision
Two phases, each releasable:
- **Phase A (source + controls):** YAACC discovers snapservers, shows each group as a receiver, plays into the server over a runtime-created TCP stream, and controls clients/groups through the server's JSON-RPC. No Snapcast binary protocol.
- **Phase B (client):** the Snapcast binary protocol in plain Java and an audio engine, so the phone is a member of a group (plays in sync), controlled through YAACC's renderer. Own gate after Phase A.

Alternatives rejected: YAACC as snapserver (needs linking to be worth it, which is dropped); a UPnP facade per group for third-party controllers (more value, but a second large design, kept possible, see Open Items); native snapclient via NDK (the owner wants plain Java).

## Shared parts with the Sendspin spec
The synthetic receiver device (or a receiver seam), the PCM capture sink and the `NsdManager` helper are needed by both this spec and `.claude/specs/2026-11-09-sendspin/`. They are built once, by whichever spec starts first; the inventory task is 1.4 here and 1.4 there (do it once).

## Technical Approach

### Packages (neutral names, Android-free parts first)
`de.yaacc.multiroom.control` (JSON-RPC client, models, notifications), `.discovery` (mDNS, manual servers), `.feed` (stream creation, PCM sink, pacing), `.protocol` (Phase B: binary messages, connection, time filter), `.client` (Phase B: audio engine). Android glue stays in `de.yaacc.browser` / `de.yaacc.player` like `yxc`.

### Control: JSON-RPC (Phase A)
- Transport (src): newline-delimited JSON-RPC 2.0 over raw TCP, default port 1705 (`tcp-control`); also HTTP POST `/jsonrpc` and a WebSocket on 1780. Use the raw TCP socket: one long-lived connection gets requests, responses and notifications.
- Methods used (src): `Server.GetStatus`, `Client.SetVolume` (`volume: {percent, muted}`), `Client.SetLatency`, `Client.SetName`, `Group.SetStream`, `Group.SetClients`, `Group.SetMute`, `Group.SetName`, `Stream.AddStream`, `Stream.RemoveStream`, `Stream.Control` (only for streams that support it). Notifications (src): `Client.OnConnect/OnDisconnect/OnVolumeChanged/OnLatencyChanged/OnNameChanged`, `Group.OnMute/OnStreamChanged/OnNameChanged`, `Stream.OnUpdate`, `Server.OnUpdate` (full state after group changes).
- `SnapcastRpc`: request ids, pending map with a call deadline (10 s) and a read timeout (5 s), notification dispatch, reconnect with backoff; one serial worker per server; the model is rebuilt from `Server.GetStatus` and patched by notifications; after any doubt (parse error, unknown notification, gap) re-read the full status.
- Models are immutable value objects parsed with the platform `org.json` (production) / the test-only `org.json:json` (JVM tests), as in `de.yaacc.yxc`.
- Group volume: the server has no group volume; YAACC computes the average and applies relative changes to the members (same idea as the MusicCast group slider), respecting 0-100 and muted clients.

### Discovery
- mDNS (src, `server/snapserver.cpp`): the server publishes `_snapcast._tcp` (stream port, default 1704), `_snapcast-ctrl._tcp` (TCP control, 1705), `_snapcast-http._tcp` (1780). Use Android's `NsdManager` for `_snapcast-ctrl._tcp` (no new library); multicast lock only while the receivers page is visible (see `ReceiverLiveUpdates` start/stop). (resolve behavior on API 27+ is checked in task 3.1; manual entry is the fallback.)
- Manual entry in settings (`host[:port]`) for networks without mDNS; stored as a plain list, no credentials.
- Server identity: the stable key is the control endpoint chosen by the user / host name from `Server.GetStatus` (`server.host.name`, `mac`; both may be empty, see `docs/tech.md`); a server found twice (mDNS + manual) is merged.

### Receiver model (decision: one receiver per group)
- `SnapcastGroupDevice`: a synthetic Cling-compatible `Device` for each group, **not** registered in Cling's registry (so it is never advertised), with id `snapcast:<serverKey>:<groupId>` (UDN-shaped), friendly name = group name, or the first member's name when the group has none, plus " (Snapcast)". It is added to `UpnpClient.getDevicesProvidingAvTransportService()` and resolved by `UpnpClient.getDevice(id)` through a `SnapcastDirectory`; precedent: `getLocalDummyDevice()`.
- Group ids are UUIDs created by the server; a group that gets all its clients removed disappears (src: groups are derived from the client set), so rows follow `Server.OnUpdate`.
- `createPlayer` returns a `SnapcastPlayer` for these ids. Code paths that assume AVTransport/OpenHome services (status polling `RendererStatus`, `OpenHomeChoice`, share addresses, renderer volume) must tolerate a device without those services; Group 1 inventories every such place.
- Members: each Snapcast client is shown in the sheet like a MusicCast member (name, connected, volume slider, mute, latency in a detail menu). Add/remove client uses `Group.SetClients` within the same server only.

### Inventory result (task 1.4, 2026-11-10): synthetic device is a GO
A receiver is a Cling `Device` throughout, but the code only needs a `Device` with an identity: `UpnpClient.LocalDummyDevice extends Device` (UDN `LOCAL_UID`, `getServices()` null) already is such a device and is handled everywhere. `SnapcastGroupDevice` follows it (UDN-shaped id `snapcast:<serverKey>:<groupId>`, `getServices()` null, never registered with Cling). Changes needed, by place:
| Place | Change |
|---|---|
| `UpnpClient.getDevicesProvidingAvTransportService()` (~523) | append the groups of `SnapcastDirectory` after the registry devices (the local dummy stays first) |
| `UpnpClient.getDevice(String)` (~575) | resolve `snapcast:` ids from `SnapcastDirectory` before the registry lookup |
| `UpnpClient.getReceiverDevices()` (~1259) | none: unknown ids are dropped, and `ReceiverAvailabilityPolicy` already gives a vanished receiver a few looks |
| `PlayerService.createPlayer` (~577) | new branch for Snapcast ids before the OpenHome/AVTransport choice |
| `PlayerService.createQueuePlayers` (~422) | treat a Snapcast group like the direct local case (no share, the phone reads and decodes itself); skip `OpenHomeReceivers.isOpenHomeOnly` for it |
| `ReceiverRowBinder` (~236, ~1112, ~1205-1262) | icon: already falls back when the device is no `RemoteDevice`; volume and status paths use `findService`/`RendererStatus`: add a Snapcast branch (group volume from the model, state from the player) |
| `DeviceSheetAdapter` (~249) | the live list comes from `ReceiverLiveUpdates.watched(...)`: add the Snapcast source |
| `ShareAddressResolver` (~81) | none for the feed (no share needed); guard the descriptor URL use for a device that is neither local nor remote |
| `YxcPlayTargets`, `YxcRowFeed` | none (they act on `RemoteDevice`s only) |
| `DeviceSessionGrouper`, `ReceiverPickController`, `LocalPickController` | none (string ids) |
| `RendererStatusMonitor.pollDevice` | skip polling for a Snapcast group (its state comes from the model and the player) |
| `ReceiverListFragment` (~85, ~186), `BrowseReceiverDeviceAdapter` (~112, ~262) | the classic receiver tab lists `getDevicesProvidingAvTransportService()`: rows must render a device without services; check each `findService` use |
| `SnapcastGroupDevice` itself | override `getDetails()` (friendly name, manufacturer text) and `getDisplayString()` like `LocalDummyDevice`, otherwise the rows and player names break |
| `Player.getDeviceId()` | returns the receiver id, here `snapcast:<serverKey>:<groupId>`; parse it from the right (the server key contains `host:port`) |
Inventory counts (main sources): app code under `de/` only: `getDevice(` 97 uses in 26 files (many are unrelated registry calls), `findService` 24 in 13, `getReceiverDevices` 14 in 4, `getIdentity().getUdn()` 86 in 27 (the vendored `org/fourthline` tree has more and is not touched). Decision: no `ReceiverTarget` seam; build the synthetic device.

### Feed: playing into a group (Phase A)
1. `SnapcastStreams.ensure(server)`: find a stream named `YAACC <phone name>`; if absent call `Stream.AddStream` with a tcp source in server mode, e.g. `tcp://0.0.0.0:<port>?name=YAACC%20<phone>&mode=server&sampleformat=48000:16:2&codec=flac` (src: `doc/configuration.md`; the server listens, the producer connects) (settled from the source, see `docs/tech.md`: raw PCM without header, 0.0.0.0 is allowed, `codec` is the transport codec toward clients, a taken port fails the call with -32603 so the next port is tried; the free-port choice from the existing streams' URIs is confirmed on the rig).
2. `Group.SetStream(group, stream)`; remember the group's previous stream id and restore it when playback ends (our own end of playback, not the stream status `idle`, which also appears during silence).
3. `PcmFeeder`: opens the TCP connection to the server and writes the PCM of what YAACC plays on this group. Pacing (src, `streamreader/asio_stream.hpp`): the server reads one chunk (`chunk_ms`, default 20 ms) at a time and waits for each chunk's real-time deadline, so TCP back pressure already throttles a producer that is ahead; the stream clock starts at the first read after connect, and a late read makes the server resync (an audible break). The feeder therefore keeps a small queue of a few chunks, uses a small socket send buffer, never underruns, and never runs far ahead: every byte in flight delays pause/stop by bytes / 192000 B/s (48000:16:2). Only one producer connection is accepted at a time per tcp source, so each phone uses its own stream.
4. Audio path: YAACC's local player decodes with Media3; a Phase A `AudioProcessor` tap converts to the stream's sample format and hands PCM to the feeder instead of (or besides) the local speaker; the local audio output is muted for a Snapcast-only play. (to check in the on-device spike, task 4.1: `TeeAudioProcessor`/custom `AudioProcessor` in media3 1.11.1, resampling with `SonicAudioProcessor`, behavior at track changes: gapless; note that digital silence of 100 ms or more makes the server mark the stream `idle` while data still flows, which is harmless for playback, see `docs/tech.md`.)
5. Transport semantics: pause/stop/seek act on the YAACC queue immediately but are audible after the server's buffer (default `bufferMs` 1000, src ServerSettings); document it. Volume of the group = group volume above; the phone's own volume is not touched.
6. Cleanup: remove the stream with `Stream.RemoveStream` after our own idle timeout (no audio written for 10 minutes by default) and on user request; never remove streams YAACC did not create (name prefix + a marker in the name).

### UI (Phase A)
- Receivers section and device sheet show the group row (name, stream, "n clients", playing state, volume) and the member list; same row binder and live updates (`ReceiverLiveUpdates` with a Snapcast source: control-socket notifications instead of polling, coalesced at 300 ms).
- Settings: "Snapcast servers" (manual list, enable discovery). All new strings in the 7 locales; German `docs`/README text; tooltips as elsewhere.

### Phase B: the phone as a client
- `protocol`: little-endian messages (src: Base 0, CodecHeader 1, WireChunk 2, ServerSettings 3, Time 4, Hello 5, ClientInfo 7, Error 8; StreamTags 6 only if needed), `Hello` JSON with `ID` = a stable phone id (the MusicCast/UPnP mapping convention of `notes.md` Discussion 3 applies: client id = a stable id derived from the YAACC device UDN), TCP port 1704.
- Time sync (src): periodic `Time` messages, offset = (latency_c2s - latency_s2c) / 2 assuming a symmetric network; keep a short median/Kalman-free filter first (the spec of Snapcast does not require a filter, unlike other protocols); expose offset/jitter for tests.
- Audio engine: decode PCM (codec header = RIFF WAVE) and FLAC (Media3/`MediaCodec` `audio/flac` with the STREAMINFO header) (Opus/Ogg are out of Phase B unless trivial); schedule chunks by `timestamp + bufferMs - latency` against the corrected clock; an `AudioTrack` in streaming mode with `getTimestamp()` feedback; correct drift by dropping/duplicating single frames (no pitch change) as small steps.
- Control plane through the YAACC renderer: when the renderer's output is "Snapcast group X", the renderer feeds the group (Phase A) and the phone is a client of that same server; volume/mute of the renderer map to the phone client's volume (`ClientInfo` message and `Client.SetVolume`); the local direct audio output is switched off so the phone plays only what comes from the server (stays in sync with the other clients).
- Runs as a foreground service with a wake lock only while it plays; Wi-Fi lock.

## Security rules for Phase B (added 2026-11-11, before Group 7)
The snapserver is untrusted for the binary connection as well (spoofed mDNS, hostile LAN host, a typed-in server that is not what it seems). Everything it sends is untrusted; the client must never be a way to read or write anything on the phone and must not leak more than it needs to.
- **Framing:** the 26-byte base header is read fully with a read timeout; `size` is checked against a cap before any allocation (default 8 MiB for a WireChunk or CodecHeader, 64 KiB for every other type); a larger or negative (u32 above `Integer.MAX_VALUE`) size drops the connection; unknown types are skipped by their (capped) size; the sum of unread bytes and the number of queued chunks are bounded; no allocation sized by an inner length field before it is checked against the remaining message size (CodecHeader/WireChunk/Error carry their own u32 lengths: reject any that exceeds the message).
- **Strings and JSON:** Hello/ServerSettings/ClientInfo JSON passes the same depth/token pre-scan and size cap as the control client (`SnapcastJson`); server strings (error text, codec name) are display text only, cleaned and truncated, never logged with addresses; the codec name is matched against a fixed allow-list (`pcm`, `flac`), anything else is refused with a typed error and no decoder is created from server data.
- **Decoding:** PCM header (RIFF WAVE) fields are validated (channels 1..8, sample rate 8000..192000, bits 16/24/32 in the ranges the engine supports, data length not trusted); the FLAC header must be a STREAMINFO block of the exact length; the decoder runs on a worker with a watchdog; a chunk that fails to decode is dropped, repeated failure ends the session. No native code.
- **Time and scheduling:** timestamps are untrusted: the offset filter clamps jumps (a step larger than a stated bound is treated as a server restart and re-synchronised, never applied at once), chunks too far in the future (more than `bufferMs + 5 s`) or too old are dropped, the chunk queue is bounded in bytes (not only in count). `bufferMs`, `latency` and `volume` from ServerSettings are clamped (bufferMs 0..10 000, latency -10 000..10 000, volume 0..100).
- **Connection:** the client connects only to the host the user (or the discovery gate) selected; one connection per server; reconnect with the backoff and stop rules of the control client; a server loss is reported once. It never follows a host or port that the server sends. No credentials are sent (Hello without `Auth`); a server that requires authorization is refused with a clear message.
- **Disclosure (Hello):** the Hello JSON tells the server `ID`, `HostName`, `MAC`, `OS`, `Arch`, `ClientName`, `Version`. `MAC` is a fixed, non-identifying value (`00:00:00:00:00:00`, as Android does not expose the real one), `HostName` is the UPnP device name already announced, `ID` is derived from the YAACC device UDN (stable, not the hardware id); no IMEI, account or Wi-Fi data. The documentation must say that the server and every controller see these values and that the audio and the control are unencrypted.
- **Resources:** a foreground service with the standard notification only while playing; wake lock and Wi-Fi lock only while playing and released on every stop, error and service destroy; no listener socket is opened on the phone (client role only).
- **Review order:** group 7 (pure) gets the usual reviewer and security reviewer; group 8 only after the owner's spike; the engine and the service get their own security review (wake locks, foreground service type, permissions).

## Security rules (security review of Group 1, 2026-11-10)
A snapserver may be hostile or spoofed (mDNS is unauthenticated and carries no TXT records, any LAN host can advertise one), so everything it sends is untrusted.
**Control client (`SnapcastRpc`, task 2.x)**
- Read bytes, not `readLine()`: a line is capped at 1 MiB at the byte level; the connection is dropped on a longer line. Every call has an overall deadline (default 10 s) as well as the per-read timeout, so a dripping sender cannot hold a call.
- Before a line goes to `org.json`, a byte-level pre-scan rejects nesting deeper than 32 and a line with more than 10 000 tokens; `StackOverflowError` is not an `Exception`, so the guard must come first (precedent: `YxcJson.parseObject`, a quote-aware depth scan plus a handler). Parsing happens on the RPC worker only.
- Caps: batch arrays at most 64 elements; at most 64 groups, 256 clients per group, 64 streams; strings (names, ids, URIs) at most 1 KiB, truncated; numbers clamped (volume 0-100, latency 0-10 000 ms); unknown notifications are ignored, not queued.
- "Re-read on doubt" is rate-limited (at most one full `Server.GetStatus` per second, at most 10 per minute); reconnects use backoff up to 30 s and stop after 5 failures until the user (or the receivers page becoming visible) retries.
- Backstop at the worker boundary: catch `Throwable` there (`StackOverflowError`, `OutOfMemoryError`), drop the connection and report a failure; request ids are generated locally and the pending-request map is bounded (at most 32); the hostile-input tests include a dripping reader (one byte per few seconds) that must hit the call deadline.
- Server-supplied names and ids are display text only: never used as an identity key, never written to logs together with MAC/IP/host names (log counts and ids' hashes instead), shown escaped in the UI.
**Feeder and stream handling (tasks 4.x)**
- The feed connects only to the **peer address of the control connection** (the `InetAddress` YAACC itself connected to) and the port YAACC asked for; a host, port or URI in a server reply is never a connect target. The server reports `0.0.0.0:<port>`; that is only used to match, not to connect.
- The stream name carries a random per-install marker (`YAACC <phone name> <marker>`); YAACC removes only stream ids it recorded itself, restores a group's previous stream only if the group is still on YAACC's stream, and adopts an existing stream with that marker only if it is `tcp`, `mode=server` and in the expected format.
- The phone name in the stream URI is percent-encoded in full (a name such as `a&mode=client` must stay inside the `name` value; unit test).
- The free port is chosen by YAACC from its own range (4953 and up) and checked against the status, with a bounded number of tries.
**Discovery and servers (tasks 3.x)**
- Accept only addresses in loopback, private, link-local or unique-local ranges; cap the number of discovered servers (default 16); connect lazily (when the receivers page is visible and for servers that are listed or chosen); no automatic feed to a server the user has not picked; manual `host[:port]` entries are validated strictly (host name or IP literal, port 1-65535).
- No credentials stored (authorization is out of scope); multicast lock only while the page is visible.
**Residual risk (documented, not removed):** the TCP feed port on the snapserver is unauthenticated, so a LAN host that sees the stream port can also push audio into YAACC's stream; this is a property of Snapcast's tcp source and is documented in the README. The rig is for trusted machines only.

## Interfaces & Contracts
- `SnapcastRpc.call(method, params) -> JSONObject` (blocking with timeout, used from the worker), `SnapcastRpc.addListener(Notification)`; `SnapcastModel` (server, groups, clients, streams) immutable; `SnapcastDirectory` (servers, groups, merged ids); `PcmFeeder` (`start(format)`, `write(byte[], len)`, `stop()`); all with injectable transports so JVM tests use a loopback fake server (as `YxcClient` does with a fake YXC server, `Host = localhost`).
- Persistence: manual server list in preferences; the stream names/ids YAACC created, to clean up after a crash.

## Test strategy
- Pure JVM: RPC framing and pending/timeout logic, model parsing from fixtures (`Server.GetStatus`, notifications, errors), group mapping and ids, relative group volume, stream URI building, pacing math, (Phase B) message codec round trips, time filter, scheduler.
- Rig (opt-in, Docker): `tools/snapcast-rig/` with one snapserver and two snapclients (null audio output); integration tests: AddStream accepted, group moves, volume changes seen by a second RPC connection, feed accepted and heard as `playing` stream state, (Phase B) a Java client receives chunks and its offset converges.
- Manual: the owner listens (sync, pause latency, track changes).
- Build gate: `:yaacc:testDebugUnitTest`, lint, assemble; reviewer then security-reviewer after each group.

## Risks (most serious first)
1. Real-time PCM out of the Android player (tap, resampling, pacing, gapless): Group 4 has a spike with a kill criterion before UI work.
2. Receivers are Cling `Device` objects in many places; a synthetic device may leak into code that assumes services. Mitigation: inventory in Group 1; fall back to a small `ReceiverTarget` seam if the inventory shows too many places.
3. `Stream.AddStream` may be refused or limited on some servers (security restriction, bind address): fallback documented static source; clear error row.
4. Phase B sync quality on arbitrary Android devices (output latency varies) and doze/Wi-Fi power save; needs a per-device manual latency setting and kill criteria.
5. mDNS on Android (multicast lock, vendor quirks): manual entry is the fallback.
6. Niche audience (see `notes.md`): keep Phase A small; Phase B only if Phase A is used.

## Open Items (not decided here)
- A UPnP/OpenHome facade per Snapcast group for third-party controllers (Home Assistant etc.): large value, own spec.
- Authorization (Basic) for control and Hello.
- Snapcast streams of other types as sources inside YAACC's UI (metadata, `Stream.Control`).
