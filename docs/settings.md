---
layout: default
permalink: settings/
---
<!-- markdownlint-configure-file {
    "first-line-h1": false
} -->
[Up]({{site.baseurl}}/)

# Settings

| setting | description |
|---------|-------------|
| **appearance** | category for appearance settings |
| dark mode | activate the dark mode or use the same mode as configured in the system settings |
| log level | define which log level will be displayed in the log view |
| **media browsing behavior** | category for media browsing settings |
| thumbnails | show thumbnails or not|
| cover images | search for cover images in media files or use a cover.jpg file within the directories |
| number of threads | Configure the number of threads used for loading content from the UPNP server. More threads causes faster loading but increase the possibility of errors. Changes are effect after restart of yaacc. |
| chunk size when browsing |amount of items fetched from server in one request |
| **media playing behavior** | category for media playing settings |
| default item duration | default item duration if media file provides no duration information |
| silence duration | default silence duration between two media files in a playlist |
| replay playlist | if set the playlist will be replayed |
| shuffle music | if set the music playlist will be shuffled |
| picture stay duration | define the duration an image is shown until the next one coming up |
| **local server configuration** | category fot the server configuration |
| local server state | enable or disable media server |
| autostart server on boot | if checked the server will be automatically started on boot of the device |
| media proxy service | if checked the urls shared with the app will be proxied by the app using an http url. This service is needed if your rendering device does not support https urls.
| media receiver service | activate or deactivate the media receiving service|
| media provider service | activate or deactivate the media providing service |
| media provider source | the source of the media server can be either the media store of the device or test content |
| serve images | activate or deactivate publishing of images |
| serve music | activate or deactivate publishing of music |
| serve videos | activate or deactivate publishing of music |
| sending UPNP alive notifications | define the frequency for sending UPNP alive notifications |
| network name for local server| display name of the media server |
