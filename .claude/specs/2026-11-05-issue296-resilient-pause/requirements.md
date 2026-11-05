# Issue #296 (follow-up): the renderer survives fast clicks on play / pause

## Problem Statement
Home Assistant's media player has one play / pause button. When it is clicked fast, a second `Pause` reaches the renderer while it is
already in PAUSED_PLAYBACK (the first Pause has not been evented yet, or the user's clicks cross the events). The renderer answers
UPnP error 701 ("State ...AvTransportMediaRendererPaused doesn't support signal 'pause'") and Home Assistant shows an error.
The Paused state also announces Seek, Next and Previous as possible actions but answers 701 to them; Pause in the idle states is a
701 as well.

## Goals
- A command that asks for the state the renderer is in already (Pause while paused, Pause while stopped or idle) is answered
  successfully and changes nothing: no second pause of the players, no new events.
- Seek while paused moves the players to the position, the transport stays paused. Next and Previous while paused are answered and do
  nothing, the way they do while playing.
- Fast alternating Pause / Play sequences never produce a 701.

## Non-Goals
- Play while PLAYING keeps restarting the item (observed and accepted in the issue).
- No new UI. Pause in the app is still not reflected in the transport (separate topic).

## Success Metrics
- `AvTransportFastClicksTest` (renderer harness, real SOAP path) is green; all other renderer tests stay green.
