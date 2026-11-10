# Snapcast test rig

A throw-away Docker setup with one **snapserver** and two **snapclients** for
developing and testing YAACC's Snapcast support (issue #319): the control
library, discovery by address, the feed of audio into a group, and later the
phone as a client.

> **Local testing only.** The server has **no authentication** and the
> containers are for development. Every port is published on `127.0.0.1` only.
> Do not change that to `0.0.0.0` or a LAN address unless you accept that anyone
> who can reach the machine can control the rig (and use it to relay audio).
> Nothing in this directory is part of the app or its build.

Snapcast is GPL-3.0-or-later. The image builds it from source at a pinned git
ref; no Snapcast code is copied into this repository.

## Files

| File | Purpose |
| --- | --- |
| `docker-compose.yml` | the rig: `snapserver`, `snapclient-1`, `snapclient-2` |
| `Dockerfile` | multi-stage build of snapserver and snapclient (Debian slim, non-root runtime user) |
| `snapserver.conf` | server configuration, every line commented |
| `spike/FeedSpike.java` | throw-away spike for task 1.5: feeds a sine tone into a stream created by `Stream.AddStream` |

## Pinned versions: check at the first run

The tags were written without network access and **must be checked once**:

- Base image `debian:12.12-slim` (`ARG DEBIAN_TAG` in the `Dockerfile`). Look it
  up at <https://hub.docker.com/_/debian/tags?name=12.> and bump to an existing
  `12.<n>-slim` tag if needed. No `latest` or floating tag is used.
- Snapcast `v0.35.0` (`ARG SNAPCAST_REF`, overridable:
  `SNAPCAST_REF=v0.35.0 docker compose up --build`). Check it at
  <https://github.com/snapcast/snapcast/tags>. Optionally pin the commit with
  `--build-arg SNAPCAST_COMMIT=<full hash>`; the build fails if the tag resolves
  to another commit.
- The Debian package names in the `Dockerfile` (`libflac12`, `libsoxr0`, ...) are
  for Debian 12. If the first build fails on a missing package or library, fix
  the list and note it here.
- `docker build --check .` (BuildKit) lints the `Dockerfile`;
  `docker compose config` validates the compose file.

## Start the rig

```sh
cd tools/snapcast-rig
docker compose up --build
```

The first build compiles Snapcast (several minutes; the C++ build is memory
hungry, lower `BUILD_JOBS` via `--build-arg` if it runs out of memory). After that `docker compose up` starts in seconds:
`snapserver` first, then, once its control port answers, both clients.

Stop and remove everything: `docker compose down`. The server keeps no state
between runs (its data directory is a tmpfs).

| Host port (127.0.0.1) | Service |
| --- | --- |
| 1704 | stream protocol (snapclients; Phase B: the phone as a client) |
| 1705 | JSON-RPC 2.0 over TCP, newline-delimited: the control interface |
| 1780 | HTTP JSON-RPC (`POST /jsonrpc`) and websocket control; no web UI is installed |
| 4953-4962 | `tcp` stream sources created at runtime with `Stream.AddStream` |

## Look at it: the control socket

```sh
echo '{"id":1,"jsonrpc":"2.0","method":"Server.GetStatus"}' | nc -q 1 localhost 1705
```

(`-q 1` makes the traditional netcat exit after the answer; with OpenBSD netcat
use `-N` instead or press Ctrl-C.) The answer is one line of JSON; pipe it
through `jq .result.server` if you have it. Expect:

- `groups`: two groups with one client each, client ids `rig-host-1` and
  `rig-host-2#2` (`--hostID` plus `--instance`: instance 2 adds the `#2`
  suffix, which is how Snapcast names a second client on one host), both
  `"connected": true`;
- `streams`: one stream `Default`, `"status": "idle"`, scheme `pipe`.

Other useful commands (same transport; the full reference is
`doc/json_rpc_api/control.md` in the Snapcast repository):

```sh
# move a group to another stream
echo '{"id":2,"jsonrpc":"2.0","method":"Group.SetStream","params":{"id":"<group id>","stream_id":"<stream id>"}}' | nc -q 1 localhost 1705
# add a tcp source (this is what YAACC will do), port from 4953-4962
echo '{"id":3,"jsonrpc":"2.0","method":"Stream.AddStream","params":{"streamUri":"tcp://0.0.0.0:4953?name=Test&mode=server&sampleformat=48000:16:2&codec=flac"}}' | nc -q 1 localhost 1705
# remove it again
echo '{"id":4,"jsonrpc":"2.0","method":"Stream.RemoveStream","params":{"id":"Test"}}' | nc -q 1 localhost 1705
```

The server also sends notifications (`Client.OnVolumeChanged`,
`Server.OnUpdate`, ...) on the same connection. To watch them keep a connection
open: `nc localhost 1705` and type requests, or run a second `nc` that only
listens.

Feed noise into the built-in pipe source to see the stream switch to `playing`
(5 s of white noise at 48 kHz, 16 bit, stereo; nobody hears it):

```sh
docker compose exec -T snapserver bash -c 'head -c 960000 /dev/urandom > /tmp/snapfifo'
docker compose logs snapserver | grep "State changed"
```

## Feed spike (task 1.5)

`spike/FeedSpike.java` is a throw-away, dependency-free Java 17 program (not part
of any Gradle module). It talks to the control port, creates the stream
`YAACC spike` with `Stream.AddStream` (tcp, server mode, first free port from
4953), moves every group that has a connected client to it, connects to the tcp
port and sends a 440 Hz sine as raw PCM (48000 Hz, 16 bit little endian, stereo,
no WAV header) in **real time** (20 ms chunks, absolute deadlines from
`System.nanoTime()`), prints the stream status every 10 s, then restores the
groups and calls `Stream.RemoveStream`.

```sh
# 5 minutes, real-time paced (the acceptance run)
java tools/snapcast-rig/spike/FeedSpike.java localhost 300
# the same without pacing, to see why pacing is needed
java tools/snapcast-rig/spike/FeedSpike.java localhost 60 --burst
```

Reading the result:

- Paced run: `RESULT: OK` means the stream was `playing` in every status sample,
  the writer was never more than one chunk late, and its lead over the clock
  stayed at about one chunk (20 ms).
- The "no underruns on the clients" criterion needs the client logs, which the
  spike cannot see. A client logs the info line `No chunks available` about
  once a second whenever it has nothing to play (also before and after the run,
  when the groups are back on an idle stream), so only a **time window** is
  meaningful. The spike prints the UTC start and end of the run and the exact
  command; it has this shape:
  `docker compose logs -t --since <start+5s> --until <end> snapclient-1 snapclient-2 | grep -c "No chunks available"`
  and must print `0` (it counts both clients together; run it once per client to
  see which one is at fault). The clients need about their buffer time to
  start, hence the 5 s; runs shorter than about 10 s leave a tiny window and
  prove nothing. The spike moves every group that has a connected
  client, so both rig clients take part.
  `docker compose logs snapserver | grep "State changed"` shows the
  `idle => playing` transition of the stream.
- `--burst`: the producer finishes far ahead of the clock (the `lead` figure) because
  the data piles up in socket buffers. The server reads its sources in real
  time, so nothing is dropped, but the audio is heard that much late and a
  stop or seek acts that late. That is the reason the real feeder paces.

Paste the run output into `docs/tech.md` (task 1.5 asks for it). If
`Stream.AddStream` is refused or the feed cannot be kept steady, that is the
kill criterion of the task: stop and decide with the owner (static source
fallback).

## Connect a real snapclient

The rig's snapclients play to `/dev/null`. To hear the stream, attach a real
client to the stream port:

```sh
# on the machine running the rig (any real snapclient, e.g. from your distribution)
snapclient tcp://127.0.0.1:1704
```

It appears as a third client in `Server.GetStatus` and joins its own group;
move it into a group with `Group.SetClients` or from the app under test. From
another machine do **not** open the ports to the LAN; use a tunnel instead:

```sh
ssh -N -L 1704:127.0.0.1:1704 -L 1705:127.0.0.1:1705 <rig host>
snapclient tcp://127.0.0.1:1704
```

### An Android device or emulator as the controller

Because the ports are loopback-only, the phone cannot reach them directly:

- **Emulator:** the host's loopback is `10.0.2.2`, so use `10.0.2.2` as the
  server address (control 1705, feed ports 4953-4962).
- **USB-connected phone:** forward the ports with adb, once per connection:
  ```sh
  adb reverse tcp:1705 tcp:1705
  adb reverse tcp:1704 tcp:1704
  for p in $(seq 4953 4962); do adb reverse tcp:$p tcp:$p; done
  ```
  and use `localhost` as the server address in the app.

## Run the opt-in integration tests (later groups)

The rig tests are skipped unless a rig host is given. With the rig running:

```sh
./gradlew :yaacc:testDebugUnitTest -Dsnapcast.rig=localhost
```

(How the property reaches the test JVM is wired in the Gradle test task when the
first rig test is added in Group 2.) Without `-Dsnapcast.rig` the tests are not
run, so CI needs no Docker. Tests expect exactly the layout of this rig: stream
`Default`, clients `rig-host-1` and `rig-host-2#2`, ports as above.

## What the rig does not cover

- **No real audio output.** The clients use Snapcast's `file` player writing to
  `/dev/null`. They receive and decode chunks, take part in time sync and obey
  volume, mute and latency settings, but nothing is audible. Sync quality,
  pause latency by ear and Android output latency (Phase B) must be judged on
  real devices.
- **No mDNS.** The image is built with `BUILD_WITH_AVAHI=OFF`, so the server does
  not publish `_snapcast-ctrl._tcp` and the clients are given the server URL
  explicitly. Discovery on Android (`NsdManager`) therefore cannot be tested with
  this rig; use the manual server entry. A server with Avahi on a real host is
  needed for that.
- **No authentication and no TLS.** The authorization and HTTPS paths of
  Snapcast are not exercised.
- **Loopback only.** Network effects (Wi-Fi jitter, power save, packet loss) are not
  reproduced; loopback socket buffers are also much larger than on a real
  network, which hides back pressure in short feed tests.
- **Version.** One Snapcast version at a time (`SNAPCAST_REF`). Behavior of older
  or newer servers must be checked by rebuilding with another ref.
- **Mixer and codecs.** Clients use the software mixer; all codecs the image was
  built with (flac, pcm, ogg, opus) can be selected per stream.

## Troubleshooting

- `port is already allocated`: another process uses 1704, 1705, 1780 or one of
  4953-4962 on the host.
- `snapserver` unhealthy: `docker compose logs snapserver` shows the reason
  (config parse error, a missing library, an unwritable `/tmp`).
- The clients show `connected: false` right after `up`: they connect once the
  server is healthy; wait a few seconds and query again.
- `Stream.AddStream` fails with "Address already in use": a stream from an
  earlier run still holds that port; remove it with `Stream.RemoveStream` or use
  the next port. "Stream with name '...' already exists" means the stream name
  is taken.
