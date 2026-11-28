# Issue #330: a Snapcast server in YAACC (coarse analysis)

Status (2026-11-28): the owner answered the questions at the end of this file (`decisions.md`); `requirements.md`, `design.md` and `tasks.md` are drafted. The text below is the analysis of 2026-11-17, unchanged. (Original status: **analysis only**. No decision, no requirements, no design yet.) This file is the feasibility answer the owner asked for at the end of issue #319 (also kept short in `.claude/specs/2026-11-09-issue319-snapcast/decisions.md`, section "Idea: a Snapcast SERVER in the app"). The next step, if the owner wants it, is a spec (requirements, design with security rules, tasks).

## Why

Today YAACC plays into a Snapcast multi-room system only through an external snapserver (Phase A feeds a TCP stream source on it; Phase B makes the phone a client; Groups 9 to 12 added the renderer output "Snapcast group", merged client lists, the client card). A server inside the app would let the YAACC renderer and the local player feed Snapcast clients directly, without a separate snapserver, and would make YAACC a self-contained multi-room hub.

## What already exists (reusable) - checked in the code on 2026-11-17

- **Binary protocol:** `SnapcastMessageCodec` encodes and decodes all seven message types (Hello, ServerSettings, CodecHeader, WireChunk, Time, ClientInfo, Error), so the server direction exists. Also `FrameReader`, `FlacFrameHeader` and `FlacStreamInfo`, `PcmFormat`, `MicroClock`, `SnapcastProtocolLimits`. Package `de.yaacc.multiroom.protocol`.
- **Audio source:** the Phase A pipeline in `de.yaacc.multiroom.feed`: `PcmTapAudioProcessor` and `FeedTapChain` (the tap behind the Media3 renderer), `PcmFormatConverter` (to 48 kHz, 16 bit, stereo), `PcmFeeder` and `RealTimePacer`, `PcmFeederOutput`. Today the output is a TCP connection to someone else's snapserver; a built-in server would take the PCM in the same process.
- **JSON model:** `SnapcastServerStatus`, `SnapcastClient`, groups and streams are parsed from `Server.GetStatus` and the notifications (`SnapcastModelUpdater`); a server has to build the same JSON.
- **Discovery names:** the service types and their meaning per Snapcast version are documented and coded (`SnapcastServiceTypes`).
- **Test material:** `FakeSnapcastServer`, `SimulatedSnapcastServer`, `LoopbackControlServer` and `ConnectionFixtures` in the tests are partial servers for both ports; `tools/snapcast-rig` runs a real snapserver and snapclients in Docker; the Phase B client in the app is a real test peer for a server on the same machine.
- **App infrastructure:** foreground service patterns with locks only while playing (`SnapcastClientService`), the autostart receiver and the startup route (`Yaacc.onCreate`), the idle and auto-close rule (`AutoCloseRule`, `IdleFacts`), the power-button model for cards, settings and docs conventions.

## What is new

- Listeners on TCP **1704** (stream) and **1705** (JSON-RPC control); the tcp source port range for external feeders is optional.
- **Client sessions:** the Hello handshake, ServerSettings (volume, mute, latency, buffer), one write queue per client with back-pressure and a size limit.
- **Time sync responder** for the Time messages.
- **Chunk pipeline:** read PCM, cut into about 20 ms chunks, stamp with the server clock plus the buffer, encode (PCM needs no encoder; FLAC through a platform encoder, to be verified for API 27 and up), send to every client of the group, send the codec header first.
- **Server state and a JSON-RPC server** with the semantics of snapserver: groups, clients, streams, `Group.SetClients` (exact membership), `Group.SetStream`, `Group.SetMute`, `Client.SetVolume/SetLatency/SetName`, `Server.GetStatus`, `Server.DeleteClient`, the notifications, persistence so that groups and client settings survive a restart.
- **mDNS announcement** (`_snapcast._tcp` and `_snapcast-ctrl._tcp` for current Snapcast versions) and the lifecycle in a foreground service with the autostart and idle rules.
- **UI and settings:** a switch and a card for the server, ports, firewall hints, what is announced.

## Architecture idea (to be checked in a design)

The built-in server exposes its JSON-RPC on 1705 like any snapserver. The existing Phase A code (control library, receivers, group UI, link sheet, the renderer output "Snapcast group", the feed) then treats it as just another server on `127.0.0.1` or the phone's address, and needs few changes. That keeps one control path and gives the whole UI for free. Open point: the feed to the own server could skip the TCP hop (in-process stream) later.

## Difficulty and size

- Audio side: easier than Phase B (no AudioTrack, no drift correction against hardware latency).
- State and semantics: the hard part (compatibility with real snapclients and controllers such as Snapweb, Snapdroid and Home Assistant depends on details that the documents describe only in part; the snapserver source (GPL-3.0) has to be read; our implementation would be our own Java code, licence compatible).
- Rough size, no time estimate: a **minimal useful server** (one stream from the app's own playback, PCM only, a JSON-RPC subset, persistence of the basics, mDNS, a switch) is roughly comparable to Phase B. **Parity with snapserver** (many stream sources, all codecs, Snapweb over HTTP and WebSocket, authentication) is much larger and not a goal of a first version.

## Risks and open technical questions

- Android is not made to be an always-on server: background limits, Doze, battery, Wi-Fi power save and client isolation on some access points.
- **Security:** new unauthenticated listeners on the LAN. The Phase B rule "no listener socket" of the #319 design does not cover this; a server needs its own security rules and review (who may connect, who may change groups and volume, limits on connections, frames and memory, what is announced, default off).
- A second snapserver on the same network: clients that find servers through mDNS could pick the phone's server; naming and default-off matter.
- Timing quality: chunk timestamps and send scheduling on a phone CPU; the buffer size has to absorb jitter.
- FLAC encoding: availability and behaviour of the platform encoder (to verify), CPU and battery per client count; PCM first.
- Interplay with existing features: the renderer output "Snapcast group" and the client card must still work with an external snapserver; the client id, merge filter and autostart rules apply to the built-in server too.
- Port clashes on the phone (1704, 1705 are above 1023, fine) and with the feed port range 4953 to 4962 if external feeders are allowed.

## Staged path (proposal)

1. **Spec and rules:** requirements, design, security rules, scope decision.
2. **Binary core:** listeners, Hello, ServerSettings, CodecHeader, PCM chunks from one fixed stream (the app's own playback), time sync; test against a real snapclient on a notebook and against the Phase B client.
3. **JSON-RPC subset and state:** `Server.GetStatus`, group and client controls, notifications, persistence.
4. **Service and UI:** announcement, foreground service, switch and card, autostart and idle rules, docs.
5. **Later:** FLAC encoding, more streams and external tcp sources, Snapweb, authentication.

## Questions for the owner (before a spec)

- Scope of the first version: one stream from the app's own playback only, or also external feeders?
- Codecs: PCM only first (proposal) or FLAC from the start?
- Default off and an explicit switch (proposal); is authentication (a shared secret) required in the first version?
- Should the built-in server be discoverable by mDNS by default when it is switched on?
- Persistence: which state must survive a restart (groups, client names, volumes, latency)?
- Which controllers must work in the first version (the app's own UI, Snapweb, Home Assistant, Snapdroid)?

## Related

- `.claude/specs/2026-11-09-issue319-snapcast/` (requirements, design incl. "Security rules for Phase B", `notes.md` with the protocol research, `decisions.md`, open items in `tasks.md`).
- `.claude/specs/2026-11-09-sendspin/` (parked spec for Sendspin as server and client; the same question of a server in the app, a different protocol).
- `tools/snapcast-rig/` (Docker rig with real snapserver and snapclients), `docs/tech.md` (protocol and platform facts).
