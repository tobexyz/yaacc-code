# Copyright (C) 2026 Tobias Schoene www.yaacc.de
# GPL-3.0-or-later, like the rest of YAACC.
"""Self-test of the rig's FCast v4 support (loopback only, a free port, a certificate in a temporary folder)."""
import base64
import hashlib
import json
import os
import random
import socket
import ssl
import struct
import tempfile
import threading
import time
import unittest

import fcast_rig as rig
import fcast_v4 as v4

HERE = os.path.dirname(os.path.abspath(__file__))


def golden():
    result = {}
    with open(os.path.join(HERE, "v4_golden.txt")) as f:
        for line in f:
            name, hexs = line.strip().split("=")
            result[name] = bytes.fromhex(hexs)
    return result


class CodecTest(unittest.TestCase):
    """The buffers of the reference implementation (the same bytes as in YAACC's tests) and what the rig writes."""

    def test_the_reference_buffers_are_read(self):
        g = golden()
        load = v4.read(g["loadFull"])
        self.assertEqual(("video/mp4", "http://192.168.0.5:8080/a.mp4", "Song", "http://h/a.jpg"),
                         (load["container"], load["url"], load["title"], load["thumbnail"]))
        self.assertAlmostEqual(12.5, load["time"])
        self.assertAlmostEqual(0.25, load["volume"], places=6)
        self.assertAlmostEqual(1.5, load["speed"], places=6)
        self.assertEqual({"kind": "load", "container": "audio/mpeg", "url": "http://h/a.mp3", "time": None, "volume": None, "speed": None,
                          "title": None, "thumbnail": None}, v4.read(g["loadMinimal"]))
        self.assertEqual((1.5, 61.0), (v4.read(g["progress"])["position"], v4.read(g["progress"])["duration"]))
        self.assertEqual(0.0, v4.read(g["volumeZero"])["volume"])
        self.assertEqual([v4.IDLE, v4.BUFFERING, v4.PLAYING, v4.PAUSED, v4.ENDED],
                         [v4.read(g[n])["state"] for n in ("stateIdle", "stateBuffering", "statePlaying", "statePaused", "stateEnded")])
        self.assertEqual("stop", v4.read(g["stop"])["kind"])
        self.assertEqual({"kind": "change_track", "id": 3, "type": v4.TRACK_AUDIO}, v4.read(g["changeTrack"]))
        self.assertEqual({"kind": "change_track", "id": None, "type": v4.TRACK_SUBTITLE}, v4.read(g["changeTrackOff"]))
        self.assertEqual(("http://h/a.srt", True, "English (external)"),
                         (v4.read(g["addSubtitle"])["url"], v4.read(g["addSubtitle"])["select"], v4.read(g["addSubtitle"])["name"]))
        self.assertEqual("sender_introduction", v4.read(g["senderIntroduction"])["kind"])
        tracks = v4.read(g["tracks"])["tracks"]
        self.assertEqual([1, 2, 0, 9], [t["id"] for t in tracks])
        self.assertEqual("eng", tracks[0]["language"])

    def test_what_the_rig_writes_reads_back(self):
        self.assertEqual({"kind": "load", "container": "video/mp4", "url": "http://h/a b.mp4", "time": 12.5, "volume": 0.5, "speed": 1.5,
                          "title": "Titel äöü", "thumbnail": "http://h/a.jpg"},
                         v4.read(v4.load("video/mp4", "http://h/a b.mp4", 12.5, 0.5, 1.5, "Titel äöü", "http://h/a.jpg")))
        zeros = v4.read(v4.load("audio/mpeg", "http://h/a.mp3", 0.0, 0.0, 0.0))
        self.assertEqual((0.0, 0.0, 0.0), (zeros["time"], zeros["volume"], zeros["speed"]))
        self.assertEqual((90.25, 61.0), (v4.read(v4.progress(90.25, 61))["position"], v4.read(v4.progress(90.25, 61))["duration"]))
        self.assertEqual(0.75, v4.read(v4.volume(0.75))["volume"])
        self.assertEqual(2.0, v4.read(v4.speed(2.0))["speed"])
        self.assertEqual(v4.PAUSED, v4.read(v4.state(v4.PAUSED))["state"])
        self.assertEqual("stop", v4.read(v4.stop())["kind"])
        self.assertEqual([(1, "eng", "English", 2), (3, "deu", None, 3)], [(t["id"], t["language"], t["title"], t["meta"]) for t in
                         v4.read(v4.tracks_available([(1, v4.TRACK_AUDIO, "eng", "English"), (3, v4.TRACK_SUBTITLE, "deu", None)]))["tracks"]])
        self.assertEqual({"kind": "change_track", "id": 7, "type": v4.TRACK_AUDIO}, v4.read(v4.change_track(7, v4.TRACK_AUDIO)))
        self.assertEqual({"kind": "change_track", "id": None, "type": v4.TRACK_SUBTITLE}, v4.read(v4.change_track(None, v4.TRACK_SUBTITLE)))

    def test_nothing_but_bad_comes_out_of_hostile_buffers(self):
        rnd = random.Random(7)
        g = list(golden().values())
        for _ in range(3000):
            data = bytearray(rnd.choice(g)) if rnd.random() < 0.5 else bytearray(rnd.getrandbits(8) for _ in range(rnd.randint(0, 200)))
            for _ in range(rnd.randint(0, 4)):
                if data:
                    data[rnd.randrange(len(data))] = rnd.getrandbits(8)
            if rnd.random() < 0.3 and data:
                del data[rnd.randrange(len(data)):]
            try:
                v4.read(bytes(data))
            except v4.Bad:
                pass


class IdentityTest(unittest.TestCase):
    def test_the_identity_is_made_once_and_the_fingerprint_is_the_spki_hash(self):
        from cryptography import x509
        from cryptography.hazmat.primitives import serialization
        with tempfile.TemporaryDirectory() as d:
            cert, key, fp = v4.make_identity(d)
            self.assertEqual(fp, v4.make_identity(d)[2], "the same key on the next start")
            self.assertEqual(32, len(base64.b64decode(fp)))
            certificate = x509.load_pem_x509_certificate(open(cert, "rb").read())
            spki = certificate.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
            self.assertEqual(base64.b64encode(hashlib.sha256(spki).digest()).decode(), fp)
            self.assertEqual(0o600, os.stat(key).st_mode & 0o777)
            os.remove(cert)
            os.remove(key)
            self.assertNotEqual(fp, v4.make_identity(d)[2], "a deleted identity is made anew")

    def test_the_link_carries_name_addresses_port_and_key(self):
        link = v4.connection_url("YAACC test rig", ["192.168.0.5"], 46899, "KEY=")
        self.assertTrue(link.startswith("fcast://r/"))
        raw = link[len("fcast://r/"):]
        body = json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))
        self.assertEqual({"name": "YAACC test rig", "addresses": ["192.168.0.5"], "services": [{"port": 46899, "type": 0}],
                          "txt": {"v": "4", "fp": "KEY="}}, body)


class V4Client:
    """A sender: TCP, the plaintext Version, then TLS 1.3 with the key pinned by the SPKI hash (as YAACC does)."""

    def __init__(self, port, pin, tls_version=ssl.TLSVersion.TLSv1_3):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=4)
        self.plain = self.sock
        self.seen = []
        first = rig.read_packet(self.sock)
        self.greeting = json.loads(first[1])
        self.sock.sendall(rig.encode_packet(rig.OP["version"], {"version": 4}))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE   # the pin below is the check
        context.minimum_version = context.maximum_version = tls_version
        self.sock = context.wrap_socket(self.sock, server_hostname=None)
        from cryptography import x509
        from cryptography.hazmat.primitives import serialization
        certificate = x509.load_der_x509_certificate(self.sock.getpeercert(binary_form=True))
        spki = certificate.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.fp = base64.b64encode(hashlib.sha256(spki).digest()).decode()
        self.pinned = self.fp == pin

    def send(self, message):
        self.sock.sendall(struct.pack("<I", 1 + len(message)) + bytes([v4.FLATBUF]) + message)

    def read(self, timeout=4):
        self.sock.settimeout(timeout)
        packet = rig.read_packet(self.sock, rig.MAX_PACKET_V4)
        if packet is None:
            return None
        opcode, raw = packet
        if opcode == v4.FLATBUF:
            return v4.read(raw)
        return {"kind": rig.OP_NAME.get(opcode, opcode)}

    def until(self, kind, test=None, timeout=4):
        end = time.monotonic() + timeout
        while True:
            left = end - time.monotonic()
            if left <= 0:
                raise socket.timeout("no %s within %s s (saw %s)" % (kind, timeout, [m["kind"] for m in self.seen]))
            message = self.read(left)
            if message is None:
                return None
            self.seen.append(message)
            if message["kind"] == kind and (test is None or test(message)):
                return message

    def close(self):
        self.sock.close()


class RigV4Test(unittest.TestCase):
    def start(self, **kw):
        options = rig.parse([])
        options.heartbeat = "none"
        options.speaks = 4
        options.cert_dir = tempfile.mkdtemp(prefix="rig-cert")
        for key, value in kw.items():
            setattr(options, key, value)
        self.addCleanup(lambda: __import__("shutil").rmtree(options.cert_dir, ignore_errors=True))
        server = rig.Server(("127.0.0.1", 0), options)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server, server.server_address[1]

    def sender(self, server, port, **kw):
        c = V4Client(port, server.fingerprint, **kw)
        self.addCleanup(c.close)
        return c

    def test_the_greeting_is_4_and_the_tls_session_is_1_3_with_the_announced_key(self):
        server, port = self.start()
        c = self.sender(server, port)
        self.assertEqual({"version": 4}, c.greeting)
        self.assertTrue(c.pinned, "the key of the certificate is the one the rig announces")
        self.assertEqual("TLSv1.3", c.sock.version())

    def test_a_receiver_says_hello_with_its_introduction_and_volume(self):
        server, port = self.start()
        c = self.sender(server, port)
        c.send(_sender_intro())
        c.until("other")     # its introduction: a message of a receiver that the rig's reader does not model
        c.until("volume", lambda m: m["volume"] == 1.0)

    def test_load_is_echoed_and_plays_with_tracks_and_the_end_is_the_ended_state(self):
        server, port = self.start(duration=1.0)
        c = self.sender(server, port)
        c.send(_sender_intro())
        c.send(v4.load("audio/mpeg", "http://h/a.mp3", None, None, None, "Song"))
        load = c.until("load")
        self.assertEqual(("audio/mpeg", "http://h/a.mp3", "Song"), (load["container"], load["url"], load["title"]))
        tracks = c.until("tracks")["tracks"]
        self.assertEqual([1, 2, 3, 4], [t["id"] for t in tracks])
        c.until("change_track", lambda m: m["id"] == 1 and m["type"] == v4.TRACK_AUDIO)
        c.until("state", lambda m: m["state"] == v4.PLAYING)
        c.until("progress")
        c.until("state", lambda m: m["state"] == v4.ENDED, timeout=5)

    def test_pause_resume_seek_volume_speed_stop(self):
        server, port = self.start()
        c = self.sender(server, port)
        c.send(_sender_intro())
        c.send(v4.load("video/mp4", "http://h/a.mp4"))
        c.until("state", lambda m: m["state"] == v4.PLAYING)
        c.send(v4.state(v4.PAUSED))
        c.until("state", lambda m: m["state"] == v4.PAUSED)
        c.send(v4.state(v4.PLAYING))
        c.until("state", lambda m: m["state"] == v4.PLAYING)
        c.send(v4.progress(42.0, 0))
        c.until("progress", lambda m: abs(m["position"] - 42.0) < 1.0)
        c.send(v4.volume(0.25))
        c.until("volume", lambda m: abs(m["volume"] - 0.25) < 1e-6)
        c.send(v4.speed(1.5))
        c.until("speed", lambda m: abs(m["speed"] - 1.5) < 1e-6)
        c.send(v4.stop())
        c.until("state", lambda m: m["state"] == v4.IDLE)

    def test_tracks_can_be_changed_and_a_subtitle_file_adds_a_track(self):
        server, port = self.start()
        c = self.sender(server, port)
        c.send(_sender_intro())
        c.send(v4.load("video/mp4", "http://h/a.mp4"))
        c.until("tracks")
        c.send(v4.change_track(2, v4.TRACK_AUDIO))
        c.until("change_track", lambda m: m["id"] == 2 and m["type"] == v4.TRACK_AUDIO)
        c.send(v4.change_track(99, v4.TRACK_AUDIO))   # a track that is not there: nothing changes
        c.send(v4.change_track(4, v4.TRACK_SUBTITLE))
        c.until("change_track", lambda m: m["id"] == 4 and m["type"] == v4.TRACK_SUBTITLE)
        c.send(_add_subtitle("http://h/a.srt", True, "English (file)"))
        added = c.until("tracks", lambda m: len(m["tracks"]) == 5)
        self.assertEqual("English (file)", added["tracks"][-1]["title"])
        c.until("change_track", lambda m: m["id"] == 5 and m["type"] == v4.TRACK_SUBTITLE)
        c.send(v4.change_track(None, v4.TRACK_SUBTITLE))
        c.until("change_track", lambda m: m["id"] is None and m["type"] == v4.TRACK_SUBTITLE)

    def test_a_second_sender_sees_the_first_ones_item_when_it_connects(self):
        server, port = self.start()
        first = self.sender(server, port)
        first.send(_sender_intro())
        first.send(v4.load("video/mp4", "http://h/a.mp4", None, None, None, "Film"))
        first.until("state", lambda m: m["state"] == v4.PLAYING)
        second = self.sender(server, port)
        second.send(_sender_intro())
        self.assertEqual("Film", second.until("load")["title"])
        second.until("state", lambda m: m["state"] == v4.PLAYING)
        second.until("tracks")

    def test_ping_is_answered_inside_tls(self):
        server, port = self.start()
        c = self.sender(server, port)
        c.send(_sender_intro())
        c.sock.sendall(struct.pack("<I", 1) + bytes([rig.OP["ping"]]))
        c.until("pong")

    def test_a_buffer_that_is_not_a_message_gets_an_error_and_the_session_goes_on(self):
        server, port = self.start()
        c = self.sender(server, port)
        c.send(_sender_intro())
        c.send(b"\x01\x02\x03")
        c.until("other")      # the Error message
        c.send(v4.load("audio/mpeg", "http://h/a.mp3"))
        c.until("load")

    def test_a_second_version_ends_the_session(self):
        server, port = self.start()
        c = self.sender(server, port)
        c.sock.sendall(rig.encode_packet(rig.OP["version"], {"version": 4}))
        end = time.monotonic() + 3
        closed = False
        while time.monotonic() < end and not closed:
            try:
                closed = c.read(0.5) is None
            except socket.timeout:
                continue
            except (ssl.SSLError, ConnectionError, OSError):
                closed = True
        self.assertTrue(closed)

    def test_a_tls_12_client_is_refused(self):
        server, port = self.start()
        with self.assertRaises(ssl.SSLError):
            V4Client(port, server.fingerprint, tls_version=ssl.TLSVersion.TLSv1_2)

    def test_a_v3_sender_is_still_served_in_v3(self):
        server, port = self.start()
        sock = socket.create_connection(("127.0.0.1", port), timeout=3)
        self.addCleanup(sock.close)
        self.assertEqual({"version": 4}, json.loads(rig.read_packet(sock)[1]))
        sock.sendall(rig.encode_packet(rig.OP["version"], {"version": 3}))
        names = [rig.OP_NAME[rig.read_packet(sock)[0]] for _ in range(2)]
        self.assertEqual(["initial", "volume_update"], names)

    def test_a_rig_that_speaks_3_does_not_do_tls(self):
        options = rig.parse([])
        options.heartbeat = "none"
        server = rig.Server(("127.0.0.1", 0), options)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        sock = socket.create_connection(("127.0.0.1", server.server_address[1]), timeout=3)
        self.addCleanup(sock.close)
        rig.read_packet(sock)
        sock.sendall(rig.encode_packet(rig.OP["version"], {"version": 4}))
        sock.settimeout(3)
        try:
            self.assertIsNone(rig.read_packet(sock))      # the session ends: the rig has no TLS
        except (ConnectionError, OSError):
            pass

    def test_the_announcement_of_a_v4_rig_has_the_version_and_the_key(self):
        options = rig.parse(["--speaks", "4", "--address", "192.168.0.5", "--cert-dir", tempfile.mkdtemp(prefix="rig-cert")])
        try:
            import zeroconf   # noqa: F401
        except ImportError:
            self.skipTest("zeroconf is not installed")
        self.assertEqual(4, rig.effective_greeting(options))


def _sender_intro():
    import flatbuffers
    b = flatbuffers.Builder(128)
    info = v4._device_info(b, "tester", "tester app", "1")
    b.StartObject(1)
    b.PrependUOffsetTRelativeSlot(0, info, 0)
    return v4._packet(b, v4.SENDER_INTRO, b.EndObject())


def _add_subtitle(url, select, name):
    import flatbuffers
    b = flatbuffers.Builder(128)
    u, n = b.CreateString(url), b.CreateString(name)
    b.StartObject(4)
    b.PrependUOffsetTRelativeSlot(0, u, 0)
    b.PrependBoolSlot(1, select, False)
    b.PrependUOffsetTRelativeSlot(2, n, 0)
    return v4._packet(b, v4.ADD_SUBTITLE, b.EndObject())


if __name__ == "__main__":
    unittest.main()
