# Snapcast support: first assessment (#319)

Status: background and research for the spec in this folder (`requirements.md`, `design.md`, `tasks.md`, `decisions.md`). Written during the discussion; the spec has the decisions.

## The idea (owner)
YAACC as a UPnP/OpenHome device, the control plane, with different sources, like a MusicCast device. One source is Snapcast: Snapcast is the audio
transport, UPnP the control, so playlists and the usual UPnP controls work from any control point (Home Assistant, BubbleUPnP, ...). Selecting the
source lets the device be linked to other Snapcast clients in the network. Plain Java, no native code, so the Snapcast protocol has to be ported.

## Findings
- **OpenHome has a link, too: Songcast** (`Sender` and `Receiver` services, `Receiver.SetSender` with an `ohz://` address). Control points such as Upplay link with it,
  upmpdcli supports it (sc2mpd); other control points are patchy (a forum report says Kazoo does not link upmpdcli). Its audio transport is Songcast's own, and
  there are far fewer cheap clients than Snapcast clients. This is why Snapcast is attractive: the client ecosystem (Pi, ESP32, ...).
- **Linking Snapcast clients is not possible from an arbitrary UPnP controller.** UPnP has no standard for it and Snapcast clients do not speak UPnP; grouping goes
  through Snapcast's JSON-RPC. So the link UI is YAACC's (like MusicCast) or Snapweb/Snapdroid. What any UPnP controller gets is the playlist, transport, volume and
  queue of the group: that is the real value.
- **A Snapcast client talks to exactly one server at a time** (checked in the source, see below). That decides the design.
- Snapcast is GPLv3 (YAACC is GPL v3 or later): compatible.
- Already possible today without YAACC: upmpdcli + MPD feeding a snapserver (Pi / Docker). The phone version wins only without extra hardware or for YAACC's other features.

## Variants
- **V1, YAACC feeds an existing snapserver:** the phone sends PCM into a stream source of the server (its TCP source), grouping and linking through the server's JSON-RPC.
  Much smaller (no binary protocol, no sync); fits people who already run Snapcast. Needs a snapserver (and a stream entry, unless streams can be added at runtime).
- **V2, YAACC is the Snapcast server:** the binary protocol (Hello, ServerSettings, CodecHeader, WireChunk with timestamps, Time sync; StreamTags for metadata) with the
  PCM codec (no encoder), plus a JSON-RPC subset for groups and clients. Larger; suits people with only snapclients; excludes people with a server.
- **V3, YAACC as a Snapcast client** (the phone as a speaker): not the goal, an Android client exists.
- Recommendation: V1 as the main path, V2 maybe later.

## Risks (most serious first)
1. Real-time PCM out of the Android player (a tap in the Media3 audio chain, real-time pacing when nothing plays locally, one stream format so every track is resampled,
   gapless track changes).
2. The phone as an always-on source: doze, Wi-Fi power save, process kills.
3. Snapcast's buffer (about a second): Pause, Seek and the other controls are audible that much later.
4. Testing: sync quality cannot be unit tested; a Docker rig (snapserver + two snapclients) like the upmpdcli one is needed.
5. Audience: niche, as an optional source.

## POC plan with kill criteria
1. Plain-Java spike, no Android: a minimal server streaming a tone to two Docker snapclients. Pass: both in sync for ten minutes, no underruns.
2. Android tap: play a UPnP queue into the stream in real time. Pass: half an hour with the screen off, track changes do not break sync.
3. Control: expose it as the OpenHome renderer (reuse the existing one), a link sheet like MusicCast's. Stop if 1 or 2 fails.

## Verified against the Snapcast sources (v0.35.0, `doc/binary_protocol.md`, `doc/json_rpc_api/control.md`, `client/`, `server/`)
- **Binary protocol:** little endian, TCP 1704. Ids: Base 0, CodecHeader 1, WireChunk 2, ServerSettings 3, Time 4, Hello 5, ClientInfo 7, Error 8 (StreamTags 6 is not in
  the doc; check the source). Join: Hello, ServerSettings, CodecHeader, then WireChunks; the client sends Time periodically and computes the offset from the round trip.
  Codecs: flac, ogg, pcm (RIFF WAVE header), opus. ServerSettings carry bufferMs (default 1000), latency, volume 0-100, muted.
- **Control:** JSON-RPC 2.0 over raw TCP 1705 (ndjson) and HTTP/WebSocket. Methods: Server.GetStatus, Server.DeleteClient, Client.SetVolume/SetLatency/SetName,
  Group.SetStream/SetClients/SetMute/SetName, Stream.Control/SetProperty/AddStream/RemoveStream. Notifications: Client.OnVolumeChanged, Group.OnStreamChanged,
  Server.OnUpdate and others. `Stream.AddStream` accepts only pipe, file, tcp, alsa, jack and meta (security restriction): a stream can be added at runtime.
- **Feeding a server:** `tcp://<ip>:4953?name=<n>` (server mode, default) takes PCM/WAV pushed by a producer (the Mopidy/GStreamer example); that is the V1 sink.
- **One server per client:** the client takes one URI (`tcp://host:1704`, ws, or the default `tcp://_snapcast._tcp` = mDNS, which takes the first server found).
  There is **no JSON-RPC method that moves a client to another server** (no Client.SetServer); it only reconnects on its own. Several snapservers can coexist in a
  network (each with its own clients), but a third-party snapclient is bound to its configured server.
- Open: StreamTags (id 6), a FLAC/Opus decoder for a client in plain Java (otherwise the stream must be configured with `codec=pcm`), the Media3 audio tap.

## Discussion 2 (owner's ordering and the final gate)
Order proposed by the owner: (1) Docker rig with a small snapserver and snapclients, (2) YAACC accepts a Snapcast server as a target and shows its controls (volume
etc.), (3) the protocol in plain Java, tested without Android, (4) YAACC as Snapcast client with a control plane through the YAACC renderer, (5) YAACC as server with
linking, (6) the final gate: how to link through UPnP/OpenHome, or a smart controller speaking both.
- Step 2 maps well on the JSON-RPC: Client.SetVolume/Group.SetStream plus the notifications fit the existing receivers/volume UI. Step 3 can start as a client against
  the Docker snapserver (needs only Hello, Time and PCM chunks), the server side follows.
- **Linking when YAACC is the server:** a YAACC device that runs a server and a client can link to another YAACC device by pointing its client to that device's server
  (unlink = back to its own server). That works because YAACC controls its own client end. Third-party snapclients cannot be moved by anyone but their own configuration.
  mDNS: two YAACC servers must not both be published under the default name, or mDNS-default clients pick whichever answers first.
- **Final gate, my recommendation:** a smart controller (YAACC's own UI speaking UPnP/OpenHome and Snapcast/YXC-style side channels), as for MusicCast. Standard UPnP
  and OpenHome have no link concept except Songcast (`Receiver.SetSender`, its own transport). A vendor-specific UPnP service for the link could be offered later, but
  no foreign controller would show UI for it; other controllers get playlist, transport and volume of the group through the leader device.

## Discussion 3 (client reconfiguration, identity mapping, a "Snapcast UPnP" convention)
- **A client cannot be reconfigured over the network** (checked in `client/controller.cpp`): the client reacts only to WireChunk, ServerSettings (bufferMs, latency,
  volume, muted), CodecHeader, Time and Error from the server; nothing carries a server address. Its server comes from its command line (`tcp://host:1704` or the
  mDNS name `_snapcast._tcp`, re-browsed on each reconnect). JSON-RPC cannot move it. What JSON-RPC **can** do for any client **of the same server**: groups
  (`Group.SetClients`), stream (`Group.SetStream`), volume, mute, latency, name. So with one snapserver, linking is fully controllable for foreign clients; only moving
  between servers is not.
- **Identity:** a Snapcast client id is its `hostID` (default MAC, plus `#instance`), unrelated to the UPnP UDN. `snapclient --hostID` accepts any string. The mapping needs
  a convention, not a protocol: for YAACC's own devices hostID = UDN (trivial); for foreign pairs match by IP + name heuristics or a user pairing in the app.
- **A small convention instead of a new wire protocol:** MusicCast does the same at its core, a vendor element in the UPnP device description
  (`X_yxcControlURL`) plus an id convention (UDN suffix = device id). The Snapcast equivalent would be two fields in the description: the JSON-RPC control URL of the
  server the device is attached to, and the device's Snapcast client id. Cheap for other renderer projects to adopt (one extra XML element); everything else
  stays JSON-RPC. Naming: own namespace, never a "snapcast" urn (it would read as official; trademark rule).
- **Piggyback on existing adoption (needs an experiment):** OpenHome already has the link idea (Songcast `Sender`/`Receiver.SetSender`). If controllers that offer Songcast
  linking accept an opaque sender URI/metadata, a Snapcast-flavoured sender might show up in their UI without a new protocol. Unknown how strictly they validate the
  `ohz://` scheme; test with the existing OpenHome renderer (a Sender/Receiver facade) before building on it.
- Honest odds: adoption by the Snapcast or renderer communities cannot be planned; the design must be useful for YAACC alone and the convention is then published as a
  short document. The better upstream target may be Snapcast itself (a "use this server" message), but it would take years to reach clients.

## Discussion 4 (goal restated: an open multi-room system as flexible as MusicCast)
Owner: Snapcast clients cannot be re-pointed, so Snapcast can be one more target type for the app, but the real goal is an open multi-room system with MusicCast's
flexibility (devices link and unlink at runtime, any device can lead a group, one control plane). Candidates (web research, partly unverified, see below):
- **Sendspin** (Open Home Foundation, formerly "Resonate"; the native protocol of Music Assistant; ESPHome has a component; a release candidate was reported). First
  read by a subagent; **the spec was then read first-hand (Discussion 5), which supersedes the details here where they differ**:
  - A **server** orchestrates devices, generates the streams, manages players and groups; **clients** have roles (player, source, controller, metadata, artwork, ...).
    A client belongs to exactly one group. Not found: a device being server and player at once.
  - **Discovery in both directions** (servers must support both): server-initiated (recommended) = the client advertises `_sendspin._tcp` (port 8928) and the server
    connects to it; client-initiated = the server advertises `_sendspin-server._tcp` (port 8927). WebSocket transport.
  - **Re-pointing is built in:** a new server connecting to a player displaces the old one (`client/goodbye`, reason `another_server`); no client reconfiguration.
  - Binary audio chunks with a server timestamp (µs) and `send_ahead`; codecs flac and pcm (mandatory for servers), opus optional; clock sync by `client/time` /
    `server/time` with a Kalman filter (accuracy claimed about 1 ms). Control: `server/command` volume (0-100, perceptual) / mute / `set_output_delay`; `client/state`,
    `group/update`, `stream/start|clear|end`. A Noise handshake (KKpsk2) is mentioned for encryption: check whether it is mandatory (effort in Java).
  - Spec under the Community Specification License 1.0 (royalty-free implementation); trademark with the Open Home Foundation (nominative use only); reference
    implementations mostly Apache-2.0 (Python, C++, TypeScript, Rust, Swift, C#). **No Java/Kotlin implementation found.**
- **Songcast** (OpenHome): the receiver pulls a sender via a UPnP action, the closest to MusicCast's peer model; open parts exist, but the sender tooling is
  reported unmaintained and controller support is patchy (see Findings).
- **Snapcast**: server-centric, clients fixed (this note, above). **Music Assistant** is the existing smart controller that already bridges Snapcast, Sonos, AirPlay,
  Cast and Sendspin (sync groups); a competitor to a YAACC controller rather than a basis.
- Honest pattern: every open, dynamic system has a central orchestrator (the server). In YAACC's case the orchestrator can be the phone (it is already a media server and
  controller), the way a MusicCast "server" device leads its group.
- **Consequence for #319:** keep Snapcast as an optional target type (V1, cheap), and evaluate Sendspin first as the multi-room backend: read the full spec (Player,
  Server, Group, `client/leave`, Noise), then a plain-Java spike (player and/or server) against `sendspin-python-cli` / Music Assistant. The Docker rig is still useful
  (Sendspin CLI containers instead of snapclients).

## Discussion 5 (Sendspin spec read first-hand, `Sendspin/spec` at c281aeb, 2026-11-09)
Spec files: `README.md` (normative: connection, Noise, messages, roles), `pairing.md`/`messaging.md`/`connection.md`, `roles/`, `SCOPE.md`; license Community Specification
License 1.0 (royalty-free patent grant for conforming implementations; "Sendspin" is a trademark of the Open Home Foundation: nominative use only).
- **Roles:** player, source, controller, metadata, artwork, visualizer, color; a client lists `supported_roles` (`player@v1`, ...), the server activates them. A phone could
  be a controller, a player, a source (streams captured audio to a server) and a server in one app. A client is in exactly one group; group id is server-chosen.
- **Connections:** server-initiated (recommended): the client advertises `_sendspin._tcp` (8928, TXT `path`), the server connects; client-initiated: the server advertises
  `_sendspin-server._tcp` (8927). Servers must support both. Plain `ws://` plus an end-to-end **Noise** layer.
- **Multiple servers = the dynamic part:** a client admits one connection at a time; at most one has the `'playback'` activity; a newer activation with equal or higher rank
  displaces the old (`client/goodbye` `another_server`), the last-playback server wins ties. So **linking a device = another server connects and activates playback on it**;
  no client configuration. `client/leave` makes the client a solo group; the controller command `switch` cycles a client through playing groups. Group membership is otherwise
  decided by the server (there is no standard "put client X into group Y" command from a controller).
- **Audio:** binary chunks with a server µs timestamp, 15-150 ms each; flac and pcm (16/24 bit, 44.1/48 kHz, 1-2 ch) mandatory for servers, opus optional. Clock sync by
  `client/time`/`server/time` with a required Kalman-style time filter. **Player accuracy must be within ±1 ms (target ±0.5 ms), speed deviation within ±0.5 %,** suggested
  strategy: drop/duplicate single frames. Group volume: delta algorithm over the players (`controller` command `volume`). Per-player `output_delay_ms`.
- **Cost for a Java implementation (the real risk):**
  - Noise `KKpsk2` with X25519 + ChaChaPoly or AESGCM + SHA256 is mandatory on every connection (identities are Curve25519 keys; `client_id` = 43-char base64url public key).
    Android minSdk 27: JCA lacks X25519 before API 33, so a library (Bouncy Castle lightweight API or a Noise library) is needed; pin versions, security review.
  - Pairing: a client must implement the Pairing PSK method; a **server must implement all three** (Pairing PSK, dynamic and static pairing codes), the code-based ones use
    **CPace (draft-irtf-cfrg-cpace-21, X25519/SHA-512)**: no standard Java library; a hand-written PAKE needs careful testing and review. A spike can use unpaired access
    (Sentinel PSK, operator approval) and the Pairing PSK first.
  - Player sync at ±1 ms on Android needs a custom `AudioTrack` path (timestamps, frame drop/insert), not the stock ExoPlayer pipeline; same class of risk as Snapcast.
  - A phone as server has the always-on problem (doze); a mains-powered YAACC device (tablet, TV box, Pi) fits better as the orchestrator.
- **Identity vs UPnP:** `client_id` is a public key, unrelated to the UPnP UDN; `client/hello` can carry `device_info.mac_address`. The mapping convention from Discussion 3
  (two description fields: control endpoint and client id) applies unchanged.
- **Verdict for #319:** Sendspin matches the "flexible as MusicCast" goal better than Snapcast (dynamic multi-server, groups, controller role, group volume, source role,
  metadata/artwork, active spec and ecosystem: Music Assistant, ESPHome, CLI players), at the price of a larger protocol (Noise, pairing, tight sync). Snapcast stays the cheap
  optional target type. Suggested spike order: (1) Docker rig with `sendspin-python-cli` player(s) and a server (Music Assistant or a Python server); (2) plain-Java **client
  connection only** (WebSocket, Noise with unpaired access, `client/hello`, time sync, receive PCM chunks, no audio output); (3) decide on the server role and CPace.

## Discussion 6 (adoption: is this a niche?)
- **SendspinZero** (RealDeco, ESPHome YAML + 3D files, no protocol code of its own): a DIY ESP32-S3 Sendspin player (analog or digital out, optional display/button), plus
  firmware installers; it shows the cheap-hardware side. The author points to ready-made players (Apollo CAST-1, a Guition display player).
- Adoption evidence (web search, not measured): Sendspin is young (formerly "Resonate"), the Music Assistant docs call it a **technical preview** and name limits (one
  Music Assistant server per network for Sendspin; earlier notes: 16-bit only); an Android client app says it needs Music Assistant 2.11 for Sendspin 1.0. Players exist
  (MA web player, ESPHome, Bluetooth bridge, tablet app, Pi players), the backing is the Open Home Foundation. Snapcast is older and established in the self-hosting
  scene, has a Home Assistant integration, but its status inside Music Assistant is unclear (one source says a beta dropped it).
- Conclusion: both are niches inside a niche (self-hosters, Home Assistant users); Sendspin has momentum but no proven reach. Do not make it a roadmap bet: keep Snapcast
  as the cheap optional target type, run only the Docker rig for now, and revisit Sendspin when Music Assistant ships it as stable.

## Open questions for the discussion
- V1 or V2 first (or the spike of step 1 first)? Is a snapserver a fair requirement for V1?
- Where does the "link" live for other controllers: only in YAACC, or also OpenHome Product sources / a Songcast-style Receiver facade?
- Does the phone play too (a member of its own group) or is it source only (no latency calibration needed)?
