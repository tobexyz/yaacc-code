# Sendspin test rig (issue #325)

A reference Sendspin player (and server) for developing and testing YAACC's Sendspin support without hardware. It runs the
command line application `sendspin` (PyPI package `sendspin` 9.1.0, Apache-2.0, built on `aiosendspin`), which implements
the protocol of `github.com/Sendspin/spec`. The player sends its audio to a PulseAudio **null sink**, so no sound card is needed
(PortAudio can not open the ALSA "null" device by name; a null sink also runs at a real-time clock, so the player's buffering and sync
correction behave as on a real device).
Nothing of it is copied into this repository. "Sendspin" is a trademark of the Open Home Foundation; this rig is not
affiliated with it.

## Without Docker (what the sandbox used)

```
export PULSE_SERVER=$(tools/sendspin-rig/rig-pulse.sh)        # a PulseAudio null sink (once); prints the server to use
tools/sendspin-rig/run-local.sh player 8928 RigPlayer true    # port, name, admit unpaired servers
tools/sendspin-rig/run-local.sh player 8929 RigPaired false   # a player that demands pairing
tools/sendspin-rig/run-local.sh server 8927                   # the reference server with demo audio
```

Needs Python 3.11+, PortAudio (`sudo apt-get install libportaudio2`) and PulseAudio (`pulseaudio`). `RIG_LOG_LEVEL=DEBUG` shows
the player's sync error (`Sync error: ... ms`), dropped and inserted frames and every stream message. The virtual environment,
identity and settings are kept in `./.sendspin-rig` (ignored by git; `RIG_DIR` changes it); delete it to get a new player identity.

**Verified in the sandbox of issue #325** with `sendspin` 9.1.0: the handshake under the Sentinel PSK, the operator approval, 12 to
40 seconds of PCM, a seek, a pause and resume and a volume command; the player's own sync error converged from about -60 ms to
within +-3 ms in 25 seconds and it dropped or inserted a few frames only while correcting.

## With Docker

```
cd tools/sendspin-rig
docker compose up -d --build player1 player2       # players on host ports 8928 and 8929, unpaired access on
RIG_UNPAIRED=false docker compose up -d player1    # a player that demands pairing (tests of the Pairing PSK)
docker compose --profile server up server          # reference server on 8927 that connects to player1
docker compose logs -f player1                     # what the player sees
```

The compose file was validated with `docker compose config` but **the image has never been built or run** (the sandbox had no
Docker daemon). The first build must check the base image tag in the `Dockerfile` (`PYTHON_TAG`) and that the PulseAudio user
mode of `rig-entrypoint.sh` starts (the sandbox runs used the system mode as root, see `rig-pulse.sh`).

mDNS does not cross Docker's bridge network: add the players in YAACC **by address** (`<docker host>:8928`, `:8929`). On
Linux, copying the file with `network_mode: host` makes them visible by mDNS (`_sendspin._tcp`) as well.

## Tests in YAACC against the rig

The JVM tests that need a running player are skipped unless a rig address is given:

```
./gradlew :yaacc:testDebugUnitTest -Dsendspin.rig=localhost:8928 --tests 'de.yaacc.sendspin.*RigTest'
```

`sendspin.rig` is `host:port` of a player started with unpaired access (`true`). A second property,
`sendspin.rig.paired=host:port`, names a player started with unpaired access `false`; `sendspin.rig.seconds` sets the length of the
tone. Gradle treats the test task as up to date when only these properties change: add `:yaacc:cleanTestDebugUnitTest` before it.
The tests are `SendspinRigTest` (the raw protocol) and `HubRigTest` (the hub and the streamer of the app).

## A real device: Kodi

The owner has a Kodi device. Whether a Kodi add-on or build offers a Sendspin player or server is **not known** and has to be
looked up at that device (the session that wrote this could not reach the project site). Until then the rig is the test
partner; a real device can be added by address in the same way.

## Notes

- `--unpaired-access true` makes the reference player admit a server it has no pairing record for; YAACC then relies on the
  operator approval of the spec (choosing the player in "Play on"). With `false` the player demands pairing.
- The reference daemon offers `pairing_psk` and `dynamic_pairing_code` in its hello, but in daemon mode it showed no pairing token
  (its store has `pairing_psk: null`): the Pairing PSK flow is not yet tested against it.
- The player lists the formats the output device supports (32 with the null sink: PCM and FLAC at 44.1, 48, 96 and 192 kHz); YAACC
  uses the first PCM stereo 16 bit entry in the player's order.
- The player prints its `client_id` (the first log lines); a second identity needs another `--settings-dir` (the script uses one
  per port).
