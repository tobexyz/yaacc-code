# Receivers and players on the top-level page (new layout)

## Problem Statement
Receivers got as important as servers, but in the new layout they were only reachable through the receiver bar at the bottom (a half-height
modal sheet whose text can say "No receiver playing"), next to a mini-player of the running players. The top-level page listed the servers
only, and the server button in the header of the content list used the broadcast icon without any hint where it leads.

## Goals (as built)
- The top level of the new layout is one scrolling list in three sections with a thin line between them:
  **Servers** (the servers, the card "Play from this device", the one refresh button), **Receivers** (every receiver with the rows and controls
  of the device sheet) and **Players** (the running players as the compact rows of the mini-player; the section and its header are only there
  while a player runs).
- Every header shows the icon of its kind of device in front of its (large) name: broadcast icon for servers, devices icon for receivers,
  playlist icon for players. One refresh button, in the Servers header: one search finds servers and receivers.
- The receiver rows are live like in the sheet: status, group/power/source of devices with group support, local status, volume and mute of the
  visible rows, receivers found after the page started. Updates run only while the page is started and are dropped when it stops.
- While only this phone is listed (the local renderer is always in the list), the Receivers section says "No other receivers found yet.".
- Every player is listed once on the page (in the Players section, not under its receiver); the device sheet and the picker are unchanged.
- The receiver bar and the mini-player at the bottom are shown below the content of a server only; the top level has the sections instead.
- The server button in the header of the content list shows its name on a long press.

## Non-Goals
- The classic layout (tabs): unchanged, it keeps its Receiver tab and its footer.
- A receiver icon in the content header, tabs or a segmented control on the top level.
- A different sheet (it stays modal, half height, with the player rows under the receivers).
- Selection checkboxes on the receiver rows (receivers are chosen per action in the picker).

## Success Metrics
- Page: header "Servers", servers, the pick card, line, header "Receivers", receivers, line, header "Players", players.
- Opening and closing the page leaks no subscriptions (status monitor, group feed, player list listener, poller, volume updater, executors).
- Unit tests for the lifecycle of the live updates, the cap and the coalescing, the positions in a `ConcatAdapter`, the resources (layouts,
  ids, strings in 7 locales). Build, tests and lint green, lint 0.

## Constraints
- Plain JVM unit tests (`android.database.Observable` of the platform stub has no observer list: adapters cannot notify there).
- Android TV: the refresh button is focusable, the labels are not; the rows keep their D-pad handling.
- Untrusted LAN strings (names of devices, tracks, art URIs) only through `setText` / `IconDownloadTask`, as in the sheet and the mini-player.
- At most 64 receivers are listed and polled (a host of the network can announce any number of devices).
