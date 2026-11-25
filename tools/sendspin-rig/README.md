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
**A group of two players** (linking): start two players that admit unpaired servers on two ports (for example `run-local.sh player 8928 A true` and `run-local.sh player 8930 B true`, with `RIG_LOG_LEVEL=DEBUG` so that they log their sync error) and run
`./gradlew :yaacc:testDebugUnitTest --tests de.yaacc.sendspin.hub.HubGroupRigTest -Dsendspin.rig=127.0.0.1:8928 -Dsendspin.rig.second=127.0.0.1:8930 -Dsendspin.rig.seconds=45`.
The test plays one tone to both through one timeline (the second player joins while it runs, then a seek, a pause, and the second
leaves); the `Sync error` lines of the two players' logs show the result. Both players share the rig's one PulseAudio sink, so their
volumes are one volume. Measured 2026-11-25: both start at -127 ms, converge to between -4 and +1.3 ms after 45 s, no dropped audio.

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

**Pairing.** The reference daemon has no command that shows its pairing token, so `rig-paired.py` prepares the settings directory of
a player that accepts only paired servers (it writes the identity and a Pairing PSK in the file format of `sendspin` 9.1.0) and
prints the token (`SP:0...`, a secret):

```
.sendspin-rig/venv/bin/python tools/sendspin-rig/rig-paired.py .sendspin-rig/player-8932
tools/sendspin-rig/run-local.sh player 8932 PairedRig false
./gradlew :yaacc:cleanTestDebugUnitTest :yaacc:testDebugUnitTest --tests 'de.yaacc.sendspin.hub.HubRigTest' \
    -Dsendspin.rig.paired=127.0.0.1:8932 -Dsendspin.rig.token=SP:0...
```

The test pairs the player with the token, plays, then connects a second time and expects the long-term key to be used without the
token. The same token can be pasted in the app (tap the card of the player). Run `run-local.sh` after the script, not before: the
daemon writes its own files at start and the script replaces them.

## Music Assistant

`music-assistant/docker-compose.yml` runs a simple Music Assistant server (image `ghcr.io/music-assistant/server`) to try the
controller of YAACC with the server that most Sendspin users have. **It was written from Music Assistant's own sources and
documentation and not run** (the machine that wrote it had no Docker daemon): the first run may need a fix.

```
python3 tools/sendspin-rig/music-assistant/make-music.py tools/sendspin-rig/music-assistant/music    # three test tones (about 60 MB)
docker compose -f tools/sendspin-rig/music-assistant/docker-compose.yml up -d
```

1. Open `http://<host>:8095` and create the first user (the server's onboarding).
2. *Settings* > *Providers* > add *Filesystem (local disk)* with the path `/media` (the folder `music/` of the compose file, read only).
   The Sendspin provider is built in and always on; it listens on port 8927 at `/sendspin`.
3. Start a player that Music Assistant can find: `tools/sendspin-rig/run-local.sh player 8928 RigPlayer true` on the same host (it
   announces itself by mDNS; Music Assistant connects to it) and, with the *Sendspin* provider, it appears as a player. Play one of the
   test tones on it.
4. In YAACC: *Settings* > *Sendspin* > *Control Sendspin servers* on; the server is found by the search (*Find Sendspin servers*) or add
   `<host>:8927` under *Sendspin servers*. The card shows what plays and controls the group of the phone; if it says "connected, waiting
   for the server to allow control", look in Music Assistant's player settings for the new client and allow it.

The container uses the host network (Music Assistant needs direct network access to find and stream to players); on Docker Desktop for
Mac or Windows host networking does not work as on Linux: run it on a Linux host or a virtual machine with a bridged network. What
Music Assistant does with a client that is not paired and that has the controller role is **not known yet**: that is what this rig is for.
Write what you see in `.claude/specs/2026-11-24-issue325-sendspin/controller.md` (open point 1).

## A real device: Kodi

The owner has a Kodi device. Whether a Kodi add-on or build offers a Sendspin player or server is **not known** and has to be
looked up at that device (the session that wrote this could not reach the project site). Until then the rig is the test
partner; a real device can be added by address in the same way.

## Notes

- `--unpaired-access true` makes the reference player admit a server it has no pairing record for; YAACC then relies on the
  operator approval of the spec (choosing the player in "Play on"). With `false` the player demands pairing.
- The reference daemon offers `pairing_psk` and `dynamic_pairing_code` in its hello, but in daemon mode it shows no pairing token
  (its store has `pairing_psk: null`): `rig-paired.py` writes one, and the Pairing PSK flow was tested that way (not with a token
  that a real player displays).
- The player lists the formats the output device supports (32 with the null sink: PCM and FLAC at 44.1, 48, 96 and 192 kHz); YAACC
  uses the first PCM stereo 16 bit entry in the player's order.
- The player prints its `client_id` (the first log lines); a second identity needs another `--settings-dir` (the script uses one
  per port).
