#!/usr/bin/env python3
# Copyright (C) 2026 Tobias Schoene www.yaacc.de
# GPL-3.0-or-later, like the rest of YAACC.
"""
A small test receiver for the FCast protocol, for YAACC's developers (issue #327).

It speaks FCast protocol v3 (plain JSON over TCP, port 46899 by default) and, with `--speaks 4`, v4 (TLS 1.3 with a self-signed key,
FlatBuffers; see fcast_v4.py) without any media player: it announces itself
as an `_fcast._tcp` service (optional, needs the `zeroconf` package), logs every message it receives in readable form,
answers the handshake like the Android and the desktop receivers do, plays a made-up track (it only moves a clock, nothing
is fetched or shown) and reports its state to every connected sender. It can also misbehave on purpose.

It is NOT a receiver for real use and it never fetches the URL it is given. Protocol facts: docs/tech.md, "FCast".
Written from the protocol documents; no FCast code is used. Like the desktop receiver it ends a session that sends a body
that is not valid, a second `Version`, or an opcode it does not know (below protocol v4): that is how YAACC's bugs show.
"""
import argparse
import json
import os
import logging
import math
import socket
import socketserver
import struct
import sys
import threading
import time

try:    # FCast v4 needs the packages `flatbuffers` and `cryptography` (requirements.txt); v1 to v3 work without them
    import fcast_v4 as v4
except ImportError:
    v4 = None

MAX_PACKET = 32000          # protocol v3: opcode + body
MAX_PACKET_V4 = 512 * 1024  # protocol v4
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


def reject_constant(name):
    """json.loads accepts NaN and Infinity, which are not JSON: such a body is invalid."""
    raise BadMessage("%s is not valid JSON" % name)


def effective_greeting(options):
    """The Version sent first: what was asked for, else the version the rig speaks (so a v2 rig greets with 2)."""
    return options.greeting if options.greeting is not None else options.speaks


def read_exact(sock, count):
    data = bytearray()
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise EOFError("closed")
        data.extend(chunk)
    return bytes(data)


def read_packet(sock, limit=MAX_PACKET):
    """One packet as (opcode, body bytes or None); None at a clean end; OversizeError over the limit."""
    first = sock.recv(1)
    if not first:
        return None
    size = struct.unpack("<I", first + read_exact(sock, 3))[0]
    if size < 1:
        raise ValueError("packet without opcode (size 0)")
    if size > limit:
        raise OversizeError("packet of %d bytes is over the limit of %d" % (size, limit))
    payload = read_exact(sock, size)
    return payload[0], (payload[1:] if size > 1 else None)


def encode_packet(opcode, body=None):
    """The bytes of a packet, or None if it would be over the limit (the receivers skip such a message)."""
    if body is None:
        data = b""
    elif isinstance(body, bytes):
        data = body
    else:
        data = json.dumps(body, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")  # ValueError on NaN
    if 1 + len(data) > MAX_PACKET:
        return None
    return struct.pack("<I", 1 + len(data)) + bytes([opcode]) + data


def number(body, key, lo=None, hi=None, default=None):
    """A finite number from the body, clamped; the default if absent; BadMessage if present and not a number."""
    if not isinstance(body, dict) or body.get(key) is None:
        return default
    value = body[key]
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise BadMessage("%s is not a number" % key)
    try:
        value = float(value)
    except OverflowError:
        raise BadMessage("%s is too large" % key)
    if not math.isfinite(value):
        raise BadMessage("%s is not a finite number" % key)
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
        self.tracks = []          # v4: the tracks of the loaded item (id, type, language, title)
        self.selected = {}        # v4: the selected track of a type
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
        if self.state == 0:   # like the desktop receiver: an idle update is all zeros
            return {"generationTime": now, "state": 0, "time": 0.0, "duration": 0.0, "speed": 0.0}
        return {"generationTime": now, "state": self.state, "time": round(self.position, 3),
                "duration": self.duration, "speed": self.speed}

    def broadcast(self, name, body):
        with self.lock:
            targets = list(self.sessions)
        for session in targets:
            if session.version >= 4:
                session.send_v4_event(name, body)
            elif session.version >= SINCE.get(name, 1):
                session.send(OP[name], body)

    def broadcast_v4(self, message):
        with self.lock:
            targets = [x for x in self.sessions if x.version >= 4]
        for session in targets:
            session.send_message(message)

    def default_tracks(self):
        """What the rig says an item has: two audio tracks and two subtitle tracks (the first audio track is selected)."""
        self.tracks = [(1, v4.TRACK_AUDIO, "eng", "English"), (2, v4.TRACK_AUDIO, "deu", "Deutsch"),
                       (3, v4.TRACK_SUBTITLE, "eng", "English"), (4, v4.TRACK_SUBTITLE, "deu", "Deutsch")] if v4 else []
        self.selected = {v4.TRACK_AUDIO: 1} if v4 else {}

    def change_track(self, track_id, kind):
        with self.lock:
            if self.play_data is None:
                return
            if track_id is not None and not any(t[0] == track_id and t[1] == kind for t in self.tracks):
                return
            self.selected[kind] = track_id
        self.broadcast_v4(v4.change_track(track_id, kind))

    def add_subtitle(self, url, select, name):
        with self.lock:
            if self.play_data is None:
                return
            track_id = max([t[0] for t in self.tracks] + [0]) + 1
            self.tracks.append((track_id, v4.TRACK_SUBTITLE, "und", name or "External subtitle"))
            tracks = list(self.tracks)
            if select:
                self.selected[v4.TRACK_SUBTITLE] = track_id
        log.info("subtitle file %s added as track %d", url, track_id)
        self.broadcast_v4(v4.tracks_available(tracks))
        if select:
            self.broadcast_v4(v4.change_track(track_id, v4.TRACK_SUBTITLE))

    def _tick(self):
        while True:
            time.sleep(0.5)     # the desktop receiver reports every 0.5 s
            with self.lock:
                if self.state != 1:
                    continue
                self.position += 0.5 * self.speed
                ended = self.position >= self.duration
                if ended:
                    # --end android: a PAUSED update at the duration, then the MediaItemEnd event for the senders that subscribed
                    # --end desktop (default): only the event, no update at all (receiver 3.0.4 on a real run); idle afterwards
                    update = {"generationTime": int(time.time() * 1000), "state": 2, "time": self.duration,
                              "duration": self.duration, "speed": self.speed}
                    item = self.play_data
                    self.state = 0
                    self.position = 0.0
                    self.play_data = None
            if ended:
                if self.options.end == "android":
                    self.broadcast("playback_update", update)
                self.broadcast_event(1, item)
                continue
            self.broadcast("playback_update", self.playback_update())

    def broadcast_event(self, event_type, item):
        body = {"generationTime": int(time.time() * 1000), "event": {"type": event_type}}
        if item is not None:
            body["event"]["item"] = item
        with self.lock:
            targets = [x for x in self.sessions if 3 <= x.version < 4 and event_type in x.subscribed]
            ended = [x for x in self.sessions if x.version >= 4] if event_type == 1 else []
        for session in targets:
            session.send(OP["event"], body)
        for session in ended:   # a v4 receiver says it with the state
            session.send_v4_event("event", body)

    # ---- operations of the senders
    def play(self, message):
        """Playing at once like the desktop receiver (Idle first only with --start-delay), the end as a Paused update at the duration."""
        try:  # a body that cannot be echoed (1e999, a lone surrogate, too deep) is invalid: the session ends, nothing is stored
            encode_packet(OP["play_update"], {"generationTime": 0, "playData": message})
        except (ValueError, RecursionError) as e:   # UnicodeEncodeError is a ValueError
            raise BadMessage("Play body cannot be sent on: %s" % e)
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
            if v4:
                self.default_tracks()
            self.load_token += 1
            token = self.load_token
        self.broadcast("play_update", {"generationTime": int(time.time() * 1000), "playData": message})
        self.broadcast_event(0, message)   # MediaItemStart, for the senders that subscribed
        if volume is not None:
            self.broadcast("volume_update", {"generationTime": int(time.time() * 1000), "volume": self.volume})
        if self.options.start_delay > 0:
            # a receiver that loads first (the desktop receiver does not): Idle now, Playing after the delay
            self.broadcast("playback_update", self.playback_update())
            timer = threading.Timer(self.options.start_delay, self._started, args=(token,))
            timer.daemon = True
            timer.start()
        else:
            self._started(token)

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
        self.subscribed = set()
        self.max_packet = MAX_PACKET
        self.v4_state = None      # the last state sent to a v4 sender (it gets a state message only when it changes)
        self.v4_speed = None

    def send_message(self, message):
        """A v4 message (the bytes of a Packet) in a Flatbuf packet."""
        if self.options.behaviour == "silent" or len(message) + 1 > MAX_PACKET_V4:
            return
        data = struct.pack("<I", 1 + len(message)) + bytes([v4.FLATBUF]) + message
        try:
            with self.write_lock:
                self.request.sendall(data)
            log.info("[%s] -> flatbuf %s", self.peer, _describe(message))
        except OSError:
            self.alive = False

    def send_v4_event(self, name, body):
        """What a change of the shared state means for a v4 sender (the rig keeps its state the v3 way)."""
        if name == "playback_update":
            now = body.get("state", 0)
            mapped = {0: v4.IDLE, 1: v4.PLAYING, 2: v4.PAUSED}.get(now, v4.IDLE)
            if mapped != self.v4_state:
                self.v4_state = mapped
                self.send_message(v4.state(mapped))
            if now != 0:
                self.send_message(v4.progress(body.get("time", 0.0), body.get("duration", 0.0)))
                if body.get("speed") != self.v4_speed:
                    self.v4_speed = body.get("speed")
                    self.send_message(v4.speed(self.v4_speed or 1.0))
        elif name == "volume_update":
            self.send_message(v4.volume(body.get("volume", 0.0)))
        elif name == "play_update":
            data = body.get("playData") or {}
            meta = data.get("metadata") or {}
            self.v4_state = None
            self.v4_speed = None
            self.send_message(v4.load(data.get("container", ""), data.get("url", ""), data.get("time"), data.get("volume"),
                                      data.get("speed"), meta.get("title"), meta.get("thumbnailUrl")))
            self.send_tracks()
        elif name == "event":
            if (body.get("event") or {}).get("type") == 1:
                self.v4_state = v4.ENDED
                self.send_message(v4.state(v4.ENDED))
        elif name == "playback_error":
            self.send_message(v4.error(v4.ERROR_UNSUPPORTED_FORMAT))

    def send_tracks(self):
        with self.receiver.lock:
            tracks = list(self.receiver.tracks)
            selected = dict(self.receiver.selected)
        if not tracks:
            return
        self.send_message(v4.tracks_available(tracks))
        for kind, track_id in selected.items():
            self.send_message(v4.change_track(track_id, kind))

    def send(self, opcode, body=None):
        if self.options.behaviour == "silent":
            return
        if self.version >= 4:
            if opcode in (OP["ping"], OP["pong"]):
                try:
                    with self.write_lock:
                        self.request.sendall(struct.pack("<I", 1) + bytes([opcode]))
                    log.info("[%s] -> %s", self.peer, OP_NAME[opcode])
                except OSError:
                    self.alive = False
            return   # the other opcodes of v3 do not exist in v4
        try:
            data = encode_packet(opcode, body)
        except (ValueError, TypeError, RecursionError) as e:
            log.warning("[%s] not sending %s: %s", self.peer, OP_NAME.get(opcode, opcode), e)
            return
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
            if effective_greeting(self.options) > 0:
                self.send(OP["version"], {"version": effective_greeting(self.options)})
            if self.options.behaviour == "oversize":
                self.request.sendall(struct.pack("<I", MAX_PACKET + 1000) + bytes([OP["playback_update"]]) + b"{")
                log.info("[%s] -> (oversize packet announced on purpose)", self.peer)
            while self.alive:
                try:
                    packet = read_packet(self.request, self.max_packet)
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
                except Exception as e:  # nothing a sender sends may leave the rig in a half done state or kill the thread
                    log.exception("[%s] unexpected %s while handling a packet", self.peer, type(e).__name__)
                    self.end("unexpected error")
                    return
        finally:
            self.alive = False
            self.receiver.remove(self)
            try:
                self.request.close()   # after a TLS upgrade this is the TLS socket: the server only closes the plain one it handed over
            except OSError:
                pass

    def handle_packet_v4(self, opcode, raw):
        """A packet inside TLS. Like the desktop receiver, a message it does not understand is answered with an Error, not the end of the session."""
        if opcode == OP["ping"]:
            self.send(OP["pong"])
            return
        if opcode == OP["pong"]:
            return
        if opcode == OP["version"]:
            raise BadMessage("a second Version")
        if opcode != v4.FLATBUF:
            log.info("[%s] <- opcode %d is not a v4 opcode: answering with an error", self.peer, opcode)
            self.send_message(v4.error(0))
            return
        try:
            message = v4.read(raw or b"")
        except v4.Bad as e:
            log.warning("[%s] a Flatbuf packet that is not valid (%s): answering with an error", self.peer, e)
            self.send_message(v4.error(v4.ERROR_MALFORMED))
            return
        log.info("[%s] <- flatbuf %s", self.peer, {k: v for k, v in message.items() if v is not None and k != "tracks"})
        receiver = self.receiver
        kind = message["kind"]
        if kind == "sender_introduction":
            pass
        elif kind == "load":
            body = {"container": message["container"], "url": message["url"]}
            for source, key in (("time", "time"), ("volume", "volume"), ("speed", "speed")):
                if message.get(source) is not None:
                    body[key] = message[source]
            if message.get("title") or message.get("thumbnail"):
                body["metadata"] = {"type": 0, "title": message.get("title"), "thumbnailUrl": message.get("thumbnail")}
            receiver.play(body)
        elif kind == "state":
            if message["state"] == v4.PAUSED:
                receiver.set_state(2)
            elif message["state"] == v4.PLAYING:
                receiver.set_state(1)
            else:
                receiver.set_state(0)
        elif kind == "stop":
            receiver.set_state(0)
        elif kind == "progress":
            if message.get("position") is not None:
                receiver.seek(message["position"])
        elif kind == "volume":
            receiver.set_volume(max(0.0, min(1.0, message["volume"])))
        elif kind == "speed":
            receiver.set_speed(max(MIN_RATE, min(MAX_RATE, message["speed"])))
        elif kind == "change_track":
            receiver.change_track(message["id"], message["type"])
        elif kind == "add_subtitle":
            receiver.add_subtitle(message["url"], message["select"], message["name"])
        else:
            log.info("[%s] (not handled by the rig)", self.peer)

    def handle_packet(self, opcode, raw):
        if self.version >= 4:
            self.handle_packet_v4(opcode, raw)
            return
        name = OP_NAME.get(opcode)
        if name is None:
            raise BadMessage("unknown opcode %d" % opcode)
        body = None
        if raw:
            try:
                body = json.loads(raw.decode("utf-8"), parse_constant=reject_constant)
            except (ValueError, RecursionError):  # UnicodeDecodeError is a ValueError
                raise BadMessage("%s with a body that is not JSON" % name)
        log.info("[%s] <- %s %s", self.peer, name, "" if body is None else json.dumps(body, ensure_ascii=False)[:300])
        receiver = self.receiver
        if opcode == OP["version"]:
            self.on_version(body)
            return
        if self.version < 2 and opcode in (OP["ping"], OP["pong"], OP["set_speed"]):
            raise BadMessage("%s is not in protocol v1" % name)
        if self.version < 3 and opcode in (OP["initial"], OP["set_playlist_item"], OP["subscribe_event"], OP["unsubscribe_event"]):
            raise BadMessage("%s is not in protocol v%d" % (name, self.version))
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
        elif opcode in (OP["subscribe_event"], OP["unsubscribe_event"]):
            event = body.get("event") if isinstance(body, dict) else None
            kind = event.get("type") if isinstance(event, dict) else None
            if isinstance(kind, bool) or not isinstance(kind, int):
                raise BadMessage("%s without an event type" % name)
            if opcode == OP["subscribe_event"]:
                self.subscribed.add(kind)
            else:
                self.subscribed.discard(kind)
        elif opcode in (OP["initial"], OP["pong"], OP["set_playlist_item"]):
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
            if self.options.speaks < 4 or v4 is None:
                raise BadMessage("the sender asks for version %d: the rig speaks up to %d (--speaks 4 gives it TLS)" % (theirs, min(3, self.options.speaks)))
            self.start_v4()
            return
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

    def start_v4(self):
        """The TLS 1.3 upgrade in place (the sender is the client), then what a v4 receiver says to a new sender."""
        self.request.settimeout(5)
        try:
            self.request = self.server.tls.wrap_socket(self.request, server_side=True)
        except (OSError, ValueError) as e:   # ssl.SSLError is an OSError: the sender refused the key, or did not speak TLS 1.3
            raise BadMessage("the TLS handshake failed: %s" % e)
        self.request.settimeout(None)
        self.version = 4
        self.settled = True
        self.max_packet = MAX_PACKET_V4
        receiver = self.receiver
        log.info("[%s] TLS %s, version settled on 4", self.peer, self.request.version())
        self.send_message(v4.receiver_introduction(self.options.name, "yaacc-test-rig", "1"))
        self.send_message(v4.volume(receiver.volume))
        with receiver.lock:
            data = receiver.play_data
            busy = receiver.state != 0
            update = receiver.playback_update()
        if data is not None:
            meta = data.get("metadata") or {}
            self.send_message(v4.load(data.get("container", ""), data.get("url", ""), data.get("time"), data.get("volume"), data.get("speed"),
                                      meta.get("title"), meta.get("thumbnailUrl")))
            self.v4_state = None
            if busy:
                self.send_v4_event("playback_update", update)
            self.send_tracks()

    def _heartbeat(self):
        mode = self.options.heartbeat
        if mode == "none" or self.options.behaviour == "silent":
            return   # a silent receiver does not ping, so it cannot end a silent sender either
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
                if idle > 10.0:   # the Android receiver drops after the 5th tick without an answer (10 to 12.5 s)
                    self.end("no packet for %.1f s" % idle)
                    return
                if self.version >= 2:
                    self.send(OP["ping"])
                time.sleep(2.0)


def _describe(message):
    try:
        found = v4.read(message)
        return {k: v for k, v in found.items() if v is not None and k != "tracks"}
    except v4.Bad:
        return "(not readable)"


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, options):
        self.options = options
        self.fingerprint = None
        self.tls = None
        if options.speaks >= 4:
            if v4 is None:
                raise SystemExit("--speaks 4 needs the packages in requirements.txt (pip install -r requirements.txt)")
            cert, key, self.fingerprint = v4.make_identity(options.cert_dir)
            self.tls = v4.tls_context(cert, key)
        self.receiver = Receiver(options)
        super().__init__(address, Session)


def advertise(options, port, fingerprint=None):
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
            properties=dict({"version": str(effective_greeting(options) or 3), "appName": "yaacc-test-rig", "appVersion": "1"},
                            **({"v": "4", "fp": fingerprint} if fingerprint else {})),
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
    p.add_argument("--greeting", type=int, default=None, help="Version sent first on connect and announced by mDNS: default the version the rig speaks (3: like the Android receiver); 4: like the desktop receiver, which then expects 3; 0: none")
    p.add_argument("--speaks", type=int, default=3, choices=(1, 2, 3, 4),
                   help="highest protocol version the rig speaks (1, 2: no Initial; 4: TLS 1.3 and FlatBuffers for a sender that asks for 4, "
                        "v3 for the others, like the desktop receiver; announces `v` and `fp` by mDNS and prints the connection link)")
    p.add_argument("--cert-dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".rig-cert"),
                   help="where the rig keeps its self-signed key for --speaks 4 (made once, so that its fingerprint stays the same between runs)")
    p.add_argument("--behaviour", default="normal", choices=("normal", "slow", "silent", "oversize", "ignore-pings"),
                   help="misbehave on purpose: slow (each answer waits --slow-seconds), silent (answers nothing), "
                        "oversize (announces a packet over 32000 bytes), ignore-pings")
    p.add_argument("--slow-seconds", type=float, default=2.0)
    p.add_argument("--heartbeat", default="desktop", choices=("desktop", "android", "none"),
                   help="ping like the desktop receiver (default), like the Android receiver, or not at all")
    p.add_argument("--end", default="desktop", choices=("desktop", "android"),
                   help="how the end of an item is reported: desktop (default, the real desktop receiver 3.0.4): only the MediaItemEnd event "
                        "to subscribers; android: a Paused update at the duration, then the event")
    p.add_argument("--duration", type=float, default=180.0, help="length in seconds of the pretend track")
    p.add_argument("--start-delay", type=float, default=0.0, help="0 (default): Playing at once after a Play, like the desktop receiver; above 0: an Idle update first and the Playing after this many seconds")
    p.add_argument("-v", "--verbose", action="store_true")
    options = p.parse_args(argv)
    for name in ("duration", "start_delay", "slow_seconds"):
        value = getattr(options, name)
        if not math.isfinite(value) or (value <= 0 and name == "duration") or value < 0:
            p.error("--%s must be a finite positive number" % name.replace("_", "-"))
    return options


def main(argv=None):
    options = parse(argv if argv is not None else sys.argv[1:])
    logging.basicConfig(level=logging.DEBUG if options.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    server = Server((options.host, options.port), options)
    port = server.server_address[1]
    log.info("test receiver '%s' listening on %s:%d (protocol up to v%d, behaviour %s, heartbeat %s)",
             options.name, options.host, port, options.speaks, options.behaviour, options.heartbeat)
    if server.fingerprint:
        addresses = options.address or [socket.gethostbyname(socket.gethostname())]
        log.info("key fingerprint (fp): %s", server.fingerprint)
        log.info("connection link (the text of the QR code a receiver shows; paste it into YAACC's Add receiver): %s",
                 v4.connection_url(options.name, addresses, port, server.fingerprint))
    registration = None if options.no_advertise else advertise(options, port, server.fingerprint)
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
