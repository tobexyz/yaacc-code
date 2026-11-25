# The phone as a Sendspin player (P1 to P4)

Status: design and build in slices, 2026-11-25. Slice 1 (the messages) and slice 2 (the session) are in this file's "Done"; the audio
engine, the service and the settings are open.

## What it is
Other servers (Music Assistant, the reference server) play to the phone: the phone is a Sendspin **client with the player role**.
It is opt-in and off by default (a listening socket and a service). It is not the controller of the app (that is a client too, with
other roles) and not the in-process path of YAACC's own groups.

## How a server reaches the phone
- **Server-initiated (the recommended mode of the protocol):** the phone listens (port 8928, path `/sendspin`) and announces
  `_sendspin._tcp` (mDNS; the existing `SendspinServerAdvert` is generalized by the service type); the server dials. The reference server
  does this with `sendspin serve --client ws://host:8928/sendspin`, which is how it is tested here.
- **Client-initiated:** the phone dials a server it found (`_sendspin-server._tcp`), as the controller does. Later.
- The opening is `ClientHandshake` in both modes (it works whichever side opened the WebSocket).

## The slices
1. **Messages** (pure, tests first): the client side of the player role: `client/hello` with `player@v1` and its support object (formats,
   `buffer_capacity`, `supported_commands`), `client/state` with the `player` object (volume, muted, output delay, required lead time,
   minimum buffer), the parser of `server/command` for the player, of `stream/start`, `stream/clear` and `stream/end` for the player, and
   of the binary audio chunk (id 4: timestamp, send-ahead, audio).
2. **Session** (`PlayerSession`, like `ControllerSession`): handshake as a client, hello, activation, state, time bursts (`ServerClock`),
   the stream messages, the volume and mute commands; the audio goes to an `AudioPort` (an interface); a fake server tests it.
3. **Audio engine:** the audio goes to the engine that already plays the Snapcast stream in sync (`SnapcastClientEngine`, `DriftController`,
   `FrameCorrector`, `AudioTrackSink`): the chunk timestamps and the clock offset are mapped to what that engine takes (one PCM header, chunks
   with a server time, the offset of the clock). Needs a close reading of that engine's inputs; a Sendspin timestamp is the play time on the
   server clock, so the Snapcast "buffer" is 0.
4. **Service and settings:** a foreground service while a stream plays (as the Snapcast client), the switch *Let servers play on this phone*
   (off by default), the announcement and the listener only while it is on, the multi-server rules (one `playback` activity wins; a server
   with the `playback` activity displaces the others; `client/goodbye` `another_server`), the card "this phone" in the receiver list.

## Decisions for the owner (recommendations)
| # | Question | Recommendation |
|---|---|---|
| P-1 | Which mode first | server-initiated (listen and announce); client-initiated later |
| P-2 | The audio engine | reuse the Snapcast client engine (tuned, tested on a phone) behind an adapter, not a second engine |
| P-3 | Formats | PCM 16 bit stereo at 48000 and 44100 Hz first; FLAC and Opus later |
| P-4 | Setting | one switch, off by default, with the risk text (the phone listens; a server you approved plays on it; unpaired access is the same rule as for the controller) |
| P-5 | Pairing | the Pairing PSK flow as a client (K8) is there; *Allow unpaired servers* decides whether an unpaired server may play |

## Done
- **Slice 1 (messages)**: `PlayerMessages` (hello with the player support object, state with the player object, the commands, the stream
  start, clear and end, the audio chunk), `PlayerMessagesTest` (round trips through the server-side parsers, hostile input).
- **Slice 2 (session)**: `PlayerSession` (handshake as a client, hello, activation with the admission rules and the pairing as a client,
  state, clock bursts, the stream to an `Audio` port, volume, mute and output delay commands), `PlayerSessionTest` (a fake server that uses
  the real handshake code). Known debt: the pairing and clock-burst code is copied from `ControllerSession`; a later change moves both onto one base.
- **Open**: slice 3 (the audio engine behind `PlayerSession.Audio`), slice 4 (the listening socket and announcement of `_sendspin._tcp`, the
  service, the setting, the card), a test against the reference server (`sendspin serve --client ws://host:8928/sendspin`) in the rig.
