# Third-party material

Icons: see [ICONS.md](ICONS.md).

## OpenHome service descriptions (test fixtures)

The files in `yaacc/src/test/resources/openhome/spec/` (`Playlist1.xml`, `Playlist2.xml`, `Info1.xml`, `Time1.xml`,
`Product2.xml` to `Product4.xml`, `Radio1.xml`, `Volume2.xml` to `Volume4.xml`, `Transport1.xml`) are the UPnP service descriptions of the
OpenHome services from the repository <https://github.com/openhome/ohPipeline> (`OpenHome/Av/ServiceXml/OpenHome/`, commit
`0446e5c6d32d9a9a4d9ab253f56f11a5282e288f`). They are used unchanged by the unit tests of the OpenHome support, which compare YAACC's own
service descriptions with them. They are used under the MIT license of that repository (`licenses/MIT-OpenHome.txt`):
Copyright (c) 2012-15 OpenHome Ltd.

OpenHome is a name of OpenHome Ltd.; YAACC is not affiliated with, authorized or endorsed by it. Linn and other product names that
appear in the documentation are trademarks of their owners and are used to name the devices only.

## Snapcast (test rig only)

The Docker test rig in `tools/snapcast-rig/` builds the Snapcast server and client from the upstream repository <https://github.com/snapcast/snapcast>
at a fixed commit (`SNAPCAST_COMMIT` in `tools/snapcast-rig/Dockerfile`) when you start it. Nothing of Snapcast is stored in this repository or shipped in
the app: YAACC's Snapcast support (`de.yaacc.multiroom`) is its own code, written from Snapcast's published documentation and behaviour, and it talks to a
Snapcast server over the network. Snapcast is licensed under the GNU General Public License version 3; the license applies to the code the rig builds, not to YAACC.

Snapcast is the name of an open source project of its authors; YAACC is not affiliated with, authorized or endorsed by the Snapcast project or its authors.
The name is used only to say which servers YAACC works with.
