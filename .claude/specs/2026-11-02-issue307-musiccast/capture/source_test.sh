#!/usr/bin/env bash
# Source experiment for issue #307, slice 5: what do the playback, repeat, shuffle, preset and tuner calls answer, and what does
# the play info look like before and after? The call shapes are from the open-source libraries, none was tried on a real device.
# CHANGES WHAT PLAYS on ONE device (pause / play / next / previous, repeat, shuffle, a preset); it puts repeat and shuffle back
# to what they were. It does not link, unlink, rename or store anything.
# Start a net radio station or a streaming source (not a local file) on the device with the manufacturer's app first and let it
# play. For the tuner part select the tuner input (FM or DAB) first.
# Usage: ./source_test.sh <device-ip> net [preset-number]    experiment with the network source (default preset number: 1)
#        ./source_test.sh <device-ip> tuner [band] [preset-number]   tuner part (default band: fm, preset 1)
# Output: every call with its answer (response_code 0 = ok) and the play info before and after. Send the whole output back
# (no names or serial numbers are in it, only the address you typed; station names and track titles can be in it - look first).
set -u
D="${1:?device ip}"; MODE="${2:?net or tuner}"; shift 2
BASE="YamahaExtendedControl/v1"
get()  { curl -s --max-time 8 "http://$D/$BASE/$1"; }
field() { sed -n "s/.*\"$1\" *: *\"\\{0,1\\}\\([^\",} ]*\\).*/\\1/p" | head -1; }
say()   { printf '\n=== %s\n' "$*"; }
call()  { printf '%s\n   -> %s\n' "$1" "$(get "$1")"; sleep 2; }
show()  { printf '   play info (%s): %s\n' "$1" "$(get "$2")"; }

case "$MODE" in
net)
  NUM="${1:-1}"
  say "main status (input) and the play info before"
  printf '   %s\n' "$(get main/getStatus)"
  show before netusb/getPlayInfo
  R0=$(get netusb/getPlayInfo | field repeat); S0=$(get netusb/getPlayInfo | field shuffle)
  say "preset list (empty slots have the input unknown)"
  get netusb/getPresetInfo | head -c 1500; echo
  say "pause, play"
  call "netusb/setPlayback?playback=pause"; show "after pause" netusb/getPlayInfo
  call "netusb/setPlayback?playback=play";  show "after play"  netusb/getPlayInfo
  say "next, previous (listen: does the source change the track?)"
  call "netusb/setPlayback?playback=next";     show "after next" netusb/getPlayInfo
  call "netusb/setPlayback?playback=previous"; show "after previous" netusb/getPlayInfo
  say "repeat (was: ${R0:-?}), shuffle (was: ${S0:-?}) - put back at the end"
  call "netusb/setRepeat?mode=one";  show "repeat one" netusb/getPlayInfo
  call "netusb/setRepeat?mode=all";  show "repeat all" netusb/getPlayInfo
  [ -n "$R0" ] && call "netusb/setRepeat?mode=$R0"
  call "netusb/setShuffle?mode=on";  show "shuffle on" netusb/getPlayInfo
  [ -n "$S0" ] && call "netusb/setShuffle?mode=$S0"
  say "recall preset $NUM (this changes what plays)"
  call "netusb/recallPreset?zone=main&num=$NUM"; show "after recall" netusb/getPlayInfo
  say "the same with a number that does not exist (does the device refuse it?)"
  call "netusb/recallPreset?zone=main&num=999"
  say "stop (the device stops its source)"
  call "netusb/setPlayback?playback=stop"; show "after stop" netusb/getPlayInfo
  ;;
tuner)
  BAND="${1:-fm}"; NUM="${2:-1}"
  say "main status (input) and the tuner play info before"
  printf '   %s\n' "$(get main/getStatus)"
  show before tuner/getPlayInfo
  say "preset list of the band $BAND"
  get "tuner/getPresetInfo?band=$BAND" | head -c 1500; echo
  say "seek up and down (FM: auto_up / auto_down; the frequency should change)"
  call "tuner/setFreq?band=$BAND&tuning=auto_up&num=0";   show "after up" tuner/getPlayInfo
  call "tuner/setFreq?band=$BAND&tuning=auto_down&num=0"; show "after down" tuner/getPlayInfo
  say "DAB service next / previous (only if the input is DAB; a refusal is fine)"
  call "tuner/setDabService?dir=next";     show "after next" tuner/getPlayInfo
  call "tuner/setDabService?dir=previous"; show "after previous" tuner/getPlayInfo
  say "recall tuner preset $NUM of $BAND"
  call "tuner/recallPreset?zone=main&band=$BAND&num=$NUM"; show "after recall" tuner/getPlayInfo
  ;;
*) echo "mode must be net or tuner"; exit 2;;
esac
say "done"
