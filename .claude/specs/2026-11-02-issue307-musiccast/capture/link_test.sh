#!/usr/bin/env bash
# Link / unlink experiment for issue #307: which `num` does startDistribution want, and what do the devices answer?
# CHANGES STATE: it links the client to the server and unlinks them again (the same calls the app will make). Both devices must
# be in no group at the start (role "none"), and the script leaves them in no group (it tries to, even after an error).
# Start some music on the SERVER before you run it and listen while the script waits: does the client play in sync?
# Usage: ./link_test.sh <server-ip> <client-ip> [num ...]       default: num variants "0 1" (0 = clients before the call, 1 = after)
#        ERRORS=1 ./link_test.sh ...   also probes the answers to calls a device should refuse (only while linked, then unlinks)
# Output: every call with its answer (response_code 0 = ok), the group info of both devices, the seconds until the client joined.
# Send the whole output back (no names or serial numbers are in it, only the addresses you typed).
set -u
S="${1:?server ip}"; C="${2:?client ip}"; shift 2
VARIANTS=("$@"); [ ${#VARIANTS[@]} -gt 0 ] || VARIANTS=(0 1)
BASE="YamahaExtendedControl/v1"
get()  { curl -s --max-time 8 "http://$1/$BASE/$2"; }
post() { curl -s --max-time 8 -H 'Content-Type: text/plain; charset=utf-8' -d "$3" "http://$1/$BASE/$2"; }
field() { sed -n "s/.*\"$1\" *: *\"\\{0,1\\}\\([^\",} ]*\\).*/\\1/p" | head -1; }
role()  { get "$1" dist/getDistributionInfo | field role; }
gid()   { get "$1" dist/getDistributionInfo | field group_id; }
inp()   { get "$1" main/getStatus | field input; }
say()   { printf '\n=== %s\n' "$*"; }
call()  { printf '%s\n   -> %s\n' "$1" "$2"; }

GROUP=$(head -c16 /dev/urandom | od -An -tx1 | tr -d ' \n' | tr a-f A-F)
ZERO="00000000000000000000000000000000"

unlink_all() {
  post "$C" dist/setClientInfo '{"group_id":""}' >/dev/null 2>&1
  get "$S" dist/stopDistribution >/dev/null 2>&1
  post "$S" dist/setServerInfo '{"group_id":""}' >/dev/null 2>&1
  sleep 2
}
trap 'echo; echo "(cleaning up: unlinking)"; unlink_all' INT TERM

say "start: server role='$(role "$S")' group='$(gid "$S")'; client role='$(role "$C")' group='$(gid "$C")' input='$(inp "$C")'"
if [ "$(role "$S")" != "none" ] || [ "$(role "$C")" != "none" ]; then
  if [ "${ALLOW_DISSOLVE:-0}" != "1" ]; then
    echo "a device is in a group already. Use two devices that are in no group, or run again with ALLOW_DISSOLVE=1, which dissolves the existing"
    echo "group(s) of these two devices through the API first (the devices stay set up in the manufacturer's app; you can link them again there)."
    exit 1
  fi
  say "ALLOW_DISSOLVE=1: dissolving the groups of the two devices (what they were is printed so you can link them again)"
  for D in "$S" "$C"; do
    echo "$D before: $(get "$D" dist/getDistributionInfo)"
    case "$(role "$D")" in
      client) post "$D" dist/setClientInfo '{"group_id":""}' >/dev/null ;;
      server) get "$D" dist/stopDistribution >/dev/null; post "$D" dist/setServerInfo '{"group_id":""}' >/dev/null ;;
    esac
  done
  sleep 4
  echo "now: server role='$(role "$S")' client role='$(role "$C")'"
  if [ "$(role "$S")" != "none" ] || [ "$(role "$C")" != "none" ]; then echo "still in a group (a third device may be in it); unlink in the manufacturer's app."; exit 1; fi
fi

for NUM in "${VARIANTS[@]}"; do
  say "variant: startDistribution?num=$NUM   group id $GROUP"
  call "client: setClientInfo"  "$(post "$C" dist/setClientInfo "{\"group_id\":\"$GROUP\",\"zone\":[\"main\"],\"server_ip_address\":\"$S\"}")"
  call "client: setInput mc_link" "$(get "$C" "main/setInput?input=mc_link&mode=")"
  call "server: setServerInfo add" "$(post "$S" dist/setServerInfo "{\"group_id\":\"$GROUP\",\"zone\":\"main\",\"type\":\"add\",\"client_list\":[\"$C\"]}")"
  if [ "$NUM" = "none" ]; then
    call "server: startDistribution (no num)" "$(get "$S" dist/startDistribution)"
  else
    call "server: startDistribution?num=$NUM" "$(get "$S" "dist/startDistribution?num=$NUM")"
  fi
  T=0
  while [ $T -lt 15 ]; do
    [ "$(gid "$C")" = "$GROUP" ] && [ "$(inp "$C")" = "mc_link" ] && break
    sleep 1; T=$((T + 1))
  done
  echo "client reached group + mc_link after about $T s (15 = gave up)"
  sleep 3
  echo "server dist info:  $(get "$S" dist/getDistributionInfo)"
  echo "client dist info:  $(get "$C" dist/getDistributionInfo)"
  echo "client input: $(inp "$C")   (now listen: is the client playing the server's music in sync?)"
  sleep 5

  if [ "${ERRORS:-0}" = "1" ]; then
    say "error probes while linked (these should be refused; the answers are what we want to know)"
    call "server: setClientInfo (a server becoming a client)" "$(post "$S" dist/setClientInfo "{\"group_id\":\"$GROUP\",\"zone\":[\"main\"],\"server_ip_address\":\"$C\"}")"
    call "client: setServerInfo (a client becoming a server)" "$(post "$C" dist/setServerInfo "{\"group_id\":\"$GROUP\",\"zone\":\"main\",\"type\":\"add\",\"client_list\":[\"$S\"]}")"
    call "server: setServerInfo with a wrong group id" "$(post "$S" dist/setServerInfo "{\"group_id\":\"ABCDEF0123456789ABCDEF0123456789\",\"zone\":\"main\",\"type\":\"add\",\"client_list\":[\"$C\"]}")"
    call "server: startDistribution?num=99" "$(get "$S" "dist/startDistribution?num=99")"
    echo "after the probes: server dist info: $(get "$S" dist/getDistributionInfo)"
    echo "after the probes: client dist info: $(get "$C" dist/getDistributionInfo)"
  fi

  say "unlink (client leaves, server stops, server clears)"
  call "client: setClientInfo group_id empty" "$(post "$C" dist/setClientInfo '{"group_id":""}')"
  sleep 2
  call "server: stopDistribution" "$(get "$S" dist/stopDistribution)"
  call "server: setServerInfo group_id empty" "$(post "$S" dist/setServerInfo '{"group_id":""}')"
  T=0
  while [ $T -lt 15 ]; do
    [ "$(gid "$C")" = "$ZERO" ] && [ "$(gid "$S")" = "$ZERO" ] && break
    sleep 1; T=$((T + 1))
  done
  echo "both groups empty after about $T s (15 = gave up); roles: server='$(role "$S")' client='$(role "$C")'; client input now '$(inp "$C")'"
  sleep 3
done
say "end: server role='$(role "$S")', client role='$(role "$C")'"
