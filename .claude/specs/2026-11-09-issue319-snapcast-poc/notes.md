# Snapcast support: first assessment (#319)

Status: idea, not a plan. Written after a first discussion; to be discussed further before a spec is written.

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

## Open questions for the discussion
- V1 or V2 first (or the spike of step 1 first)? Is a snapserver a fair requirement for V1?
- Where does the "link" live for other controllers: only in YAACC, or also OpenHome Product sources / a Songcast-style Receiver facade?
- Does the phone play too (a member of its own group) or is it source only (no latency calibration needed)?
