#!/usr/bin/env python3
"""Replaces personal data in captured YXC answers so that they can be committed as test fixtures (issue #307).

Usage: python3 scrub.py <dir>      (changes the .json and .xml files in place)
Replaced: MAC addresses, IPv4 addresses (stable mapping to 192.0.2.x), values of keys that identify a device or a network
(serial / id / names / SSID / keys / account data), UUIDs in the UPnP description. Read the result before you share it.
"""
import json, re, sys, pathlib

SECRET_KEYS = {
    "device_id", "system_id", "serial_number", "serial", "network_name", "name", "ssid", "key", "password", "pin",
    "wired_mac_address", "wireless_lan_mac_address", "wireless_direct_mac_address", "bluetooth_mac_address", "mac_address",
    "ip_address", "ipv4_address", "location_id", "account_name", "user_name", "group_name", "text", "friendly_name",
}
KEEP_KEYS = set()  # add keys here that are named like a secret key but carry no personal data
MAC = re.compile(r"\b[0-9A-Fa-f]{2}(?:[:\-][0-9A-Fa-f]{2}){5}\b")
IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
HEX32 = re.compile(r"\b[0-9A-Fa-f]{32}\b")  # the group id of a link group

ips, macs, uuids, hexes = {}, {}, {}, {}


def mapped(table, key, fmt):
    if key not in table:
        table[key] = fmt(len(table) + 1)
    return table[key]


def scrub_text(s):
    s = MAC.sub(lambda m: mapped(macs, m.group(0).lower(), lambda n: "00:00:5e:00:53:%02x" % n), s)
    s = UUID.sub(lambda m: mapped(uuids, m.group(0).lower(), lambda n: "00000000-0000-0000-0000-%012d" % n), s)
    s = HEX32.sub(lambda m: mapped(hexes, m.group(0).lower(), lambda n: "%032x" % n), s)
    s = IPV4.sub(lambda m: mapped(ips, m.group(0), lambda n: "192.0.2.%d" % n), s)
    return s


def scrub_json(v, key=None):
    if isinstance(v, dict):
        return {k: scrub_json(x, k) for k, x in v.items()}
    if isinstance(v, list):
        return [scrub_json(x, key) for x in v]
    if isinstance(v, str):
        if key in SECRET_KEYS and key not in KEEP_KEYS and v:
            return "scrubbed-" + key
        return scrub_text(v)
    return v


def main():
    d = pathlib.Path(sys.argv[1])
    for p in sorted(d.glob("*")):
        if p.suffix == ".json":
            try:
                data = json.loads(p.read_text(encoding="utf-8"))
            except ValueError:
                print("not json, scrubbed as text:", p.name)
                p.write_text(scrub_text(p.read_text(encoding="utf-8")), encoding="utf-8")
                continue
            p.write_text(json.dumps(scrub_json(data), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        elif p.suffix == ".xml":
            text = scrub_text(p.read_text(encoding="utf-8"))
            text = re.sub(r"(<(?:friendlyName|serialNumber|UDN|yamaha:X_URLBase|presentationURL)>)[^<]*(</)", r"\1scrubbed\2", text)
            p.write_text(text, encoding="utf-8")
        else:
            continue
        print("scrubbed", p.name)
    print("replaced: %d MAC, %d IP, %d UUID, %d group ids" % (len(macs), len(ips), len(uuids), len(hexes)))


if __name__ == "__main__":
    main()
