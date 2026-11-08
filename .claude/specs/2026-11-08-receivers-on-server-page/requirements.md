# Receivers on the top-level page (new layout)

## Problem Statement
Receivers got as important as servers, but in the new layout they are only reachable through the receiver bar at the bottom (a half-height
modal sheet, whose text can say "No receiver playing"). The top-level page lists the servers only. The server button in the header of the
content list uses the broadcast icon without any hint what it leads to, and the page it leads to ("Servers") shows no icon.

## Goals
- The top-level page of the new layout has two sections in one scrolling list: **Servers** (with the card "Play from this device") and
  **Receivers** (every receiver with its controls and the yaacc players running on it, the rows of the device sheet).
- Both section headers show their icon in front of the label (broadcast icon for servers, devices icon for receivers), the same icons
  the rows, the content footer and the receiver bar use.
- Device search: the refresh button of the Servers header stays; the Receivers header has one too (same action: one search finds both).
- While only this phone is listed as a receiver (the local renderer is always in the list), the Receivers section shows the hint "No other receivers found yet.".
- The receiver rows are live like in the sheet: status of the receivers, group/power/source of devices with group support, local status,
  volume and mute of the visible rows, new/ended players, and receivers that are found after the page started. Updates run only while the page is started and are dropped when it stops.
- The server button in the header of the content list says what it is: tooltip on long press (the existing content description).
- The receiver bar and the device sheet are unchanged.

## Non-Goals
- The classic layout (tabs): unchanged, it keeps its Receiver tab.
- A receiver icon in the content header, a segmented control/tabs on the top level.
- A different sheet (the sheet stays modal and half height).
- Selection checkboxes on the receiver rows (receivers are chosen per action in the picker).

## Success Metrics
- Server page: header "Servers" with icon, servers, "Play from this device", header "Receivers" with icon, receivers with their players.
- Opening and closing the page does not leak subscriptions (status monitor, group feed, player list listener, poller, volume updater).
- Unit tests: lifecycle of the live updates (start/stop/restart, nothing after stop, refresh only when the list is stale), resources
  (layouts, ids, strings in 7 locales). Build gate green, lint 0.

## Constraints
- Plain JVM unit tests; `assembleDebug testDebugUnitTest lintDebug` stays green.
- Android TV: the headers' refresh buttons are focusable, the label is not; the rows keep their D-pad handling.
- Untrusted LAN strings (names of devices, tracks) only through `setText` as in the sheet.
