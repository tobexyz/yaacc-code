# Copyright (C) 2026 Tobias Schoene www.yaacc.de
# GPL-3.0-or-later, like the rest of YAACC.
# Prints the v4 messages the rig writes as `name=hex` lines, for tools/fcast-rig/v4check (see its README).
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fcast_v4 as v4  # noqa: E402
m={
 'receiverIntroduction': v4.receiver_introduction("YAACC test rig","yaacc-test-rig","1"),
 'load': v4.load("audio/mpeg","http://h/a b.mp3",3.5,0.5,1.25,"Song","http://h/a.jpg"),
 'loadZeros': v4.load("audio/mpeg","http://h/a.mp3",0.0,0.0,0.0),
 'progress': v4.progress(1.5,61),
 'volume': v4.volume(0.25),
 'speed': v4.speed(1.5),
 'statePlaying': v4.state(v4.PLAYING),
 'stateEnded': v4.state(v4.ENDED),
 'stop': v4.stop(),
 'error': v4.error(v4.ERROR_UNSUPPORTED_FORMAT),
 'errorNumber': v4.error(v4.ERROR_MALFORMED, 7),
 'tracks': v4.tracks_available([(1,v4.TRACK_AUDIO,'eng','English'),(2,v4.TRACK_AUDIO,'deu',None),(3,v4.TRACK_SUBTITLE,'eng','English'),(0,v4.TRACK_VIDEO,'und','Main')]),
 'changeTrack': v4.change_track(3,v4.TRACK_AUDIO),
 'changeTrackOff': v4.change_track(None,v4.TRACK_SUBTITLE),
}
for k,v in m.items(): print(k+'='+v.hex())
