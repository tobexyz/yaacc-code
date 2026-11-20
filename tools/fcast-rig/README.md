# Test receiver for the FCast protocol (issue #327)

A tiny **receiver for testing YAACC without a TV**. It speaks the FCast protocol v3, announces itself on the network as an
`_fcast._tcp` service, writes every message it gets to the screen in readable form, "plays" a made-up track (it only moves a
clock, **nothing is fetched or shown**) and reports its state to every connected sender. It can also misbehave on purpose,
to test how YAACC copes. It is not an FCast receiver for real use.

For real media (does the video play, does the picture show, does the track end) use the **official FCast desktop receiver**
on your notebook: the downloads are on https://fcast.org (Windows installer, macOS image, Linux Flatpak). The two tools
complement each other: the official receiver shows what real receivers do, this rig shows exactly what YAACC sends and lets
you test errors, slow and silent receivers, and old protocol versions.

> This application is an independent community implementation of the FCast protocol. It is not affiliated with FUTO. FCast is a registered trademark of FUTO.

## Run it (any system with Python 3.11 or newer)

```
cd tools/fcast-rig
python3 -m venv .venv
.venv/bin/pip install --no-input -r requirements.txt
.venv/bin/python fcast_rig.py --address 192.168.1.50       # the notebook's address in the network the phone is in
```

Windows (PowerShell):

```
cd tools\fcast-rig
py -3 -m venv .venv
.venv\Scripts\pip install --no-input -r requirements.txt
.venv\Scripts\python fcast_rig.py --address 192.168.1.50
```

- `--address` is the IPv4 address that the phone should use to reach the notebook (find it with `ip -4 addr`, `ipconfig`
  or `ifconfig`). Without it the rig announces the address its host name resolves to, which is often `127.0.1.1` and useless.
- Without the `zeroconf` package (or with `--no-advertise`) the rig still runs; then add it by hand in YAACC
  (*FCast receivers* in the settings: `192.168.1.50` or `192.168.1.50:46899`).
- The default port is 46899. `--port 0` picks a free port and prints it.
- Stop it with Ctrl+C.

Firewall: allow **TCP 46899** (the protocol) and **UDP 5353** (mDNS) on the notebook, for the network the phone is in. When
YAACC says "could not connect", this is the first thing to check (a Linux `ufw` or a Windows network profile "public").

## Try a receiver with the probe sender (`fcast_probe.py`)

A command line **sender** (protocol v3, standard library only) to check a real receiver, or the rig, from the notebook before
YAACC itself can talk to FCast. It sends `Version 3` and `Initial`, answers pings, prints every packet in both directions, and
can send `Play` and then pause, resume, seek, change volume and speed and stop at times you give.

```
.venv/bin/python fcast_probe.py --browse 5                       # list the receivers announced in the network
.venv/bin/python fcast_probe.py 192.168.1.50                     # connect to the receiver, print what it says
python3 -m http.server 8000                                      # in a folder with test files, in another terminal
.venv/bin/python fcast_probe.py 192.168.1.50 --play http://192.168.1.50:8000/test.mp4 --container video/mp4 \
    --do "pause@6,resume@9,seek:30@12,volume:0.3@15,speed:1.5@18,stop@24" --seconds 30
```

Try `audio/mpeg` with an mp3, `image/jpeg` with a picture, and a wrong or empty container to see what your receiver does;
please note the receiver and its version. (`--browse` needs `zeroconf`, everything else needs only Python.)

## Run it in Docker (Linux only)

```
cd tools/fcast-rig
RIG_ADDRESS=192.168.1.50 docker compose up --build        # the notebook's address; there is no default on purpose
```

The compose file uses the host network on purpose: the bridge network of Docker does not carry mDNS multicast, so a phone
would not find the receiver. Docker Desktop on Windows and macOS runs a virtual machine and cannot give the container the
host's network; run the Python script natively there. (The image and the compose file were written and the compose file was
syntax-checked, but the image was not built in the sandbox where this was written: there was no Docker daemon. If the build
fails, say so in the issue.)

## What it does

| You do in YAACC | The rig |
| --- | --- |
| connect | sends the `Version` it speaks first (3 by default like the Android receiver, 2 or 1 with `--speaks`; `--greeting 4` acts like the desktop receiver, which then expects the sender's `Version 3`), waits for the sender's `Version`, settles on the smaller one, answers with `Initial` and the volume |
| play an item (`Play`) | logs the message (container, URL, headers, metadata), then reports like a receiver that loads an item: `PlayUpdate`, a `PlaybackUpdate` with state **idle**, `VolumeUpdate` if the message had a volume, and after `--start-delay` (default 0.5 s) state **playing**; the pretend track runs for `--duration` seconds (default 180) with a `PlaybackUpdate` each second and ends with state idle |
| pause, resume, stop, seek, volume, speed | does it, and sends the new state to **every** connected sender |
| a second sender (another phone, the receiver's own sender app) | gets the same updates, and its changes reach YAACC; a sender that connects later gets the playing item in `Initial` and the current `PlaybackUpdate` |
| nothing for a while | pings like the desktop receiver (after 3 s of silence, ends the session after 6 s without any packet); `--heartbeat android` pings every 2.5 s like the Android receiver; `--heartbeat none` never |
| the end of the track | sends `PlaybackUpdate` state 0 (idle): YAACC should start the next item |
| pause, resume, seek or stop with nothing loaded | changes nothing (no message) |

Options for bad behaviour (`--behaviour`): `slow` (every answer waits `--slow-seconds`), `silent` (answers nothing and pings nobody, still
logs), `oversize` (announces a packet larger than the 32000 bytes of the protocol right after the connect), `ignore-pings`
(never answers a ping). `--speaks 2` or `1` makes it an old receiver without `Initial` (it greets with that version too, and announces it by mDNS). A sender that asks for protocol
**version 4** is disconnected, because the rig has no TLS (planned together with YAACC's v4 support, task 7.6).

Like the desktop receiver the rig **ends the session** of a sender that sends a body that is not valid JSON or has the wrong type (a text for a number, an infinite or missing value), an opcode it does not know, a second `Version`, a `Version` below 1 or of 4 and up, an `Initial`, `SetPlaylistItem` or event subscription from a version 1 or 2 session, a body with `NaN` or `Infinity` or a number too large for a float, a packet of size 0 or over 32000 bytes, or (in a version 1 session) a ping or a speed; the log says why. This is meant to show bugs in YAACC's client. It does not send what is over the 32000 bytes limit either: the `PlayUpdate` echo of a very long `Play` is skipped (and logged), as the real receivers do. Times and speeds in a `Play` are clamped (speed 1/16 to 16, time not below 0) like the desktop receiver does. `PlayUpdate`, `Initial` and the other messages that only exist from version 3 are sent only to sessions of that version.

Everything the rig receives is only logged; it parses JSON and nothing else, never fetches a URL and never runs anything it
receives.

## Test the rig itself

```
cd tools/fcast-rig
python3 -I -c "import sys; sys.path.insert(0, '.'); import unittest; unittest.main(module='test_fcast_rig', argv=['x', '-v'])"
```

The tests use the loopback address and a free port (never a fixed one) and take about 30 seconds (two of them wait for the
heartbeat).

## Protocol facts

See `docs/tech.md`, section "FCast (issue #327)", and `.claude/specs/2026-11-17-issue327-fcast/notes.md`. The rig was
written from the protocol documents of the FCast project (MIT licence); no FCast code is used.
