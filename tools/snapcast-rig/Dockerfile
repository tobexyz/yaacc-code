# Snapcast test rig image: builds snapserver and snapclient from source.
#
# Local testing only (see README.md). Snapcast is GPL-3.0-or-later; nothing of it
# is copied into this repository, the sources are cloned at build time.
#
# Both pins below must be checked at the first build (the tags are written from
# memory, not verified in the sandbox this file was authored in):
#   - DEBIAN_TAG:    https://hub.docker.com/_/debian/tags?name=12.
#   - SNAPCAST_REF:  https://github.com/snapcast/snapcast/tags
# No "latest" or floating tag is used anywhere.

ARG DEBIAN_TAG=12.12-slim

# ---------------------------------------------------------------- build stage
FROM debian:${DEBIAN_TAG} AS build

ARG SNAPCAST_REF=v0.35.0
# Optional integrity check: full commit hash the tag must resolve to (empty = skip).
ARG SNAPCAST_COMMIT=
# Parallel compile jobs; keep low, the C++ build is memory hungry.
ARG BUILD_JOBS=2

ENV DEBIAN_FRONTEND=noninteractive

# Build dependencies for Debian 12 (boost >= 1.74 is required by Snapcast).
# ALSA, Avahi (mDNS), PulseAudio, JACK and PipeWire are not needed on the rig.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        cmake \
        git \
        libboost-dev \
        libexpat1-dev \
        libflac-dev \
        libogg-dev \
        libopus-dev \
        libsoxr-dev \
        libssl-dev \
        libvorbis-dev \
        pkg-config \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 --branch "${SNAPCAST_REF}" https://github.com/snapcast/snapcast.git /src \
    && if [ -n "${SNAPCAST_COMMIT}" ]; then \
         test "$(git -C /src rev-parse HEAD)" = "${SNAPCAST_COMMIT}" \
           || { echo "SNAPCAST_REF ${SNAPCAST_REF} does not match SNAPCAST_COMMIT" >&2; exit 1; }; \
       fi

WORKDIR /src

# BUILD_WITH_AVAHI=OFF: mDNS publishing is compiled out, so the rig is NOT
# discoverable via _snapcast-ctrl._tcp; connect to it by address (README.md).
# The binaries land in /src/bin (CMAKE_RUNTIME_OUTPUT_DIRECTORY of the project).
RUN cmake -S . -B build \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SERVER=ON \
        -DBUILD_CLIENT=ON \
        -DBUILD_TESTS=OFF \
        -DBUILD_WITH_AVAHI=OFF \
        -DBUILD_WITH_ALSA=OFF \
        -DBUILD_WITH_PULSE=OFF \
        -DBUILD_WITH_JACK=OFF \
        -DBUILD_WITH_PIPEWIRE=OFF \
    && cmake --build build --parallel "${BUILD_JOBS}" \
    && strip /src/bin/snapserver /src/bin/snapclient

# -------------------------------------------------------------- runtime stage
FROM debian:${DEBIAN_TAG} AS runtime

ENV DEBIAN_FRONTEND=noninteractive

# Runtime libraries matching the build dependencies (Debian 12 package names).
# bash is part of the base image and is used by the compose healthcheck.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libexpat1 \
        libflac12 \
        libogg0 \
        libopus0 \
        libsoxr0 \
        libssl3 \
        libvorbis0a \
        libvorbisenc2 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 snapcast \
    && useradd --system --uid 10001 --gid 10001 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin snapcast

COPY --from=build /src/bin/snapserver /src/bin/snapclient /usr/local/bin/

LABEL org.opencontainers.image.title="yaacc-snapcast-rig" \
      org.opencontainers.image.description="Snapcast server and client built from source for local YAACC integration tests" \
      org.opencontainers.image.licenses="GPL-3.0-or-later" \
      org.opencontainers.image.source="https://github.com/snapcast/snapcast"

# Non-root from here on; the build fails if a shared library is missing.
USER 10001:10001
RUN snapserver --version && snapclient --version

# docker-compose.yml supplies the real command; the default only prints help.
CMD ["snapserver", "--help"]
