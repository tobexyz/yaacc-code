YAACC - UPNP Client and Server

# About YAACC 

[<img src="https://f-droid.org/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/de.yaacc/)

YAACC (Yet Another Android Client Controller) is a hobby project I am working on in my free time.
So it's not perfect and I am only able to test the app with my own hardware setup.

Please, help improving YAACC by reporting problems or sending pull requests.

Have fun!

tobexyz formerly known as @theopenbit

[<img src="https://f-droid.org/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/de.yaacc/)


# Description:
The App allows you to play media from UPNP/DLNA devices in your network on your android
device or any capable UPNP device in your network. It is also possible to start
an UPNP server on your device in order to play media from your device on any
UPNP renderer in your network.

# Features:

* UPNP Server
* UPNP Client
* UPNP Controller
* Control multiple media receivers
* Multiple Receivers
* Allow download files to the device
* Implemented parts of UPnP Version 3 at once (sending synchronization information to the renderer)

## A word about the cling library
This project is based on the work of https://github.com/4thline/cling. 
This gread UPNP library made yaacc possible and all honor belongs to the authors of that project. 
Thank you for your work on that project!

Unfortunatly the project went into the EOL state and no maintainer is found yet. 
Therfore a copy of the parts of cling needed by YAACC are now included in this repository because I am not able to maintain the whole project. 
 
# Build dependencies
Android SDK (set ANDROID_HOME or create a local.properties file to point to it)
Maven (tested with 3.6.0)

# Build from source
Build the project by running following command in the project root:

```./gradlew build```

After the build has finished the debug apk is located in 
```./yaacc/build/outputs/apk/debug/yaacc-debug.apk```

# the old sf wiki

[wiki](./wiki/YaaccWiki.md)
