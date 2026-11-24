#!/usr/bin/env python3
"""Writes a few short test files (sine tones, with tags in the file names) for the local music folder of the Music Assistant rig.

    python3 tools/sendspin-rig/music-assistant/make-music.py ./music

Standard library only. The files are WAV (16 bit, stereo, 44.1 kHz); Music Assistant reads the title and artist from the names
"Artist - Title.wav" when a file has no tags (it may show them differently; this is a test fixture, not a library).
"""
import math
import os
import struct
import sys
import wave

RATE = 44100
TRACKS = [("Test Tones", "A 220 Hz", 220), ("Test Tones", "A 440 Hz", 440), ("Test Tones", "A 880 Hz", 880)]


def write(path, hz, seconds=120):
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        frames = bytearray()
        for i in range(RATE * seconds):
            sample = int(5000 * math.sin(2 * math.pi * hz * i / RATE))
            frames += struct.pack("<hh", sample, sample)
            if len(frames) > 1 << 20:
                w.writeframes(bytes(frames))
                frames.clear()
        w.writeframes(bytes(frames))


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: make-music.py <folder>")
    folder = sys.argv[1]
    os.makedirs(folder, exist_ok=True)
    for artist, title, hz in TRACKS:
        path = os.path.join(folder, "%s - %s.wav" % (artist, title))
        write(path, hz)
        print("wrote", path)


if __name__ == "__main__":
    main()
