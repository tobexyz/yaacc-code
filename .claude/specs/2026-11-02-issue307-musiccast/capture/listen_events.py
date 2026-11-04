#!/usr/bin/env python3
"""Records the UDP events a YXC device pushes (issue #307). Read-only: it only asks for the status once to register.

Usage: python3 listen_events.py <device-ip> <seconds> <output-file> [port]
While it runs, change things with the device's own app (volume, input, play / pause, link two devices, unlink).
The device keeps sending events for a limited time after the last request that carried the registration headers
(see the notes in docs/tech.md), so the script repeats the status call every 20 seconds.
Run `python3 scrub.py <dir>` over the output afterwards (it handles .json; save as <label>_events.json by renaming if needed).
"""
import socket, sys, time, json, urllib.request

ip, seconds, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
port = int(sys.argv[4]) if len(sys.argv) > 4 else 41100
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("0.0.0.0", port))
sock.settimeout(1.0)
url = "http://%s/YamahaExtendedControl/v1/main/getStatus" % ip
headers = {"X-AppName": "MusicCast/1.0", "X-AppPort": str(port)}
events, last_register, end = [], 0.0, time.time() + seconds
while time.time() < end:
    if time.time() - last_register > 20:
        try:
            urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=5).read()
        except OSError as e:
            print("register call failed:", e)
        last_register = time.time()
    try:
        data, addr = sock.recvfrom(65535)
    except socket.timeout:
        continue
    try:
        payload = json.loads(data.decode("utf-8"))
    except ValueError:
        payload = {"raw": data.decode("utf-8", "replace")}
    events.append({"t": round(time.time() - (end - seconds), 2), "event": payload})
    print("event", len(events), list(payload.keys()) if isinstance(payload, dict) else "")
with open(out, "w", encoding="utf-8") as f:
    json.dump(events, f, indent=2, ensure_ascii=False)
print("saved %d events to %s" % (len(events), out))
