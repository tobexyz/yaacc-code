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

## FlatBuffers (library of the app, FCast protocol v4)

The app uses the Java runtime `com.google.flatbuffers:flatbuffers-java` (version 25.2.10, <https://github.com/google/flatbuffers>) to read and write the
messages of version 4 of the FCast protocol. It is licensed under the Apache License 2.0 (`licenses/Apache-2.0.txt`). The message tables of the protocol that
YAACC uses are written by hand against the published schema (`fcast.fbs` of <https://github.com/futo-org/fcast>, MIT licence); no code generated from it and no
FCast code is part of the app. FlatBuffers is a project of Google; FCast is a trademark of FUTO; YAACC is not affiliated with either.

## Snapcast (test rig; no Snapcast code in the app)

The Docker test rig in `tools/snapcast-rig/` builds the Snapcast server and client from the upstream repository <https://github.com/snapcast/snapcast>
at a fixed commit (`SNAPCAST_COMMIT` in `tools/snapcast-rig/Dockerfile`) when you start it. Nothing of Snapcast is stored in this repository or shipped in
the app: YAACC's Snapcast support (`de.yaacc.multiroom`: the control of a server, the feed of audio into a group and the phone's own client with its time sync) is its own
code, written from Snapcast's published documentation and behaviour, and it talks to a Snapcast server over the network. The client uses no library for it: PCM is
handled in Java and FLAC by the decoder of the phone's platform (`MediaCodec`). Snapcast is licensed under the GNU General Public License version 3 or, at your option, any later version; the license applies to the code the rig builds, not to YAACC.

Snapcast is the name of an open source project of its authors; YAACC is not affiliated with, authorized or endorsed by the Snapcast project or its authors.
The name is used only to say which servers YAACC works with.
