# Unified device sheet

## Problem Statement
Playing something takes four tabs: Server → Content → Receiver → Player.
The Receiver tab comes *after* Content, but a receiver has to be picked
*before* playing, so it's either set up front and forgotten, or found to be
wrong only when playback lands on the wrong device. Receivers carry real
controls (transport, volume, mute) and running players open full player
screens, so neither can simply become a one-line picker.

## Goals
- Reach every receiver, with its full controls, from the Content tab
  without switching tabs.
- Show each running player under the receiver it plays on, so it's obvious
  which session belongs to which device.
- When the user plays something with no receiver selected, take them to
  receiver selection instead of showing an error toast.
- Keep the existing Receiver and Player tabs working unchanged in this
  phase, so nothing is lost while the new path is being proven.

## Non-Goals
- Removing the Receiver or Player tabs (phase 3, see design.md).
- A persistent mini-player bar (phase 2).
- A server picker sheet (phase 3).
- Changing how many player objects PlayerService creates per device (see
  open questions in prd/unified-device-sheet.md).

## Success Metrics
- From the Content tab, a receiver can be selected and its volume changed
  in at most 2 taps, with no tab switch.
- Every player listed in the Player tab also appears in the sheet, nested
  under exactly one device (verified by DeviceSessionGrouperTest).
- Starting playback with no receiver selected opens the sheet instead of
  showing `error_no_receiver_selected`.

## Constraints
- Android, Java, Material Components 1.14.0 (already a dependency); no new
  dependencies.
- Must stay usable with a D-pad: Android TV (leanback) support shipped in
  5.4.0.
- Receivers and players are **not** 1:1 (see design.md, "Findings"); the
  UI must not assume they are.
- No duplicated receiver-row logic: the tab and the sheet must share one
  binder.
