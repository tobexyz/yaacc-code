#!/usr/bin/env bash
# Records the READ-ONLY answers of a device that speaks the YXC API (Yamaha MusicCast devices), for the test fixtures of issue #307.
# Only GET calls that read information are made; nothing is changed on the device.
# Usage: ./capture.sh <device-ip> <output-dir> [label]      e.g. ./capture.sh 192.168.0.50 /tmp/yxc deviceA
# Then: python3 scrub.py <output-dir>   (replaces MAC addresses, IPs, serial numbers and names) and look at the files before you send them.
set -u
IP="${1:?device ip}"; OUT="${2:?output dir}"; LABEL="${3:-device}"
BASE="http://$IP/YamahaExtendedControl/v1"
mkdir -p "$OUT"
get() { # get <name> <path>
  local name="$1" path="$2"
  if curl -sS --max-time 8 -o "$OUT/${LABEL}_${name}.json" -w "%{http_code} ${name}\n" "$BASE/$path"; then :; else echo "failed ${name}"; fi
}
get deviceInfo      system/getDeviceInfo
get features        system/getFeatures
get funcStatus      system/getFuncStatus
get networkStatus   system/getNetworkStatus
get locationInfo    system/getLocationInfo
get statusMain      main/getStatus
get statusZone2     zone2/getStatus
get statusZone3     zone3/getStatus
get statusZone4     zone4/getStatus
get distInfo        dist/getDistributionInfo
get netusbPlayInfo  netusb/getPlayInfo
get tunerPlayInfo   tuner/getPlayInfo
get soundPrograms   main/getSoundProgramList
get clockInfo       clock/getSettings
# the UPnP device description (the tag that marks a YXC device); the usual place, adjust the port if yours differs
curl -sS --max-time 8 -o "$OUT/${LABEL}_upnpDescription.xml" -w "%{http_code} upnpDescription\n" "http://$IP:49154/MediaRenderer/desc.xml" || echo "no description at the usual place (ignore)"
echo "done: $(ls "$OUT" | wc -l) files in $OUT. Next: python3 scrub.py $OUT"
