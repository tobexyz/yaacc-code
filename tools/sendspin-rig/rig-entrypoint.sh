#!/bin/sh
# Container entrypoint: a PulseAudio null sink first (see rig-pulse.sh), then the reference application with its arguments.
set -e
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/rig-run}"
mkdir -p "$XDG_RUNTIME_DIR"
pulseaudio --daemonize=yes --exit-idle-time=-1 --disable-shm=yes -n \
  -L "module-null-sink sink_name=rig sink_properties=device.description=rig" \
  -L "module-native-protocol-unix" >/dev/null 2>&1 || true
export PULSE_SERVER="unix:$XDG_RUNTIME_DIR/pulse/native"
exec sendspin "$@"
