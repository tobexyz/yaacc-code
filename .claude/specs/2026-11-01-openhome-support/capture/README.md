# Capture of an OpenHome device (issue #301)

`oh_capture.py` records what a device offers: the device description, every service description, the answers of the read-only
OpenHome actions and, if asked, the events while you use the device. It works the same against a real OpenHome device and against
the YAACC renderer (find its description URL in the "UPnP devices" list of a control point such as another phone).

    python3 oh_capture.py http://<device>:<port>/<description>.xml ./capture-linn 60
    python3 ../../2026-11-02-issue307-musiccast/capture/scrub.py ./capture-linn

The second command replaces addresses, serial numbers and names (read the result before you share it). The files then go into
`yaacc/src/test/resources/openhome/captured/` as fixtures.
