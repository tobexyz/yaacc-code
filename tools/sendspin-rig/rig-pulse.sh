#!/usr/bin/env bash
# Starts a PulseAudio server with a "null" sink that swallows audio in real time, for the reference player of the rig.
#
# Why: the player opens its output through PortAudio, which can not open the ALSA "null" device by name, and a machine without
# a sound card (a CI runner, a container, a sandbox) has no other output. A PulseAudio null sink runs at a real-time clock, so
# the player's buffering and its sync correction behave as on a real device.
#
#   tools/sendspin-rig/rig-pulse.sh            # prints the PULSE_SERVER value to use
#
# Needs the package "pulseaudio" (Debian/Ubuntu: sudo apt-get install pulseaudio). Verified as root with --system in a sandbox;
# the user mode (not root) is the usual one and was not run there.
set -euo pipefail

if [ "$(id -u)" = "0" ]; then
  mkdir -p /run/pulse
  pulseaudio --system --daemonize=yes --disallow-exit --exit-idle-time=-1 --disable-shm=yes -n \
    -L "module-null-sink sink_name=rig sink_properties=device.description=rig" \
    -L "module-native-protocol-unix auth-anonymous=1 socket=/run/pulse/native" >/dev/null 2>&1 || true
  echo "unix:/run/pulse/native"
else
  export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/rig-run-$(id -u)}"
  mkdir -p "$XDG_RUNTIME_DIR"
  pulseaudio --daemonize=yes --exit-idle-time=-1 --disable-shm=yes -n \
    -L "module-null-sink sink_name=rig sink_properties=device.description=rig" \
    -L "module-native-protocol-unix" >/dev/null 2>&1 || true
  echo "unix:$XDG_RUNTIME_DIR/pulse/native"
fi
