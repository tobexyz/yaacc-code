# Snapcast test rig image: builds snapserver and snapclient from source.
#
# Local testing only (see README.md). Snapcast is GPL-3.0-or-later; nothing of it
# is copied into this repository, the sources are cloned at build time.
#
# The pins below must be checked at the first build (written in a sandbox without Docker):
#   - DEBIAN_TAG:    https://hub.docker.com/_/debian/tags?name=12.
#   - SNAPCAST_COMMIT: the commit the YAACC notes (docs/tech.md) were read on; it is fetched by hash
#                      and verified, so the build cannot silently get other sources.
# No "latest" or floating tag is used anywhere.

ARG DEBIAN_TAG=12.12-slim

# ---------------------------------------------------------------- build stage
FROM debian:${DEBIAN_TAG} AS build

# Full 40-character commit hash of snapcast/snapcast (develop at 2026-11-03, CMake version 0.35.0).
# The build fetches exactly this commit and fails if the hash is malformed or does not match.
ARG SNAPCAST_COMMIT=ea981ec5ec0805dfc9b4af3979090c325abf501d
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

RUN printf '%s' "${SNAPCAST_COMMIT}" | grep -Eq '^[0-9a-f]{40}$' \
    || { echo "SNAPCAST_COMMIT must be a full 40 character lowercase commit hash" >&2; exit 1; }; \
    git init /src \
    && git -C /src remote add origin https://github.com/snapcast/snapcast.git \
    && git -C /src fetch --depth 1 origin "${SNAPCAST_COMMIT}" \
    && git -C /src checkout --detach FETCH_HEAD \
    && { test "$(git -C /src rev-parse HEAD)" = "${SNAPCAST_COMMIT}" \
         || { echo "fetched commit does not match SNAPCAST_COMMIT" >&2; exit 1; }; }
# The sources have no git submodules and the CMake configure step (BUILD_TESTS=OFF) downloads
# nothing (FetchContent is used only for the tests); re-check this when the commit is changed.

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
