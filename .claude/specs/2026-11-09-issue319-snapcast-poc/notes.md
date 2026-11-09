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
- **A Snapcast client talks to exactly one server.** That decides the design (below).
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

## To verify before a spec (not checked first hand: GitHub was not reachable for the fetch tool)
- The binary protocol (message ids, Time semantics, WireChunk layout): `doc/binary_protocol.md` of Snapcast. Third-party implementations give Base 0, CodecHeader 1,
  WireChunk 2, ServerSettings 3, Time 4, Hello 5, StreamTags 6; an older Python library numbers differently (version difference).
- The JSON-RPC methods (Server.GetStatus, Group.SetClients, Group.SetStream, Client.SetVolume, Stream.Control, runtime `Stream.AddStream`) and the TCP stream source options.
- Media3: tapping PCM (`TeeAudioProcessor`), resampling (Sonic) and a real-time paced sink.

## Open questions for the discussion
- V1 or V2 first (or the spike of step 1 first)? Is a snapserver a fair requirement for V1?
- Where does the "link" live for other controllers: only in YAACC, or also OpenHome Product sources / a Songcast-style Receiver facade?
- Does the phone play too (a member of its own group) or is it source only (no latency calibration needed)?
