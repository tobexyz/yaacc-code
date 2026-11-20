# Copyright (C) 2026 Tobias Schoene www.yaacc.de
# GPL-3.0-or-later, like the rest of YAACC.
"""Self-test of the rig (no network beyond the loopback, no fixed port): python3 -I -m unittest discover -s tools/fcast-rig"""
import json
import socket
import struct
import threading
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

    def read(self, timeout=3):
        self.sock.settimeout(timeout)
        packet = rig.read_packet(self.sock)
        if packet is None:
            return None
        opcode, raw = packet
        return rig.OP_NAME[opcode], (json.loads(raw) if raw else None)

    def until(self, name, timeout=3):
        while True:
            packet = self.read(timeout)
            if packet is None or packet[0] == name:
                return packet

    def close(self):
        self.sock.close()


class RigTest(unittest.TestCase):
    def start(self, **kw):
        kw.setdefault("heartbeat", "none")
        opts = options(**kw)
        server = rig.Server(("127.0.0.1", 0), opts)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server, server.server_address[1]

    def client(self, port):
        c = Client(port)
        self.addCleanup(c.close)
        return c

    def test_port_zero_picks_a_free_port(self):
        _, port = self.start()
        self.assertNotEqual(port, 0)
        self.assertNotEqual(port, 46899)

    def test_framing_is_little_endian_and_counts_the_opcode(self):
        self.assertEqual(rig.encode_packet(rig.OP["pause"]), bytes([1, 0, 0, 0, 2]))
        body = json.dumps({"time": 5.5}, separators=(",", ":")).encode()
        self.assertEqual(rig.encode_packet(rig.OP["seek"], {"time": 5.5}), struct.pack("<I", 1 + len(body)) + bytes([5]) + body)

    def test_handshake_greeting_initial_and_volume(self):
        _, port = self.start()
        c = self.client(port)
        self.assertEqual(c.read(), ("version", {"version": 3}))
        c.send("version", {"version": 3})
        name, body = c.read()
        self.assertEqual(name, "initial")
        self.assertEqual(body["displayName"], "FCast-rig")
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

    def test_a_sender_asking_for_version_4_is_closed(self):
        _, port = self.start()
        c = self.client(port)
        c.read()
        c.send("version", {"version": 4})
        self.assertIsNone(c.until("initial"))

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

    def test_play_pause_resume_seek_volume_speed_stop(self):
        _, port = self.start()
        c = self.client(port)
        c.send("version", {"version": 3})
        c.until("volume_update")
        c.send("play", {"container": "video/mp4", "url": "http://192.168.1.2/a.mp4", "time": 10, "volume": 0.5})
        self.assertEqual(c.until("play_update")[1]["playData"]["url"], "http://192.168.1.2/a.mp4")
        update = c.until("playback_update")[1]
        self.assertEqual((update["state"], update["time"], update["duration"]), (1, 10.0, 180.0))
        self.assertEqual(c.until("volume_update")[1]["volume"], 0.5)
        c.send("pause")
        self.assertEqual(c.until("playback_update")[1]["state"], 2)
        c.send("resume")
        self.assertEqual(c.until("playback_update")[1]["state"], 1)
        c.send("seek", {"time": 99})
        self.assertEqual(c.until("playback_update")[1]["time"], 99.0)
        c.send("set_volume", {"volume": 2})  # clamped
        self.assertEqual(c.until("volume_update")[1]["volume"], 1.0)
        c.send("set_speed", {"speed": 1.5})
        self.assertEqual(c.until("playback_update")[1]["speed"], 1.5)
        c.send("stop")
        self.assertEqual(c.until("playback_update")[1]["state"], 0)

    def test_a_play_without_url_is_an_error(self):
        _, port = self.start()
        c = self.client(port)
        c.send("version", {"version": 3})
        c.send("play", {"container": "video/mp4"})
        self.assertEqual(c.until("playback_error")[0], "playback_error")

    def test_a_second_sender_sees_the_changes_of_the_first(self):
        _, port = self.start()
        a, b = self.client(port), self.client(port)
        for c in (a, b):
            c.send("version", {"version": 3})
            c.until("volume_update")
        a.send("set_volume", {"volume": 0.25})
        self.assertEqual(b.until("volume_update")[1]["volume"], 0.25)

    def test_an_oversize_packet_from_the_sender_closes_the_connection(self):
        _, port = self.start()
        c = self.client(port)
        c.send("version", {"version": 3})
        c.sock.sendall(struct.pack("<I", rig.MAX_PACKET + 1) + bytes([1]))
        self.assertIsNone(c.until("never"))

    def test_a_packet_of_size_zero_closes_the_connection(self):
        _, port = self.start()
        c = self.client(port)
        c.sock.sendall(b"\0\0\0\0")
        self.assertIsNone(c.until("never"))

    def test_the_limit_itself_is_accepted(self):
        _, port = self.start()
        c = self.client(port)
        c.send("version", {"version": 3})
        c.until("volume_update")
        url = "http://h/" + "a" * (rig.MAX_PACKET - 200)
        c.send("play", {"container": "audio/mpeg", "url": url})
        self.assertEqual(c.until("playback_update")[1]["state"], 1)

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

    def test_desktop_heartbeat_pings_after_3_seconds_and_ends_a_silent_sender_after_6(self):
        import time
        _, port = self.start(heartbeat="desktop", greeting=0)
        c = self.client(port)
        c.send("version", {"version": 3})
        started = time.monotonic()
        self.assertEqual(c.until("ping", timeout=5)[0], "ping")
        self.assertGreaterEqual(time.monotonic() - started, 2.5)
        self.assertIsNone(c.until("never", timeout=6))  # no pong: the rig ends the session
        self.assertLess(time.monotonic() - started, 8.5)

    def test_a_sender_that_answers_pings_is_kept(self):
        import time
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

    def test_slow_behaviour_delays_answers(self):
        import time
        _, port = self.start(behaviour="slow", slow_seconds=0.3, greeting=0)
        c = self.client(port)
        started = time.monotonic()
        c.send("version", {"version": 3})
        c.read()
        self.assertGreaterEqual(time.monotonic() - started, 0.25)


if __name__ == "__main__":
    unittest.main()
