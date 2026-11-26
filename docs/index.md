---
layout: default
---

<!-- markdownlint-configure-file {
    "first-line-h1": false
} -->

* TOC
{:toc}

# Features

* UPnP/DLNA Server - share files of your device in the network
* UPnP/DLNA Client - receive media from other devices on your device
* UPnP/DLNA Controller - control media renderer in the network
* Control multiple media renderer
* Link Yamaha MusicCast devices into a group, set the group volume, control their own sources (now playing, tuner, presets) and change device settings, see [MusicCast devices](#musiccast-devices)
* Play music on OpenHome devices, which keep the playlist themselves, and let OpenHome control points play on YAACC, see [OpenHome](#openhome)
* Control Snapcast multi-room servers (each group is a receiver: group volume and mute, per-client volume, latency and name, the stream of a group, linking and unlinking clients), play music from the phone to a Snapcast group, let the YAACC renderer play into a group, and use the phone itself as a Snapcast client (PCM and FLAC), see [Snapcast](#snapcast)
* Play music, video and pictures on FCast receivers (found by mDNS or added by address, device name, connection link or the QR code of a receiver), see [FCast](#fcast)
* Power buttons on the cards of MusicCast devices, OpenHome devices, the app's own OpenHome renderer and the phone's own Snapcast client, see [The row](#the-row), [OpenHome](#openhome) and [Snapcast](#snapcast)
* Allow download files to the device
* Share a link (http or https) or one audio, video or image file from another app to YAACC and play it on the receivers you pick, see [Sharing to YAACC](#sharing-to-yaacc)
* Pick files or a folder of your device and play them on the receivers you pick without serving them constantly, see [Playing files from this device](#playing-files-from-this-device)
* Use your device as an proxy if your media renderer can't process https media URLs
* Download files to the device for offline access
* A Shutdown timer stops the running players and the app after a given time
* Sort browsed content by name or date, ascending or descending
* Android TV support (leanback launcher)
* Two layouts: the classic tabs or a single screen (the default of a fresh install) with servers, receivers and the running players on one page, chosen in the settings
* Share an item you browse (the file or its link) with other apps, see [Content browsing](#content-browsing)

# Usage

YAACC has two layouts. A fresh install and an installation updated from a version without this setting start in the new layout; a layout you chose before stays. You switch
in the settings under *Appearance* >
*Layout*: choose "Classic (tabs)" or "New (single screen)". The change takes
effect when you return from the settings to the app screen; running players keep
playing. This page describes the classic layout first, see
[New layout](#new-layout-single-screen) for the single screen.

In the classic layout all UPnP/DLNA servers in your network are listed on the
first screen. Select one and the app will automatically switch to the content tab.
It allows to browse the content provided by the selected server.

![browse_servers](./screenshots/6.0.x/browse_servers.png){:height="30%" width="30%"}

Before playing content make sure you have chosen a receiver. In the classic
layout you tick receivers on the receiver tab. In the new layout there is no
persistent selection: every time you play, the "Play on…" list asks for the
receivers, see [Choosing receivers](#choosing-receivers-new-layout) and
[Receiver controls](#receiver-controls).
Receivers are either UPnP/DLNA media renderers in your network or the
android device itself.

![browse_receiver](./screenshots/6.0.x/browse_receiver.png){:height="30%" width="30%"}

Normally senders and receivers will appear automatically.
If not you can use the refresh button at the bottom of
the sender and receiver tab to trigger a new search for devices
(in the new layout: the refresh button in the "Servers" header).

## New layout (single screen)

Switch to it with *Settings* > *Appearance* > *Layout* > "New (single screen)".
Switching back to "Classic (tabs)" works the same way. A fresh install starts in
the new layout (also on Android TV, where it has not been checked with a TV
remote yet); an updated installation without a stored layout starts in the new layout.
There are no tabs. The screen shows one level at a time:

* **Servers, receivers and players** is the top level, one list in three sections with a
  thin line between them. The "Servers" section (broadcast icon in front of the header) lists
  all UPnP/DLNA servers in your network with the card "Play from this device" below them; the YAACC
  entry has the live streaming buttons (see [Live streaming](#live-streaming-android-10)). Its header has
  the refresh button to search for devices again (one search finds servers and receivers). The
  "Receivers" section (devices icon) lists every receiver with the same rows and controls as the
  device sheet, see [Receiver controls](#receiver-controls) (the players that run on a receiver are in the
  "Players" section, not under the receiver as in the sheet); while only this phone is listed it says that no other
  receiver was found yet. The "Players" section lists every running player as one compact row, like
  the mini-player below the content (see below); it is only there while a player runs. The rows update
  live while this level is shown. This level has neither the receiver bar nor the mini-player at the
  bottom, because the sections show the same. At the bottom of the screen are the YAACC
  server switch with the button for the server settings, and the icons showing
  whether the device acts as media provider, media renderer or proxy
  (see [Media server](#media-server)). These bottom controls exist only on
  this level.
* **Content**: tap a server to browse it, see [Content browsing](#content-browsing).
  The arrow in front of the path and the system back button go back one step at a
  time: from a folder to the parent folder, from the root of a server to the
  server list, and from the server list the app goes to the background like any
  other app (it keeps running; the exit button and the idle close below end it). The server button in the
  header of the content (the broadcast icon, the same one as in front of the "Servers"
  header; a long press shows its name) shows the server list at once from any folder
  depth, so you can change the server without going back folder by folder.
* **Toolbar**: the timer symbol is the [shutdown timer](#shutdown-timer) and its
  only entry. While a timer runs, a tap stops it and the remaining time is shown
  under the title of the toolbar. Otherwise a tap opens the duration picker; confirming a
  duration above 0 starts the timer. The overflow menu has the settings, the log
  and the about page, and the exit button quits the app.
* **Receiver bar**: at the bottom of the content of a server (the top level has the Receivers section instead). It shows the names of the
  receivers that are playing something right now (a paused player counts), or
  "No receiver playing". It does not show a selection. Tap it to open the device
  sheet, see [Receiver controls](#receiver-controls).
* **Mini-player**: directly above the receiver bar, below the content of a server (the top level has the Players section instead). It shows every running player
  as one compact row: the album art when available, the title, the name of the
  receiver it plays on, and the buttons previous, play/pause, next, stop and exit (stop only stops the
  playback, exit removes the player). Tap a row itself to open that player's screen. The rows are in the same order as the
  players in the device sheet (the one started first is on top). Up to about three
  rows are visible; with more players the list scrolls inside the mini-player. The
  mini-player is hidden when no player is running.

## Content browsing

The content browsing has a bread crumb on the top.
Behind each content entry different symbols are showing the possible actions:

* add an item to the playlist
* download the item
* play the item
* play all items on the same directory level
* share the item with another app: the symbol (and the share entry in the menu of the image viewer) offers *Share file*, which copies the item to the cache of YAACC and hands it to the app you choose (so it works also for a server that is only reachable in your network), and *Share link*, which hands over the address of the item

The bread crumb starts with the name of the selected server. The arrow in front
of it goes up one folder (in the new layout it also leaves the server at its
root folder).

In the classic layout the bottom of the screen shows two lines: the name of the
selected server and the names of the selected receivers (separated by "; "),
each with its icon. They only display the current selection; choose the
receivers on the receiver tab. Playing while no receiver is selected shows a
message asking you to select one there.

In the new layout the receiver bar at the bottom of the content shows the receivers that are
playing; tap it to open the device sheet. Playing does not use a stored selection:
play, play all, tapping an item, and add to playlist first open the "Play on…"
list, see [Choosing receivers](#choosing-receivers-new-layout).

A button in the folder header switches between a list and a grid of cards (the
same entries and the same buttons); the cards are the default of a fresh install
and your choice is remembered. In landscape the cards are laid out in several
columns.

A toggle in the folder header lets you sort the current folder by name or date,
ascending or descending. yaacc uses the server's own sort support when browsing
a UPnP/DLNA server that offers it, falling back to sorting the results itself
otherwise. The chosen sort order is remembered across app restarts.

![browse_music_folder](./screenshots/6.0.x/browse_music_folder.png){:height="30%" width="30%"}

### Choosing receivers (new layout)

In the new layout the receiver is chosen for each action:

1. Tap play, play all, an item row, or add to playlist. A sheet titled "Play on…"
   opens with every receiver, including this device, and a checkbox per row.
   The receivers of your last confirmed choice are ticked, so the usual receiver
   is one tap away; on the very first use nothing is ticked.
2. Tick one or more receivers and tap "OK". With nothing ticked "OK" only shows
   "Choose at least one receiver." and the list stays open.
3. YAACC starts the playback on exactly those receivers. Afterwards nothing is
   selected again, so the next action asks again. The receiver bar shows the
   receivers that are playing.

"Cancel", the back button, a tap outside the list or dragging it down close it
and change nothing: nothing starts. While a previous action is still starting, a
short message says so and the list does not open.

"Add to playlist" adds the item to the playlists of the chosen receivers that
already have a player. Only if none of the chosen receivers has a player yet,
YAACC starts a new player on each chosen receiver. A chosen receiver without a
player is skipped while another chosen receiver already plays.

The classic layout keeps its way: tick receivers on the receiver tab, they stay
selected, and the bottom of the content tab shows two read-only lines (the
selected server and the selected receivers).

## Playing content

YAACC is able to control multiple players at the same time.
For example you are able to stream an image show with background music
on your device, starting a movie on a TV or play music on a
smart speaker at the same time.

Each player is displayed in the player tab (classic layout). The new layout has
no player tab: every running player has a row in the mini-player (on the top level: in the Players section) and is listed in
the device sheet under the receiver it plays on. Depending on the content type
and if the content is played by YAACC itself or a network device, the player ui differs.

![browse_player](./screenshots/6.0.x/browse_player.png){:height="30%" width="30%"}

YAACC includes a player for music and image shows.
Videos are played using a third parties app on the device.
The video app will start automatically, if video content is selected for playing

![music_player](./screenshots/6.0.x/music_player.png){:height="30%" width="30%"}

![image_player_show_menu](./screenshots/6.0.x/image_player_show_menu.png){:height="30%" width="30%"}

### Receiver controls

The receiver tab shows all available UPnP/DLNA renderers in your network, including your device.
Each renderer displays:

* Current playback status (PLAYING badge when active)
* Track title and album art (when available)
* Play/pause/stop controls
* Volume control
* Mute button

![browse_receiver](./screenshots/6.0.x/browse_receiver.png){:height="30%" width="30%"}

You can control playback directly from the receiver tab without opening the player view.
The controls work for both local playback and remote UPnP devices.

In the new layout there is no receiver tab: the top level lists the receivers below the servers, and the receiver bar
below the content of a server (or selected with the D-pad) opens the device sheet. The classic layout does not
have the sheet; use the receiver tab and the player tab there.
The sheet is titled "Receivers". It lists every receiver with its status, play/pause/stop, volume and mute controls,
and under each receiver the YAACC players currently running on it, in the order they were started.
It has no selection checkboxes: receivers are chosen when you start something,
see [Choosing receivers](#choosing-receivers-new-layout).
Players on a device that is no longer in the receiver list are shown at the end.
Tap a player to open its player view.

* On phones and tablets the sheet opens at half height. Drag it up for full height;
  drag it down or press Back to close it.
* On Android TV, or when using a D-pad, it opens at full height with the first receiver focused.
* While the sheet is open, receiver status and the list of running players update live.
  Status polling stops when the sheet is closed or the app goes to the background.

### Repeat and shuffle

Every player has its own repeat and shuffle buttons: the music player, the player of a
receiver (AVTransport) and the player for videos and mixed content have them next to the
previous/next buttons (the image viewer in its menu bar).

* **Repeat** cycles *off* (the playlist is played once, then the player stops), *all*
  (starts again with the first item after the last one) and *one* (the same item is
  played again and again; the next and previous buttons still skip to another item).
  The symbol shows a "1" for repeat one; a symbol drawn half transparent means off.
* **Shuffle** plays the items in a random order, every item once per round. Switching
  it on or off while playing keeps the current item; previous goes back through the
  order that was played.

With shuffle on, the playlist dialog lists the items in the order they will play, with the playing item marked at its place; dragging an item changes the play order and leaves your list as it is, deleting removes the item from both. Without shuffle it shows the list as before.

The settings *Repeat for new players* and *Shuffle for new players* (see [Settings](settings.html); they
were called *replay playlist* and *shuffle music*) only give the mode a new player starts with. They are not a switch
for all players: every player, also one that Home Assistant controls, has its own mode. A change with the buttons belongs to that player
only: it is not written to the settings and ends with the player. The mode of the
player of YAACC's own media renderer can also be changed by a control point
(UPnP *SetPlayMode*, for example the repeat and shuffle services of Home Assistant), and a change
with the buttons is reported back to the control point.

### Lock screen controls

When playing media locally, YAACC provides lock screen controls:

* Play/pause/stop buttons
* Next/previous track
* Track metadata display
* Album art
* Hardware volume buttons control playback volume

## Live streaming (Android 10+)

YAACC can stream your device's screen and system audio to UPnP/DLNA renderers in your network.

### System audio streaming

Stream all audio playing on your device to network speakers or receivers:

* Captures system audio (music, podcasts, games, etc.)
* 44.1kHz stereo, high quality
* Works with most UPnP audio renderers
* Multiple simultaneous clients supported

**Note:** Some apps may block audio capture (DRM-protected content like Spotify, YouTube Premium).

### Screen casting

Stream your device's screen to TVs or displays:

* 720p video at 15fps
* MJPEG format for broad compatibility
* Works in web browsers and some UPnP video players
* Useful for presentations and screen sharing

**Note:** the screen is sent as MJPEG (a stream of JPEG pictures), which not every receiver can play. The FCast desktop receiver
(receiver 3.0.4, GStreamer 1.29.2) refuses it with "Internal data stream error", with the link of the server and with the link of
the card "Play from this device" alike (tested 2026-11-23); the device audio stream plays there. Try the screen cast with your own
receiver; H.264 is not offered (a possible later enhancement).

### Enabling live streaming

1. Go to the Server tab (in the new layout: the Servers screen)
2. Find your local device in the list
3. Tap the audio or video button
4. Grant the microphone permission (needed to capture the device audio; the app records no microphone sound) and then the screen capture permission when prompted. If you deny either, the stream buttons stay off
5. The stream appears in the "Live Streams" folder

Access streams via UPnP or directly:
* Audio: `http://<device-ip>:49157/live/audio`
* Video: `http://<device-ip>:49157/live/video`

### Sending a live stream to a receiver

The card "Play from this device" has the same two live stream buttons (device audio and screen). A tap sends the stream to a
receiver in one step: the list of receivers opens (a screen cast is offered to receivers that can show video), and after you
confirm, the stream plays there. As with the files you pick on this card, the receiver gets a short-lived link of its own: it needs
neither the UPnP server switch nor the media provider, only the receivers you chose may open it, and it ends with the play. The
capture of the device audio or of the screen runs as long as that link lives; the system asks for the microphone and screen capture
permission first if they are not given yet. The buttons of the server card are not changed by it. If the server serves the stream
already (the UPnP server switch, the media provider and the stream button of the server card are on), the receiver gets the
server's link, the one listed in the "Live Streams" folder.

## Storage Access Framework (SAF)

YAACC supports browsing files from external storage, USB drives, and SD cards using Android's
Storage Access Framework. This allows sharing media from any storage location accessible on your device.

### Configuring SAF folders

In the server control view, you can add SAF folders to share:

1. Tap "Add SAF folder"
2. Select the folder using Android's file picker
3. Grant permission when prompted
4. The folder appears in the server content directory

![server_control](./screenshots/6.0.x/server_control.png){:height="30%" width="30%"}

### New files and folders

New files and folders inside an added SAF folder appear by themselves; you do not have to add the folder again. While the local server runs,
YAACC looks at the added folders (and at the folders your control points opened lately) every 30 seconds and tells the connected control points when something changed,
at most once every 15 seconds, so a download that is still running does not flood them. A new file or folder is therefore shown after about 30 to 45 seconds.
If a control point (media player, Home Assistant ...) still shows the old content after that, refresh the folder there or open it again; not every control point reacts to the message.
Changing which folders are ticked in the server settings is not announced; refresh the control point after that.

### Performance optimization

YAACC caches metadata (duration, MIME type, file size) for faster browsing:

* First browse: Extracts metadata from files
* Subsequent browses: Uses cached data
* Cache persists across app restarts
* Clear cache button available in server control view

The cache significantly improves browsing performance, especially for large media collections.

## Battery optimization

YAACC includes several battery-saving features:

### Smart WiFi lock management

The WiFi lock is only held when actually needed:

* Released when app is backgrounded (if server/renderer disabled)
* Released when no active streaming
* Automatically re-acquired when needed


### Smart foreground service

The player service intelligently manages foreground state:

* Foreground only when actively playing media
* Stops foreground when paused (notification hidden)
* Service stops completely when no players active


## Modern architecture

YAACC uses modern Android APIs for better performance and system integration:

* **Media3/ExoPlayer** - Modern media playback engine (replaces deprecated MediaPlayer)
* **MediaSession** - System-wide media controls and lock screen integration
* **Improved UPnP stack** - Enhanced reliability and network state handling
* **AudioPlaybackCapture** - System audio streaming (Android 10+)
* **MediaProjection** - Screen casting support

## MusicCast devices

YAACC recognizes receivers that are Yamaha MusicCast devices and offers a few extra controls on their rows (in the device sheet of the new layout and on the receiver tab of the classic layout). Other receivers are not changed in any way. A device counts as a MusicCast device when its UPnP description announces the address of the Yamaha Extended Control (YXC) interface; nothing is configured by hand. YAACC only works with devices that are already set up in your network (with the manufacturer's app); it does not add a device to the network or remove one.

### The row

Besides the usual status and controls, the row of a MusicCast device shows whether it is on, in standby or not reachable, its current input, and, while it is in a group, "Linked with <name>" (a group leader shows the names of its members). The row follows the device within a moment, also when you change something with another app or the remote control: the devices report changes by themselves, and YAACC reads them completely every 30 seconds in addition.

**The power button.** A device that offers power (the main zone lists `power` in its features; the captured devices all do) has a power button in the title line of its card, next to the check box. The symbol is full when the device is on and dimmed in standby or when its state is not known, and it says its state aloud ("Power: Kitchen", "On", "Standby" or "State unknown"). A tap reads the state of the device again first and then does the opposite of what the device says now (so a card that was a moment behind does not switch the wrong way), waits a moment and reads it once more; the card follows the device's own events. While the change runs the button is disabled; a second tap is ignored. If the device does not answer, does not say whether it is on, or does not accept the change, a message says so and nothing is changed. Devices that do not list `power`, and a device that has not told its features yet, show no button. The same button is on the cards of OpenHome devices (see [OpenHome](#openhome)).

### The device's own sources

While a MusicCast device plays something of its own (net radio, a streaming service, a server on the network, the FM or DAB tuner) and YAACC plays nothing on it, the row shows a second line with what is playing (station, title, artist, or the frequency or service of the tuner) and adds controls for that source. Only what the device reports for the current source is shown:

* Play, pause and stop on the row work on the device's source. While YAACC plays or has paused something on the device, they stay with YAACC's own playback and the controls of the source are hidden.
* Previous, next, repeat and shuffle appear when the source supports them (net radio, for example, has only play and stop).
* Tuner: the two buttons step the FM frequency by one step; a long press scans to the next station (a tap stops the scan). A switch with both names, FM and DAB, changes the band; with DAB the two buttons select the previous or next service.
* The star button opens a sheet with the device's presets (the stored stations of the net source and of the tuner) and its recently played entries. Empty slots are not shown; a tap recalls the entry, which also switches the device to its input.

The controls act on the device only after YAACC has read its current state again, so a stale display does not send a wrong command.

### Linking devices

The link button on the row opens a list of the other MusicCast devices in the same style as the receiver picker.

* Tick the devices that should play together with this one and confirm. This device becomes the group leader and the ticked devices follow it; they play in sync.
* On the row of a leader the button opens the list again with its members ticked: tick more devices to add them, untick some to remove them, untick all to end the group.
* The button is disabled on a device that is a member of someone else's group; change the group on the leader's row. Linking is also refused for devices that are already in another group (they are listed as "in another group") or that do not answer.
* A device that is removed from a group is put back on the input it used before it was linked, if YAACC has seen that input. If YAACC never saw an earlier input, the device stays on the link input (the row says "No group, still on the link input") and you choose its input in the device settings.
* Changing a group interrupts what is playing on the devices concerned.

### Playing on a group

When you play on a device that is a member of a group, YAACC sends the media to the leader of the group instead and tells you so ("Kitchen is linked with Living room. Playing on Living room."); the members play in sync with the leader. This also applies to files and folders picked with [Playing files from this device](#playing-files-from-this-device). In the receiver picker such a member is marked "plays via <leader>". If the leader of a group cannot be determined clearly, YAACC does not guess and asks you to play on the leader.

### Volume

The volume slider and the mute button of a MusicCast device use the device's own interface, because the generic UPnP volume control does not work for devices that are in a group. Every device keeps its own volume. The devices do not pass the volume of the leader on to the members by themselves. A leader therefore has an additional slider "Group volume": it shows the average of all members and moves every member by the same number of percentage points while you drag (each member stays within 0 to 100 %; dragging back restores every member's own level).

### Device settings

The settings button on the row opens a sheet with what the device reports as changeable: power, input, sound program, sleep timer, a three-band equalizer, the brightness of the display, the automatic standby and the link settings (audio delay, audio quality). Entries a device does not offer are not shown, and the sheet only offers the values the device reports. The sheet says so when the settings cannot be read or changed.

### Limits

* MusicCast devices only; no MusicCast account, streaming-service login, network or firmware setup, clock or alarm.
* Play, pause and stop on the row are those of the UPnP renderer while YAACC plays on the device; otherwise they control the device's own source. Searching and choosing content inside a streaming service or the net radio directory is not part of YAACC; use the presets or the manufacturer's app for that.
* Updates reach the app as UDP messages from the device to the phone. If the Wi-Fi isolates devices from each other, or the phone's Wi-Fi is in power save, they may not arrive; the row is then updated by the 30 second read.
* YAACC talks to a device only at the address it announced in your network, and shows everything a device sends as plain text.

Yamaha and MusicCast are trademarks or registered trademarks of Yamaha Corporation. OpenHome is the name of an open specification for networked music players and is used only to describe compatibility. YAACC is an independent project and is not affiliated with, authorized or endorsed by Yamaha.

## Snapcast

[Snapcast](https://github.com/snapcast/snapcast) is an open source multi-room audio system: a server (snapserver) sends audio streams with a common buffer to any number of clients (snapclients) that play them in sync. The clients are organised in groups, and a group plays one stream. YAACC works with a Snapcast server that already runs in your network, in four ways:

* **Control.** Every group of a server appears among the receivers, and you control it from there: group volume and mute, the volume, latency and name of every client, the stream a group plays, and which clients belong to the group.
* **Play (on by default; an installation that stored "off" keeps it off).** YAACC plays music from the phone to a group: it adds a stream to the server while you play and sends the audio into it.
* **The renderer into a group.** The YAACC renderer, the one other apps and UPnP/OpenHome controllers play to, can feed a Snapcast group instead of playing on the phone, see [Snapcast as the output of the renderer](#snapcast-as-the-output-of-the-renderer).
* **The phone as a client (off by default).** The phone itself plays as a member of a group, in sync with the other clients, see [This phone as a Snapcast client](#this-phone-as-a-snapcast-client).

YAACC is never a Snapcast server. Unless you switch on the client, the phone does not play a Snapcast stream and the clients of a group are other devices. One receiver is one Snapcast group, and a group always lives on one server: YAACC has no way to move a client to another server (the phone's own client is the one exception, it can connect to another server, see below).

The Snapcast support was tested by the author on his devices with a snapserver, and in automated tests and the test rig. Other devices, servers and networks have not been tested. How well the phone's own client holds the sync depends on the phone and on the Wi-Fi, see [Snapcast limits](#snapcast-limits). Audio and control are not encrypted and not authenticated, see [Snapcast security](#snapcast-security).

### What you need

* A snapserver that your phone can reach in the network, with its TCP control interface switched on (port 1705 unless the server uses another one). A server that has authorization (a login) switched on is not supported. Neither the control port nor the stream ports are protected, so keep them inside a network you trust (or behind a VPN) and do not type in the public address of a server.
* **The ports the phone connects to** (TCP, from the phone to the server; a firewall on the server must let the phone through, see [Troubleshooting Snapcast](#troubleshooting-snapcast)):

  | Port | Used for | By |
  | --- | --- | --- |
  | 1705 | control (JSON-RPC): groups, clients, volume, streams | every Snapcast function of the app |
  | 4953 to 4962 | the audio that YAACC sends into the stream it adds (the first free port is used) | play to a group, the renderer output |
  | 1704 | the stream protocol: the phone plays the stream as a client | the phone's own client |

* For the play option and the renderer output the server must **allow adding a TCP stream source at run time** (a control client may add it with `Stream.AddStream`). YAACC adds one stream per server while it plays and removes it again. A server that refuses cannot be played to, and there is no fallback (see [Playing music to a group](#playing-music-to-a-group)).
* For finding a server by itself the server must announce itself in the network (mDNS, for example through Avahi); otherwise type its address in.
* For the phone's own client, the server needs nothing special: the phone connects to the stream port like any other client. A server that requires a login is refused.
* Tested with: Snapcast 0.35.0 (a build of its development sources from November 2026) in the Docker test rig of this project, one snapserver and two snapclients without sound output, see [The test rig](#the-snapcast-test-rig). Other versions have not been tested; the discovery also knows the service names of Snapcast 0.26 to 0.32. The app was tested on the author's devices with a snapserver. Limits: the stream YAACC sends is raw PCM, the phone's own client plays PCM and FLAC only, nothing is encrypted, and how well the sync holds depends on the device.
* Android: the app targets Android 13 (API 33) and runs from Android 8.1 (API 27). The phone's own client is a foreground service of the type media playback with a notification that stays while it runs; Android 12 and later may refuse to start such a service while the app is in the background (see [This phone as a Snapcast client](#this-phone-as-a-snapcast-client)).

### Adding servers

Open *Settings* > *Snapcast*:

* **Snapcast servers** opens the list of servers. Type a host name or an IP address with an optional port (`snapserver.local`, `192.168.1.20`, `192.168.1.20:1705`; the default port is 1705; an IPv6 address needs brackets when a port follows, `[fd00::5]:1705`; IPv4 numbers are written without leading zeros) and tap *Add*. Nothing but host and port is accepted: a text with `@` (user name and password) or a web address is refused, because YAACC stores no credentials. Up to 16 servers are listed in total; a server you typed in, or one that was found, and the same address twice are one entry. *Remove* removes a typed-in server and the groups of that server from your receiver selection; a server that is announced in the network right now cannot be removed (clear *Find Snapcast servers* instead).
* **Find Snapcast servers** (**on by default**) searches the local network for servers that announce themselves. What it does while the checkbox is on: it sends mDNS queries on the network (Wi-Fi, a cable or a VPN: whatever network the phone is on; it holds the multicast lock the system needs for that) only while a page that shows Snapcast receivers or the *Snapcast servers* window is open (this includes the first screen of a fresh install: the first *Servers* tab shows the receivers section, so the search starts there without a tap), and the receivers page then connects (control connection) to every server that announces itself in your network. **The announcements are not authenticated:** any device in the network can announce a server, so a look-alike "Living room" is possible (the first line of the group row shows the server's address). Clear the checkbox in networks you do not trust. Only servers at addresses of the local network (private, link-local or loopback addresses) on a port of 1024 or higher are accepted. With the checkbox off only the servers in the list (and the remembered ones, see below) are used and nothing is scanned. An installation that never changed the checkbox has it on.

**If the search does not find your server.** The search looks for the service that a snapserver announces for its control port: `_snapcast-ctrl._tcp` (Snapcast 0.33 and newer) and `_snapcast-tcp._tcp` (0.32 and older, which many distribution packages still have). It also listens for `_snapcast._tcp` (the stream port, every version), only to tell in the report below that a server announces itself but not its control port; such a server is never listed from that announcement. A server that does not announce itself at all can not be found: that is the case when mDNS or Avahi is off on the server (the Docker test rig has it compiled out) or when the router or access point does not pass multicast between the phone and the server. Then add the server by address.

Below the list of servers, *Snapcast servers* shows the **discovery report**: the state of the switch, of the Wi-Fi, of the multicast permission and lock, what is browsed and how many services each type reported (with the names of the system's failure codes), the servers listed, and the last events with their time. *Copy report* puts it on the clipboard, to paste into a report. It names the servers found and their addresses in your network and carries the manufacturer, model and Android API level of the phone; look at it before you paste it somewhere. On Android 13 and newer the copy is marked as sensitive, so the system keeps it out of the preview of the pasted text. While the window is open and the checkbox is on, the network is searched, so the report is live. Its last line, *reading*, says what the facts mean: for example that the switch is off, that the phone is not on Wi-Fi, that the system reports no Snapcast service at all (the server does not announce itself, or its announcements do not reach the phone), or that a server was found.

A server that holds a receiver you selected is remembered (its address only) so that the selection survives an app restart; it is listed again without a scan, also when the discovery is off. With the discovery on, a remembered server that is not announced any more disappears after the receivers have been shown for about half a minute.

### Groups as receivers

Each group appears in the receivers (the top-level page, the device sheet and the receiver tab of the classic layout) as *group name (Snapcast)*; a group without a name takes the name of its first client (its host name if that has no name either, and the label "Snapcast group" if there is nothing to take), and a group of a server that is not reachable gets *(Snapcast, offline)*. The row works like the other receiver rows, with these differences:

* The first line shows the server (its address), so you can see which server a group belongs to; the second line shows the stream the group plays, how many of its clients are connected (*Clients connected: 2 of 3*), the volume and *muted*. *server unreachable* means that the connection to the server is down (the group stays listed with what was known last, and is not removed at once); *stream unavailable* means that the stream of the group is not in the server's list or is disabled.
* **A client is listed once.** A Snapcast client keeps an entry on every server it ever connected to, so a client that moved (the phone, whose client id is the same on all servers, or a Raspberry Pi) used to show up on the old server as an offline leftover next to its live entry on the new one. The lists now show a client id **once**: on the server where it is **connected**; when it is connected nowhere, on the server where it was **last seen** (`lastSeen` of the server's status; the first server in the list on a tie; a server that does not say counts as the oldest). The offline copies on the other servers are left out of the group cards, the group sheet, the link picker and the counts (*Clients connected: 1 of 2*; the title of a card comes from its first shown client), and a group card whose clients are all shown on other servers is not listed. A server whose connection is down does not count as having the client connected (what it said last may be out of date). The name, volume, mute and latency you see are those of the server where the client is connected. If the same id is connected on two servers (it should not happen), it is shown on both. This is only what the lists show: nothing is deleted from a server, a hidden client stays in its group on its server and is never dropped when you link or unlink other clients (every change is computed from a fresh read of the whole server), and the group ids and the stored choices (the output of the renderer, the selected receivers) are unchanged. An offline leftover that is hidden on one server cannot be removed there from the app (there is nothing to tap); it shows again, and can be removed with *Remove from server*, when it is the entry that was seen last.
* The slider and the mute symbol of the row are the volume and the mute of the **group**; the mute is the speaker / muted-speaker symbol of the other receiver cards (also in the group sheet, for the group and for each client; it has a spoken description and says whether it is muted). Snapcast has no group volume of its own: the slider shows the average of the connected clients and moves each of them by the same number of points while you drag (each stays within 0 and 100, and dragging back restores their own levels). The mute is the server's group mute.
* The **link icon** of the row (the same icon as on a MusicCast row) opens the sheet that links clients to the group and unlinks them: it lists every client that **this server shows** (never a client of another server, and not an offline copy that another server shows; the clients of other servers cannot be linked, Snapcast has no way to move a client to another server), the members of the group are checked, and *OK* makes the group consist of exactly the checked clients. A checked client of another group moves into this group (the list says when its group would end because it loses its last client), an unchecked member leaves the group and gets a group of its own (it is not disconnected), and a client that is not connected can be linked too (marked *offline*). A group always keeps at least one client: the only member of a group is checked and disabled, and *OK* needs one check. The change is applied to the group as the server has it at that moment, so a client that another controller added while the sheet was open normally stays in the group (when the server cannot be asked again right away, YAACC uses the last state it knows, and then such a client can be dropped). The icon is disabled while the server does not answer.
* The **hardware volume buttons** control the Snapcast group of the row that has the focus (with a D-pad or keyboard) or that you touched last, as long as that row is on the screen (the receivers page of the default layout, the Receiver tab of the classic layout, or the device sheet): one press changes the group volume by 5 points (0 to 100; holding the button repeats). The up button on a muted group unmutes it, the down button on a muted group changes nothing. When no group was touched or has the focus, or the group cannot be controlled (the server does not answer, no client is connected), the buttons change the volume of the phone as before. A device sheet that is open only passes the buttons to its own rows, and the receiver picker ("Play on…") leaves them to the system. The volume buttons do not control other receivers this way.
* Play, pause and stop on the row act on YAACC's own player for the group (see below); they do nothing when YAACC does not play to the group.
* The settings button opens the **group sheet**: the group volume and mute, the **stream** of the group (a list of the server's streams; a disabled stream cannot be chosen, and YAACC's own stream is marked) and the **clients** of the group, each with its name, whether it is connected, its own volume slider and mute symbol, and a menu with *Rename* (an empty name falls back to the host name) and *Latency* (0 to 10000 ms, Snapcast's own per-client delay compensation). Which clients belong to the group is set with the link icon (see above). For a client that is **offline** the menu also has *Remove from server*: the server keeps an entry for every client it has ever seen (a phone that was reinstalled, a client that came under another address and so under another id), and such an entry stays in its group for ever. A confirmation names the client and says that this only removes its entry from the server's list; a client that is still around comes back by itself when it connects again (with its volume, name and latency forgotten). The app reads a **fresh status** from the server right before and removes nothing that this status shows as connected, nothing that is not in it any more, and never the client of **this phone's own Snapcast session** (the client: while a session runs or starts, its client is protected even if it looks offline). Two taps are one removal, and the same client is not removed twice within two seconds; a message tells why nothing was removed. Only the entry is removed: a client with a configuration of its own can reappear when it reconnects. **The entries of this phone.** The phone gives every server the **same client id**, so the app recognises its own client in the status of a server, also when the entry is offline. The row of the own client in the sheet is labelled *(This phone)*, has a **power toggle** (see below) and in its menu *Server* (the server the phone's client uses). An **offline** entry of this phone may be removed (it is a leftover of this installation and comes back with the next session); it is never removed while a session of the phone's client starts, runs or reconnects. Entries that earlier builds left on the servers (one per address used) are handled as described under *One client id for all servers* in [This phone as a Snapcast client](#this-phone-as-a-snapcast-client); *Remove from server* clears any other offline entry.
* **Power on the card.** The card of a Snapcast group has a **power button** in its title line only when the phone's own client is the group's **first member** (the one whose name titles the card) or its only member; no other Snapcast client or group card has one, because the Snapcast protocol has no way to disconnect or wake a client of another device. The button is **on** while the client has a session (connecting, playing or trying again after a lost connection), **off** otherwise, and says so aloud; a tap switches the client on or off the same way as the switch on the first page (**off disconnects the client from the server** by ending the session), a message tells why a start was refused, and the card follows the session within about two seconds. The card of an **offline** entry of this phone has the button enabled: *power on* connects the client. In the sheet the own row always has the same toggle, also when the entry is offline.
* **Server of the client.** The menu of the own row has *Server*: it lists **all** servers the app knows (typed-in, remembered and found servers of the Snapcast server list and the address the *Snapcast client details* used last; host first, then the cleaned name, at most 17), the current one marked. Choosing another server while the client is **on** ends the session and starts it again on that server (once; the state stays on); while it is **off** it only changes the next start. See *Which server the client uses* in [This phone as a Snapcast client](#this-phone-as-a-snapcast-client).

The rows follow changes that another controller makes on the server (the Snapcast web app, another phone): YAACC keeps one connection to the server open while the receivers are shown, and closes it about 30 seconds after they were hidden. Names that a server sends are shown as plain text, cleaned of control and invisible characters and cut to a length that fits.

### Playing music to a group

*Settings* > *Snapcast* > *Play to Snapcast groups* is on by default (an installation that stored "off" keeps it off). Choose a group as a receiver like any other (the "Play on…" list, or the selection in the classic layout) and play music. With the option off, a group is listed and controlled but playing to it only shows "Playing to Snapcast groups is not available yet". Only music is played to a group: when the content you play holds an image or a video, the "Play on…" list does not offer a Snapcast group (a folder or an item whose type is not known offers it, and video and images are left out with a message when the play starts). Playlist files (`.m3u`, `.pls`, `.asx`) in an album are not played (the other tracks are; if nothing playable is left, a message says so); streams in the HLS format (`.m3u8`) are not playlist files and still play. A track that cannot be read is skipped with a message that names it and the next one starts; after three unreadable tracks in a row, or when the last one fails, the play stops with a message. The same holds for music played on this phone.

What YAACC does when you play:

1. It asks the server, at runtime, to add a stream named `YAACC <phone name> <marker>`: a *tcp* source in server mode on the first free port from 4953 to 4962 that takes raw PCM (48000 Hz, 16 bit, stereo, no header). Nothing has to be configured on the server. It adds only this one stream per server, whichever group plays.
2. It puts the group on that stream and remembers the stream the group played before.
3. The phone decodes the music with the player of the app and sends the PCM, in real time, to that port of the server (the address of the control connection). The speaker of the phone stays silent and the volume of the phone is not touched; the volume slider of the row is the group volume.
4. When the play ends or you stop it, the group gets its previous stream back (if it is still on YAACC's stream; a group that somebody moved elsewhere in the meantime is left alone). YAACC removes its stream when you close the player, and after 10 minutes without audio (stopping only gives the group back its stream), as long as no group plays it. It removes only streams that carry its own name or marker: the stream it added, a stream with exactly that name that it found (also one that was there before it started), a stream of that name with another configuration when no group plays it, and tcp streams that carry its marker and that no group plays (leftovers of an earlier run). It never touches any other stream.

What to know about it:

* **Delay.** The snapserver buffers about one second (1000 ms by default) before its clients play, so pause, stop, seek and a track change are heard about a second after you press the button, and a little more because the phone has a small buffer of its own; the app says so in a message when the play starts. The audio is sent in real time and never ahead, so the delay does not grow while it plays.
* **Formats.** The stream is always PCM at 48000 Hz, 16 bit, stereo. The phone decodes every format it can play (YAACC does not send a compressed format to the server); other sample rates are converted to 48 kHz, mono is played on both channels and surround sound (up to 8 channels, for example 5.1) is mixed down to stereo with the low-frequency channel left out (it is somewhat quieter than a stereo original). A compressed format that a phone would normally pass on to an HDMI, USB or Bluetooth sink (AC-3, E-AC-3, DTS) is decoded on the phone for this player; a phone that has no decoder for a format plays that track silently for its length, without a message.
* **One feed per server.** A phone has one stream per server, so it can play to one group of a server at a time ("Another group of this Snapcast server is already being played from this phone"); groups of different servers can play at once. Another phone has a stream of its own.
* **If the connection to the server is lost,** the player stops with a message. A group may then stay on YAACC's stream (silent) until somebody moves it back, with the group sheet or another controller. The same happens when you remove the server in the settings while it plays: the feed stops at once, the group can stay on the idle stream.
* **If the server does not take the stream,** the play stops with the message "The Snapcast server did not accept a stream from YAACC" and a hint on a second line. The same message appears when the server refused to add the stream, when every port from 4953 to 4962 is already used by a stream of the server, when a stream with YAACC's own name but another configuration is in use, and when the server answers with another stream id. In the cases where every port is in use and where a stream with YAACC's own name has another configuration, the reason in brackets is the cause (the ports are in use; the name is taken), not the hint; the hint line below it does not help there. YAACC can only play to a server that allows a control client to add a TCP stream source at run time: Snapcast's documentation lists tcp among the source types that can be added that way and the test server accepted it, but other servers have not been tried, and there is no fallback for a server that refuses. The hint line in the app says what the server must allow ("The server must allow adding a TCP stream source at run time"); it does not suggest a workaround, because there is none that YAACC supports: it adopts a TCP stream source that was written into the server configuration only under its exact name (see above) and removes it again when the player is closed. The stream YAACC asks for is this URI (the name is cut down to letters, digits, space, `-` and `_`, at most 32 characters, from the setting *network name for local server*, default "Yaacc"; `<marker>` is 8 hexadecimal characters that YAACC made once for this installation):

  ```
  tcp://0.0.0.0:4953?name=YAACC%20Yaacc%20<marker>&mode=server&sampleformat=48000:16:2&codec=flac
  ```

  Stream names that start with "YAACC " and end with the marker are reserved for YAACC: it may remove such a stream, see the end of the list of what YAACC does above.

### Snapcast as the output of the renderer

This makes the YAACC renderer a flexible UPnP wrapper for a Snapcast server: any app or controller that plays to the YAACC renderer
(a UPnP/DLNA controller, Home Assistant, an OpenHome control point) is heard on a Snapcast group.

1. *Settings* > *UPnP server*: switch on the *media receiver service*, set *Output* to *Snapcast group*. If no group is chosen yet the chooser opens.
2. The chooser first lists the servers of the *Snapcast servers* list (typed in, remembered, found on the network; the address comes first, the name the network gave is shown after it). Choose one: YAACC connects to it, reads its groups (up to six seconds) and lists them with their member counts. Choose the group. What is stored is the id of the group, never its name; the server is remembered like the server of a selected group, so it is known after a restart.
3. Play to the YAACC renderer from any controller. The renderer feeds the group exactly like a group chosen in the app (see [Playing to a group](#snapcast)): the stream, the delay of about a second, the unencrypted audio. The connection for the audio is opened only when a play is requested.

* **Which clients play is your choice.** The renderer does not add this phone's own client to the group and does not start the Snapcast client; use the link sheet of the group to decide which clients are members.
* **Volume and mute** are those of the group (the same as the volume slider of the group row, which moves every connected member by the same number of points). A volume change made on the server (or in the group row) is reported to the controllers; reading never sets anything, so the two can not chase each other. **The control connection to the server of the group:** a controller's own *GetVolume*/*GetMute* call opens it (at most once in 20 seconds) and the first subscription to the renderer's events opens it once; it closes by itself about 30 seconds after the last such request, and the close, which is itself an event, does not open it again. So for a controller that is subscribed and does not ask, the connection is up when it subscribes and then not again until it asks or something plays. The OpenHome renderer looks at its volume once a second while it runs, but it reads the model without waking the connection: it opens the connection once when the renderer starts in group mode and when a play is requested, and otherwise does not hold or retry it while nothing plays. The price: the volume and mute that an OpenHome control point sees are the last ones this phone knows from the server (the model), which can be out of date until the next play or the next start of the renderer.
* **A server that does not answer** is waited for up to four seconds at a play; a play that comes within five seconds after such a failed wait is refused at once with the same message, so a controller that keeps pressing Play does not hold the transport for four seconds each time. Every Play and Stop is still a stream added to and removed from the server and a move of the group; YAACC does not limit that further.
* **Errors, never silence.** *No group chosen*, *the server is not in the server list*, *the server does not answer* (YAACC tries for about four seconds, because a connection may only be coming up), *the group is not on the server any more* and *video or images* give a message on the phone, no audio on the phone, and the transport state *STOPPED* for the controller. Errors only the feed knows (no free stream port, the server refuses the stream, another group of the same server is fed) are the messages of [Playing to a group](#snapcast).
* **Switching the output while playing** stops the renderer's running play on the other output (a play to the group closes the feed and gives the group its stream back; a local play ends); the next play uses the new output. A play you started in the app is not touched.
* **A damaged stored value** (an unknown output or a group id that is not valid) falls back to *This device* and the phone says so on the next play; the settings show a note under *Snapcast group*.
* **Not done:** video and images are not shown on the phone in this mode. Audio focus (pausing for a call or another app), the pause when headphones are pulled and a ramp of the gain at the start and at volume changes are not built (they belong to the Snapcast client). The hardware volume keys of the phone still change the phone's volume in this mode, which nobody hears; the volume row of YAACC's own renderer in the app is the group's volume (the row of "this phone" is the phone's).
* **A content without a type** (the controller's description has none and the address has no known file extension) is refused with a message of its own, like video and images are refused: a group plays music only.
* **Who can do what.** UPnP and OpenHome have no login: anything on the network that can reach the renderer can start a play there, and in this mode also change the volume and mute of the **whole group** (a flood of volume changes, and a flood of mute changes, each becomes one coalesced change on the server: one wanted value and one task per group, the latest wins, and the group card in the app uses the same path; a failure message appears at most every three seconds, the messages of refused plays too). The controller's Stop, Pause and Seek also reach a play of the same group that you started in the app (the group has one player; a play from the app and a play from a controller to the same group replace each other).

### This phone as a Snapcast client

With the option *Snapcast client* checked (*Settings* > *Snapcast*, off by default) this phone can play as a Snapcast client of a server: a foreground service plays the stream in sync with the other clients of its group. Audio and control are not encrypted, and the server and its controllers see the name of this phone, the name of your YAACC server with the app version (*Yaacc 6.1.0*) and a generated id that is the **same on all servers** (see [Snapcast security](#snapcast-security)). How well the sync holds depends on the device.

**What it plays.** The client connects to the stream port (1704) of a server and plays the codecs **PCM and FLAC** (FLAC is the codec Snapcast uses by default). A server that sends Opus or Vorbis (`opus`, `ogg`) is refused with a message that names the reason; those codecs are a possible future enhancement, not built. PCM is decoded in the app, FLAC by the phone's own decoder (a phone without a FLAC decoder gives a message). The server's volume and mute for the client are applied as a gain on the phone (the sliders of the group sheet and the card change them); there is no ramp, so a volume or mute change is a step. The latency of the client (the group sheet's *Latency*, and a field in the *Snapcast client details*) shifts the phone against the other clients, which is what a Bluetooth speaker needs: the delay of the route to a Bluetooth speaker is not measured.

**Which group it plays.** When a client connects, a snapserver puts it in a group of its own on its default stream (or keeps the group it had there). Nothing is heard on the phone until the group plays a stream that has audio: put the phone into the group you want with the link sheet and the stream picker of the group (or *Move my group to a stream* in the *Snapcast client details*, which asks before it changes the server), and again after the phone reconnected. YAACC does not do this by itself, also not for the renderer output (which does not add the phone to a group).

**How it keeps the sync.** The phone estimates the difference between its clock and the server's clock from time messages, places every chunk of audio at the time the server stamped it plus the server's buffer (one second by default) minus the latency, and keeps the output at that time: it pads or skips exactly at the start and after a gap in the stream, and while playing it drops or repeats single frames (at most one in 400, no change of pitch). A phone that is more than 300 ms late for several position reports in a row rebuilds its output queue (a short gap you can hear). The phone keeps about 250 ms of audio queued in front of the speaker (the field *write-ahead* in the details, 40 to 350 ms); a phone whose audio output needs more raises it by itself in steps of 40 ms (up to 350) when the platform reports that the output ran short. A stream that stops and starts again, a lost connection and a change of the server's clock make the phone place the audio anew. The *Snapcast client details* show the state, the numbers of the sync and a trace of events that you can copy for a report; a report names the server's streams and the group id and carries the phone model.

**Limits.** *How well the sync holds depends on the device and the Wi-Fi.* It was tried on one phone (Android 16) with a snapserver and gave continuous playback with the default write-ahead; how closely it stays with another client over a long time, and how it behaves on other phones, routes and networks has not been measured. What is known to matter:

* *The output latency of the phone.* The client relies on the position that the audio output reports; phones differ in how exactly they report it, and a route that reports nothing (Bluetooth is expected to be one) is not corrected except by the latency field. Without a position the client gives up after five seconds with a message.
* *Wi-Fi power saving and Doze.* While it plays, the client holds a partial wake lock and a Wi-Fi lock (both only while it plays, plus a minute of grace after a start or a pause of the stream; they are released when it does not play), and a session that has played no audio for 15 minutes ends by itself with the notice "no audio for 15 minutes". Up to Android 13 the Wi-Fi lock keeps the radio out of power save. **From Android 14 no Wi-Fi lock works with the screen off** (the system replaces it by a lock that is active only while the screen is on and the app is in the foreground), so with the screen off the Wi-Fi may save power and the audio can stutter or the connection can drop. If that happens, try the phone's own settings: battery saver off, the app not restricted in the battery settings, Wi-Fi power saving or adaptive connectivity off. During a long pause of the stream the phone may sleep and the connection may need a reconnect; the client then places the audio anew.
* *The deep-buffer output.* Many phones play through an output that wants about 130 ms of audio queued; a client that keeps less would break up regularly. That is why the default write-ahead is 250 ms, and why the adaptive raise exists; a phone that needs even more than 350 ms is not covered.
* *FLAC.* A FLAC stream is placed by what the decoder returns, and a stall of the server's source or a lost chunk is corrected by looking at the position inside the FLAC frames; on a link that loses chunks regularly PCM holds up better than FLAC. FLAC data is parsed by the phone's native decoder, which is the one part of the client that is not Java: do not connect to a server you do not trust.
* *Not built.* The client does not take the audio focus (a call or another app does not pause it) and does not pause when headphones are pulled (the sound moves to the speaker).

**One switch, one state.** Whether the client is *switched on* is one stored choice that survives the end of the app. It is set from four places, and all of them show the same state:

* **The switch on the first page.** In the bottom bar, above the *UPnP server* switch, there is a row *Snapcast client* with a switch and a settings gear, built like the *UPnP server* row: the label, no line of text under it. The state in words (*Off*, *Connecting…*, *Playing*, *Connection lost, trying again…*, or *Stopped: reason*, also a restore that failed at the start) is read aloud with the switch (its state description) and shown in the *Snapcast client details*; a start that is refused is a message when you tap. The gear opens the settings scrolled to the entry *Connected to the Snapcast server*. The row is shown only while the option is checked **and** at least one server is known (see *Which server the client uses* below).
* **The checkbox *Connected to the Snapcast server*** in *Settings* > *Snapcast* (visible while the option *Snapcast client* is checked), with the summary *This phone is connected to a Snapcast server and plays as a client.* or *This phone is not connected to a Snapcast server.*
* **The power button on the card of the phone** (a Snapcast group card whose first member is the phone's own client, and the own row in the group sheet; see *Snapcast groups*): a tap switches the client on or off. Off ends the session, which is the disconnect.
* **The Snapcast client details** (the entry in *Settings* > *Snapcast*, a sheet for troubleshooting): *Start* switches the client on with the address in its field (that address is the server from then on), *Stop* switches it off; it shows the same state line as the first page.

**Which server the client uses.** The client has no server setting of its own. It connects to the server it was **last connected** to (remembered when a session first reaches the connected state, also when you choose a server on the card or start the client in the *Snapcast client details* with an address); if it was connected to none, or that server is not known any more, to the **first** known server (the order of *Choose server*: the address the *Snapcast client details* used last, the typed-in servers, the remembered ones, the found ones). Known servers are the ones of the Snapcast server list, so the client is offered as soon as one server is known, and an address typed in the *Snapcast client details* counts as known. There is **no automatic failover**: when the server does not answer the client keeps trying it, and you choose another server on the card of the phone (*Server* in the menu of its row in the group sheet), which restarts the session on it while the client is on. Only the host is used; the client connects to the stream port 1704 (a server on another stream port is typed in the *Snapcast client details*).

**One client id for all servers.** The id in the Hello of the client (`yaacc-` and 16 hexadecimal characters) is a one-way function of a random secret of this installation and of nothing else: it is the same for every server, also for a public address, and it does not change when a server is reached under another name, port or address. It is not a hardware id and does not contain one (the MAC in the Hello is the fixed `00:00:00:00:00:00`). **The consequence:** any server and its controllers can recognise that the same phone connected, and two servers can tell that they saw the same phone; the name sent as the host name is still the name of the phone for a local address and the generic *YAACC* for a public one. The *version* in the Hello, which a server shows in its client list, is the name of your YAACC server (*Settings* > *UPnP server*, default *Yaacc*) and the app version, for example *Yaacc 6.1.0*; for a public address it is *YAACC 6.1.0* without your server name. The text is cleaned (no control characters, at most 40 characters). The app uses the id to recognise its own client in the status of a server (the label *This phone*, the power button on its card, the removal rule above). **Migration:** earlier builds gave every server an id of its own (derived from the secret and the address the client connected to), so those builds left an offline entry of this phone on each server (one per address used). The app computes these old ids again from the secret of this installation (for every known server with the ports 1704, 1705 and the one of its list entry, and for the address of the *Snapcast client details*) and treats them as **this phone**: the lists show the phone **once** (connected wins, else the last seen), the other copies, also on the same server, are left out of the cards, the counts and the member lists, and a card that held only an old entry is gone. In the sheet an old entry that is still the one shown reads *This phone, old entry*. They can be deleted for good: the menu of the phone's own row has *Remove old entries of this phone* when the server has offline ones (a confirmation says how many; connected entries and the current one are never touched, nor are entries of other YAACC installations or of other devices). An old entry whose address the app can not reproduce (for example a host name that you typed in the *Snapcast client details* for a server the list knows by number) stays a normal client; *Remove from server* clears it. A reinstall or cleared app data makes a new id, and the old entries of the earlier installation are then no longer recognised.

**On** saves the choice first and then starts the client. The option and the server are checked again at every start; if the start is refused (the option is off, no server is known or its address is wrong, or the system did not allow the start) a message gives the reason, the choice **stays on**, and the state shows *Stopped* with the reason of the last end. There is no retry loop. **Off** clears the choice and stops the session (the end is recorded as a stop by you; the wake lock and the Wi-Fi lock are released as before; they are held only while the client plays).

**What a restart does.** Only your own off clears the choice: the exit button, the shutdown timer, the idle close, a crash or the end of the process do not. When the app starts, YAACC starts the client again if the choice is on, the option is checked, a server is known, no session runs and the last end is not a failure you have not seen yet (the state text or the *Snapcast client details* shows it, which counts as seen). This happens once per process, at the end of the normal startup (also when the system or a reboot created the process: with *autostart server on boot* checked the device start wakes YAACC and the same startup runs; with it unchecked nothing starts at the boot, and the client comes back when you open the app). Right after the exit button (the first two minutes) nothing is started until you open the app yourself. Android 12 and later may refuse to start a foreground service while the app is in the background; the start is then not retried in a loop, but once more when you open a screen of the app. A running client session keeps the app from closing itself (see the idle close above).

**What it does not do.** It does not start the client when you did not switch it on, after a force stop (the system sends no boot message to a force-stopped app), while the option is off, or when no server is known. It does not change anything on the server beyond what a client does (connect, play, report its volume). Not verified on a phone yet: the switch on the first page, the restore at the start and after a reboot with *autostart server on boot* checked on Android 16, and a start the system refuses in the background.

### Snapcast limits

* **No login.** A server with authorization switched on is not supported; the error of the server is shown.
* **No encryption.** Control (port 1705), the audio YAACC sends (ports 4953 to 4962) and the stream the phone's own client plays (port 1704) are plain TCP, see [Snapcast security](#snapcast-security).
* **A group is on one server.** YAACC cannot move a client to another server (Snapcast has no way to), rename a group, or control what a stream plays (it only lets you choose the stream of a group). The phone's own client can be switched to another server.
* **Codecs.** The phone's own client plays PCM and FLAC only; Opus and Vorbis are a possible future enhancement. The audio YAACC sends into a group is always raw PCM at 48000 Hz, 16 bit, stereo (the server encodes it for its clients).
* **Delay.** Everything that YAACC plays to a group is heard about a second late (the buffer of the server); pause, stop, seek and a track change are late by the same amount.
* **Sync of the phone's own client** depends on the phone and on the Wi-Fi: the output latency the phone reports, Wi-Fi power saving and Doze (from Android 14 no Wi-Fi lock works with the screen off), the deep-buffer output (adaptive write-ahead), a Bluetooth route (use the latency field). See [This phone as a Snapcast client](#this-phone-as-a-snapcast-client). No measurement of the sync against other clients over a long time exists.
* **The server must allow adding a TCP stream source at run time** for the play option and the renderer output; there is no fallback.
* **Audio focus, headphones and gain ramp** are not built for the phone's own client, and the hardware volume buttons keep changing the phone's volume in the renderer's group mode.
* **One feed per server** from one phone at a time; the *Snapcast groups* that YAACC lists are the ones of the servers it knows, at most 16 servers and 64 groups are shown.
* The play option was tested on the author's devices with a snapserver. Not checked on a device: gapless changes between tracks, the delay, the quality of the conversion to 48 kHz, playing with the screen off for a long time, AC-3 and surround files, the notification and the audio focus of the player. Reports are welcome.

### Troubleshooting Snapcast

* **"Could not connect to the stream of the Snapcast server".** YAACC asked the server to add a stream and the server accepted it, but nothing accepted the TCP connection on the stream's port. The usual cause is a firewall on the **server**: ufw, firewalld, or a Docker container whose ports are published for 127.0.0.1 only. These TCP ports must be reachable from the phone: **1704** (stream), **1705** (control) and **4953-4962** (YAACC's feed streams, the first free one is used). Example for ufw on the server:

  ```
  sudo ufw allow 1704/tcp
  sudo ufw allow 1705/tcp
  sudo ufw allow 4953:4962/tcp
  ```

  For Docker publish them for the network, not for loopback (for example `-p 4953-4962:4953-4962`, not `-p 127.0.0.1:4953-4962:4953-4962`). The debug log of the app (logcat, tag `de.yaacc.player.SnapcastPlayer`, prefix `feed:`) has the line `connect failed: <exception class> (port <n>)`, without the address and without the text of the error: `ConnectException` means the port was refused or nothing listens, `SocketTimeoutException` means a firewall drops the packets.
* **A group is listed, but playing to it says the server did not accept a stream.** See [Playing music to a group](#playing-music-to-a-group): the server must allow adding a TCP stream source at runtime.
* **The phone's own client does not connect, or connects but nothing is heard.** The phone connects to the stream port **1704** of the server (not 1705): it must be reachable like the ports above. If the *Snapcast client details* say the client is connected but no audio arrives, the server has put the phone in a group of its own on a stream that is idle: link the phone to the group you want and give that group a stream with audio (see [This phone as a Snapcast client](#this-phone-as-a-snapcast-client)). If the audio stutters with the screen off, see the limits of Wi-Fi power saving there. *Copy trace* in the details gives the numbers for a report.
* **A server is not found.** See *If the search does not find your server* and the discovery report under [Adding servers](#adding-servers); a server that does not announce itself has to be added by address.
* **The phone appears twice, or as an old entry, in the groups of a server.** Earlier builds gave the phone's client a different id for every server; those entries stay on the servers as offline entries and are removed with *Remove old entries of this phone* in the menu of the phone's own row of the group sheet (see [This phone as a Snapcast client](#this-phone-as-a-snapcast-client)). Other offline clients are removed with *Remove from server*.
* **The group is not on YAACC's stream any more, or stays silent after an error.** After a lost connection, a removed server or an exit while a server did not answer, a group may stay on YAACC's idle stream; move it back with the stream picker of the group sheet or another controller.

### Snapcast security

The connections YAACC uses to a snapserver (the raw TCP control port 1705, the TCP ports 4953 to 4962 that YAACC feeds, and the stream port 1704 that the phone's own client plays) are neither encrypted nor authenticated (a server can offer an encrypted web interface, which YAACC does not use, and it can require a login on the control port, which YAACC does not support). What that means for you:

* **Control** goes to the server's control port in plain text, and anybody in the same network can do what YAACC does there. YAACC stores no credentials.
* **Audio** goes to the server unencrypted, to whoever answers at the address of the server you chose, on the ports 4953 to 4962. For a server found by the discovery that is whichever host announces itself: announcements are not authenticated, any host of the network can announce a server, so a look-alike "Living room" is possible (that is why the first line of the group row shows the server's address). For a server you typed in with a host name it is whatever that name resolves to, also a public address: typed-in servers are not limited to the local network. The play option is **on by default** (an installation that stored "off" keeps it off), and it applies to every later play to a group you chose until you clear the checkbox *Play to Snapcast groups*.
* **The stream name discloses the name of your phone.** It contains the setting *network name for local server* (default "Yaacc"), also when the local media server is switched off, so every server YAACC plays to, and every controller of it, can read it. The marker in the name is an identifier of this installation, not a secret.
* **The client id of the phone is the same on all servers** (Snapcast client). The Hello of the client carries a generated id (`yaacc-` and 16 hexadecimal characters, a one-way function of a random secret of this installation; no hardware id, the MAC is the fixed `00:00:00:00:00:00`), the name of the phone (the generic *YAACC* for a public address), generic program and system words (*YAACC*, *Android*, architecture *unknown*) and a version text made of the name of your YAACC server and the app version (for example *Yaacc 6.1.0*; *YAACC 6.1.0* for a public address). The server shows the version text in its client list. Every server, also one at a public address, and every controller of it can read them, and can tell that two connections, also on two servers, came from the same phone. Earlier builds used an id per server; that was dropped so that the app recognises its own client everywhere (the entries those builds left on servers are merged into the phone and removed with *Remove old entries of this phone*).
* While YAACC's stream exists, the server listens on its port on all its interfaces, and the port has no authentication either: a host that can reach it can feed audio into that stream. This is a property of Snapcast's tcp source.
* **YAACC only removes or restores what carries its own name or marker:** a stream with its exact name or its marker that no group plays (a tcp source for the leftovers of an earlier run; a stream a user wrote under YAACC's exact name is removed as well), and a group's previous stream only when the group is still on YAACC's stream. Names that start with "YAACC " and end with the marker are reserved for YAACC.
* **Server text** (names of groups, clients, streams, errors) is shown as plain text, with control, invisible and direction characters removed and cut short; the group row shows the server's address first.
* **Any device that can reach the YAACC renderer can change the volume and mute of the whole group** while the renderer's output is a Snapcast group (UPnP and OpenHome have no login), and a controller can choose among the groups of your servers that the app knows through the OpenHome source list. Leave the output at *This device* if the network is not yours alone.
* **Stored on the phone only:** the servers you typed in, the receivers you selected (including the Snapcast groups), the address of a discovered server that holds a selected group (a remembered server), the output of the renderer (the id of the chosen group, never its name), and, once you have used the play option, the marker of the stream name. For the phone's own client: whether it is switched on, the server it last connected to, the last address typed in the *Snapcast client details*, the latency and write-ahead you entered, the reason of the last end of a session, and a random secret of this installation from which the client id is made (the secret is not sent). YAACC does not take part in Android's backup, so none of it is copied to the cloud or to another device.
* **Log:** at the debug level the log has states, counters and error kinds of the feed, but no names, addresses or text of a server.
* **By default** the play option is on, the discovery is on, and the phone's own client is off. With the discovery on YAACC sends mDNS queries on the network while a page that shows Snapcast is open (also on the first screen of a fresh install) and contacts every server that announces itself in your network, and announcements are not authenticated (see *Find Snapcast servers* above); clear the checkbox in networks you do not trust. With the discovery off and no server in the list YAACC does not contact any Snapcast server. A remembered server (see above) is contacted when the receivers are shown, also with the discovery off.

### The Snapcast test rig

For development there is a Docker setup with one snapserver and two snapclients (no sound output) in `tools/snapcast-rig/`, see [its README](https://github.com/tobexyz/yaacc-code/blob/develop/tools/snapcast-rig/README.md). Start it with `cd tools/snapcast-rig && docker compose up --build`; its ports are published on `127.0.0.1` only, so a phone reaches it through `adb reverse` or an emulator address. It is for trusted machines and testing only (no authentication) and is not part of the app.

Snapcast is free software under the GNU General Public License version 3 or, at your option, any later version. YAACC contains no Snapcast code: the control client is written from Snapcast's published documentation and behaviour, and the test rig builds the upstream sources at a fixed version when you start it. Snapcast is the name of an open source project of its authors; YAACC is an independent project and is not affiliated with, authorized or endorsed by the Snapcast project or its authors. The name is used only to say which servers YAACC works with, as plain text: YAACC does not show the Snapcast logo or icon, because it is not settled whether third-party apps may use it.

## FCast

[FCast](https://fcast.org) is an open casting protocol of FUTO with receivers for Android TV, phones and computers. YAACC is an FCast **sender**: it finds receivers, sends them the address of an item and controls the playback; it is never an FCast receiver.

### Finding and adding receivers

* **Finding.** *Settings* > *FCast* > *Find FCast receivers* (on by default) searches the local network by mDNS (`_fcast._tcp`) while a page with the receiver list or the list of FCast receivers is open, and nothing runs in the background. It sends mDNS queries on the network and lists every device that announces itself. The announcements are not authenticated: any device on the network can announce a receiver, so clear the option in networks you do not trust. Nothing is played to a receiver before you choose it.
* **Adding by hand.** *Settings* > *FCast* > *FCast receivers*: type an **address** (`host` or `host:port`, port 46899 by default; added at once, also if the receiver is switched off), the **name of the device** (what the receiver announces, for example `FCast-mycomputer`, or the computer's host name; YAACC looks among the receivers found, then asks the system's name lookup, then waits four seconds for an announcement; the message says what was tried) or paste a **connection link**. At most 16 receivers can be added by hand. The list shows every receiver, how it was found, and *Remove* for those you added; below it is the **discovery report** (what is browsed, what the system said, the state of the switch, Wi-Fi and multicast) with *Copy report*.
* **The QR code.** The receivers show a QR code with a link `fcast://r/...`. Read it with the camera or scanner app of your phone and open the link with YAACC (if another installed app also opens `fcast://` links, Android asks which one). A dialog shows the name and the addresses; *Add* tests the addresses and stores the receiver, *Cancel* changes nothing. The addresses of the network the phone uses are tried first, the others only if none answers. YAACC accepts only addresses of the local network from a link, and no CAMERA permission is needed. A link that is not an FCast connection link only shows "This is not an FCast link"; the same link twice within two seconds is ignored. If your scanner does not offer the link, copy its text and paste it in *Add receiver*.
* **Not accepted.** IPv6 link-local addresses (`fe80::`) need a network zone that cannot be stored: use the IPv4 or a global IPv6 address. A host name that resolves to a public address is not accepted.

### The row

A receiver is one card. It shows "FCast", the address, the state (not reachable, idle, playing or paused, with the title) and the volume. An idle card has no connection (YAACC connects when you use the receiver and closes it about half a minute later), so the state of a receiver that nothing plays to is shown only while a connection is open. Play, pause and stop act on the player of the receiver, and while nothing of this app plays to the receiver they act on what another sender plays (a second YAACC, the receiver's own app): pause, resume or stop is sent to the receiver. The card shows the receiver's state once a connection is open (after your first tap, or while the sheet is open). The volume slider works at any time (a connection is made for it); the mute symbol and the speed work while a player plays to the receiver. The gear opens the settings sheet.

### Playing

You play to an FCast receiver like to any receiver: pick it, then play music, video or pictures from a server, your files or a shared link. YAACC keeps the queue (next, previous, repeat, shuffle and the list) and sends the receiver one item at a time: the receiver fetches the item itself, so it must be able to reach your UPnP server or the phone. The next item starts when the receiver reports the end of the current one, and there can be a short gap. A picture stays for *picture stay duration*. Seeking, pause, resume, stop, the volume and the speed (0.5 to 2) are sent to the receiver; what another sender or the receiver itself does (pause, stop, volume) shows on the card and in the player within a moment, and a stop on the receiver stops the player. The volume of a receiver is not changed when an item starts.

### The settings sheet

The FCast protocol cannot read or change the receiver's own settings, so the sheet shows what can be controlled and known: the **device** (name, address, how it was found, the receiver app and version, the protocol in use: version 4 says where its key came from, version 3 says that it is unencrypted and, if the receiver offers version 4, what to do to use it; with version 4 also what the receiver says it can play), **playback** (volume; mute and speed while a player plays; stop), **tracks** (version 4, while something plays: the audio track, the subtitle track or *Off*, and *Add subtitle file* by a web address if the receiver takes subtitle files), the **connection** (state, *Check connection* with the time it took, *Reconnect*), **troubleshooting** (a trace of what the sheet saw, with addresses shortened to host and path; *Copy trace*) and **manage** (*Remove* for a receiver you added by hand).

### FCast limits

* Protocol version 3, and version 4 (TLS 1.3 and FlatBuffers) on Android 10 or later for a receiver whose key YAACC knows (see *FCast security*); a receiver of version 1 or 2 may work with fewer functions. Audio and subtitle tracks and subtitle files by address need version 4; the queue messages of version 4 are not used (YAACC keeps the queue).
* No playlist is sent to the receiver, no grouping of receivers, no screen mirroring, no key control of the receiver's screen.
* At most 64 discovered receivers are listed, at most 8 connections for cards and sheets are open at once, a connection link may have 4 KB and 16 addresses at most, and the trace of the sheet keeps 50 lines.
* The protocol has no mute: YAACC sets the volume to 0 and puts it back. A volume that another sender sets ends the mute.
* The receivers differ in how they report the end of an item. YAACC uses the end event, a paused state at the duration (the Android receiver) and, as a safety net, two seconds without an update near the end. A pause by another sender in the last second can look like the end. What a receiver does with a displayed picture is not known for every receiver.
* Tested by the author with FCast receivers on his own devices, in automated tests and with a test receiver. Other receivers, devices and networks have not been tested.

### Troubleshooting FCast

* **No receiver is found.** Check that *Find FCast receivers* is on, that the phone and the receiver are in the same network and that the network passes mDNS multicast (UDP 5353); many guest networks and some mesh systems isolate devices. Open *Settings* > *FCast* > *FCast receivers* and read the discovery report (Wi-Fi, multicast lock, the services seen). Add the receiver by address as a workaround.
* **The receiver is found but "not reachable" or the check fails.** The receiver's TCP port 46899 must be open in its firewall (`sudo ufw allow 46899/tcp`); on a computer with Docker or virtual machines its link lists several addresses, YAACC tries the one of your network first. The *Check connection* button of the sheet shows the reason.
* **The item does not play.** The receiver must reach the address of the item; a server that answers only the phone, an address of localhost, or a format the receiver cannot play shows as an error on the phone and the next item is tried (three failures in a row stop the player).

### FCast security

*Settings* > *FCast* > *Use the encrypted version 4* (on by default) turns the use of version 4 off. FCast version 4 is encrypted (TLS 1.3) and identifies the receiver by the fingerprint of its key. YAACC uses it on Android 10 or later for a receiver whose fingerprint it knows: from its **connection link** (the QR code on the receiver's screen, scanned with the camera app or pasted: the strong source, which a network attacker cannot change) or from the receiver's **announcement** (this keeps a listener out, not someone who answers the search with his own key). If the key does not match, YAACC does not connect and does **not** fall back to the unencrypted version: remove the receiver and add it with its link again if it was reinstalled. A receiver added only by its address or name, and every receiver on Android 9 or older, is spoken to in version 3, and the settings sheet says so.

FCast version 3 is neither encrypted nor authenticated. The commands and the **full address of every item**, including any user name or token in it, go in plain text to the receiver you chose, and anyone on the network who can reach a receiver can control it. YAACC sends no cookies or headers and stores no credentials, opens no listening port for FCast, accepts only addresses of the local network from a link or a name, and shows what a link adds before it adds anything. Discovered receivers can be spoofed by any host on the network. Use FCast only inside a network you trust, and do not type in a public address.

### The FCast test receiver

For development there is a small test receiver in `tools/fcast-rig/` that announces itself, logs what it is sent and answers like a receiver (no media player), see [its README](https://github.com/tobexyz/yaacc-code/blob/develop/tools/fcast-rig/README.md). The official desktop receiver on a notebook is the check for real media.

This application is an independent community implementation of the FCast protocol. It is not affiliated with FUTO. FCast is a registered trademark of FUTO.

## Sendspin

[Sendspin](https://www.sendspin-audio.com/) is an open multi-room audio protocol of the Open Home Foundation (used by Music Assistant, ESPHome and small players). YAACC is a Sendspin **server**: it plays the music of your queue to Sendspin players on the network. This application implements the Sendspin protocol and is not affiliated with the Open Home Foundation; Sendspin is a trademark of the Open Home Foundation. **This is a first, experimental version:** it was tested against the reference player of the project in a test rig, not yet on a phone with real players.

### Switching it on and adding players

* *Settings* > *Sendspin* > *Use Sendspin players* (**off by default**) lists the players you added and the ones found on the network as receivers and lets YAACC connect to them. YAACC only connects out; nothing listens on the phone.
* *Settings* > *Sendspin* > *Find Sendspin players* (on by default, but only while *Use Sendspin players* is on) searches the local network by mDNS (`_sendspin._tcp`) **while a page with the receivers is open** and lists every player that announces itself; nothing runs in the background. It sends mDNS queries and the announcements are not authenticated: anyone on the network can announce a player, so a look-alike name is possible (the card shows the address too). Only addresses of the local network are accepted (private ranges, no loopback, no public address), ports from 1024, and a WebSocket path that is valid (the announcement's `path`, `/sendspin` if it has none). A player that disappears stays listed for 30 seconds; a player you selected stays listed after a restart until it is announced again. At most 32 players are listed and connected to.
* *Settings* > *Sendspin* > *Sendspin players*: type the **address** of a player (`host` or `host:port`, port 8928 by default, for example `192.168.0.7` or `kitchen.local:8928`) and add it, or remove it. Use this for a player that does not announce itself or when the network does not pass multicast (Docker's bridge network does not).

### Playing

* The player appears among the receivers with its name, its address and its state. Choose it and play music (music only: no pictures or videos). Sound goes to the player; the phone stays silent.
* **Approval.** A player that does not know YAACC is not given audio until you approve it. **Playing to it is the approval**; it is remembered, and the card says "not approved yet" before. The protocol warns that such a connection is not authenticated: someone on your network could pose as the player or as your phone. Pairing (below) fixes that.
* **Pairing.** A player that does not admit unpaired servers is shown as "needs pairing: tap to enter its token". The player shows a **pairing token** (a text that starts with `SP:`; how depends on the player, for the reference player see its documentation). Tap the card, paste the token and tap *Pair*. YAACC checks that the token belongs to this player, pairs (the player and YAACC then share a long-term key) and the card says "ready, paired". From then on the player is used without the token, also after a restart. The token is a secret: anyone who has it can pair any server with the player, so keep it to yourself. A player that lost its pairing (for example after a reset) is shown as "forgot this server"; paste its token again. *Settings* > *Sendspin* > *Sendspin players* lists the paired players and the approved players (by the first characters of their id) and removes a pairing or an approval with the ✕ button. Pairing with a code (dynamic or static pairing code) is not supported; players that offer only that cannot be paired yet.
* **Other servers.** YAACC takes a player only while you play to it. Otherwise it keeps a quiet connection (so that the card shows the state and the volume) and does not take the player from another server such as Music Assistant. If another server plays to the player, the card says "in use by another server" and YAACC does not take it back by itself; play to it again to take it. A player that is held by another server which does not play may also show this; playing to it takes it.
* The volume slider and the mute symbol of the card are the player's own volume and mute (if the player offers them). Pause and seek work as for other receivers (a pause ends the stream, a seek clears what the player had buffered).
* The stream is PCM, 16 bit, stereo, at a rate the player lists. A player that offers only FLAC or Opus can not be used. Sync between several players, groups and linking are not part of this version; each player is played to on its own.

### Controlling a Sendspin server (Music Assistant and others)

YAACC can also be a *controller* of another Sendspin server: it shows what that server plays and sends commands, and plays nothing itself.

* *Settings* > *Sendspin* > switch *Control Sendspin servers* (off by default). Servers are found by the search (*Find Sendspin servers*, on by default, only while a receivers page is open and only addresses of the local network) or added by address under *Sendspin servers* (the default port is 8927). YAACC connects out to them; nothing listens on the phone.
* The server appears among the receivers as a **card** ("Sendspin server"): the text shows the name, address and what plays (artist and title). The play, pause and stop buttons, the volume slider and the mute symbol act on the group this phone is in on the server. The **gear** of the card opens a sheet with the cover of what plays (if the server sends one), previous, next, seek by 30 seconds, shuffle, repeat and the switch to the next group of the server. A control is enabled only if the server offers it. The card is not a place to play to: the "Play on…" list does not offer it (choose a player there).
* The server decides what this phone may do: a card that says "connected, waiting for the server to allow control" needs the server's operator to allow YAACC (in Music Assistant, its settings for players and clients).
* **Pairing as a client.** *Allow unpaired servers* (on by default) lets a server you added control and show without pairing; the connection is encrypted but does not prove who the server is. A server that insists on pairing is shown as "needs pairing: open the gear for the token". The gear of the card (or *Pairing…* in its sheet) shows **this app's pairing token** (a text starting with `SP:`): copy it, enter it in the server's settings as the pairing token of this client, then tap *Connect again*. The server and the app then share a long-term key (the Pairing PSK flow of the protocol, then a new key exchange inside the connection), kept for at most 8 servers; the card and the next connections use it without the token. The token is a secret (anyone who has it can pair a server with this app) and is used up by a pairing; *New token* makes another one and withdraws the old one; *Forget pairing* removes the key of that server. Tested against a fake server and the protocol's own key exchange in unit tests; **not tested with Music Assistant** (which pairing screen a server offers for a client depends on the server).
* Tested with the reference server of the project (it offers only the group switch, sends no cover and announces itself by mDNS); not tested with Music Assistant, and the search with the phone's network service was not run on a device.

**Playing, pausing, stopping.** While YAACC plays to a player, the phone holds a wake lock and a Wi-Fi lock (ExoPlayer's network wake mode), only while it plays; from Android 14 no Wi-Fi lock works with the screen off, so the stream can still suffer with the screen off on a weak Wi-Fi. A **pause** ends the stream but YAACC keeps the playback activity on the player, so no other server takes it in between. A **stop** (the stop button or the end of the queue) drops the activity, so that another server such as Music Assistant can use the player again; the next play declares it again. The lists *Paired players* and *Approved players* show the last name the player announced next to the first characters of its id.

**Linking players into a group.** The card of a player has a link button (as the MusicCast and Snapcast cards). It opens a sheet that lists the other players with a tick each; the ticked players play together with this one, in sync: one queue, one stream, the same timestamps. The card you opened is the **leader**: its text names the members ("leads a group with: this phone, wozi"), a member's text says "in the group of …" (the card of this phone too) and the members are listed directly under their leader: its slider and mute symbol are the group's (the slider moves every player by the same number of points the average moves; the card shows the average), and "Play on" a member plays on its group. Every player that is found can be linked; linking is the operator action that approves a player (as playing to it does), and a player that demands pairing is refused until it is paired. The group uses one PCM format that all members list (48000 Hz first, then 44100 Hz, then the highest other rate they share); a player without a common rate is refused. **This phone can be a member:** when the player of the phone is on (*Let servers play on this phone*), the sheet of another player's card also lists the card of this phone, so that the music plays on the other device and on this phone in sync (two phones, each on the music of the other: pair the other phone by its QR code, open its link button and tick *this phone*). The app's own hub dials its own player over the loopback address, which counts as the app itself: no pairing is needed for it, whatever *Allow unpaired servers to play on this phone* says (that switch is for the network). The card of this phone has no link button of its own, because it leads nothing. Open the sheet a moment before tapping *Link*: the connection to the own player is made when the sheet opens, and a *not known yet* message means it was not ready. A group has at most 8 players. A member whose connection breaks stays in the group and comes back into the stream by itself when it is ready again; a player you unlink stops at once. **Limits:** groups are not kept across a restart; players you link while a single player is already playing join the group at the next play (a group that plays takes new members at once); the sync between the players depends on their own clock sync and was only measured with the test rig's player, not heard with several real players; only music is played to a group, as before.

**Volume buttons.** With one active player of YAACC that plays to a Sendspin player (on the receivers page), the hardware volume buttons change that player's volume by 5 percent per press, as for FCast receivers; the card of a server is not a target.

**Gear on the cards.** Both cards have a gear button, like the FCast and Snapcast cards. On the card of a server it opens the sheet above. On the card of a player it opens a sheet with the player's address, whether it connected to this phone, whether the connection is unencrypted, whether it is paired, approved or not approved yet, its audio formats, and the buttons *Enter pairing token* (when the player needs it), *Forget pairing* and *Withdraw approval*.

### This phone as a Sendspin player (off by default)

*Settings* > *Sendspin* > *Let servers play on this phone* (**off by default**, independent of the other Sendspin switches) makes the phone a Sendspin **player**: Music Assistant or another server can play its music on it. A service with a notification (*Sendspin player*, with a *Switch off* button) keeps a listening socket open (port 8928, another port if it is taken) and announces the phone on the local network (`_sendspin._tcp`); a server that finds it connects, and the phone plays what it streams. **One server plays at a time**, decided by the rules of the specification for several servers: a server that declares the *playback* activity ranks above one that only holds a connection or pairs, and a new activation of an equal rank displaces the holder (it gets `another_server`); a server that loses is turned away (`concurrent_attempt`); a connection that does not activate within 30 seconds is dropped; with nothing on either side the server that played last wins (it is remembered). **One rule is not in the specification:** a server that is not paired can not displace a paired one that plays, so a stranger on the Wi-Fi can not take the music from your own server; it can still take it from another unpaired server. At most 3 servers are connected at once; only addresses of the local network are accepted, never the phone's own addresses on the network (the loopback address of the phone itself is the app's own hub, see *Linking*). The volume, mute and output delay a server sets are kept for the next run. A server that is not paired plays only if *Allow unpaired servers to play on this phone* is on (**off by default**, its own switch: the connection is encrypted but does not prove who the server is, so any device on your network could play on the phone). Without it only servers you paired play: the gear of this phone's card has a *Pairing token* button (copy the token and enter it in the server, for Music Assistant where it asks for a client's pairing token); the pairing then runs as for the controller. The token screen has a *Show QR code* button: the code carries the token verbatim (a scanner reads the text `SP:0…`), so another phone can scan it instead of pasting it. The token screen blocks screenshots, because the text and the code are a secret. The dialog of a player's token has a *Scan QR code* button: it opens the camera (the app asks for the camera permission only then; without it you can still paste the token), reads the code on the other phone and pairs as if the text had been pasted, with the same check that the token belongs to this player. Camera and permission are used for nothing else. The scanner library is ZXing (`zxing-android-embedded`). The token screen also names the short id of this phone's key; the message "belongs to another player" names the short id of the token and of the player of the card, so a token taken from the wrong phone is easy to spot. The sound is played by the engine of the phone's Snapcast client (exact start, drift correction by dropping or repeating single frames, the output's own position reports); **PCM or FLAC, 16 bit stereo at 48000 and 44100 Hz** (no Opus yet). The phone offers PCM first and FLAC second; the checkbox *Prefer FLAC for the phone as a player* (off by default) turns the order round: a server then sends FLAC (about half the size) when it can, and the phone decodes it with the platform's FLAC decoder, the same path as the Snapcast client. The header the server sends with a FLAC stream is checked (it must be a FLAC header of the same rate and channels, else the connection ends). FLAC is tested up to the engine (the real header and chunks of the reference server arrive and are taken); **the decoding itself was not heard with a Sendspin server** (it needs a phone). **Controlled from other devices (the controller role of the YAACC server):** with *Advertise this server* on, another Sendspin controller (another YAACC phone, or any client of the protocol that has the controller role) can connect to this phone's server: it sees what plays on this phone (title, artist, album, progress, cover), and plays, pauses, skips, seeks, sets volume and mute, repeat and shuffle, and switches between the groups that play. The group it controls is the one that plays (the last started if several); *switch* cycles through the groups. What it does is what the buttons of the app do. Who may: only **paired devices**, or any device when *Allow unpaired devices to use this phone* is on (**off by default**; one setting for every role of the phone, the player role included). To pair a device before it connects: *Settings* > *Sendspin* > *Sendspin players* > *Add a device by its pairing token* (enter the token the other phone shows, or scan its QR code). At most 4 controllers at once. **Tested** end to end on a computer between the two halves of YAACC (the server role and the controller client), with the volume rule of the protocol (what a player at a boundary could not take is shared out); **not tried on two phones, and not tried with a controller other than YAACC.** The wake lock and the Wi-Fi lock are held only while a stream plays; from Android 14 no Wi-Fi lock works with the screen off. The service starts again when the app starts (not after a reboot alone). **The card of this phone** ("… (Sendspin player, this phone)") is in the receiver list while the switch is on: its text says what the phone does (not listening yet, waiting for a server, connected to …, playing from …); its slider and mute symbol are the volume and mute of the phone as a player (a server that is connected is told); the gear opens a sheet with the connected servers (each can be disconnected) and *Switch the player off*. Nothing is played to this card: it is not offered in "Play on…". **Not tested on a phone or with Music Assistant.** The protocol part is tested with a fake server and against the reference server (it dials the phone in the test rig and its stream arrives); the sound path is tested with a simulated output; nothing was heard yet.

### Older players without encryption and players that look for a server (both off by default)

Some players do not wait for you to add them: they look for a server that announces itself and then connect to it (the Kodi add-on works like this), and some speak an older version of the protocol without encryption, which the specification YAACC implements does not define. Two optional settings under *Settings* > *Sendspin* make YAACC work with them. **Both are off by default** and both need *Use Sendspin players*.

* *Let players find this phone (listen on the network)*: **while a page with the receivers is open**, the phone opens a listening socket (port 8927, another port if that one is taken) and announces itself by mDNS as `_sendspin-server._tcp`. A player that connects appears among the receivers like the players you added (name and address; the address shows no port because it is the player that connected). Only addresses of the local network are accepted, at most 8 players at once, and a browser's cross-site connection (a web page with an Origin header) is refused. When you leave the receivers page the announcement and the socket stop; a player that is connected stays connected until its connection ends, and it has to connect again afterwards (YAACC never dials a player that connected to it).
* *Allow players without encryption (less secure)*: a player whose first message is the older unencrypted `client/hello` is accepted. **Nothing it sends or receives is protected, and the connection does not prove who the other side is**: anyone on the network can read the music and pose as the player or as the phone. As for every unpaired player, it gets no audio before you approve it, and playing to it is the approval. Use it only on a network you trust. Without this switch such a player is refused (the connection is closed before anything is read from it beyond the first message).
* The two switches are independent: the listener alone accepts only players that speak the encrypted protocol (a player that connected to you can be approved, or paired with its token like any other), the legacy switch alone lets you add such a player by its address. The older protocol offers PCM only (the Kodi add-on takes PCM 48000 and 44100 Hz, 16 bit, stereo); volume and mute are the player's.
* **Not tested with the real Kodi add-on or on a phone.** The code follows the add-on's own source and the reference library's behaviour and is tested with a simulated player. If the add-on does not find the phone, check that the announcement reaches it (mDNS must pass your router and the receivers page must be open on the phone) and that the add-on's own list of servers shows YAACC.

### Limits and security

* No groups yet; the search finds players, it does not trust them; the connection to a player is encrypted end to end (Noise protocol), but without pairing it does not prove who the other side is.
* The identity key of the phone as a Sendspin server is kept in the app's private storage (backup is off); wrapping it with the Android Keystore is still to do.
* The phone as a Sendspin **player** (other servers play to the phone) is not part of this version.
* A test rig with the reference player is in `tools/sendspin-rig/`.

## Media server

YAACC includes a media server service, which has to be enabled separately.
A switch for this is located at the bottom of the server list tab
(in the new layout: at the bottom of the Servers screen).

![browse_servers](./screenshots/6.0.x/browse_servers.png){:height="30%" width="30%"}

Depending on the configurations for the server in the settings,
the server service is used as media provider, media renderer or proxy.
The icons behind the server switch are showing which service is activated.

## Media provider

If the device is used as media provider, media files stored on the device
are accessible for other UPnP/DLNA devices in you network.

The shareable folders have to be configured using the server control view.
All included files and folders are accessible for other devices in your network.

The media of the phone are listed in the folders *Images*, *Videos* and *Music*. *Images* and *Videos* both
offer *All images* / *All videos* and *Galleries*: a gallery is one directory of the phone that holds images or videos
(for example *Camera*, *Download*, *Movies*), named like the directory. In the galleries, two directories with
the same name are told apart by a number (*Camera (2)*), files directly in the root of a storage volume are listed in a
gallery named *(root folder)* (in the language of the app), and the media paths of the server settings apply to the list of
galleries and to the images or videos in a gallery. The list of galleries is sorted by name without regard to the case, and a client that
asks for it page by page gets the same galleries as one that asks for all of them.
The media paths apply to everything the server answers: a client that guesses the id of an image, a video, an album or a
gallery outside them gets "no such object" (and no thumbnail or cover), and a media path is matched literally (a `_` or `%` in
the name is no wildcard). A folder you deselect in *SAF content* is no longer listed or sent, also not to a client that
remembers its ids.

![server_control](./screenshots/6.0.x/server_control.png){:height="30%" width="30%"}

The server control view is accessible through the settings button next to the server
switch at the bottom of the server list, or the YAACC server service notification.

## Media renderer

This service allows your device acting as an media renderer. Therefore the
device can be controlled by other UPnP/DLNA controllers in your network
and receive and play media from UPnP/DLNA servers.
Control points can set the play mode (*SetPlayMode*: `NORMAL`, `REPEAT_ONE`, `REPEAT_ALL`,
`SHUFFLE`, `RANDOM`; `DIRECT_1` and `INTRO` are accepted and played as normal) and read it
(*GetTransportSettings*, the `CurrentPlayMode` event). It is the repeat and shuffle mode of the
player the renderer plays on, see [Repeat and shuffle](#repeat-and-shuffle).

**Output.** *Settings* > *UPnP server* > *Output* (below the receiver switch) says where the renderer plays what other
apps and controllers send to it: *This device* (the default, nothing changes) or *Snapcast group*. With *Snapcast group* the renderer
only feeds the group chosen under *Snapcast group* (see [Snapcast as the output of the renderer](#snapcast-as-the-output-of-the-renderer)); the speaker of
the phone stays silent. This works for the UPnP renderer (AVTransport and RenderingControl) and the OpenHome renderer alike. The volume and
mute that a controller sets (*SetVolume*, *SetMute*, the OpenHome volume) are the volume and mute of the group, and the volume and mute it
reads and is told about are the group's. If the play can not go to the group (no group chosen, the server is not in the list, does not
answer, the group is gone, or the content is video or an image) the phone shows a message, nothing plays on the phone, and the
controller sees the transport *STOPPED*.

## Sharing to YAACC

Use the share menu of another app (gallery, files, browser, music player) and
choose YAACC to play something on the receivers in your network. Two kinds of
items are accepted:

* **A link.** The shared text is at most 2048 characters long and contains a link
  that starts with `http://` or `https://` and has a host. If an app shares a sentence
  with a link in it ("Look at this https://..."), the first link is used and the
  rest of the text is ignored. Text without such a link, other kinds of links and
  a shared `file:` link are not played; a short message says that YAACC only
  accepts audio, video and image files and web links. With the [media proxy](#media-proxy)
  switched on, links that point to this device itself (for example `localhost` or
  an address of this phone) or to a link-local address (`169.254.x.x`, `fe80::`)
  are not played; in the new layout the message "Could not play the shared link."
  appears.
* **One audio, video or image file.** Only a single file is accepted from the
  share menu, and the sending app must allow YAACC to read it. Several files,
  other file types and `file:` shares are ignored. To play several files or a
  folder, pick them in YAACC itself, see
  [Playing files from this device](#playing-files-from-this-device).

What happens next depends on the layout:

* **New layout.** The "Play on…" list opens (see
  [Choosing receivers](#choosing-receivers-new-layout)). Under the title it
  shows the name of the shared file, or "Link: <host>" for a link (the host of the
  link only, not the whole address, so you see which site is about to play). Nothing
  is opened, copied or played until you tick receivers and tap "OK". Cancel,
  back, a tap outside or waiting for two minutes discards the share. Only one
  share can wait at a time: a second one that arrives while the list is open or
  a share is waiting is ignored and a short message says so. If you rotate the
  device while the list is open, it closes and the share is discarded; share it
  again.
* **Classic layout.** A shared file opens the same "Play on…" list, with the
  receivers selected on the receiver tab already ticked. After "OK" the file plays
  on the ticked receivers and your previous selection is restored. A shared link
  plays on the selected receivers as before: YAACC waits a few seconds for
  receivers and, if none is found, plays it on this device. Normal browsing in
  the classic layout never shows this list.

A shared file is not copied when the sending app lets YAACC read it directly:
YAACC keeps it open and the built-in HTTP server hands it to the receivers, with
support for seeking. If the sending app provides the file only as a stream that
cannot seek, YAACC copies it into its private cache first (up to 512 MiB, and
only while enough free storage remains; the copy is deleted when the share ends
and leftovers are removed at the next start). Copying a big file can take a
while. If the file cannot be opened, the message "Could not play the shared
file." appears; "Another shared file is still being processed. Try again in a
moment." means the previous share has not finished.

A shared item (not files picked in YAACC, see below) is available to the receivers while a player is playing it and for
10 minutes after that player ended (renderers may still buffer or seek), but at
most 6 hours. Up to 8 shares can be active at once; if more are tried, "Too many
active shares. Try again in a moment." appears. Quitting YAACC ends all shares.
YAACC does not start or stop anything for a share and does not change your
settings: the built-in HTTP server runs whenever the app runs, whatever the
"local server state" setting says.

The address a receiver is given contains a random key. It is kept in memory only
and is never written to the settings. Who may use it is decided by the setting
[restrict the media proxy to the chosen receivers](settings/) (on by default).

## Playing files from this device

To play files or a folder of your phone on receivers without serving them
constantly, use the card "Play from this device" (with the phone icon) directly
below the servers in the server list. Tapping the card (or pressing the centre key of a D-pad on it) opens the file picker; the card also has two icon buttons:

* The **file** button opens the system file picker; pick one or
  more audio, video or image files.
* The **folder** button opens the system folder picker; the files
  directly inside the folder are used, sub folders are not. On Android 11 and
  later the system does not let you pick the root of the internal storage or the
  `Download` folder; pick a folder like `Music` or `Movies` instead.

Then the "Play on…" list opens (see [Choosing receivers](#choosing-receivers-new-layout);
in the classic layout the receivers selected on the receiver tab are ticked) and
"OK" starts the playback on exactly the ticked receivers. There is nothing to
browse: YAACC does not list the content of your phone, and the local server does
not have to run.

* Only audio, video and image files are played; other files are skipped. A
  folder is played in name order (numbers in numeric order: `track2` before
  `track10`), at most 500 files in all; a message says when files were skipped or
  could not be read.
* Audio, video and images go to their own players, which start together on each
  chosen receiver: a folder with songs, a video and photos starts the music
  player, the video player and the image show at the same time. A receiver that
  cannot play a kind fails for that player only.
* On other receivers the image show changes to the next image after the default
  duration of the settings (the same time as for images you start from a server;
  "never" keeps an image until you go on yourself). This phone's own image viewer
  uses the slideshow time of its own settings.
* This phone plays audio and images straight from the files you picked. For any
  other receiver (and for video on this phone) a file is offered on the network
  only while it is the file being played: it gets its own random address when its
  turn comes and the address stops working as soon as the player moves on, stops
  or is closed. Only the receiver that plays it may fetch it. A queue needs one
  such address per player at a time (kinds times receivers), and the active ones
  are limited to 8 in all, see [Sharing to YAACC](#sharing-to-yaacc).
* If a file cannot be opened any more (moved, provider gone) it is skipped; after
  three in a row the player stops and says so.
* The access the system gives YAACC to the picked files lasts until the device
  restarts. You can edit the playlist (remove, move) while it plays.
* The length of a file is read when its turn comes, so receivers can show it and
  the next file starts after it. If it cannot be read (the provider is slow or
  the file has no length) the receiver decides when a file has ended.
* Videos on this phone open in the video app.

## Media proxy

The proxy service is used in the context of sharing URLs with YAACC and
playing the URL on a UPnP/DLNA device in your network. Normally the
URL will directly passed to the rendering device. Sometimes the rendering
device is not able to play https URLs. Therefore we need to get rid of the
encryption and provide the content with an unencrypted http URL.
For this the proxy service can be used. In that case the URL shared with
YAACC will not directly passed to the rendering device. YAACC generates a
new http based URL which points to YAACC and send the to the receiver.
When the content is played YAACC fetches the data from the origin through the
encrypted https connection an passed the content unencrypted through the http
connection to the rendering device. Because the content then travels unencrypted
to the rendering device, enable the proxy only on a network you trust.

The proxy address of a shared link is random, belongs to that one share, lives in
memory only and expires as described in [Sharing to YAACC](#sharing-to-yaacc).
Earlier versions stored the shared links in the settings; YAACC removes those
entries when it starts. Shared files always use this mechanism, whether or not
the "media proxy service" setting is on; the setting only decides whether a
shared link is proxied or handed to the receiver directly.

The proxy is the SSL offload during sharing, and it needs the *UPnP server* switch on
the first page to be on: the proxy is the app's own http server, and with the switch off the
app does not serve a proxied link (its http server answers "403" for it). A shared link
with the proxy checked and the *UPnP server* switch off is therefore refused, and the app
shows a message that names the switch; switch the *UPnP server* on or uncheck the proxy in
the settings. With the proxy not checked a shared link is handed to the receiver directly,
whatever the switch says. Normal sharing works without the switch: a file shared from
another app or from the device is handed out with its short-lived address whether or not the
*UPnP server* switch is on.

By default only the receivers you chose may fetch a shared item from the proxy
(setting "restrict the media proxy to the chosen receivers"). If a receiver is
refused, see [Settings](settings/) for when to switch this off.

For renderers with "enable server-side seeking for external URLs" switched on
(settings, per renderer), a shared link that is proxied also supports seeking
through the proxy, see [Settings](settings/).

## Appearance and log

*Settings* > *Appearance* > *Theme* offers "System" (follow the system setting), "Dark" and "Light"; a fresh install
starts with "Dark". The app is gray scale in Material 3 style: check boxes, switches, sliders and progress bars are gray in both
themes. The dark theme has a pure black page with slightly lighter bars, cards and sheets; the light theme has a light gray page and
a light title bar.

The log opens from the overflow menu ("log"). It shows the entries of the level chosen in the settings
(*Appearance* > *Log level*), refreshes every two seconds while it is open and scrolls down by itself only while you are
at the end. The clear button hides all entries from before the moment you pressed it. The pause button stops the automatic refresh, so you can select and copy text; it starts again when you press it again.

## Permissions

YAACC asks for the permissions that this Android version can grant (the media permissions, on Android 13 and newer also the notification permission, on older versions the storage permission), and only for those that are missing. It asks once: a permission you declined is not asked again at the next start, and the partial photo and video access of Android 14 counts as an answer. You can grant a permission at any time in the system settings of the app. The request to exempt YAACC from battery optimization (the server and the players have to run with the screen off) is shown once, not at every start; if you declined, you can still exempt the app in the system settings.

## Shutdown timer

The shutdown timer stops all running players and the app after a certain time.
The timer is settable and can be enabled at the bottom of the server list tab and the remaining time
is displayed. In the new layout the timer is the symbol in the toolbar instead: tap it
while a timer runs to stop it (the remaining time is shown under the title of the toolbar); otherwise a tap opens the
duration picker, and confirming a duration above 0 starts the timer.

The back button never closes YAACC: the last back puts the app in the background and everything keeps running. The ways to end the app are the exit button (toolbar menu, and the notification action), the shutdown timer and the idle close below. The exit stops the players, the services and the notifications before the process ends (also the media notification of a player and a Snapcast play, which gives its group the previous stream back), and the app does not start again by itself afterwards: a restart that the system does within two minutes of an exit is ignored, and your own start of the app always works.

The shutdown timer is your own decision: it ends the app when it runs out, also while the media server, the receiver or a Snapcast session runs (a Snapcast client session that is ended this way is noted as ended by the app, and the *Snapcast client details* shows it afterwards). Apart from that, YAACC closes itself five minutes after its last screen went to the background, **unless** a player, a play to a Snapcast group or a Snapcast client session is running, the *UPnP server* switch on the first page is on (the media server, and with it the checked services *media receiver* and *media proxy*; those two checkboxes count only while that switch is on, because the service is stopped and serves nothing without it) or the setting *autostart server on boot* is checked (then YAACC is never closed automatically, even when every service is switched off: it only listens for devices). The five minutes also run when the app was started without a screen (after a restart of the device or a restart by the system) and nobody opens it; opening the app stops that timer, and it starts again when the last screen goes to the background.

*Autostart on boot* (*Settings* > *UPnP server*) is selectable at any time. Checked, the device start wakes YAACC, which then starts what is switched on (media server, receiver, proxy, and the Snapcast client if it is switched on), and the app is not closed automatically; not checked (the default), nothing starts at the boot. The setting switches the boot receiver of the app on or off; it is applied when you change it and at every start of the app, so an installation that had the setting checked before this version starts at the boot again after the app was opened once. The app targets Android 13 (API 33), for which the system allows a foreground service to be started from the boot receiver; Android 15 (the platform pages disagree on whether it is 14) may refuse it for apps that target those versions (and Android 12 and later may refuse a foreground service that is started from the background in other cases), so raising the target version would need a different boot start. The boot start has not been verified on a phone with Android 16.

![browse_servers](./screenshots/6.0.x/browse_servers.png){:height="30%" width="30%"}

![shutdown_timer](./screenshots/6.0.x/shutdown_timer.png){:height="30%" width="30%"}

## OpenHome

OpenHome is an extension of UPnP for music players. Its devices keep the playlist themselves, so the playlist survives when the app is closed and other apps can control the same device. YAACC supports it in both directions. Nothing has to be switched on; the only choice you can make is described below.

**YAACC as the controller.** When a receiver offers the OpenHome Playlist and Product services, YAACC plays music on it with OpenHome: the playlist of the player screen is the playlist of the device (what you add is added to the device, what you delete is deleted on the device, what another app adds shows up here within a few seconds), and play, pause, next, previous, seek, volume, mute, repeat and shuffle go to the device. A device in standby is woken up, and its source is switched to the playlist. A playlist on an OpenHome device holds at most 1000 tracks; tracks beyond that are left out. Devices that speak OpenHome only (without AVTransport) are listed among the receivers too; their row has play, pause, stop, volume and mute (through the OpenHome volume service). Tracks of the playlist of a device can be dragged to another place (a device with OpenHome Playlist version 2 has an action for it; with version 1 the track is inserted again at the new place and the old entry is deleted, which gives it a new id), except the track that plays. A device that already plays, started by another app or before YAACC was reinstalled, is shown too: the row of an OpenHome-only device shows the state and the title of the track every ten seconds, and a tap on the row opens the player screen with the playlist and the current track of the device. Opening it only reads from the device (it is not woken up and its source is not changed); the first button you press does that. Videos and images, and devices without these services, are played with AVTransport as before. Repeat one does not exist in OpenHome and shows as repeat all. Tracks must have an http or https address. Audio files you pick on this phone are played on an OpenHome device too: the playlist of the player screen shows all of them, but, as for other receivers, a file is only shared with the device while it plays and while it is next, so the device gets the files two at a time (the current one and the next) and the app adds the following file when the device moves on. Picking a file in the playlist, or *previous*, shares the file again and starts it; files that are not on the device yet can be taken out of the list, the ones it has cannot. Video and images are not sent to a device that speaks OpenHome only (the app says so). The row of an OpenHome device has a settings icon, like a MusicCast device; it opens a sheet with *Power* (standby; the device is woken up when you play on it), *Source* (to switch the device to another of its sources, for example its radio or an input; YAACC only switches the source and does not control it, and playing from YAACC switches the device back to its playlist) and *Playback protocol* (to play on the device with AVTransport although it offers OpenHome; the choice is kept for that device). The same device has a **power button** on its card, like a MusicCast device (see [The row](#the-row)): it is shown when the device description of the Product service has the actions *Standby* and *SetStandby*; the state it shows is the state the device reports (read every ten seconds while the receivers are shown, and again after each tap, since a device on the network is not subscribed to); a tap reads *Standby* first, then sends *SetStandby* the other way and reads again. The phone's own renderer has no power button on its card (its standby is an app state).

**YAACC as the renderer.** The local renderer (*Settings* > local server > receiver) also offers the OpenHome services Product, Playlist, Info, Time and Volume next to AVTransport and RenderingControl. An OpenHome control point sees the app as a music player named like the app, with the source "Playlist" (this device) and, when Snapcast groups are known, one more source per group (see below), a queue of at most 1000 tracks and a standby state (a phone is not switched off: standby stops the playback and is reported to the control points, the queue stays, and playing or choosing a track wakes the app again); the tracks are played by the music player of the app. Control points that do not know OpenHome ignore the services and work as before. The services Product, Playlist, Info, Time and Volume are accompanied by a small Radio service: controllers that start a single stream with it (Home Assistant's "play media" does) replace the queue with that one track and play it; the app does not offer a radio source. The OpenHome services are always offered by the root device of the app (when the app serves files and plays, that is the media server device with the renderer embedded in it), because OpenHome controllers such as Home Assistant look there only. The queue and the playlist of the music player of the phone are one list: what an OpenHome control point adds, deletes or moves shows up in the playlist of the local music player, and what you delete, move or add there (tracks with an http or https address; other tracks are taken out again) is changed in the queue and shown to the control points within about a second. Like the AVTransport renderer, it can be used by every device in the network.

**The power button on the card of the renderer.** When the renderer is on in OpenHome mode (*Settings* > local server > receiver, with the media server on), the card of the app's own renderer in the receiver list has the same **power button** as the cards of other OpenHome devices, in the title line next to the check box (the card of "this phone" without the renderer has none). It shows the **same state as the power switch in the OpenHome settings of that renderer** (the settings icon of the card opens a sheet whose first row is *Power*): the symbol is full while the renderer is on and dimmed in standby, and it says its state aloud. A tap does what that switch does (it reads the state first and then does the opposite): standby stops the playback and keeps the queue, switching on only says the renderer is awake. Both are the one *Standby* state of the renderer's Product service, so they always agree: a change of the switch, a control point's *SetStandby*, a play that wakes the renderer, or the button, is seen on the card at once while a list is shown (the card is told by the renderer; nothing polls), and the state is not stored anywhere (a restart of the server starts awake). It is not the same as the renderer switch in the settings, which starts and stops the whole renderer service.

**Snapcast groups as sources.** The source list of the app's own OpenHome renderer shows *Playlist* (this device, exactly as before) followed by one source per Snapcast group the app knows (at most 16, sorted by name; the name is the group's name as the receiver list shows it, with a number when two groups have the same name; the type of every source is *Playlist*, so a controller can queue on each of them). Choosing a source in a controller sets the **same** *Output* setting as *Settings* > *UPnP server* > *Output* (*This device*, or *Snapcast group* with that group), and the other way round: a change in the settings, or in another controller, is reported to every controller as an event (*SourceIndex*, and *SourceXml* when the list changed), and an open settings screen shows it, so both always show the same state. The sources come from the groups the app has read from a server since it started (the receivers page, a play into a group, or the renderer's own group output made the connection): the renderer does not connect to a server just to fill the list, so with no server known only *Playlist* is listed; the list is updated when groups appear or vanish. If the group that is chosen is not known at the moment (its server is off, or has not answered yet) the list shows it as *Snapcast group (not available)*, which is the current source and can not be chosen. A controller that chooses a group that vanished since it read the list gets an error, the output and the source it shows stay as they were, and the phone shows a short message. A controller that holds a list from before a change and then chooses by number can pick another group than it meant: choosing by name or by the system name (a token that does not change while the list does) is safe. A controller that writes a negative number as the source gets this device (the network library turns it into 0).

*Who can do what:* as for the output itself, any device in the network that can reach the renderer can already set the output; the source list adds only that it can choose among the groups of **your** servers that the app knows, never an address or a group of its own: the sources carry the name of the group and an opaque token, and the id of the group and the address of its server stay on the phone. Choosing the source that is current changes nothing. A YAACC that controls another YAACC renderer leaves its current source alone when it prepares a play (it only switches to a playlist source when the current one is not a playlist).

Not supported yet: playing radio, Songcast or other sources from YAACC (the source can be switched, see above).

## Trademarks

Android and Android TV are trademarks of Google LLC. UPnP and DLNA are trademarks or certification marks of their respective owners; YAACC is not UPnP or DLNA certified. Yamaha and MusicCast are trademarks or registered trademarks of Yamaha Corporation. Snapcast is the name of an open source project of its authors. FCast is a registered trademark of FUTO; YAACC is an independent community implementation of the FCast protocol and is not affiliated with FUTO, and it shows no FCast logo. Home Assistant, VLC, Kodi, BubbleUPnP, Windows and the other product and company names mentioned in this documentation are trademarks or registered trademarks of their respective owners and are used only to identify compatible products. YAACC is an independent project and is not affiliated with, authorized or endorsed by any of them.

| [Screenshots](screenshots/) | [Settings](settings/) |  [About](about/) | [Code](doxygen/html/inherits.html) | [Legal Notice (Impressum)](https://schoenesnetz.de/pages/disclamer_license/about.en.html) | [Privacy Policy](privacy.html)




