#!/usr/bin/env python3
"""Records what an OpenHome device (or the YAACC renderer) offers, for fixtures and for the OpenHome support of issue #301.

Usage:  python3 oh_capture.py <description-url> <outdir> [event-seconds]

  <description-url>  the device description, e.g. http://192.168.1.20:8000/description.xml (from the SSDP LOCATION header,
                     or the "UPnP devices" list of any control point)
  <outdir>           a new directory; the files are written there
  [event-seconds]    how long the events of all OpenHome services are recorded (default 0 = none); use your device's own
                     app or YAACC to change something meanwhile (play, pause, volume, add a track)

Written: description.xml, <Service>.scpd.xml for every service, reads.json (the answers of the read-only actions), events.json.
Only read-only actions are called (no Insert, Play, SetVolume and so on). The files hold addresses, names and serial numbers of
your devices: run ../../2026-11-02-issue307-musiccast/capture/scrub.py <outdir> before you share them or commit them.
Standard library only, no installation.
"""
import json, os, re, socket, sys, threading, time, urllib.request, xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urljoin

OH = "av-openhome-org"
READS = {  # service name -> read-only actions without arguments
    "Product": ["Manufacturer", "Model", "Product", "Standby", "SourceCount", "SourceXml", "SourceIndex", "Attributes"],
    "Playlist": ["TransportState", "Repeat", "Shuffle", "Id", "TracksMax", "IdArray", "ProtocolInfo"],
    "Info": ["Counters", "Track", "Details", "Metatext"],
    "Time": ["Time"],
    "Volume": ["Characteristics", "Volume", "Mute", "Balance", "Fade", "VolumeLimit", "UnityGain"],
}


def fetch(url, data=None, headers=None, method=None, timeout=10):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def local_name(tag):
    return tag.split("}")[-1]


def services_of(description_xml, base):
    root = ET.fromstring(description_xml)
    out = []
    for el in root.iter():
        if local_name(el.tag) == "service":
            d = {local_name(c.tag): (c.text or "").strip() for c in el}
            out.append({"type": d.get("serviceType", ""), "id": d.get("serviceId", ""),
                        "scpd": urljoin(base, d.get("SCPDURL", "")), "control": urljoin(base, d.get("controlURL", "")),
                        "events": urljoin(base, d.get("eventSubURL", ""))})
    return out


def soap(control, service_type, action):
    body = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:%s xmlns:u="%s"/></s:Body></s:Envelope>'
            % (action, service_type)).encode()
    headers = {"Content-Type": 'text/xml; charset="utf-8"', "SOAPACTION": '"%s#%s"' % (service_type, action)}
    try:
        answer = fetch(control, body, headers, "POST").decode("utf-8", "replace")
    except Exception as e:  # an HTTP error carries the UPnP error code in its body
        answer = "ERROR %s %s" % (type(e).__name__, getattr(e, "read", lambda: b"")().decode("utf-8", "replace")[:400])
    return answer


def record_events(services, seconds, outfile):
    events = []
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_NOTIFY(self):
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length).decode("utf-8", "replace")
            with lock:
                events.append({"t": round(time.time() - start, 3), "sid": self.headers.get("SID"), "seq": self.headers.get("SEQ"),
                               "body": body})
            self.send_response(200)
            self.end_headers()

        def log_message(self, *a):
            pass

    server = HTTPServer(("0.0.0.0", 0), Handler)
    port = server.server_address[1]
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    probe.connect((re.sub(r"^https?://([^/:]+).*$", r"\1", services[0]["events"]), 9))
    my_ip = probe.getsockname()[0]
    probe.close()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    start = time.time()
    sids = []
    for s in services:
        try:
            req = urllib.request.Request(s["events"], method="SUBSCRIBE", headers={
                "CALLBACK": "<http://%s:%d/%s>" % (my_ip, port, s["id"].split(":")[-1]), "NT": "upnp:event", "TIMEOUT": "Second-%d" % (seconds + 30)})
            with urllib.request.urlopen(req, timeout=10) as r:
                sids.append((s["events"], r.headers.get("SID")))
        except Exception as e:
            print("subscribe failed for", s["id"], e)
    print("recording events for %d s: use the device now" % seconds)
    time.sleep(seconds)
    for url, sid in sids:
        try:
            urllib.request.urlopen(urllib.request.Request(url, method="UNSUBSCRIBE", headers={"SID": sid}), timeout=5)
        except Exception:
            pass
    server.shutdown()
    json.dump(events, open(outfile, "w"), indent=1)
    print(len(events), "events written")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    url, out = sys.argv[1], sys.argv[2]
    seconds = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    os.makedirs(out, exist_ok=False)
    description = fetch(url)
    open(os.path.join(out, "description.xml"), "wb").write(description)
    services = services_of(description, url)
    print("services:", *[s["type"] for s in services], sep="\n  ")
    reads = {}
    oh = [s for s in services if OH in s["type"]]
    for s in services:
        name = s["type"].split(":")[-2] if s["type"].count(":") >= 4 else s["id"]
        try:
            open(os.path.join(out, "%s.scpd.xml" % name), "wb").write(fetch(s["scpd"]))
        except Exception as e:
            print("scpd failed", name, e)
        if OH in s["type"] and name in READS:
            reads[name] = {"type": s["type"], "answers": {a: soap(s["control"], s["type"], a) for a in READS[name]}}
    json.dump(reads, open(os.path.join(out, "reads.json"), "w"), indent=1)
    if seconds > 0 and oh:
        record_events(oh, seconds, os.path.join(out, "events.json"))
    print("done:", out, "(scrub before sharing)")


if __name__ == "__main__":
    main()
