#!/usr/bin/env python3
# Copyright (C) 2026 Tobias Schoene www.yaacc.de
# GPL-3.0-or-later, like the rest of YAACC.
"""
A small command line sender for the FCast protocol v3, to try a real receiver (or the rig) from a notebook before YAACC
itself can talk to it (issue #327). Standard library only; `--browse` needs the `zeroconf` package (requirements.txt).

  python3 fcast_probe.py --browse 5                                   list the _fcast._tcp receivers of the network
  python3 fcast_probe.py HOST                                         connect, handshake, print what the receiver says
  python3 fcast_probe.py HOST --play http://192.168.1.50:8000/a.mp4 --container video/mp4 \
        --do "pause@6,resume@9,seek:30@12,volume:0.3@15,speed:1.5@18,stop@24" --seconds 30

It sends `Version 3` first, answers every Ping with a Pong and writes every packet in both directions in readable form.
It only sends what you ask for, and it never fetches the URL itself: the receiver does.
"""
import argparse
import json
import socket
import struct
import sys
import threading
import time

OPS = {"play": 1, "pause": 2, "resume": 3, "stop": 4, "seek": 5, "playback_update": 6, "volume_update": 7, "set_volume": 8,
       "playback_error": 9, "set_speed": 10, "version": 11, "ping": 12, "pong": 13, "initial": 14, "play_update": 15,
       "set_playlist_item": 16, "subscribe_event": 17, "unsubscribe_event": 18, "event": 19}
NAMES = {v: k for k, v in OPS.items()}
MAX_PACKET = 32000
start = time.monotonic()


def say(text):
    print("%6.1fs  %s" % (time.monotonic() - start, text), flush=True)


def packet(name, body=None):
    data = b"" if body is None else json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return struct.pack("<I", 1 + len(data)) + bytes([OPS[name]]) + data


def read_exact(sock, n):
    out = bytearray()
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            raise EOFError("the receiver closed the connection")
        out.extend(chunk)
    return bytes(out)


def read_packet(sock):
    size = struct.unpack("<I", read_exact(sock, 4))[0]
    if size < 1 or size > MAX_PACKET:
        raise ValueError("the receiver sent a packet of %d bytes" % size)
    payload = read_exact(sock, size)
    raw = payload[1:]
    return payload[0], (json.loads(raw.decode("utf-8")) if raw else None)


def browse(seconds):
    try:
        from zeroconf import ServiceBrowser, Zeroconf
    except ImportError:
        sys.exit("--browse needs the zeroconf package: pip install -r requirements.txt")
    found = {}

    class Listener:
        def add_service(self, zc, type_, name):
            info = zc.get_service_info(type_, name, 3000)
            if info:
                addresses = [socket.inet_ntoa(a) for a in info.addresses if len(a) == 4]
                props = {k.decode(): (v.decode() if isinstance(v, bytes) else v) for k, v in info.properties.items()}
                found[name] = (addresses, info.port, props)
                say("found %s  %s port %s  txt %s" % (name, ", ".join(addresses), info.port, props))

        def update_service(self, zc, type_, name):
            pass

        def remove_service(self, zc, type_, name):
            say("gone  %s" % name)

    zc = Zeroconf()
    ServiceBrowser(zc, "_fcast._tcp.local.", Listener())
    time.sleep(seconds)
    zc.close()
    say("%d receiver(s) found" % len(found))
    if not found:
        say("none: check that the receiver runs, that notebook and receiver are in the same network, and the firewall (UDP 5353)")


def main():
    p = argparse.ArgumentParser(description="FCast v3 command line sender for tests")
    p.add_argument("host", nargs="?", help="address of the receiver")
    p.add_argument("--port", type=int, default=46899)
    p.add_argument("--browse", type=float, metavar="SECONDS", help="list the _fcast._tcp receivers found in this time and exit")
    p.add_argument("--play", metavar="URL", help="send Play with this URL")
    p.add_argument("--container", help="MIME type of the URL (required with --play), e.g. video/mp4, audio/mpeg, image/jpeg")
    p.add_argument("--time", type=float, help="start position in seconds")
    p.add_argument("--volume", type=float, help="volume 0 to 1 sent with Play")
    p.add_argument("--do", default="", help="actions at seconds after the handshake: pause@6,resume@9,seek:30@12,volume:0.3@15,speed:1.5@18,stop@24")
    p.add_argument("--seconds", type=float, default=15.0, help="how long to stay connected (default 15)")
    args = p.parse_args()
    if args.browse:
        browse(args.browse)
        return
    if not args.host:
        p.error("give the address of the receiver, or --browse")
    if args.play and not args.container:
        p.error("--play needs --container (the MIME type)")

    actions = []
    for item in [a for a in args.do.split(",") if a.strip()]:
        what, _, when = item.partition("@")
        name, _, value = what.partition(":")
        actions.append((float(when), name.strip(), value.strip()))
    actions.sort()

    sock = socket.create_connection((args.host, args.port), timeout=5)
    say("connected to %s:%d" % (args.host, args.port))
    lock = threading.Lock()

    def send(name, body=None):
        with lock:
            sock.sendall(packet(name, body))
        say("-> %s %s" % (name, "" if body is None else json.dumps(body, ensure_ascii=False)))

    send("version", {"version": 3})
    send("initial", {"displayName": "fcast_probe", "appName": "yaacc-test-probe", "appVersion": "1"})

    def run_actions():
        t0 = time.monotonic()
        if args.play:
            body = {"container": args.container, "url": args.play}
            if args.time is not None:
                body["time"] = args.time
            if args.volume is not None:
                body["volume"] = args.volume
            send("play", body)
        for when, name, value in actions:
            time.sleep(max(0.0, when - (time.monotonic() - t0)))
            try:
                if name in ("pause", "resume", "stop"):
                    send(name)
                elif name == "seek":
                    send("seek", {"time": float(value)})
                elif name == "volume":
                    send("set_volume", {"volume": float(value)})
                elif name == "speed":
                    send("set_speed", {"speed": float(value)})
                else:
                    say("unknown action %r" % name)
            except OSError:
                return

    threading.Thread(target=run_actions, daemon=True).start()
    end = time.monotonic() + args.seconds
    sock.settimeout(1.0)
    try:
        while time.monotonic() < end:
            try:
                opcode, body = read_packet(sock)
            except socket.timeout:
                continue
            name = NAMES.get(opcode, "opcode %d" % opcode)
            say("<- %s %s" % (name, "" if body is None else json.dumps(body, ensure_ascii=False)[:400]))
            if opcode == OPS["ping"]:
                send("pong")
    except (EOFError, ValueError, OSError) as e:
        say("ended: %s" % e)
    finally:
        sock.close()
        say("done")


if __name__ == "__main__":
    main()
