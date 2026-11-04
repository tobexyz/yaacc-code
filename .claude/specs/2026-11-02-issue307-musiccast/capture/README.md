# Capture tools for issue #307 (read-only)

These scripts record what a device that speaks the YXC API answers, for the test fixtures (task "Capture answers" in tasks.md). They only read; nothing is changed on the device.

1. `./capture.sh <device-ip> <dir> <label>` - records the read-only answers (device info, features, status of the zones, distribution info, play info, the UPnP description).
   Do it for each device, and for the distribution info also while the device is not linked, linked as server and linked as client (run it again with another label, e.g. `deviceA_server`).
2. `python3 listen_events.py <device-ip> 90 <dir>/deviceA_events.json` - records the pushed UDP events while you operate the device with its own app (volume, input, play / pause, link, unlink). Needs a free UDP port 41100 on your computer and the device in the same network.
3. `python3 scrub.py <dir>` - replaces MAC addresses, IPs, serial numbers, names, SSID and group ids. Open the files and check them. Only then share them (or put them into `yaacc/src/test/resources/yxc/`).
4. For the volume experiment (does the server's volume reach the linked clients?): link two devices with the app of the manufacturer, change the volume of the server with that app and look at `main/getStatus` of the client (`curl http://<client-ip>/YamahaExtendedControl/v1/main/getStatus`); then do the opposite. Write down what happens.
