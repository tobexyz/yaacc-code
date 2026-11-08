#!/usr/bin/env bash
# Volume experiment for issue #307: does a volume change on the server of a linked group reach the clients by itself (through the device),
# and the other way round? Changes the volume by a small step and puts it back. Run it with the devices linked.
# Usage: ./volume_test.sh <server-ip> <client-ip> [step]       e.g. ./volume_test.sh 192.168.0.31 192.168.0.230 3
set -u
S="${1:?server ip}"; C="${2:?client ip}"; STEP="${3:-3}"
call() { curl -s --max-time 8 "http://$1/YamahaExtendedControl/v1/$2"; }
vol() { call "$1" main/getStatus | sed -n 's/.*"volume":\([0-9]*\).*/\1/p'; }
show() { printf '%-34s server=%s client=%s\n' "$1" "$(vol "$S")" "$(vol "$C")"; }

S0=$(vol "$S"); C0=$(vol "$C")
[ -n "$S0" ] && [ -n "$C0" ] || { echo "could not read the volumes (server='$S0' client='$C0'); check the addresses"; exit 1; }
echo "start: server=$S0 client=$C0   step=$STEP"

echo "--- 1. change ONLY the server through the API to $((S0 + STEP))"
echo "answer: $(call "$S" "main/setVolume?volume=$((S0 + STEP))")"; sleep 2
show "after server +$STEP"
echo "--- put the server back to $S0"
echo "answer: $(call "$S" "main/setVolume?volume=$S0")"; sleep 2
show "after server back"
echo "--- 2. change ONLY the client through the API to $((C0 + STEP))"
echo "answer: $(call "$C" "main/setVolume?volume=$((C0 + STEP))")"; sleep 2
show "after client +$STEP"
echo "--- put the client back to $C0"
echo "answer: $(call "$C" "main/setVolume?volume=$C0")"; sleep 2
show "after client back"
echo "done (start was server=$S0 client=$C0)"
