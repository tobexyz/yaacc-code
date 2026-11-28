# Tasks: a Snapcast server in YAACC (#330)

Plan: `requirements.md`, `design.md`. Decisions: `decisions.md`. Rules as in the other specs of this repository: spec first, tests first for the pure parts, a general and a security review before it is called done, texts in 7 locales (values, de, es, fr, nl, pt, zh), docs and CHANGELOG updated with each group.

Status 2026-11-28: spec written, waiting for the owner's approval. Nothing is built.

## Group 1: reading and seams
- [ ] 1.1 Read the snapserver source for the details marked (verify) in `design.md`: Hello and ServerSettings fields, the Time answer, the PCM and FLAC CodecHeader, the WireChunk fields, the stream parameters, the answers of `Server.GetStatus`. Write the facts into `docs/tech.md` (own words, no copied code). **Accept**: each (verify) of the design is closed or turned into a decision.
- [ ] 1.2 Check the seams `SnapcastTransport` and `FeedConnection` for an in-process implementation (and what else assumes a socket to a host and port); list the smallest changes.
- [ ] 1.3 Check the Docker rig (`tools/snapcast-rig`) for a snapclient that joins an external server and what the owner needs to run it against a phone.

## Group 2: binary core (PCM, one stream)
- [ ] 2.1 `ServerClock`, `Chunker` (pure, tests first: timeline, re-anchor after a gap, back pressure, chunk sizes for 44100 and 48000 Hz and other formats).
- [ ] 2.2 `PcmEncoder` (RIFF WAVE header) and the server direction of the codec tests.
- [ ] 2.3 `StreamListener` and `StreamClientSession`: Hello, ServerSettings, CodecHeader, chunks, Time, ClientInfo; limits and write queue (S3), write watchdog. Test against the app's own client engine over a socket.
- [ ] 2.4 `StreamSource`: the tcp feeder on a port of the range; one producer; a stream with the default sample format. Test with the app's own feeder.

## Group 3: control and state
- [ ] 3.1 `ServerState` (clients, groups, streams; status JSON; events). Tests first, shapes checked with the app's own model parser.
- [ ] 3.2 `ControlListener`, `ControlSession`, `RpcDispatcher`: the methods and notifications of R3, errors as snapserver gives them; `Stream.AddStream` tcp only (S4). Tests with the app's own control library.
- [ ] 3.3 `ServerStore` persistence (R8).

## Group 4: service, announcement, own use
- [ ] 4.1 `SnapcastServerService` (foreground, locks while streaming, autostart and idle rules), `SnapcastServerAnnouncer` (R9).
- [ ] 4.2 In-process transports (design, "Own feed") so that the app's UI and feed use the built-in server; the server in the lists; the phone's own client.
- [ ] 4.3 Settings, card and status line; strings in 7 locales.

## Group 5: FLAC
- [ ] 5.1 Bring the FLAC encoder to this branch (see `design.md`, "FLAC"); `FlacChunkEncoder` and the stream parameter `codec=flac`.
- [ ] 5.2 Test: the app's own client plays a FLAC stream from the server (JVM, with the platform decoder replaced by the pass-through used in the tests of #319); the Docker rig with a real snapclient is the owner's check.

## Group 6: documentation and review
- [ ] 6.1 README, `docs/index.md`, `docs/settings.md`, CHANGELOG, THIRD_PARTY (no new library expected).
- [ ] 6.2 General review, then security review (S1 to S7), fixes tests first.

## Checks only the owner can do
- A real `snapclient` and the Docker rig against the phone: PCM, then FLAC; sync of two clients; Snapdroid or Snapweb as a bonus.
- Doze, battery and Wi-Fi power save with the server running for hours; client isolation on the access point.
