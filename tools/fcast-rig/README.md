# FCast test receiver (issue #327)

A tiny **FCast receiver for testing YAACC without a TV**. It speaks FCast protocol v3, announces itself on the network as an
`_fcast._tcp` service, writes every message it gets to the screen in readable form, "plays" a made-up track (it only moves a
clock, **nothing is fetched or shown**) and reports its state to every connected sender. It can also misbehave on purpose,
to test how YAACC copes. It is not an FCast receiver for real use.

For real media (does the video play, does the picture show, does the track end) use the **official FCast desktop receiver**
on your notebook: the downloads are on https://fcast.org (Windows installer, macOS image, Linux Flatpak). The two tools
complement each other: the official receiver shows what real receivers do, this rig shows exactly what YAACC sends and lets
you test errors, slow and silent receivers, and old protocol versions.

FCast is a registered trademark of FUTO. This rig is an independent community tool and is not affiliated with FUTO.

## Run it (any system with Python 3.11 or newer)

```
cd tools/fcast-rig
python3 -m venv .venv
.venv/bin/pip install --no-input -r requirements.txt      # Windows: .venv\Scripts\pip install -r requirements.txt
.venv/bin/python fcast_rig.py --address 192.168.1.50       # the notebook's address in the network the phone is in
```

- `--address` is the IPv4 address that the phone should use to reach the notebook (find it with `ip -4 addr`, `ipconfig`
  or `ifconfig`). Without it the rig announces the address its host name resolves to, which is often `127.0.1.1` and useless.
- Without the `zeroconf` package (or with `--no-advertise`) the rig still runs; then add it by hand in YAACC
  (*FCast receivers* in the settings: `192.168.1.50` or `192.168.1.50:46899`).
- The default port is 46899. `--port 0` picks a free port and prints it.
- Stop it with Ctrl+C.

Firewall: allow **TCP 46899** (the protocol) and **UDP 5353** (mDNS) on the notebook, for the network the phone is in. When
YAACC says "could not connect", this is the first thing to check (a Linux `ufw` or a Windows network profile "public").

## Run it in Docker (Linux only)

```
cd tools/fcast-rig
# edit the --address in docker-compose.yml first
docker compose up --build
```

The compose file uses the host network on purpose: the bridge network of Docker does not carry mDNS multicast, so a phone
would not find the receiver. Docker Desktop on Windows and macOS runs a virtual machine and cannot give the container the
host's network; run the Python script natively there. (The image and the compose file were written and the compose file was
syntax-checked, but the image was not built in the sandbox where this was written: there was no Docker daemon. If the build
fails, say so in the issue.)

## What it does

| You do in YAACC | The rig |
| --- | --- |
| connect | sends `Version 3` first (like the Android receiver; `--greeting 4` acts like the desktop receiver, which then expects the sender's `Version 3`), waits for the sender's `Version`, settles on the smaller one, answers with `Initial` and the volume |
| play an item (`Play`) | logs the full message (container, URL, headers, metadata), starts a pretend track of `--duration` seconds (default 180) and sends `PlayUpdate`, `PlaybackUpdate` once a second, `VolumeUpdate` if the message had a volume |
| pause, resume, stop, seek, volume, speed | does it, and sends the new state to **every** connected sender |
| a second sender (another phone, the receiver's own sender app) | gets the same updates, and its changes reach YAACC |
| nothing for a while | pings like the desktop receiver (after 3 s of silence, ends the session after 6 s without any packet); `--heartbeat android` pings every 2.5 s like the Android receiver; `--heartbeat none` never |
| the end of the track | sends `PlaybackUpdate` state 0 (idle): YAACC should start the next item |

Options for bad behaviour (`--behaviour`): `slow` (every answer waits `--slow-seconds`), `silent` (answers nothing, still
logs), `oversize` (announces a packet larger than the 32000 bytes of the protocol right after the connect), `ignore-pings`
(never answers a ping). `--speaks 2` or `1` makes it an old receiver without `Initial`. A sender that asks for protocol
**version 4** is disconnected, because the rig has no TLS (planned together with YAACC's v4 support, task 7.6).

Everything the rig receives is only logged; it parses JSON and nothing else, never fetches a URL, never runs anything it
receives and closes a connection that sends a packet over the limit or a packet without an opcode.

## Test the rig itself

```
cd tools/fcast-rig
python3 -I -c "import sys; sys.path.insert(0, '.'); import unittest; unittest.main(module='test_fcast_rig', argv=['x', '-v'])"
```

The tests use the loopback address and a free port (never a fixed one) and take about 25 seconds (two of them wait for the
heartbeat).

## Protocol facts

See `docs/tech.md`, section "FCast (issue #327)", and `.claude/specs/2026-11-17-issue327-fcast/notes.md`. The rig was
written from the protocol documents of the FCast project (MIT licence); no FCast code is used.
