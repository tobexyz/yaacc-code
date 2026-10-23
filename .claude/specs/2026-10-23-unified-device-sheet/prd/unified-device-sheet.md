# PRD: Unified device sheet

## Problem Statement
Choosing where to play happens on a tab the user reaches *after* choosing
what to play, and running players live on yet another tab. The order of the
tabs works against the order of the task.

## Goals
- Receiver selection and controls reachable from the Content tab.
- Running players shown under the device they play on.
- No dead-end error when playing with no receiver selected.

## Non-Goals
- Removing tabs, a mini-player bar, or a server picker (later phases).

## User Stories
- As a listener browsing my music, I want to pick or change the speaker
  without leaving the folder I'm in, so I don't lose my place.
- As someone running a slideshow with background music on my phone, I want
  to see and control both sessions, not whichever one the app happens to
  pick.
- As a TV user, I want the same controls reachable with the remote.

## Requirements
### Must Have
- Bottom-bar receiver area opens a sheet listing all receivers with
  selection, status, transport, volume and mute.
- Every running player shown under its device; tapping opens its full
  player.
- Play with no receiver selected opens the sheet.
- D-pad navigable.

### Should Have
- Live status updates while the sheet is open.
- Receiver tab shows a deterministic session per device (bug fix).

### Won't Have (this iteration)
- Mini-player bar, server picker, tab removal.

## Success Metrics
See requirements.md.

## Open Questions
1. **One session per renderer?** A remote renderer can only play one thing
   at a time, but yaacc can hold a music *and* an image `AVTransportPlayer`
   for it at once (design.md finding 1). Should starting new content on a
   renderer shut down *all* its existing players, not just the same-type
   one? That's a behavior change to `PlayerService`, out of scope here, but
   it would simplify the sheet.
2. **Local video:** `MultiContentPlayer` hands video to a third-party app.
   Should its session row appear at all, and what should tapping it do?
3. **TV long term:** once tabs are removed (phase 3), is the sheet enough on
   TV, or should TV keep the tabs?
