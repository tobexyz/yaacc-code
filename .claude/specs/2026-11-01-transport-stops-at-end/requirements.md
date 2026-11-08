# Issue #296: HA music does not start after a TTS announcement (the transport stays PLAYING)

## Evidence (owner's HA flow + app log, 2026-11-01 18:48)
Flow: repeat off -> tts.speak (2 s mp3) -> shuffle on -> delay 3 s -> repeat one -> play_media (music). The TTS plays, the music never starts. Repeat and shuffle work.
Log:
- 18:48:03.775 `LocalMediaSessionPlayer$1: End of the playlist reached, repeat is off: stopping` (the TTS player ended).
- 18:48:04.533 event `CurrentPlayMode=REPEAT_ONE` (Seq 19); 18:48:04.568 HA sends **Stop** and the log says `AvTransportMediaRendererPlaying: Stop`: the transport was STILL in the Playing state although the player had ended 0.8 s before (GetPositionInfo says RelTime 2 / Duration 2 / 100 %).
- 18:48:04.582 SetAVTransportURI (music) succeeds. **No Play ever arrives** (nothing in the log for 90 s). The next event (Seq 20) goes out at 18:48:04.797, 250 ms after the Stop.
HA source (dlna_dmr `async_play_media`, read from GitHub): Stop if `can_stop`, SetAVTransportURI, then `if self._device.transport_state == PLAYING or not autoplay: return` (**no Play**), else Play.
`transport_state` of HA is the last evented TransportState. We never evented STOPPED after the TTS ended (nothing moves the AVTransport state machine out of Playing when the player ends; TrackInfoUpdater only stops itself), and the STOPPED event of the Stop action was held back 250 ms by the pacing of `AsyncLastChangeServiceManager.MIN_EVENT_INTERVAL_MILLIS` (the previous event had just gone out), so HA still saw PLAYING when it checked.

## Problem Statement
1. The AVTransport state stays PLAYING after the player has played to its end (repeat off) or was stopped/closed in the app. UPnP: the renderer must leave PLAYING (STOPPED; TransportState and CurrentTransportActions evented). Controllers (HA) rely on it.
2. A TransportState change is delayed up to 250 ms by the event pacing, long enough for a controller that acts right after its own Stop (HA: Stop, SetAVTransportURI, check transport_state) to see the old state.

## Goals
- When the player of the transport ends (playlist finished with repeat off, or `stop()` of the player) or is removed (closed in the app), the transport moves to STOPPED with the normal state-machine path (events: TransportState=STOPPED, CurrentTransportActions; position info stays readable). No change for repeat all/one (they do not end).
- A change of TransportState (or CurrentTransportActions) is sent at once, not paced; pacing stays for the other variables.
- The scenario of the flow above works in the harness: TTS ends -> state STOPPED + event -> SetAVTransportURI -> Play is possible (`transport_state` is not PLAYING).

## Non-Goals
- No change to repeat/shuffle behavior, no change of what Stop does in other states, no NO_MEDIA_PRESENT handling beyond what exists.
- Not verified without HA: the exact HA debug log; ask the owner for HA's log lines ("Playing media", "Cannot do Play") only if the fix does not help.
