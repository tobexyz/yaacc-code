#!/usr/bin/env python3
# Copyright (C) 2026 Tobias Schoene www.yaacc.de
# GPL-3.0-or-later, like the rest of YAACC.
"""
A small test receiver for the FCast protocol, for YAACC's developers (issue #327).

It speaks FCast protocol v3 (plain JSON over TCP, port 46899 by default) without any media player: it announces itself
as an `_fcast._tcp` service (optional, needs the `zeroconf` package), logs every message it receives in readable form,
answers the handshake like the Android and the desktop receivers do, plays a made-up track (it only moves a clock, nothing
is fetched or shown) and reports its state to every connected sender. It can also misbehave on purpose.

It is NOT a receiver for real use and it never fetches the URL it is given. Protocol facts: docs/tech.md, "FCast".
Written from the protocol documents; no FCast code is used. Like the desktop receiver it ends a session that sends a body
that is not valid, a second `Version`, or an opcode it does not know (below protocol v4): that is how YAACC's bugs show.
"""
import argparse
import json
import logging
import math
import socket
import socketserver
import struct
import sys
import threading
import time

MAX_PACKET = 32000          # protocol v3: opcode + body
SERVICE_TYPE = "_fcast._tcp.local."
MIN_RATE, MAX_RATE = 1.0 / 16.0, 16.0   # what the desktop receiver clamps the speed to

OP = {
    "none": 0, "play": 1, "pause": 2, "resume": 3, "stop": 4, "seek": 5, "playback_update": 6,
    "volume_update": 7, "set_volume": 8, "playback_error": 9, "set_speed": 10, "version": 11,
    "ping": 12, "pong": 13, "initial": 14, "play_update": 15, "set_playlist_item": 16,
    "subscribe_event": 17, "unsubscribe_event": 18, "event": 19,
}
OP_NAME = {v: k for k, v in OP.items()}
# the first protocol version that has the message (the receivers send a message only to sessions that have it)
SINCE = {"playback_update": 1, "volume_update": 1, "playback_error": 2, "ping": 2, "pong": 2, "initial": 3,
         "play_update": 3, "event": 3}

log = logging.getLogger("fcast-rig")


class OversizeError(Exception):
    pass


class BadMessage(Exception):
    """A body that is not valid for its opcode: the session ends (as the desktop receiver does)."""


def read_exact(sock, count):
    data = bytearray()
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise EOFError("closed")
        data.extend(chunk)
    return bytes(data)


def read_packet(sock):
    """One packet as (opcode, body bytes or None); None at a clean end; OversizeError over the limit."""
    first = sock.recv(1)
    if not first:
        return None
    size = struct.unpack("<I", first + read_exact(sock, 3))[0]
    if size < 1:
        raise ValueError("packet without opcode (size 0)")
    if size > MAX_PACKET:
        raise OversizeError("packet of %d bytes is over the limit of %d" % (size, MAX_PACKET))
    payload = read_exact(sock, size)
    return payload[0], (payload[1:] if size > 1 else None)


def encode_packet(opcode, body=None):
    """The bytes of a packet, or None if it would be over the limit (the receivers skip such a message)."""
    if body is None:
        data = b""
    elif isinstance(body, bytes):
        data = body
    else:
        data = json.dumps(body, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")
    if 1 + len(data) > MAX_PACKET:
        return None
    return struct.pack("<I", 1 + len(data)) + bytes([opcode]) + data


def number(body, key, lo=None, hi=None, default=None):
    """A finite number from the body, clamped; the default if absent; BadMessage if present and not a number."""
    if not isinstance(body, dict) or body.get(key) is None:
        return default
    value = body[key]
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise BadMessage("%s is not a finite number" % key)
    value = float(value)
    if lo is not None:
        value = max(lo, value)
    if hi is not None:
        value = min(hi, value)
    return value


class Receiver:
    """The state of the pretend receiver, shared by all senders."""

    def __init__(self, options):
        self.options = options
        self.lock = threading.RLock()
        self.sessions = []
        self.state = 0            # 0 idle, 1 playing, 2 paused
        self.position = 0.0
        self.duration = options.duration
        self.speed = 1.0
        self.volume = 1.0
        self.play_data = None
        self.load_token = 0
        self._ticker = threading.Thread(target=self._tick, name="rig-clock", daemon=True)
        self._ticker.start()

    def add(self, session):
        with self.lock:
            self.sessions.append(session)

    def remove(self, session):
        with self.lock:
            if session in self.sessions:
                self.sessions.remove(session)

    def playback_update(self):
        now = int(time.time() * 1000)
        if self.state == 0:
            return {"generationTime": now, "state": 0}
        return {"generationTime": now, "state": self.state, "time": round(self.position, 3),
                "duration": self.duration, "speed": self.speed}

    def broadcast(self, name, body):
        with self.lock:
            targets = list(self.sessions)
        for session in targets:
            if session.version >= SINCE.get(name, 1):
                session.send(OP[name], body)

    def _tick(self):
        while True:
            time.sleep(1.0)
            with self.lock:
                if self.state != 1:
                    continue
                self.position += self.speed
                if self.position >= self.duration:
                    self.state = 0
                    self.position = 0.0
                    self.play_data = None
            self.broadcast("playback_update", self.playback_update())

    # ---- operations of the senders
    def play(self, message):
        """Like a receiver that loads a new item: Idle first, Playing after the start delay, Idle at the end."""
        start = number(message, "time", lo=0.0, default=0.0)
        speed = number(message, "speed", lo=MIN_RATE, hi=MAX_RATE, default=1.0)
        volume = number(message, "volume", lo=0.0, hi=1.0)
        with self.lock:
            self.play_data = message
            self.state = 0
            self.position = start
            self.speed = speed
            self.duration = self.options.duration
            if volume is not None:
                self.volume = volume
            self.load_token += 1
            token = self.load_token
        self.broadcast("play_update", {"generationTime": int(time.time() * 1000), "playData": message})
        self.broadcast("playback_update", self.playback_update())
        if volume is not None:
            self.broadcast("volume_update", {"generationTime": int(time.time() * 1000), "volume": self.volume})
        threading.Timer(self.options.start_delay, self._started, args=(token,)).start()

    def _started(self, token):
        with self.lock:
            if token != self.load_token or self.play_data is None or self.state != 0:
                return
            self.state = 1
        self.broadcast("playback_update", self.playback_update())

    def set_state(self, state):
        with self.lock:
            if self.play_data is None:
                return                      # nothing loaded: pause, resume and stop change nothing
            if state == 0:
                self.load_token += 1
                self.position = 0.0
                self.play_data = None
            self.state = state
        self.broadcast("playback_update", self.playback_update())

    def seek(self, seconds):
        with self.lock:
            if self.play_data is None:
                return
            self.position = max(0.0, min(seconds, self.duration))
        self.broadcast("playback_update", self.playback_update())

    def set_volume(self, volume):
        with self.lock:
            self.volume = volume
        self.broadcast("volume_update", {"generationTime": int(time.time() * 1000), "volume": self.volume})

    def set_speed(self, speed):
        with self.lock:
            self.speed = speed
        self.broadcast("playback_update", self.playback_update())


class Session(socketserver.BaseRequestHandler):
    """One connected sender."""

    def setup(self):
        self.options = self.server.options
        self.receiver = self.server.receiver
        self.write_lock = threading.Lock()
        self.version = 1 if self.options.speaks < 2 else 2
        self.settled = False
        self.sent_initial = False
        self.last_packet = time.monotonic()
        self.waiting_for_pong = False
        self.peer = "%s:%s" % self.client_address[:2]
        self.alive = True
        self.handler_thread = None

    def send(self, opcode, body=None):
        if self.options.behaviour == "silent":
            return
        data = encode_packet(opcode, body)
        if data is None:
            log.warning("[%s] not sending %s: over the %d byte limit (the receivers skip such a message too)",
                        self.peer, OP_NAME.get(opcode, opcode), MAX_PACKET)
            return
        if self.options.behaviour == "slow" and threading.current_thread() is self.handler_thread:
            time.sleep(self.options.slow_seconds)   # only the answers of this session, not the shared clock
        try:
            with self.write_lock:
                self.request.sendall(data)
            log.info("[%s] -> %s %s", self.peer, OP_NAME.get(opcode, opcode), "" if body is None else json.dumps(body, ensure_ascii=False)[:300])
        except OSError:
            self.alive = False

    def end(self, reason):
        log.warning("[%s] %s: ending the session", self.peer, reason)
        self.alive = False
        try:
            self.request.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def handle(self):
        self.handler_thread = threading.current_thread()
        log.info("[%s] connected", self.peer)
        self.receiver.add(self)
        threading.Thread(target=self._heartbeat, name="rig-heartbeat", daemon=True).start()
        try:
            if self.options.greeting > 0:
                self.send(OP["version"], {"version": self.options.greeting})
            if self.options.behaviour == "oversize":
                self.request.sendall(struct.pack("<I", MAX_PACKET + 1000) + bytes([OP["playback_update"]]) + b"{")
                log.info("[%s] -> (oversize packet announced on purpose)", self.peer)
            while self.alive:
                try:
                    packet = read_packet(self.request)
                except OversizeError as e:
                    log.warning("[%s] %s: closing", self.peer, e)
                    return
                except (EOFError, OSError, ValueError) as e:
                    log.info("[%s] closed (%s)", self.peer, e)
                    return
                if packet is None:
                    log.info("[%s] closed", self.peer)
                    return
                self.last_packet = time.monotonic()
                self.waiting_for_pong = False
                try:
                    self.handle_packet(*packet)
                except BadMessage as e:
                    self.end("invalid message: %s" % e)
                    return
        finally:
            self.alive = False
            self.receiver.remove(self)

    def handle_packet(self, opcode, raw):
        name = OP_NAME.get(opcode)
        if name is None:
            raise BadMessage("unknown opcode %d" % opcode)
        body = None
        if raw:
            try:
                body = json.loads(raw.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                raise BadMessage("%s with a body that is not JSON" % name)
        log.info("[%s] <- %s %s", self.peer, name, "" if body is None else json.dumps(body, ensure_ascii=False)[:300])
        receiver = self.receiver
        if opcode == OP["version"]:
            self.on_version(body)
            return
        if self.version < 2 and opcode in (OP["ping"], OP["pong"], OP["set_speed"]):
            raise BadMessage("%s is not in protocol v1" % name)
        if self.options.behaviour == "ignore-pings" and opcode == OP["ping"]:
            return
        if opcode == OP["ping"]:
            self.send(OP["pong"])
        elif opcode == OP["play"]:
            if not isinstance(body, dict) or not isinstance(body.get("container"), str) or not body["container"] \
                    or not (isinstance(body.get("url"), str) and body["url"] or isinstance(body.get("content"), str) and body["content"]):
                self.send(OP["playback_error"], {"message": "Play needs a container and a url or content"})
                return
            receiver.play(body)
        elif opcode == OP["pause"]:
            receiver.set_state(2)
        elif opcode == OP["resume"]:
            receiver.set_state(1)
        elif opcode == OP["stop"]:
            receiver.set_state(0)
        elif opcode == OP["seek"]:
            seconds = number(body, "time")
            if seconds is None:
                raise BadMessage("Seek without time")
            receiver.seek(seconds)
        elif opcode == OP["set_volume"]:
            volume = number(body, "volume", lo=0.0, hi=1.0)
            if volume is None:
                raise BadMessage("SetVolume without volume")
            receiver.set_volume(volume)
        elif opcode == OP["set_speed"]:
            speed = number(body, "speed", lo=MIN_RATE, hi=MAX_RATE)
            if speed is None:
                raise BadMessage("SetSpeed without speed")
            receiver.set_speed(speed)
        elif opcode in (OP["initial"], OP["pong"], OP["subscribe_event"], OP["unsubscribe_event"], OP["set_playlist_item"]):
            pass
        else:
            log.info("[%s] (not handled by the rig)", self.peer)

    def on_version(self, body):
        if self.settled:
            raise BadMessage("a second Version")
        theirs = body.get("version") if isinstance(body, dict) else None
        if isinstance(theirs, bool) or not isinstance(theirs, int) or theirs < 1:
            raise BadMessage("illegal version %r" % (theirs,))
        if theirs >= 4:
            raise BadMessage("the sender asks for version %d: the rig speaks up to 3 and has no TLS" % theirs)
        self.version = min(theirs, self.options.speaks)
        self.settled = True
        receiver = self.receiver
        log.info("[%s] version settled on %d", self.peer, self.version)
        if self.version >= 3 and not self.sent_initial:
            self.sent_initial = True
            initial = {"displayName": self.options.name, "appName": "yaacc-test-rig", "appVersion": "1"}
            with receiver.lock:
                if receiver.play_data is not None:
                    initial["playData"] = receiver.play_data
            self.send(OP["initial"], initial)
        self.send(OP["volume_update"], {"generationTime": int(time.time() * 1000), "volume": receiver.volume})
        with receiver.lock:
            busy = receiver.state != 0
        if busy and self.version >= 1:
            self.send(OP["playback_update"], receiver.playback_update())

    def _heartbeat(self):
        mode = self.options.heartbeat
        if mode == "none":
            return
        while self.alive:
            time.sleep(0.5)
            idle = time.monotonic() - self.last_packet
            if mode == "desktop":
                # like the desktop receiver: ping after 3 s of silence, end the session after 6 s
                if idle >= 6.0 and self.waiting_for_pong:
                    self.end("no packet for %.1f s" % idle)
                    return
                if idle >= 3.0 and not self.waiting_for_pong:
                    self.waiting_for_pong = True
                    self.send(OP["ping"])
            elif mode == "android":
                # like the Android receiver: a ping every 2.5 s, gone after more than 3 unanswered (about 10 s)
                if idle > 10.0:
                    self.end("no packet for %.1f s" % idle)
                    return
                self.send(OP["ping"])
                time.sleep(2.0)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, options):
        self.options = options
        self.receiver = Receiver(options)
        super().__init__(address, Session)


def advertise(options, port):
    try:
        from zeroconf import IPVersion, ServiceInfo, Zeroconf
    except ImportError:
        log.warning("the package 'zeroconf' is not installed: not announcing the service (pip install -r requirements.txt)")
        return None
    try:
        addresses = options.address or [socket.gethostbyname(socket.gethostname())]
        packed = [socket.inet_aton(a) for a in addresses]
        info = ServiceInfo(
            SERVICE_TYPE, "%s.%s" % (options.name, SERVICE_TYPE), addresses=packed, port=port,
            properties={"version": str(options.greeting or 3), "appName": "yaacc-test-rig", "appVersion": "1"},
            server=options.name.replace(" ", "-") + ".local.")
        zc = Zeroconf(ip_version=IPVersion.V4Only)
        zc.register_service(info)
    except Exception as e:  # the rig is still useful without the announcement: add it by hand in YAACC
        log.warning("could not announce the service (%s: %s); add the receiver by hand in YAACC", type(e).__name__, e)
        return None
    log.info("announcing %s on %s port %d", info.name, ", ".join(addresses), port)
    return zc, info


def parse(argv):
    p = argparse.ArgumentParser(description="Test receiver for the FCast protocol v3, for YAACC (no media is played)")
    p.add_argument("--host", default="0.0.0.0", help="address to listen on (default all)")
    p.add_argument("--port", type=int, default=46899, help="TCP port (default 46899; 0 picks a free one and prints it)")
    p.add_argument("--name", default="YAACC test rig", help="name announced by mDNS and sent in Initial")
    p.add_argument("--address", action="append", help="IPv4 address to announce (repeat for more; default: this host's)")
    p.add_argument("--no-advertise", action="store_true", help="do not announce the service by mDNS")
    p.add_argument("--greeting", type=int, default=3, help="Version sent first on connect: 3 (Android receiver, default), 4 (desktop receiver, which then expects 3), 0 none")
    p.add_argument("--speaks", type=int, default=3, choices=(1, 2, 3), help="highest protocol version the rig speaks (1, 2: no Initial)")
    p.add_argument("--behaviour", default="normal", choices=("normal", "slow", "silent", "oversize", "ignore-pings"),
                   help="misbehave on purpose: slow (each answer waits --slow-seconds), silent (answers nothing), "
                        "oversize (announces a packet over 32000 bytes), ignore-pings")
    p.add_argument("--slow-seconds", type=float, default=2.0)
    p.add_argument("--heartbeat", default="desktop", choices=("desktop", "android", "none"),
                   help="ping like the desktop receiver (default), like the Android receiver, or not at all")
    p.add_argument("--duration", type=float, default=180.0, help="length in seconds of the pretend track")
    p.add_argument("--start-delay", type=float, default=0.5, help="seconds between the Idle after a Play and the Playing")
    p.add_argument("-v", "--verbose", action="store_true")
    return p.parse_args(argv)


def main(argv=None):
    options = parse(argv if argv is not None else sys.argv[1:])
    logging.basicConfig(level=logging.DEBUG if options.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    server = Server((options.host, options.port), options)
    port = server.server_address[1]
    log.info("test receiver '%s' listening on %s:%d (protocol up to v%d, behaviour %s, heartbeat %s)",
             options.name, options.host, port, options.speaks, options.behaviour, options.heartbeat)
    registration = None if options.no_advertise else advertise(options, port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        if registration:
            zc, info = registration
            zc.unregister_service(info)
            zc.close()
        server.server_close()


if __name__ == "__main__":
    main()
