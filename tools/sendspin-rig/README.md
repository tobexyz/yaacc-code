# Sendspin test rig (issue #325)

A reference Sendspin player (and server) for developing and testing YAACC's Sendspin support without hardware. It runs the
command line application `sendspin` (PyPI package `sendspin` 9.1.0, Apache-2.0, built on `aiosendspin`), which implements
the protocol of `github.com/Sendspin/spec`. The player sends its audio to the ALSA "null" device, so no sound card is needed.
Nothing of it is copied into this repository. "Sendspin" is a trademark of the Open Home Foundation; this rig is not
affiliated with it.

## Without Docker (what the sandbox used)

```
tools/sendspin-rig/run-local.sh player 8928 RigPlayer true    # port, name, admit unpaired servers
tools/sendspin-rig/run-local.sh server 8927                   # the reference server with demo audio
```

Needs Python 3.11+ and PortAudio (`sudo apt-get install libportaudio2`). The virtual environment, identity and settings are
kept in `./.sendspin-rig` (ignored by git); delete it to get a new player identity.

## With Docker

```
cd tools/sendspin-rig
docker compose up -d --build player1 player2       # players on host ports 8928 and 8929, unpaired access on
RIG_UNPAIRED=false docker compose up -d player1    # a player that demands pairing (tests of the Pairing PSK)
docker compose --profile server up server          # reference server on 8927 that connects to player1
docker compose logs -f player1                     # what the player sees
```

The compose file was validated with `docker compose config` but **has not been built or run** (the sandbox had no Docker
daemon). The first build must check the base image tag in the `Dockerfile` (`PYTHON_TAG`).

mDNS does not cross Docker's bridge network: add the players in YAACC **by address** (`<docker host>:8928`, `:8929`). On
Linux, copying the file with `network_mode: host` makes them visible by mDNS (`_sendspin._tcp`) as well.

## Tests in YAACC against the rig

The JVM tests that need a running player are skipped unless a rig address is given:

```
./gradlew :yaacc:testDebugUnitTest -Dsendspin.rig=localhost:8928 --tests 'de.yaacc.sendspin.*RigTest'
```

`sendspin.rig` is `host:port` of a player started with unpaired access (`true`). A second property,
`sendspin.rig.paired=host:port`, names a player started with unpaired access `false` for the pairing tests.

## A real device: Kodi

The owner has a Kodi device. Whether a Kodi add-on or build offers a Sendspin player or server is **not known** and has to be
looked up at that device (the session that wrote this could not reach the project site). Until then the rig is the test
partner; a real device can be added by address in the same way.

## Notes

- `--unpaired-access true` makes the reference player admit a server it has no pairing record for; YAACC then relies on the
  operator approval of the spec (choosing the player in "Play on"). With `false` the player demands the Pairing PSK.
- The player prints its `client_id` (the first log lines); a second identity needs another `--settings-dir` (the script uses one
  per port).
