# YAACC - UPNP Client and Server

## About YAACC

YAACC (Yet Another Android Client Controller) is a hobby project I am working on in my free time. So
it's not perfect and I am only able to test the app with my own hardware setup.

Please, help improving YAACC by reporting problems or sending pull requests.

Have fun!

tobexyz

[<img src="https://f-droid.org/badge/get-it-on.png"
alt="Get it on F-Droid"
height="80">](https://f-droid.org/packages/de.yaacc/)



    dashboard
    app badges
    app metrics
    package details
    search metrics

🆒 F-Droid App Badges

Generate dynamic badges for F-Droid apps to display monthly download and search statistics. These badges can be embedded in README files, websites, or any platform that supports image embedding.

Badges are delivered via Shields.io for easy integration.

A GitHub Actions workflow runs daily to process raw F-Droid metrics data and generates appwise JSON files.

First we aggregate monthly (and cumulative) data like this:

{
"package_id": "io.github.kitswas.virtualgamepadmobile",
"total_downloads": 3204,
"api_hits": 6507,
"versions": 2,
"search_count": 0
}

Then, we produce badges like these:

Downloads last month Searches last month Downloads (all time)
Generate Your Badges

Enter the F-Droid App ID (e.g., org.fdroid.fdroid):

Downloads last month

![Downloads last month](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fgithub.com%2Fkitswas%2Ffdroid-metrics-dashboard%2Fraw%2Frefs%2Fheads%2Fmain%2Fprocessed%2Fmonthly%2Fde.yaacc.json&query=%24.total_downloads&logo=fdroid&label=Downloads%20last%20month)


Downloads (all time)

https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fgithub.com%2Fkitswas%2Ffdroid-metrics-dashboard%2Fraw%2Frefs%2Fheads%2Fmain%2Fprocessed%2Ftotal%2Fde.yaacc.json&query=%24.total_downloads&logo=fdroid&label=Downloads%20(all%20time)


<img src="./docs/screenshots/5.0.x/browse_servers.png" alt= “” width="30%" height="30%"> <img src="./docs/screenshots/5.0.x/browse_image_folder.png" alt= “” width="30%" height="30%"> <img src="./docs/screenshots/5.0.x/browse_receiver.png" alt= “” width="30%" height="30%">
<img src="./docs/screenshots/5.0.x/music_player.png" alt= “” width="30%" height="30%"> <img src="./docs/screenshots/5.0.x/playlist_fully_editable.png" alt= “” width="30%" height="30%"> <img src="./docs/screenshots/5.0.x/image_player_show_menu.png" alt= “” width="30%" height="30%">

## Description

The App allows you to play media from UPnP/DLNA devices in your network on your android device or
any capable UPnP device in your network. It is also possible to start an UPnP server on your device
in order to play media from your device on any UPnP renderer in your network.

## Features

* UPnP/DLNA Server - share files of your device in the network
* UPnP/DLNA Client - receive media from other devices on your device
* UPnP/DLNA Controller - control media renderer in the network
* Live streaming - stream system audio and screen to network devices (Android 10+)
* SAF support - browse and share files from external storage, USB drives, SD cards
* Lock screen controls - hardware volume buttons and media controls
* Receiver controls - play/pause/stop directly from receiver tab with status display
* Control multiple media renderer
* Allow download files to the device
* Allow sharing of URLs and sending them to the current media renderers
* Use your device as an proxy if your media renderer can't process https media URLs
* Battery optimized with smart WiFi lock and foreground service management

## Configuration

Please take a look in the settings of the app. A lot of configuration can be done there.

The media server is stopped by default and has to be activated in the settings.
For this go to settings and tick some checkboxes under "Local server configuration" depending
on your configuration needs. You can start the server by activating the corresponding setting
or by activating the switch at the bottom of the server tab

## A word about the cling library

This project is based on the work of <https://github.com/4thline/cling>. This great UPnP library
made
yaacc possible and all honor belongs to the authors of that project. Thank you for your work on that
project!

Unfortunately the project went into the EOL state and no maintainer is found yet. Therefore a copy
of the parts of cling needed by YAACC are now included in this repository because I am not able to
maintain the whole project.

## Build dependencies

Android SDK (set ANDROID_HOME or create a local.properties file to point to it)

## Build from source

Build the project by running following command in the project root:

```./gradlew build```

After the build has finished the debug apk is located in
```./yaacc/build/outputs/apk/debug/yaacc-debug.apk```

## Documentation

<https://tobexyz.github.io/yaacc-code>
