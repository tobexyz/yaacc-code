---
layout: default
permalink: settings/
---

<!-- markdownlint-configure-file {
    "first-line-h1": false
} -->
[Up]({{site.baseurl}}/)

# Settings

| setting                          | description                                                                                                                                                                                          |
|----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **appearance**                   | category for appearance settings                                                                                                                                                                     |
| layout                           | choose "New (single screen)" (the default, also after an update from a version without this setting) or "Classic (tabs)"; the change applies when you return to the app screen                                                                                     |
| theme                            | choose between "System" (follow the system setting), "Dark" and "Light" (a fresh install starts with "Dark"); a former "Dark mode" check box is converted (on becomes "Dark", off becomes "System"). The colors are shades of gray in both themes |
| log level                        | define which log level will be displayed in the log view; the log view has a clear button and refreshes every two seconds while it is open                                                          |
| swipe on browse                  | enable or disable swipe navigation between tabs                                                                                                                                                      |
| **media browsing behavior**      | category for media browsing settings                                                                                                                                                                 |
| thumbnails                       | show thumbnails or not                                                                                                                                                                               |
| cover images                     | search for cover images in media files or use a cover.jpg file within the directories                                                                                                                |
| number of threads                | Configure the number of threads used for loading content from the UPnP server. More threads causes faster loading but increase the possibility of errors. Changes are effect after restart of YAACC. |
| chunk size when browsing         | amount of items fetched from server in one request                                                                                                                                                   |
| result set size                  | amount of items fetched from server in total                                                                                                                                                         |
| **media playing behavior**       | category for media playing settings                                                                                                                                                                  |
| default item duration            | default item duration if media file provides no duration information                                                                                                                                 |
| silence duration                 | default silence duration between two media files in a playlist                                                                                                                                       |
| repeat for new players (key: replay playlist) | the repeat mode a new player starts with: set means repeat all, not set (the default of a new installation) means play the playlist once. Each player has its own repeat button (off, all, one) that changes only that player; the setting is not changed by it |
| shuffle for new players (key: shuffle music) | the shuffle mode a new player starts with. Each player has its own shuffle button that changes only that player; the setting is not changed by it                                                      |
| picture stay duration            | define the duration an image is shown until the next one coming up                                                                                                                                   |
| **Snapcast**                     | category for the Snapcast settings; see [Snapcast](../#snapcast) |
| what Snapcast needs              | an explanation only: YAACC plays into and controls an existing Snapcast server in your network, it needs one it can reach |
| play to Snapcast groups (key: settings_snapcast_play_experimental) | **on by default** (an installation that stored "off" keeps it off). When on, music you play to a Snapcast group is sent from this phone to the server: YAACC adds a stream to the server while it plays and sends the audio into it (the server must allow adding a TCP stream source at run time; the ports 4953 to 4962 of the server must be reachable). The audio goes unencrypted to the server and the stream name carries the name of this phone (see "network name for local server"). With it off, groups are still listed and controlled, but playing to one only shows a message. The key keeps its old name |
| find Snapcast servers (key: settings_snapcast_discovery) | **on by default** (an installation that never changed it has it on). Searches the local network (mDNS queries on the network, also on the first screen of a fresh install) for Snapcast servers (control service `_snapcast-ctrl._tcp` of Snapcast 0.33 and newer, `_snapcast-tcp._tcp` of 0.32 and older) while the receivers are shown or the server list is open, and the receivers page connects to every server that announces itself there. Announcements are not authenticated (any device in the network can announce a server), so clear it on networks you do not trust. Off: only the servers in the list below (and remembered ones) are used. The report of the server list window names the servers found and their addresses and the phone model |
| Snapcast servers (key: settings_snapcast_servers) | opens the list of servers and, below it, the discovery report with a *Copy report* button (what is browsed, what the system reported, the state of the switch, Wi-Fi, permission and multicast lock; it names the servers found and their addresses). With the switch above on, the network is searched while this window is open. In the list you can add a server by host name or IP address with an optional `:port` (default 1705), remove a typed-in or remembered server (its selected groups are deselected, and a running play to it stops). At most 16 servers; no user name or password is accepted, YAACC stores no credentials |
| Snapcast client (key: settings_snapcast_client_spike) | **off by default.** Lets this phone act as a Snapcast client: shows the entries below and the *Snapcast client* row (switch) on the first page and its card (the row also needs a known server). The server and its controllers see a generated client id, the name of this phone and a version text made of the name of your YAACC server (*UPnP server* > server name) and the app version, e.g. *Yaacc 6.1.0* (for a public address only the generic *YAACC* and the app version). Audio and control are sent unencrypted. The client connects to the stream port 1704 of the server and plays PCM and FLAC only (how well the sync holds depends on the phone and the Wi-Fi). Unchecking it ends a running client session; the choice *Connected to the Snapcast server* below is kept. See [This phone as a Snapcast client](../#this-phone-as-a-snapcast-client) |
| Connected to the Snapcast server (key: settings_snapcast_client_switched_on) | visible while the option above is checked. Says whether the client is connected (summary *This phone is connected to a Snapcast server and plays as a client.* or *This phone is not connected to a Snapcast server.*). **The one stored choice that the phone plays as a Snapcast client**, the same as the switch on the first page and *Start*/*Stop* of the Snapcast client details. Checking it starts the client on the server it uses (the one it was last connected to, else the first known server of the Snapcast server list; the option and the address are checked again at every start; a refusal is shown with its reason and the checkbox stays checked); unchecking stops it. The card of the phone's own client (the group card whose first member it is) has a power button for the same choice, and the menu of its row in the group sheet has *Server* to choose another server; the client uses the **same client id on all servers** (see [This phone as a Snapcast client](../#this-phone-as-a-snapcast-client)). Only you clear it: the exit, the shutdown timer, the idle close or the end of the process do not. When the app starts and it is checked, the client starts again (also after a reboot with *autostart server on boot* checked) |
| Snapcast client details          | visible while the option is checked: opens the troubleshooting sheet that shows the connection state, the sync values and the event trace of this client (address of the server, which becomes the server of the client when you tap *Start*; *Choose server*, latency, write-ahead, *Start*, *Stop*, the state and the numbers of the session, *Copy trace*). The gear of the *Snapcast client* row on the first page opens the settings at the entry *Connected to the Snapcast server* |
| **local server configuration**   | category for the server configuration                                                                                                                                                                |
| local server state               | enable or disable media server                                                                                                                                                                       |
| autostart server on boot         | **always selectable** (it no longer depends on the media server switch). Checked: the device start wakes YAACC (the boot receiver is enabled), and it starts what is switched on (media server, receiver, proxy and, when its switch is on, the Snapcast client); with all of them off the app only listens for devices (the discovery service and its notification stay). **Checked also means YAACC is never closed automatically** (the idle close of five minutes in the background does not apply); the exit button and the shutdown timer still end it. Not checked (the default): the receiver is disabled, nothing starts at the boot, and a start of the app without a screen closes itself after five minutes when nothing runs. The switch of the receiver is applied when you change the setting and at every start of the app: an installation that had the setting checked before this version starts at the boot again once the app was opened after the update. An app that was stopped by force gets no boot start |
| media proxy service              | if checked the http and https links shared with the app are proxied by the app using an http url, which is needed if your rendering device does not support https urls. The proxy is the SSL offload during sharing and **needs the *UPnP server* switch (the first-page switch) to be on**: with the proxy checked and the switch off nothing of the app serves the link, so the shared link is refused and the app says so (switch the *UPnP server* on or uncheck the proxy). With the proxy not checked the link is handed to the receivers directly and the switch does not matter. Normal sharing (files and links from other apps and from the device, with the short-lived address) works without the switch and does not depend on this setting. See [Media proxy](../#media-proxy). |
| restrict the media proxy to the chosen receivers | checked by default. A shared file or link is then only handed to the receivers you chose for it (their network addresses, and this phone's own addresses only if this device was chosen); a request from another address is answered with "403" and the log shows the refused address. Switch it off if a chosen receiver is refused, see below. |
| media receiver service           | activate or deactivate the media receiving service                                                                                                                                                   |
| output of the media receiver (key: settings_renderer_output) | *This device* (the default: images, video and audio play on this phone) or *Snapcast group (audio only)*: where the renderer plays what other apps and controllers send to it. Video and images are refused while the group is the output. With the group it only feeds the chosen Snapcast group (this phone stays silent, nothing is added to the group), and the renderer's volume and mute are the group's. The audio is sent unencrypted to the server. Any device that can reach the receiver can change the volume and mute of the whole group (UPnP and OpenHome have no login). The short text under the setting in the app says the three things that matter most (unencrypted audio, who can change the volume, and that only the group plays); the rest is here. The volume and mute an OpenHome control point sees are the last ones this phone knows from the server until the next play or the next start of the renderer. Takes effect at the next play; a running renderer play on the other output is stopped. **The same setting can be changed from an OpenHome control point:** the source list of the renderer has *Playlist* (this device) and one source per Snapcast group the app knows, and choosing a source there writes this setting and the group below; a change here is shown to the controllers (and the other way, an open settings screen follows a change from a controller), see [OpenHome](index.html#openhome). See [Snapcast as the output of the renderer](index.html#snapcast-as-the-output-of-the-renderer) |
| Snapcast group of the media receiver (key: settings_renderer_output_group) | visible with the output *Snapcast group*: opens the chooser (the server from the server list, then one of its groups). Stores the id of the group, not its name; a damaged value falls back to this device and says so |
| media provider service           | activate or deactivate the media providing service                                                                                                                                                   |
| media provider source            | the source of the media server can be either the media store of the device or test content                                                                                                           |
| media store filtering            | filter media store by configured paths                                                                                                                                                               |
| serve images                     | activate or deactivate publishing of images                                                                                                                                                          |
| serve music                      | activate or deactivate publishing of music                                                                                                                                                           |
| serve videos                     | activate or deactivate publishing of videos                                                                                                                                                          |
| sending UPnP alive notifications | define the frequency for sending UPnP alive notifications                                                                                                                                            |
| network name for local server    | display name of the media server                                                                                                                                                                     |
| UPnP network interface           | select specific network interface for UPnP (empty = auto-detect).                                                                                                                                    |
| blacklisted interfaces           | comma-separated list of interface name prefixes to exclude from UPnP discovery (e.g., lo,dummy,rmnet,ccmni,tun)                                                                                      |
| **renderer settings** | settings per discovered renderer |
| enable server-side seeking for external URLs | enable server-side time range management in proxy mode for this renderer. It applies to http and https links shared with the media proxy on and played on this renderer (not to this device and not to shared files, which are served from the file with range support). Useful for renderers that cannot handle seeking on external HTTPS URLs. |


## Restrict the media proxy to the chosen receivers

Every shared item (see [Sharing to YAACC](../#sharing-to-yaacc)) is served from a
random, per-share address that lives in memory only. This setting decides who may
use that address.

* **On (default).** Only the network addresses of the receivers you chose in the
  "Play on…" list may fetch the item. The addresses come from the receiver's
  UPnP description (its location and URL base). This phone's own addresses count
  only if "this device" was chosen. Everybody else gets "403".
* **Off.** Any device that knows the address can fetch the item while it is
  shared. Only the random key and the expiry limit access.

Switch it off if playback fails on a receiver you chose although it can reach the
phone. Typical reasons are:

* a receiver that fetches from another address than the one it announces
  (several network interfaces, IPv6 next to IPv4);
* a receiver whose description names a host name instead of an address (host
  names are not resolved, so the receiver is never allowed).

At log level "info" the log view then shows
"Media proxy refused a request from `<address>`".

The restriction is a check of the network address, not a login: a device that
can use a chosen receiver's address, or another app on this phone when "this
device" was chosen, is not stopped by it.

Do not rely on the random key as protection when the setting is off. The address
a receiver is playing, including the key, is visible to any UPnP control point in
your network that can query that receiver (for example with `GetMediaInfo`), so
such a device can read it. With the setting on, the same device is still refused
because its address is not one of the chosen receivers. Switch it off only on a
network you trust.

The preference key is `proxy_restrict_receivers_chkbx`. Changing this setting
applies to requests from then on, including shares that are already playing.

## Media proxy and the settings file

Shared links used to be stored in the app's settings. They are not any more:
shares are kept in memory with a random key and expire (10 minutes after the player
ended, at most 6 hours). Entries that an earlier version left in the settings are
deleted when the app starts. Your other settings are never changed by a share.


| [Screenshots](../screenshots/) | [Settings](../settings/) |  [About](../about/) | [Code](doxygen/html/inherits.html) | [Legal Notice (Impressum)](https://schoenesnetz.de/pages/disclamer_license/about.en.html) | [Privacy Policy](../privacy.html)
