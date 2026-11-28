# Tasks: a Snapcast server in YAACC (#330)

Plan: `requirements.md`, `design.md`. Decisions: `decisions.md`. Rules as in the other specs of this repository: spec first, tests first for the pure parts, a general and a security review before it is called done, texts in 7 locales (values, de, es, fr, nl, pt, zh), docs and CHANGELOG updated with each group.

Status 2026-11-28: spec approved by the owner ("go ahead"). Built and tested on the JVM: groups 1 to 3 (the stream port with clients, chunks and time sync, the tcp stream source, the state, the control port, persistence), all with tests, the stream port tested against the app's own binary client and the control port against the app's own control client. Not built yet: group 4 (service, announcement, in-process use by the app's own UI and feed, settings and card), group 5 (FLAC), group 6 (docs, reviews).

## Group 1: reading and seams
- [x] 1.1 (closed 2026-11-28: the facts were already read for #319 and are in `docs/tech.md`, "Snapcast (issue #319): behavior notes from the Snapcast sources", written in our own words; the one correction to the design is the stamp rule in `design.md`) Read the snapserver source for the details marked (verify) in `design.md`: Hello and ServerSettings fields, the Time answer, the PCM and FLAC CodecHeader, the WireChunk fields, the stream parameters, the answers of `Server.GetStatus`. Write the facts into `docs/tech.md` (own words, no copied code). **Accept**: each (verify) of the design is closed or turned into a decision.
- [x] 1.2 (2026-11-28: the seams are as clean as assumed. Control: `SnapcastTransport.open()` returns a `Connection` of two streams, a read timeout, a peer address (may be null), `shutdownOutput` and `close`; an in-process pair of queues implements it, and `SnapcastHub` builds the control session with `new ResolvingTransport(endpoint, resolver)` in one place (the place to choose the in-process transport for the own server). Feed: `FeedOutputFactory.open(address, port, onFailed)` is the one place that connects the feed (`PcmFeederOutput.FACTORY`, given to `SnapcastFeedSessions` by `SnapcastHub`), and `FeedConnection.Factory` below it opens the socket; an in-process `FeedConnection.Factory` that writes into the server's stream source is the second place. The feed needs `peerAddress` of the control connection, which an in-process transport answers with a marker address; the feed code must accept it (small change in `SnapcastFeedController` line 498 area). No other code assumes a socket.) Check the seams `SnapcastTransport` and `FeedConnection` for an in-process implementation (and what else assumes a socket to a host and port); list the smallest changes.
- [ ] 1.3 Check the Docker rig (`tools/snapcast-rig`) for a snapclient that joins an external server and what the owner needs to run it against a phone.

## Group 2: binary core (PCM, one stream)
- [x] 2.1 `ServerClock`, `Chunker` (pure, tests first: timeline, re-anchor after a gap, back pressure, chunk sizes for 44100 and 48000 Hz and other formats).
- [x] 2.2 `PcmEncoder` (RIFF WAVE header) and the server direction of the codec tests.
- [x] 2.3 `StreamListener` and `StreamClientSession`: Hello, ServerSettings, CodecHeader, chunks, Time, ClientInfo; limits and write queue (S3), write watchdog. Test against the app's own client engine over a socket.
- [x] 2.4 `StreamSource`: the tcp feeder on a port of the range; one producer; a stream with the default sample format. Test with the app's own feeder.

## Group 3: control and state
- [x] 3.1 `ServerState` (clients, groups, streams; status JSON; events). Tests first, shapes checked with the app's own model parser.
- [x] 3.2 `ControlListener`, `ControlSession`, `RpcDispatcher`: the methods and notifications of R3, errors as snapserver gives them; `Stream.AddStream` tcp only (S4). Tests with the app's own control library.
- [x] 3.3 `ServerStore` persistence (R8).

## Group 4: service, announcement, own use
- [x] 4.1 `SnapcastServerService` (foreground, locks while streaming, autostart and idle rules), `SnapcastServerAnnouncer` (R9).
- [ ] 4.2 (**changed 2026-11-28, version 1 goes without it**: the listeners accept the phone's own address on the Wi-Fi like any local-network address, and the announcement lets the app's own pages find the server, so the app's UI and feed use the built-in server over the LAN address with no change in the seams; the in-process route stays an improvement for use without Wi-Fi) In-process transports (design, "Own feed") so that the app's UI and feed use the built-in server; the server in the lists; the phone's own client.
- [x] 4.3 Settings, card and status line; strings in 7 locales.

## Group 5: FLAC
- [ ] 5.1 Bring the FLAC encoder to this branch (see `design.md`, "FLAC"); `FlacChunkEncoder` and the stream parameter `codec=flac`.
- [ ] 5.2 Test: the app's own client plays a FLAC stream from the server (JVM, with the platform decoder replaced by the pass-through used in the tests of #319); the Docker rig with a real snapclient is the owner's check.

## Group 6: documentation and review
- [ ] 6.1 README, `docs/index.md`, `docs/settings.md`, CHANGELOG, THIRD_PARTY (no new library expected).
- [ ] 6.2 General review, then security review (S1 to S7), fixes tests first.

## Checks only the owner can do
- A real `snapclient` and the Docker rig against the phone: PCM, then FLAC; sync of two clients; Snapdroid or Snapweb as a bonus.
- Doze, battery and Wi-Fi power save with the server running for hours; client isolation on the access point.
