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
| layout                           | choose "Classic (tabs)" (default) or "New (single screen)"; the change applies when you return to the app screen                                                                                     |
| dark mode                        | activate the dark mode or use the same mode as configured in the system settings                                                                                                                     |
| log level                        | define which log level will be displayed in the log view                                                                                                                                             |
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
| replay playlist                  | if set the playlist will be replayed                                                                                                                                                                 |
| shuffle music                    | if set the music playlist will be shuffled                                                                                                                                                           |
| picture stay duration            | define the duration an image is shown until the next one coming up                                                                                                                                   |
| **local server configuration**   | category for the server configuration                                                                                                                                                                |
| local server state               | enable or disable media server                                                                                                                                                                       |
| autostart server on boot         | if checked the server will be automatically started on boot of the device                                                                                                                            |
| media proxy service              | if checked the http and https links shared with the app are proxied by the app using an http url, which is needed if your rendering device does not support https urls. Shared files do not depend on this setting: they are always served by the app's built-in http server. See [Media proxy](../#media-proxy). |
| restrict the media proxy to the chosen receivers | checked by default. A shared file or link is then only handed to the receivers you chose for it (their network addresses, and this phone's own addresses only if this device was chosen); a request from another address is answered with "403" and the log shows the refused address. Switch it off if a chosen receiver is refused, see below. |
| media receiver service           | activate or deactivate the media receiving service                                                                                                                                                   |
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
phone. Typical reasons are a receiver that fetches from another address than the one
it announces (several network interfaces, IPv6 next to IPv4), or a receiver whose description names a host name instead of an address
(host names are not resolved, so the receiver is never allowed). At log level "info" the log view
then shows "Media proxy refused a request from `<address>`".

Do not rely on the random key as protection when the setting is off. The address
a receiver is playing, including the key, is visible to any UPnP control point in
your network that can query that receiver (for example with `GetMediaInfo`), so
such a device can read it. With the setting on, the same device is still refused
because its address is not one of the chosen receivers. Switch it off only on a
network you trust.

The preference key is `proxy_restrict_receivers_chkbx` (string resource
`settings_proxy_restrict_receivers_chkbx`). Changing this setting applies to requests from then on, including shares that are
already playing.

## Media proxy and the settings file

Shared links used to be stored in the app's settings. They are not any more:
shares are kept in memory with a random key and expire (10 minutes after the player
ended, at most 6 hours). Entries that an earlier version left in the settings are
deleted when the app starts. Your other settings are never changed by a share.


| [Screenshots](../screenshots/) | [Settings](../settings/) |  [About](../about/) | [Code](doxygen/html/inherits.html) | [Legal Notice (Impressum)](https://schoenesnetz.de/pages/disclamer_license/about.en.html) | [Privacy Policy](../privacy.html)
