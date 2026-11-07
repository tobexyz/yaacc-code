# MPD for the OpenHome test device: it plays what upmpdcli gives it on a "null" output (no sound card needed; the playback
# runs in real time, so position, duration and state are real). See README.md.
FROM debian:stable-slim

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends mpd ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/lib/mpd/playlists /music

COPY mpd.conf /etc/mpd.conf

# only the container itself talks to MPD (upmpdcli runs on the same host network)
EXPOSE 6610
CMD ["mpd", "--no-daemon", "--stderr", "/etc/mpd.conf"]
