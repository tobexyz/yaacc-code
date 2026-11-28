# Requirements: a Snapcast server in YAACC (#330)

Status: draft for the owner (2026-11-28). Decisions: `decisions.md`. Analysis: `analysis.md`.

## Goal

With one switch the phone becomes a Snapcast server: stock snapclients (and the phone's own client) join it, the app's own playback and external feeders are the streams, and the app's own Snapcast UI controls it like any snapserver.

## Functional requirements

- **R1 Switch and service.** *Settings > Snapcast > Snapcast server* (off by default) starts a foreground service that opens the listeners and announces the server; switching off closes them and ends all sessions. The service follows the app's autostart and idle rules.
- **R2 Stream port (1704).** A client that says Hello gets ServerSettings, then the CodecHeader of its group's stream, then WireChunks; it can send Time (the server answers), ClientInfo (volume, mute are taken as the client's state) and is dropped on a malformed or oversized frame.
- **R3 Control port (1705).** JSON-RPC 2.0, one request per line (ndjson): `Server.GetStatus`, `Server.GetRPCVersion`, `Server.DeleteClient`, `Client.SetVolume`, `Client.SetLatency`, `Client.SetName`, `Group.SetStream`, `Group.SetClients`, `Group.SetMute`, `Group.SetName`, `Stream.AddStream` (tcp only), `Stream.RemoveStream`, and the notifications `Client.OnConnect`, `Client.OnDisconnect`, `Client.OnVolumeChanged`, `Client.OnLatencyChanged`, `Client.OnNameChanged`, `Group.OnMute`, `Group.OnStreamChanged`, `Group.OnNameChanged`, `Stream.OnUpdate`, `Server.OnUpdate`. The answers have the shape that the app's own `multiroom.control` model parses from a snapserver (`SnapcastServerStatus`).
- **R4 Streams.** A stream is a tcp source in server mode (the producer connects to the server): PCM, with the sample format of the stream (default 48000:16:2). The feeder ports are 4953 to 4962; a stream is created at run time by `Stream.AddStream` (with the same limits as snapserver: tcp only) and removed by `Stream.RemoveStream` or when its producer goes. The app's own playback uses this path.
- **R5 Chunks.** The server cuts the PCM of a stream into chunks of about 20 ms, stamps each with the server time at which it is to be heard (the time it was read plus the buffer, default 1000 ms), and sends it to every client of the groups that play the stream. The CodecHeader goes first (PCM: the RIFF WAVE header; FLAC: the stream header).
- **R6 Codecs.** The codec of a stream is `pcm` or `flac` (stream parameter `codec`, default `pcm`; `flac` encodes on the server). `ogg` and `opus` are refused with a clear error.
- **R7 Groups.** A client is in exactly one group; a new client gets a group of its own (or the group it was in before, from the persisted state); `Group.SetClients` sets the exact membership, groups that become empty disappear, a client that moves is sent the CodecHeader of the new stream.
- **R8 State and persistence.** Client names, volumes, latencies, mutes and the groups (members, stream, mute, name) are persisted and restored; a client that is not connected stays in the status as disconnected.
- **R9 Discovery.** mDNS announcement while the server runs: `_snapcast._tcp` (port 1704) and `_snapcast-ctrl._tcp` (port 1705), with a name that tells it is a phone.
- **R10 UI.** A card or entry for the server, shown like any Snapcast server in the receiver lists (it appears as a normal server on the local address); settings with the switch, the ports, the buffer (ms) and the text about the risks; a status line (clients, streams, listening or not); the firewall and client-isolation hints in the docs.
- **R11 Compatibility.** The Phase B client of the app can join the built-in server on the same phone and another phone; a real `snapclient` (the Docker rig of `tools/snapcast-rig`) plays in sync with it.

## Security (new listeners on the local network)

- **S1 Default off** and no start at install; the switch text names the risk (unauthenticated: any device on the network can join, control and feed).
- **S2 Local network only:** a connection from an address that is not a local-network address is closed before anything is read (the filter of the discovery code); never the loopback from another app **unless** it is the app's own feed (see `design.md`, "Own feed": by a secret path or token, not by the address alone; the lesson of the Sendspin review).
- **S3 Limits:** at most 16 stream clients and 4 control connections at a time; a frame of a client is at most 64 KiB, a control line at most 64 KiB; a read has a timeout; a connection that has not said Hello within 5 s is dropped; a write queue per client has a size cap and drops the client when it is full (a client that stops reading never holds a thread or memory); a write that does not end is broken after a limit (as in `WsConnection`).
- **S4 Only tcp stream sources:** `Stream.AddStream` refuses `pipe`, `file`, `alsa`, `process` and every other source type, and refuses a path or a command in any parameter; at most 10 streams; names are cleaned and limited.
- **S5 Memory and CPU:** a feeder's data is read in fixed blocks; the buffer per stream is bounded by the buffer time; FLAC encoding has a bound on clients.
- **S6 No secrets:** no credentials are stored or sent; logs carry no audio and no client identifiers beyond what the app's logs already show.
- **S7 Review:** a general and a security review of the server before the feature is called done (the lesson of #319 and #325: the reviews found real problems).

## Not in the first version

Authentication, Snapweb (HTTP and WebSocket), Opus and Ogg, pipe and file sources, `Stream.Control` and stream properties (metadata), the in-process feed without the loopback hop, Snapdroid and Home Assistant as promised controllers.
