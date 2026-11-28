# Design: a Snapcast server in YAACC (#330)

Status: draft for the owner (2026-11-28). Requirements: `requirements.md`. Decisions: `decisions.md`. Facts about the protocol: `.claude/specs/2026-11-09-issue319-snapcast/notes.md` ("Verified against the Snapcast sources") and `docs/tech.md`. Every protocol detail below that is marked (verify) is checked against the snapserver source (GPL-3.0, read only; our code is our own) in task 1.1 before it is coded.

## Overview

A new package `de.yaacc.multiroom.server`, plain Java on top of what exists:

| Part | Job | Reuses |
|---|---|---|
| `SnapcastServer` | lifecycle: opens the listeners, owns the state, closes everything | |
| `StreamListener` and `StreamClientSession` | port 1704: Hello, ServerSettings, CodecHeader, WireChunks, Time; one reader thread and one writer thread per client, a bounded write queue | `SnapcastMessageCodec`, `FrameReader`, `SnapcastProtocolLimits`, `MessageHeader` |
| `ControlListener`, `ControlSession`, `RpcDispatcher` | port 1705: ndjson JSON-RPC, the methods of R3, the notifications | `SnapcastLineReader`, `SnapcastJson` (the model classes show the shape of the answers) |
| `ServerState` | clients, groups, streams; the one lock; builds `Server.GetStatus`; emits change events that become notifications | `SnapcastServerStatus` as the reference of the JSON |
| `StreamSource` (a tcp feeder) | accepts one producer per stream on a port of 4953 to 4962, reads PCM in fixed blocks | `PcmFormat`, `FeedSlots` for the port range idea |
| `Chunker` | pure: PCM in, timed chunks of about 20 ms out, one continuous timeline | like `PcmStreamer` of the Sendspin hub (timeline, re-anchor after a gap) |
| `ChunkEncoder` | `PcmEncoder` (RIFF WAVE header once, payload as is) and `FlacChunkEncoder` | the FLAC encoder, see "FLAC" |
| `ServerClock` | monotonic microseconds for stamps and the Time answers | `MicroClock` |
| `ServerStore` | persistence of clients and groups | the app's preference store |
| `SnapcastServerService` | foreground service, locks while a stream has listeners and audio, autostart and idle rules | `SnapcastClientService` and its power policies |
| `SnapcastServerAnnouncer` | NSD: `_snapcast._tcp`, `_snapcast-ctrl._tcp` | `SnapcastServiceTypes` |

## Own feed and own control: in-process, never over the loopback address

The app's own Snapcast UI and its feed (Phase A) talk to a server through `SnapcastTransport` (control) and `FeedConnection` (feed). For the built-in server these two seams get in-process implementations that call the server directly (no socket). So:

- the listeners accept **local-network addresses only** and never the loopback address (a program of another app on the phone is then a client like any other device; the Sendspin review found that trusting the loopback address trusts every app on the phone);
- the server shows in the lists as a Snapcast server with the id of the phone (like the "this phone" card of Sendspin), and the existing code paths (renderer output "Snapcast group", link sheet, merged client lists, the client card) work unchanged, because they only see `SnapcastTransport` and `FeedConnection`;
- task 1.2 checks that the seams are as clean as assumed here; if not, the smallest change is made in them, and that change is reviewed first.

## Threads and locks

- Accept threads: one per listener (1704, 1705) and one per feeder port that is open; each accepts, checks the address, counts, hands the socket to a session.
- Per stream client: a reader thread (frames in) and a writer thread (queue out). The queue holds chunks and control messages; when it exceeds its cap (about the buffer time of audio plus some control messages) the client is dropped (S3). A write that does not end is broken by a watchdog (the lesson of `WsConnection`: a blocking socket write has no timeout).
- Per control connection: a reader thread; answers and notifications go through one writer queue with the same rules.
- Per stream: one thread reads the feeder and runs the `Chunker`; it puts chunks on the queues of the clients of the groups that play the stream. It never blocks on a client.
- `ServerState` has one monitor; it is never held while writing to a socket (events are collected and sent after).

## Timeline and chunks (R5)

- **Stamps (corrected on 2026-11-28 from the notes of #319, `docs/tech.md`, "Binary protocol"):** a chunk is stamped with the server time at which its audio was **produced** (the stream clock: the time of the first read after the producer connected, plus the duration of everything read since), **not** with the time at which it is to be heard; the client plays it at `stamp + bufferMs - latency - its own output latency`. The server therefore needs no "send ahead": it paces its reads in real time like snapserver (it waits for the deadline of each chunk, so TCP back pressure slows a producer that is ahead) and stamps with the stream clock. If a read is late (its end is after the deadline of the next chunk) the stream clock is restarted from the server time (a gap, logged as a resync).
- The `Chunker` makes the timeline per stream from that rule: the stream clock starts at the first read; each chunk adds its duration (kept in nanoseconds, never rounded down); after a gap the clock restarts.
- The feed of the app (Phase A) already paces PCM in real time; an external feeder is in real time by nature; a feeder that is faster than real time would fill the queues: the chunker then waits (back pressure on the feeder socket) when the data is more than `bufferMs` ahead.
- `ServerSettings` carries `bufferMs`, the client's `latency`, `volume`, `muted` (verify field names and units).
- The `Time` exchange: the client sends `Time`; the server answers with the fields the snapserver sets (verify), so that the client can compute its offset; the answer is sent at once on the reader thread.

## Groups, clients, streams (R7, R8)

- A client is identified by the id in its Hello (MAC-like string or the id that the app's client makes); the server keeps an entry per id, connected or not.
- Defaults as snapserver: a new client gets a new group with the default stream; `Group.SetClients` moves clients and removes groups that become empty; `Server.DeleteClient` only deletes a disconnected client.
- The default stream: the first stream that exists. With no stream the clients are connected but get no CodecHeader and no chunks (they play nothing); a stream that appears sends them its header.
- Notifications are produced from `ServerState` events, to every control connection except for the one that made the change where snapserver does the same (the existing client code relies on that: W1 in the #319 decisions).

## FLAC

The server needs a FLAC encoder in plain Java: one FLAC frame per chunk, the stream header as the CodecHeader. The Sendspin hub (`feat/issue325`) has exactly this: `FlacEncoder` (fixed predictors, Rice coding, the best of four channel assignments, tests with an own decoder, decoded bit for bit by ffmpeg). It is not on this branch.

Proposal (owner to confirm): when task 6 starts, the class and its test are copied unchanged to a neutral package `de.yaacc.audio` on this branch, and the Sendspin branch moves its class to the same path before it is merged, so that the two branches add the identical file (no conflict). Until then PCM is complete without it. The FLAC path is checked against a real `snapclient` in the Docker rig, which is the only real proof (the platform decoder of the app's own client is a second one).

## Persistence (R8)

A small store with the clients (id, name, volume, muted, latency, last group) and the groups (id, name, stream, muted, members). Written after a change, not more often than once a second; a damaged or too large store is ignored and the server starts empty.

## Settings and UI (R10)

Settings: `settings_snapcast_server` (switch, off), the buffer in ms (default 1000, 200 to 5000), the server name. The card: a server in the receiver list like the others, with the status line ("listening on port 1704, 2 clients, 1 stream"). The switch text and the docs carry the risks and the hints (firewall, client isolation, Doze).

## Tests

- Pure and fast: `Chunker`, `ServerState`, `RpcDispatcher` (request and answer shapes against the model parser of the app), the codec round trips for the server direction, the store.
- Against the app's own client: the Phase B `SnapcastClientEngine` joins a server over a real loopback socket in a JVM test (the test is the only user of the loopback address; the production listener refuses it, so the tests bind the local network address or a test-only filter) and plays PCM and FLAC.
- Against the real thing (not possible on the build machine): the Docker rig with a real `snapclient`, Snapweb is out of scope; the owner's device checks.

## Open points

- The `Time` answer, the `ServerSettings` fields, the shapes of the JSON-RPC answers and the stream behaviour are read already: `docs/tech.md`, "Snapcast (issue #319): behavior notes" (task 1.1 is closed by it). What stays to be seen with a real `snapclient` (the rig) is listed under "Checks only the owner can do".
- The in-process seams (see "Own feed", task 1.2).
- Where the phone's own client (Phase B) plays when the server is on the same phone: it joins over the in-process path or over the local address; to decide in task 4.
