# Copyright (C) 2026 Tobias Schoene www.yaacc.de
# GPL-3.0-or-later, like the rest of YAACC.
"""
FCast protocol v4 for the test rig: the FlatBuffers messages the rig reads and writes, the self-signed certificate and the TLS 1.3
context of a receiver, and the connection link (task 7.6 of issue #327).

Written from the published schema (`fcast.fbs`, namespace FCast.V4) with the `flatbuffers` runtime; the slots (4, 6, 8 ...) are
the vtable offsets of the schema, checked against the reference implementation (see tools/fcast-rig/v4check/README.md). No FCast
code is used. A message is the body of a `Flatbuf` packet (opcode 20): a serialized `Packet` table with the message as a union.
"""
import base64
import datetime
import hashlib
import json
import os
import ssl

import flatbuffers
from flatbuffers import number_types as N
from flatbuffers import table

FLATBUF = 20
MAX_PACKET_V4 = 512 * 1024

# members of the Message union
LOAD, PROGRESS, VOLUME, STATE, SPEED, SENDER_INTRO, RECEIVER_INTRO, STOP = 1, 2, 3, 4, 5, 6, 7, 8
TRACKS, CHANGE_TRACK, ADD_SUBTITLE, PROGRESS_INTERVAL, ERROR = 13, 14, 16, 17, 23
# PlaybackState and MediaTrackType and ErrorKind
IDLE, BUFFERING, PLAYING, PAUSED, ENDED = 0, 1, 2, 3, 4
TRACK_VIDEO, TRACK_AUDIO, TRACK_SUBTITLE = 0, 1, 2
ERROR_UNSUPPORTED_FORMAT, ERROR_MALFORMED, ERROR_INTERNAL = 5, 6, 12


def micros(seconds):
    return 0 if seconds is None or seconds != seconds or seconds <= 0 else min(int(round(seconds * 1e6)), 2 ** 63 - 1)


# ------------------------------------------------------------------ writing

def _packet(b, kind, payload):
    b.StartObject(2)
    b.PrependUint8Slot(0, kind, 0)
    b.PrependUOffsetTRelativeSlot(1, payload, 0)
    b.Finish(b.EndObject())
    return bytes(b.Output())


def _time(b, slot, seconds):
    b.Prep(8, 8)
    b.PrependUint64(micros(seconds))
    b.PrependStructSlot(slot, b.Offset(), 0)


def _device_info(b, name, app, version):
    n, a, v = b.CreateString(name), b.CreateString(app), b.CreateString(version)
    b.StartObject(3)
    b.PrependUOffsetTRelativeSlot(0, n, 0)
    b.PrependUOffsetTRelativeSlot(1, a, 0)
    b.PrependUOffsetTRelativeSlot(2, v, 0)
    return b.EndObject()


def _strings(b, items):
    offsets = [b.CreateString(s) for s in items]
    b.StartVector(4, len(offsets), 4)
    for o in reversed(offsets):
        b.PrependUOffsetTRelative(o)
    return b.EndVector()


def receiver_introduction(name, app, version, protocols=("http", "https"), containers=("mp4", "mkv", "webm"),
                          video=("h264", "vp9"), audio=("aac", "opus", "mp3"), subtitles=("srt", "vtt"), images=("png", "jpeg"),
                          external_subtitles=True, volume_step=0.01):
    b = flatbuffers.Builder(512)
    info = _device_info(b, name, app, version)
    lists = [_strings(b, x) for x in (protocols, containers, video, audio, subtitles, (), images)]
    b.StartObject(9)
    for slot, offset in enumerate(lists):
        b.PrependUOffsetTRelativeSlot(slot, offset, 0)
    b.PrependBoolSlot(7, external_subtitles, False)
    b.PrependBoolSlot(8, False, False)
    media = b.EndObject()
    b.StartObject(1)
    b.PrependFloat32Slot(0, volume_step, 0.0)
    audio_caps = b.EndObject()
    b.StartObject(1)
    display = b.EndObject()
    b.StartObject(3)
    b.PrependUOffsetTRelativeSlot(0, media, 0)
    b.PrependUOffsetTRelativeSlot(1, display, 0)
    b.PrependUOffsetTRelativeSlot(2, audio_caps, 0)
    caps = b.EndObject()
    b.StartObject(2)
    b.PrependUOffsetTRelativeSlot(0, info, 0)
    b.PrependUOffsetTRelativeSlot(1, caps, 0)
    return _packet(b, RECEIVER_INTRO, b.EndObject())


def load(container, url, start=None, volume=None, speed=None, title=None, thumbnail=None):
    b = flatbuffers.Builder(512)
    c, u = b.CreateString(container), b.CreateString(url)
    t = b.CreateString(title) if title else 0
    th = b.CreateString(thumbnail) if thumbnail else 0
    b.StartObject(11)
    b.PrependUOffsetTRelativeSlot(0, c, 0)
    b.PrependUOffsetTRelativeSlot(1, u, 0)
    if start is not None:
        _time(b, 2, start)
    if volume is not None:
        b.PrependFloat32(volume)
        b.Slot(3)
    if speed is not None:
        b.PrependFloat32(speed)
        b.Slot(4)
    if t:
        b.PrependUOffsetTRelativeSlot(6, t, 0)
    if th:
        b.PrependUOffsetTRelativeSlot(7, th, 0)
    item = b.EndObject()
    b.StartObject(2)
    b.PrependUint8Slot(0, 1, 0)       # MediaSource.Single
    b.PrependUOffsetTRelativeSlot(1, item, 0)
    return _packet(b, LOAD, b.EndObject())


def progress(position, duration):
    b = flatbuffers.Builder(64)
    b.StartObject(2)
    _time(b, 0, position)
    _time(b, 1, duration)
    return _packet(b, PROGRESS, b.EndObject())


def volume(value):
    b = flatbuffers.Builder(64)
    b.StartObject(1)
    b.PrependFloat32Slot(0, value, 0.0)
    return _packet(b, VOLUME, b.EndObject())


def speed(value):
    b = flatbuffers.Builder(64)
    b.StartObject(1)
    b.PrependFloat32Slot(0, value, 0.0)
    return _packet(b, SPEED, b.EndObject())


def state(value):
    b = flatbuffers.Builder(64)
    b.StartObject(1)
    b.PrependUint8Slot(0, value, 0)
    return _packet(b, STATE, b.EndObject())


def stop():
    b = flatbuffers.Builder(64)
    b.StartObject(0)
    return _packet(b, STOP, b.EndObject())


def error(kind, packet_num=None):
    b = flatbuffers.Builder(64)
    b.StartObject(2)
    b.PrependUint8Slot(0, kind, 0)
    if packet_num is not None:
        b.PrependUint32(packet_num)
        b.Slot(1)
    return _packet(b, ERROR, b.EndObject())


def tracks_available(tracks):
    """tracks: list of (id, type, language, title or None)."""
    b = flatbuffers.Builder(512)
    offsets = []
    for track_id, kind, language, title in tracks:
        t = b.CreateString(title) if title else 0
        lang = b.CreateString(language)
        b.StartObject(0)
        meta = b.EndObject()
        b.StartObject(5)
        b.PrependUint32Slot(0, track_id, 0)
        b.PrependUOffsetTRelativeSlot(1, lang, 0)
        if t:
            b.PrependUOffsetTRelativeSlot(2, t, 0)
        b.PrependUint8Slot(3, {TRACK_VIDEO: 1, TRACK_AUDIO: 2, TRACK_SUBTITLE: 3}[kind], 0)
        b.PrependUOffsetTRelativeSlot(4, meta, 0)
        offsets.append(b.EndObject())
    b.StartVector(4, len(offsets), 4)
    for o in reversed(offsets):
        b.PrependUOffsetTRelative(o)
    vector = b.EndVector()
    b.StartObject(1)
    b.PrependUOffsetTRelativeSlot(0, vector, 0)
    return _packet(b, TRACKS, b.EndObject())


def change_track(track_id, kind):
    b = flatbuffers.Builder(64)
    b.StartObject(2)
    if track_id is not None:
        b.PrependUint32(track_id)
        b.Slot(0)
    b.PrependUint8Slot(1, kind, 0)
    return _packet(b, CHANGE_TRACK, b.EndObject())


# ------------------------------------------------------------------ reading

class Bad(Exception):
    """The buffer is not a valid message."""


class _T:
    """A table with the slots as the vtable offsets of the schema."""

    def __init__(self, buf, pos):
        self.t = table.Table(buf, pos)

    def _o(self, slot):
        return self.t.Offset(slot)

    def u8(self, slot):
        o = self._o(slot)
        return None if o == 0 else self.t.Get(N.Uint8Flags, o + self.t.Pos)

    def u32(self, slot):
        o = self._o(slot)
        return None if o == 0 else self.t.Get(N.Uint32Flags, o + self.t.Pos)

    def f32(self, slot):
        o = self._o(slot)
        return None if o == 0 else self.t.Get(N.Float32Flags, o + self.t.Pos)

    def boolean(self, slot):
        o = self._o(slot)
        return None if o == 0 else bool(self.t.Get(N.BoolFlags, o + self.t.Pos))

    def time(self, slot):
        o = self._o(slot)
        return None if o == 0 else self.t.Get(N.Uint64Flags, o + self.t.Pos) / 1e6

    def string(self, slot):
        o = self._o(slot)
        return None if o == 0 else self.t.String(o + self.t.Pos).decode("utf-8")

    def table(self, slot):
        o = self._o(slot)
        return None if o == 0 else _T(self.t.Bytes, self.t.Indirect(o + self.t.Pos))

    def vector_len(self, slot):
        o = self._o(slot)
        return 0 if o == 0 else self.t.VectorLen(o)

    def table_at(self, slot, index):
        o = self._o(slot)
        return _T(self.t.Bytes, self.t.Indirect(self.t.Vector(o) + index * 4))


def read(data):
    """A message of a sender as a dict with a "kind" and its fields; Bad for a buffer that is not valid."""
    try:
        return _read(bytes(data))
    except Exception as e:   # whatever a hostile buffer does to the reader (an index, a number, a text, a type)
        raise Bad("%s: %s" % (type(e).__name__, e))


def _read(data):
    if len(data) < 8:
        raise Bad("too short")
    root = int.from_bytes(data[0:4], "little")
    if root < 4 or root >= len(data):
        raise Bad("root offset outside the buffer")
    packet = _T(data, root)
    kind = packet.u8(4)
    payload = packet.table(6)
    if not kind or payload is None:
        return {"kind": "other"}
    if kind == LOAD:
        if payload.u8(4) != 1:
            return {"kind": "other"}   # a queue: not handled by the rig
        item = payload.table(6)
        if item is None or item.string(4) is None or item.string(6) is None:
            raise Bad("a Load without container or url")
        return {"kind": "load", "container": item.string(4), "url": item.string(6), "time": item.time(8),
                "volume": item.f32(10), "speed": item.f32(12), "title": item.string(16), "thumbnail": item.string(18)}
    if kind == PROGRESS:
        return {"kind": "progress", "position": payload.time(4), "duration": payload.time(6)}
    if kind == VOLUME:
        return {"kind": "volume", "volume": payload.f32(4) or 0.0}
    if kind == SPEED:
        return {"kind": "speed", "speed": payload.f32(4) or 0.0}
    if kind == STATE:
        return {"kind": "state", "state": payload.u8(4) or 0}
    if kind == STOP:
        return {"kind": "stop"}
    if kind == SENDER_INTRO:
        info = payload.table(4)
        if info is None:
            raise Bad("a SenderIntroduction without device info")
        return {"kind": "sender_introduction", "name": info.string(4), "app": info.string(6), "version": info.string(8)}
    if kind == CHANGE_TRACK:
        return {"kind": "change_track", "id": payload.u32(4), "type": payload.u8(6) or 0}
    if kind == ADD_SUBTITLE:
        url = payload.string(4)
        if url is None:
            raise Bad("an AddSubtitleSource without url")
        return {"kind": "add_subtitle", "url": url, "select": bool(payload.boolean(6)), "name": payload.string(8)}
    if kind == PROGRESS_INTERVAL:
        return {"kind": "progress_interval", "interval": payload.time(4)}
    if kind == TRACKS:
        tracks = []
        for i in range(min(payload.vector_len(4), 256)):
            t = payload.table_at(4, i)
            tracks.append({"id": t.u32(4) or 0, "language": t.string(6), "title": t.string(8), "meta": t.u8(10)})
        return {"kind": "tracks", "tracks": tracks}
    return {"kind": "other"}


# ------------------------------------------------------------------ TLS, the fingerprint and the link

def make_identity(directory):
    """The self-signed key and certificate of the rig in `directory` (made once, kept: the fingerprint stays the same between runs).
    Returns (certificate file, key file, fingerprint). The key is a test key: it protects nothing but this rig."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID
    os.makedirs(directory, exist_ok=True)
    cert_file, key_file = os.path.join(directory, "cert.pem"), os.path.join(directory, "key.pem")
    if not (os.path.isfile(cert_file) and os.path.isfile(key_file)):
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "yaacc-test-rig")])
        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
                .not_valid_after(now + datetime.timedelta(days=3650)).sign(key, hashes.SHA256()))
        with open(os.open(key_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "wb") as f:
            f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        with open(cert_file, "wb") as f:
            f.write(cert.public_bytes(serialization.Encoding.PEM))
    with open(cert_file, "rb") as f:
        cert = x509.load_pem_x509_certificate(f.read())
    spki = cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return cert_file, key_file, base64.b64encode(hashlib.sha256(spki).digest()).decode("ascii")


def tls_context(cert_file, key_file):
    """TLS 1.3 only, the receiver is the server and nobody is asked for a certificate (the protocol authenticates the server only)."""
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.maximum_version = ssl.TLSVersion.TLSv1_3
    context.load_cert_chain(cert_file, key_file)
    return context


def connection_url(name, addresses, port, fingerprint):
    """The text of the QR code a receiver shows: fcast://r/<base64url of the JSON> (docs/tech.md, "FCast")."""
    body = {"name": name, "addresses": list(addresses), "services": [{"port": port, "type": 0}],
            "txt": {"v": "4", "fp": fingerprint}}
    raw = json.dumps(body, separators=(",", ":")).encode("utf-8")
    return "fcast://r/" + base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
