#!/usr/bin/env bash
# Runs the reference Sendspin player (or server) without Docker, from a Python virtual environment.
#
#   tools/sendspin-rig/run-local.sh player [port] [name] [unpaired true|false]
#   tools/sendspin-rig/run-local.sh server [port]
#
# The virtual environment, the settings and the identity of the player live in $RIG_DIR (default: ./.sendspin-rig).
# Needs Python 3.11 or newer and PortAudio (Debian/Ubuntu: sudo apt-get install libportaudio2). Audio goes to the ALSA "null"
# device, so no sound card is needed.
set -euo pipefail

SENDSPIN_VERSION="${SENDSPIN_VERSION:-9.1.0}"
RIG_DIR="${RIG_DIR:-$(pwd)/.sendspin-rig}"
mode="${1:-player}"
mkdir -p "$RIG_DIR"
if [ ! -x "$RIG_DIR/venv/bin/sendspin" ]; then
  python3 -m venv "$RIG_DIR/venv"
  "$RIG_DIR/venv/bin/pip" -q install "sendspin==${SENDSPIN_VERSION}"
fi
export HOME="$RIG_DIR/home"
mkdir -p "$HOME"

case "$mode" in
  player)
    port="${2:-8928}"
    name="${3:-RigPlayer}"
    unpaired="${4:-true}"
    exec "$RIG_DIR/venv/bin/sendspin" daemon --unpaired-access "$unpaired" --audio-device null --disable-mpris \
      --settings-dir "$RIG_DIR/player-$port" --name "$name" --port "$port" --log-level "${RIG_LOG_LEVEL:-INFO}"
    ;;
  server)
    port="${2:-8927}"
    exec "$RIG_DIR/venv/bin/sendspin" serve --demo --name RigServer --port "$port" --log-level "${RIG_LOG_LEVEL:-INFO}"
    ;;
  *)
    echo "usage: $0 player [port] [name] [unpaired] | server [port]" >&2
    exit 2
    ;;
esac
