# Sendspin test rig image: the reference command line player and server from PyPI (package "sendspin", Apache-2.0).
#
# Local testing only (see README.md). Nothing of it is copied into this repository.
#
# Written in a sandbox without a Docker daemon: the tag below must be checked at the first build
#   - PYTHON_TAG: https://hub.docker.com/_/python/tags?name=3.13-slim-bookworm
# The package version is pinned (SENDSPIN_VERSION); its dependencies (aiosendspin and others) are what pip resolves for it.
# The player is verified outside Docker with the same package and the same options (run-local.sh).

ARG PYTHON_TAG=3.13-slim-bookworm
FROM python:${PYTHON_TAG}

ARG SENDSPIN_VERSION=9.1.0

ENV DEBIAN_FRONTEND=noninteractive \
    PIP_NO_CACHE_DIR=1 \
    PYTHONUNBUFFERED=1 \
    HOME=/data

# PortAudio is what the player needs to open an output; the ALSA "null" device (part of libasound2) swallows the audio,
# so no sound card is needed. ffmpeg is for the server's file and URL sources.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libportaudio2 libasound2 ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && pip install "sendspin==${SENDSPIN_VERSION}" \
    && useradd --create-home --home-dir /data --uid 10001 rig \
    && chown -R rig:rig /data

USER rig
WORKDIR /data
ENTRYPOINT ["sendspin"]
# a headless player that admits unpaired servers (task 4.x of the spec) and plays into the void
CMD ["daemon", "--unpaired-access", "true", "--audio-device", "null", "--disable-mpris", "--settings-dir", "/data/settings", "--name", "RigPlayer"]
