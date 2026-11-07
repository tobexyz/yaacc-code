# A local OpenHome device for testing

Two containers: **MPD** (built here, plays on a "null" output, so no sound card is needed) and **upmpdcli**, which makes MPD an
OpenHome renderer (Product, Playlist, Info, Time, Volume, Radio; it is not a Songcast sender or receiver). It answers like a
device of another make, so it is the independent test partner for YAACC's OpenHome controller.

## Start

```sh
docker compose up --build        # in this folder; add -d to run in the background
docker compose down              # stop
```

The device is called "Test OpenHome device" (room "Test room"). After a minute it shows in YAACC's receiver list, with the
settings icon for OpenHome devices.

## The network (important)

The device is found by multicast (SSDP), and the controller connects to it afterwards, so the containers use `network_mode: host`.

* **Linux host** (a notebook, a Raspberry Pi, a NAS, a VM with a *bridged* adapter): works as it is. Set `UPNPIFACE` in
  `docker-compose.yml` when the host has more than one network interface.
* **Docker Desktop on Windows or macOS**: host networking does not give the containers a presence in your LAN, so the phone will
  not find the device. Run the setup in a Linux VM with a bridged network adapter, or on a Linux machine in the network.

## The device is not found by the phone or Home Assistant

Seen only by an app on the same machine (Upplay, for example): the traffic on the machine itself does not pass the firewall or the
Wi-Fi, the traffic from other devices does. Check, in this order:

1. **Firewall of the host**: allow SSDP and the UPnP port, e.g. `sudo ufw allow 1900/udp` and `sudo ufw allow 49152:49160/tcp`
   (the port is in the log: `LibUPnP: Using IPV4 <ip> port 49152`), or switch the firewall off for a moment to test.
2. **Is multicast arriving**: `sudo tcpdump -ni <interface> udp port 1900` while the receiver list is refreshed in YAACC. The
   announcements of the device should leave, and the search of the phone should arrive. Searches that arrive without an answer:
   firewall. No searches at all: the access point (client isolation, IGMP snooping) or the Wi-Fi power saving of the host; try a
   cable, or `sudo iw dev <interface> set power_save off`.
3. **Port reachable** from another machine: `nc -zv <host ip> 49152`.

## What to test with it (YAACC as the controller)

* The device shows in the receiver list; its settings sheet shows power, the sources of the device and the playback protocol.
* Play a track from a server on the device: the queue of the device is the playlist of the player screen. Add, delete, next,
  previous, seek, pause, stop; the state, the track and the position follow the device.
* Volume and mute, repeat and shuffle.
* A change made by another controller (a second phone, Home Assistant, another OpenHome app) shows in YAACC within a few seconds.
* Standby: switch it with the power switch of the settings sheet.

Tracks must have an http address that the container can reach (MPD fetches them). With the null output the music is not audible;
use the `httpd` output in `mpd.conf` to listen in a browser.

## Notes

* Not tested by the author: the files were written without a Docker daemon at hand. The settings are those of the image's README
  (`MPD_HOST`, `MPD_PORT`, `RENDERER_MODE`, `UPMPD_FRIENDLY_NAME`, `OH_PRODUCT_ROOM`, `UPNPIFACE`). If a variable is not
  understood, compare with the README of <https://github.com/GioF71/upmpdcli-docker>.
* The images are not pinned (`debian:stable-slim`, `giof71/upmpdcli:stable-renderer`): this is a throw-away test tool. Pin both
  when the setup is kept.
* upmpdcli and the image are third-party software (upmpdcli: GPL, see its project page); nothing of them is part of YAACC.
