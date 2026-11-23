# Copyright (C) 2026 Tobias Schoene www.yaacc.de
# GPL-3.0-or-later, like the rest of YAACC.
"""Self-test of the rig (loopback only, no fixed port): see README.md, "Test the rig itself"."""
import json
import socket
import struct
import threading
import time
import unittest

import fcast_rig as rig


def options(**kw):
    base = rig.parse([])
    for key, value in kw.items():
        setattr(base, key, value)
    return base


class Client:
    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=3)

    def send(self, name, body=None):
        self.sock.sendall(rig.encode_packet(rig.OP[name], body))

    def raw(self, data):
        self.sock.sendall(data)

    def read(self, timeout=3):
        self.sock.settimeout(timeout)
        packet = rig.read_packet(self.sock)
        if packet is None:
            return None
        opcode, raw = packet
        return rig.OP_NAME[opcode], (json.loads(raw) if raw else None)

    def until(self, name, timeout=3):
        """The next packet with this name; socket.timeout when none comes within `timeout` seconds in total."""
        end = time.monotonic() + timeout
        while True:
            left = end - time.monotonic()
            if left <= 0:
                raise socket.timeout("no %s within %s s" % (name, timeout))
            packet = self.read(left)
            if packet is None or packet[0] == name:
                return packet

    def until_where(self, name, test, timeout=3):
        """The next packet of this name whose body passes `test`."""
        end = time.monotonic() + timeout
        while True:
            packet = self.until(name, max(0.01, end - time.monotonic()))
            if packet is None or test(packet[1]):
                return packet

    def until_state(self, state, timeout=3):
        while True:
            packet = self.until("playback_update", timeout)
            if packet is None or packet[1]["state"] == state:
                return packet

    def states(self, count, timeout=4):
        """The states of the next `count` PlaybackUpdates."""
        return [self.until("playback_update", timeout)[1]["state"] for _ in range(count)]

    def closed(self, timeout=3):
        """True if the rig closes the connection (nothing but closing is accepted as the end)."""
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                if self.read(timeout=max(0.05, end - time.monotonic())) is None:
                    return True
            except socket.timeout:   # a TimeoutError is an OSError too: it must be caught first, it is no close
                continue
            except (ConnectionError, OSError):
                return True
        return False

    def close(self):
        self.sock.close()


class RigTest(unittest.TestCase):
    def start(self, **kw):
        kw.setdefault("heartbeat", "none")
        server = rig.Server(("127.0.0.1", 0), options(**kw))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server, server.server_address[1]

    def client(self, port, handshake=False):
        c = Client(port)
        self.addCleanup(c.close)
        if handshake:
            c.send("version", {"version": 3})
            c.until("volume_update")
        return c

    # ---- framing

    def test_port_zero_picks_a_free_port(self):
        _, port = self.start()
        self.assertNotIn(port, (0, 46899))

    def test_framing_is_little_endian_and_counts_the_opcode(self):
        self.assertEqual(rig.encode_packet(rig.OP["pause"]), bytes([1, 0, 0, 0, 2]))
        body = json.dumps({"time": 5.5}, separators=(",", ":")).encode()
        self.assertEqual(rig.encode_packet(rig.OP["seek"], {"time": 5.5}), struct.pack("<I", 1 + len(body)) + bytes([5]) + body)

    def test_a_message_over_the_limit_is_not_encoded_and_text_stays_utf8(self):
        self.assertIsNone(rig.encode_packet(rig.OP["play"], {"url": "a" * rig.MAX_PACKET}))
        self.assertIn("é".encode("utf-8"), rig.encode_packet(rig.OP["play"], {"t": "é"}))
        with self.assertRaises(ValueError):
            rig.encode_packet(rig.OP["play"], {"t": float("inf")})

    # ---- handshake

    def test_handshake_greeting_initial_and_volume(self):
        _, port = self.start()
        c = self.client(port)
        self.assertEqual(c.read(), ("version", {"version": 3}))
        c.send("version", {"version": 3})
        name, body = c.read()
        self.assertEqual(name, "initial")
        self.assertEqual(body["displayName"], "YAACC test rig")
        self.assertEqual(c.read()[0], "volume_update")

    def test_desktop_style_greeting_4_settles_on_3(self):
        _, port = self.start(greeting=4)
        c = self.client(port)
        self.assertEqual(c.read(), ("version", {"version": 4}))
        c.send("version", {"version": 3})
        self.assertEqual(c.read()[0], "initial")

    def test_version_2_receiver_sends_no_initial(self):
        _, port = self.start(speaks=2, greeting=2)
        c = self.client(port)
        c.read()
        c.send("version", {"version": 3})
        self.assertEqual(c.read()[0], "volume_update")

    def test_a_v2_rig_greets_with_2_by_default(self):
        _, port = self.start(speaks=2)
        c = self.client(port)
        self.assertEqual(c.read(), ("version", {"version": 2}))
        self.assertEqual(rig.effective_greeting(options(speaks=1)), 1)
        self.assertEqual(rig.effective_greeting(options()), 3)
        self.assertEqual(rig.effective_greeting(options(greeting=4)), 4)

    def test_a_hostile_body_does_not_spoil_the_rig_for_the_next_sender(self):
        _, port = self.start()
        bad = self.client(port)
        bad.read()
        bad.raw(struct.pack("<I", 1 + len(b'{"container":"a/b","url":"http://h","m":NaN}')) + bytes([1]) + b'{"container":"a/b","url":"http://h","m":NaN}')
        self.assertTrue(bad.closed())
        good = self.client(port)
        good.send("version", {"version": 3})
        self.assertEqual(good.until("initial")[0], "initial")

    def test_a_rejected_play_leaves_nothing_behind_for_later_senders(self):
        _, port = self.start()
        bad = self.client(port)
        bad.read()
        body = b'{"container":"a/b","url":"http://h","metadata":{"m":1e999}}'
        bad.raw(struct.pack("<I", 1 + len(body)) + bytes([1]) + body)
        self.assertTrue(bad.closed())
        good = self.client(port)
        good.send("version", {"version": 3})
        initial = good.until("initial")[1]
        self.assertNotIn("playData", initial)

    def test_the_options_are_checked(self):
        for argv in (["--duration", "inf"], ["--duration", "0"], ["--start-delay", "-1"], ["--slow-seconds", "nan"]):
            with self.assertRaises(SystemExit):
                rig.parse(argv)

    def test_version_1_receiver_sends_no_initial_and_ends_the_session_on_a_ping(self):
        _, port = self.start(speaks=1, greeting=0)
        c = self.client(port)
        c.send("version", {"version": 3})
        self.assertEqual(c.read()[0], "volume_update")
        c.send("ping")  # v1 has no Ping: the desktop receiver ends such a session
        self.assertTrue(c.closed())

    def test_the_session_ends_on_bad_input_like_the_desktop_receiver(self):
        cases = {
            "version 4": lambda c: c.send("version", {"version": 4}),
            "version 0": lambda c: c.send("version", {"version": 0}),
            "version not an integer": lambda c: c.send("version", {"version": "abc"}),
            "version without a body": lambda c: c.send("version"),
            "a second version": lambda c: (c.send("version", {"version": 3}), c.send("version", {"version": 3})),
            "an unknown opcode": lambda c: c.raw(struct.pack("<I", 1) + bytes([99])),
            "a body that is not json": lambda c: c.raw(struct.pack("<I", 4) + bytes([1]) + b"{x}"),
            "a size of zero": lambda c: c.raw(b"\0\0\0\0"),
            "a packet over the limit": lambda c: c.raw(struct.pack("<I", rig.MAX_PACKET + 1) + bytes([1])),
            "seek without time": lambda c: c.send("seek", {}),
            "seek with a text": lambda c: c.send("seek", {"time": "5"}),
            "set_volume with a list": lambda c: c.send("set_volume", [1]),
            "a body with NaN": lambda c: c.raw(
                struct.pack("<I", 1 + len(b'{"container":"a/b","url":"http://h","m":NaN}')) + bytes([1]) + b'{"container":"a/b","url":"http://h","m":NaN}'),
            "a number too large anywhere in a play": lambda c: c.raw(
                struct.pack("<I", 1 + len(b'{"container":"a/b","url":"http://h","metadata":{"m":1e999}}')) + bytes([1])
                + b'{"container":"a/b","url":"http://h","metadata":{"m":1e999}}'),
            "a lone surrogate in a play": lambda c: c.raw(
                struct.pack("<I", 1 + len(b'{"container":"a/b","url":"\\ud800"}')) + bytes([1]) + b'{"container":"a/b","url":"\\ud800"}'),
            "a time larger than any float": lambda c: c.raw(
                struct.pack("<I", 1 + len(b'{"time":' + b"1" * 400 + b"}")) + bytes([5]) + b'{"time":' + b"1" * 400 + b"}"),
            "a body nested too deep": lambda c: c.raw(
                struct.pack("<I", 1 + 20000) + bytes([5]) + b"[" * 10000 + b"]" * 10000),
            "initial from a version 2 sender": lambda c: (c.send("version", {"version": 2}), c.send("initial", {"displayName": "x"})),
            "play with an infinite speed": lambda c: c.raw(
                struct.pack("<I", 1 + len(b'{"container":"a/b","url":"http://h","speed":1e999}')) + bytes([1])
                + b'{"container":"a/b","url":"http://h","speed":1e999}'),
        }
        for label, act in cases.items():
            with self.subTest(label):
                _, port = self.start()
                c = self.client(port)
                c.read()
                act(c)
                self.assertTrue(c.closed(), label + ": the rig kept the session")

    def test_ping_pong_and_ignore_pings(self):
        _, port = self.start()
        c = self.client(port)
        c.send("version", {"version": 3})
        c.send("ping")
        self.assertEqual(c.until("pong")[0], "pong")
        _, port2 = self.start(behaviour="ignore-pings")
        d = self.client(port2)
        d.send("version", {"version": 3})
        d.send("ping")
        with self.assertRaises(socket.timeout):
            d.until("pong", timeout=0.4)

    # ---- playing

    def test_play_pause_resume_seek_volume_speed_stop(self):
        _, port = self.start()
        c = self.client(port, handshake=True)
        c.send("play", {"container": "video/mp4", "url": "http://192.168.1.2/a.mp4", "time": 10, "volume": 0.5})
        self.assertEqual(c.until("play_update")[1]["playData"]["url"], "http://192.168.1.2/a.mp4")
        self.assertEqual(c.until("volume_update")[1]["volume"], 0.5)   # sent with the new item, before it starts
        playing = c.until_state(1)[1]
        self.assertEqual((playing["time"], playing["duration"]), (10.0, 180.0))
        c.send("pause")
        self.assertEqual(c.until_state(2)[1]["state"], 2)
        c.send("resume")
        self.assertEqual(c.until_state(1)[1]["state"], 1)
        c.send("seek", {"time": 99})
        self.assertGreaterEqual(c.until_where("playback_update", lambda b: b["time"] >= 99.0)[1]["time"], 99.0)
        c.send("set_volume", {"volume": 2})  # clamped
        self.assertEqual(c.until("volume_update")[1]["volume"], 1.0)
        c.send("set_speed", {"speed": 1.5})
        self.assertEqual(c.until_where("playback_update", lambda b: b["speed"] == 1.5)[1]["speed"], 1.5)
        c.send("stop")
        idle = c.until_state(0)[1]
        self.assertEqual((idle["time"], idle["duration"], idle["speed"]), (0.0, 0.0, 0.0), "an idle update is all zeros, like the desktop receiver")

    def test_an_item_plays_at_once_and_the_desktop_style_end_is_only_the_event(self):
        _, port = self.start(duration=1.0)
        subscribed, plain = self.client(port, handshake=True), self.client(port, handshake=True)
        subscribed.send("subscribe_event", {"event": {"type": 0}})
        subscribed.send("subscribe_event", {"event": {"type": 1}})
        subscribed.send("play", {"container": "audio/mpeg", "url": "http://h/a.mp3"})
        start = subscribed.until("event", 3)[1]["event"]
        self.assertEqual((start["type"], start["item"]["url"]), (0, "http://h/a.mp3"), "MediaItemStart after the play")
        states = []
        while True:
            packet = subscribed.read(4)
            self.assertIsNotNone(packet)
            if packet[0] == "playback_update":
                states.append(packet[1]["state"])
            elif packet[0] == "event":
                end = packet[1]["event"]
                break
        self.assertEqual((end["type"], end["item"]["url"]), (1, "http://h/a.mp3"))
        self.assertEqual(set(states), {1}, "playing updates up to the end, no paused and no idle update (desktop receiver 3.0.4)")
        with self.assertRaises(socket.timeout):
            subscribed.until("playback_update", 0.8)   # nothing after the end
        # a sender that did not subscribe gets the updates and nothing at the end
        with self.assertRaises(socket.timeout):
            plain.until("event", 0.3)

    def test_the_android_style_end_is_a_paused_update_at_the_duration_then_the_event(self):
        _, port = self.start(duration=1.0, end="android")
        c = self.client(port, handshake=True)
        c.send("subscribe_event", {"event": {"type": 1}})
        c.send("play", {"container": "audio/mpeg", "url": "http://h/a.mp3"})
        ended = c.until_state(2, 4)[1]
        self.assertEqual((ended["time"], ended["duration"]), (1.0, 1.0))
        self.assertEqual(c.until("event", 3)[1]["event"]["type"], 1)

    def test_a_receiver_with_a_start_delay_reports_idle_first(self):
        _, port = self.start(start_delay=0.2)
        c = self.client(port, handshake=True)
        c.send("play", {"container": "audio/mpeg", "url": "http://h/a.mp3"})
        self.assertEqual(c.states(2), [0, 1])

    def test_unsubscribing_stops_the_events_and_a_bad_subscription_ends_the_session(self):
        _, port = self.start(duration=1.0)
        c = self.client(port, handshake=True)
        c.send("subscribe_event", {"event": {"type": 1}})
        c.send("unsubscribe_event", {"event": {"type": 1}})
        c.send("play", {"container": "audio/mpeg", "url": "http://h/a.mp3"})
        time.sleep(1.5)  # the item ended meanwhile
        with self.assertRaises(socket.timeout):
            c.until("event", 0.4)
        d = self.client(port, handshake=True)
        d.send("subscribe_event", {"event": {}})
        self.assertTrue(d.closed())

    def test_speed_and_time_of_a_play_are_clamped(self):
        _, port = self.start()
        c = self.client(port, handshake=True)
        c.send("play", {"container": "audio/mpeg", "url": "http://h/a.mp3", "speed": 100, "time": -5})
        playing = c.until_state(1)[1]
        self.assertEqual((playing["speed"], playing["time"]), (16.0, 0.0))

    def test_pause_seek_and_stop_with_nothing_loaded_change_nothing(self):
        _, port = self.start()
        c = self.client(port, handshake=True)
        for name, body in (("pause", None), ("resume", None), ("stop", None), ("seek", {"time": 5})):
            c.send(name, body)
        with self.assertRaises(socket.timeout):
            c.until("playback_update", timeout=0.4)

    def test_a_play_without_url_is_an_error(self):
        _, port = self.start()
        c = self.client(port, handshake=True)
        c.send("play", {"container": "video/mp4"})
        self.assertEqual(c.until("playback_error")[0], "playback_error")

    def test_a_second_sender_sees_the_changes_of_the_first_and_a_late_one_gets_the_state(self):
        _, port = self.start()
        a, b = self.client(port, handshake=True), self.client(port, handshake=True)
        a.send("set_volume", {"volume": 0.25})
        self.assertEqual(b.until("volume_update")[1]["volume"], 0.25)
        a.send("play", {"container": "audio/mpeg", "url": "http://h/a.mp3"})
        a.until_state(1)
        late = self.client(port)
        late.send("version", {"version": 3})
        initial = late.until("initial")[1]
        self.assertEqual(initial["playData"]["url"], "http://h/a.mp3")
        self.assertEqual(late.until("playback_update")[1]["state"], 1)

    def test_a_version_2_sender_gets_no_play_update(self):
        _, port = self.start(speaks=2)
        c = self.client(port)
        c.send("version", {"version": 2})
        c.until("volume_update")
        c.send("play", {"container": "audio/mpeg", "url": "http://h/a.mp3"})
        self.assertEqual(c.until("playback_update")[1]["state"], 1)
        with self.assertRaises(socket.timeout):
            c.until("play_update", timeout=0.4)

    # ---- limits

    def test_the_largest_packet_is_accepted_and_one_byte_more_ends_the_session(self):
        _, port = self.start()
        c = self.client(port, handshake=True)
        prefix = '{"container":"audio/mpeg","url":"http://h/'
        suffix = '"}'
        body = prefix + "a" * (rig.MAX_PACKET - 1 - len(prefix) - len(suffix)) + suffix
        self.assertEqual(len(body) + 1, rig.MAX_PACKET)
        c.raw(struct.pack("<I", rig.MAX_PACKET) + bytes([1]) + body.encode())
        self.assertEqual(c.until_state(1)[1]["state"], 1)  # accepted; the PlayUpdate echo is too big and is skipped
        d = self.client(port, handshake=True)
        d.raw(struct.pack("<I", rig.MAX_PACKET + 1) + bytes([1]))
        self.assertTrue(d.closed())

    def test_a_silent_receiver_does_not_end_a_silent_sender(self):
        _, port = self.start(behaviour="silent", heartbeat="desktop")
        c = self.client(port)
        c.send("version", {"version": 3})
        self.assertFalse(c.closed(timeout=7.0), "a silent receiver pings nobody and so ends nobody")

    def test_silent_behaviour_answers_nothing(self):
        _, port = self.start(behaviour="silent")
        c = self.client(port)
        c.send("version", {"version": 3})
        c.send("ping")
        with self.assertRaises(socket.timeout):
            c.read(timeout=0.4)

    def test_oversize_behaviour_announces_a_packet_over_the_limit(self):
        _, port = self.start(behaviour="oversize")
        c = self.client(port)
        c.read()  # the greeting
        with self.assertRaises(rig.OversizeError):
            c.read()

    def test_slow_behaviour_delays_answers(self):
        _, port = self.start(behaviour="slow", slow_seconds=0.3, greeting=0)
        c = self.client(port)
        started = time.monotonic()
        c.send("version", {"version": 3})
        c.read()
        self.assertGreaterEqual(time.monotonic() - started, 0.25)

    # ---- heartbeat

    def test_desktop_heartbeat_pings_after_3_seconds_and_ends_a_silent_sender_after_6(self):
        _, port = self.start(heartbeat="desktop", greeting=0)
        c = self.client(port)
        c.send("version", {"version": 3})
        started = time.monotonic()
        self.assertEqual(c.until("ping", timeout=5)[0], "ping")
        self.assertGreaterEqual(time.monotonic() - started, 2.5)
        self.assertIsNone(c.until("never", timeout=6))  # no pong: the rig ends the session
        self.assertLess(time.monotonic() - started, 8.5)

    def test_a_sender_that_answers_pings_is_kept(self):
        _, port = self.start(heartbeat="desktop", greeting=0)
        c = self.client(port)
        c.send("version", {"version": 3})
        end = time.monotonic() + 7.5
        pings = 0
        while time.monotonic() < end:
            try:
                packet = c.read(timeout=1.0)
            except socket.timeout:
                continue
            self.assertIsNotNone(packet, "the rig closed a sender that answers pings")
            if packet[0] == "ping":
                pings += 1
                c.send("pong")
        self.assertGreaterEqual(pings, 1)


if __name__ == "__main__":
    unittest.main()


class ProbeTest(unittest.TestCase):
    """The probe sender against the rig (both in this folder)."""

    def test_the_probe_handshakes_plays_and_stops(self):
        import subprocess, sys, os
        server = rig.Server(("127.0.0.1", 0), options(heartbeat="none", start_delay=0.1))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        port = server.server_address[1]
        here = os.path.dirname(os.path.abspath(__file__))
        out = subprocess.run([sys.executable, "-I", os.path.join(here, "fcast_probe.py"), "127.0.0.1", "--port", str(port),
                              "--events", "--play", "http://h/a.mp3", "--container", "audio/mpeg", "--do", "pause@1,resume@1.5,volume:0.3@2,stop@2.5",
                              "--seconds", "4"], capture_output=True, text=True, timeout=30).stdout
        for expected in ("<- version", "<- initial", "-> play", "<- play_update", "<- playback_update", "-> pause", "-> resume",
                         "-> set_volume", "<- volume_update", "-> stop", "-> subscribe_event"):
            self.assertIn(expected, out)
