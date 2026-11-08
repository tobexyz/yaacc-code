# Decisions: receivers on the top-level page

## 2026-11-08 — Page layout (owner's decision: option 2)
Servers and Receivers are two sections of one scrolling list on the top level, each header with the icon of its kind of device; the
receiver rows are the rows of the device sheet. No tabs (the owner chose the tab-less layout), no receiver icon in the content header.

## 2026-11-08 — The content footer is classic-only
The provider name in the footer of the content list exists in the classic layout only (the new layout has the receiver bar), so the
server button in the header of the content list got a tooltip (long press) and nothing else.

## 2026-11-08 — Stable ids of the sheet adapter are not used on the page
`ConcatAdapter` with the default config ignores `DeviceSheetAdapter.setHasStableIds(true)`. Stable ids across the parts would need
them on every part (`BrowseDeviceAdapter`, `PickCardAdapter`, the headers). The sheet's stable ids keep D-pad focus on a row when
rows above it are inserted or removed during a refresh; on the page this can move the focus (not checked on a TV). Accepted for now;
the list is rebuilt only when a device or player comes or goes, and each row update is a `notifyItemChanged` that keeps the focus.

## 2026-11-08 — The hint rule
The list of receivers always contains the local renderer (`getDevicesProvidingAvTransportService` appends it), so "no receiver" would
never be true. The hint says "No other receivers found yet." and shows while only the local device is listed.

## 2026-11-08 — Subscriptions are a snapshot, the page re-subscribes
`RendererStatusMonitor.subscribe` and `YxcRowFeed.start` take the receivers known at that moment. The page is the landing screen, so
receivers are discovered after `onStart`; `ReceiverLiveUpdates.devicesChanged()` is called after every refresh of the rows (the
Receiver tab does the same with `groupFeed.start`). Both calls accept the same listener again and add the new receivers.

## 2026-11-08 — Bounds for the landing screen (security review cycle 1)
The page polls the receivers for as long as it is started, and a host of the network can announce any number of devices. At most
`ReceiverLiveUpdates.MAX_RECEIVERS` (64, the order of the limit of the group feed) receivers are listed, watched and polled (this phone
first, then by name: `ReceiverLiveUpdates.watched`), also in the device sheet. Device events (announce / byebye floods) rebuild the rows
once per 300 ms, counted from the first event of a burst (`Coalescer`), the first build is immediate. Not changed (pre-existing, noted as follow-ups): the status
monitor never drops the poll chain of a device that left the network, and `ReceiverRowBinder` logs some device strings without cleaning.

## 2026-11-08 — Players as the third section; bottom stack only below the content (owner's request after trying the page)
The receiver bar with the mini-player on the first screen was too much next to the Receivers section. The top level lists the running players as a
third section (the rows of the mini-player, hidden with their header while no player runs) and has no receiver bar and no mini-player; below
the content of a server both stay. The Receivers header has no second refresh button (one is enough). A thin line sits above the Receivers and
the Players header. The activity binds the bar and the mini-player only while the content is on top, the page binds its own player rows only
while it is started.

## 2026-11-08 — Larger section names (owner's request)
The section names (Servers, Receivers, Players) use the large text appearance (22sp instead of 18sp) and their icons are 36dp instead of 32dp.
A style change only; checked by the resource tests, no separate review cycle.

## 2026-11-08 — The player rows have the spacing of the other cards (owner's request)
The mini-player rows are tight (2dp margin) because they sit in a small bar below the content. As the Players section of the page they get the
spacing of the server and receiver cards: `MiniPlayerAdapter(actions, cardSpacing = true)` sets `cardUseCompatPadding` on the row card and
removes its margin (page mode of `MiniPlayerBar` only; the bar below the content is unchanged). A style change; no separate review cycle.

## 2026-11-08 — No player rows under the receivers on the page; side margins of the player rows (owner approved after seeing the page)
The screenshots showed every player twice: under its receiver (the sheet's session rows) and in the Players section. The page's receiver list is
`DeviceSheetAdapter.receiversOnly` (no session rows; `listsRow` decides, the sheet and the picker are unchanged). The player rows were
narrower than the cards: the row declares `layout_marginStart/End`, which `setMargins(0,0,0,0)` does not clear; `setMarginStart(0)` and
`setMarginEnd(0)` are called too. Style/list change; no separate review cycle.

