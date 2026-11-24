# Sendspin test rig image: the reference command line player and server from PyPI (package "sendspin", Apache-2.0).
#
# Local testing only (see README.md). Nothing of it is copied into this repository.
#
# Written in a sandbox without a Docker daemon: this image was NEVER built. The tag below must be checked at the first build,
# and the PulseAudio user mode of rig-entrypoint.sh (the sandbox run used the system mode as root) is part of what the first run shows
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

# PortAudio is what the player needs to open an output. It can not open the ALSA "null" device by name, so a PulseAudio null
# sink (started by rig-entrypoint.sh) is the output: no sound card is needed. ffmpeg is for the server's file and URL sources.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libportaudio2 libasound2 libasound2-plugins pulseaudio ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && pip install "sendspin==${SENDSPIN_VERSION}" \
    && useradd --create-home --home-dir /data --uid 10001 rig \
    && chown -R rig:rig /data

COPY rig-entrypoint.sh /usr/local/bin/rig-entrypoint.sh
RUN chmod +x /usr/local/bin/rig-entrypoint.sh

USER rig
WORKDIR /data
ENTRYPOINT ["/usr/local/bin/rig-entrypoint.sh"]
# a headless player that admits unpaired servers and plays into the null sink
CMD ["daemon", "--unpaired-access", "true", "--audio-device", "pulse", "--disable-mpris", "--settings-dir", "/data/settings", "--name", "RigPlayer"]
