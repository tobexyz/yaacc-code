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

## 2026-11-08 — Style changes after the owner tried the page (no separate review cycle)
- Section names are 22sp (was 18sp), their icons 36dp (was 32dp).
- The player rows have the spacing of the other cards: `MiniPlayerAdapter(actions, cardSpacing = true)` sets `cardUseCompatPadding` and clears the margins,
  the relative `layout_marginStart/End` too (`setMargins` does not clear them). The bar below the content keeps its tight rows.
- Every player was listed twice (under its receiver and in the Players section): the page's receiver list is `DeviceSheetAdapter.receiversOnly`
  (`listsRow` decides); the sheet and the picker are unchanged.
- Line above the server footer (it was the line of the bottom stack); the Spanish, French and Portuguese "Players" are Reproductores, Lecteurs, Reprodutores (the tab titles read as game players).

## Open follow-ups (not in this change)
- `RendererStatusMonitor` never drops the poll chain of a device that left the registry; `ReceiverRowBinder` logs some device strings uncleaned.
- The Players rows are emptied on `onStop` and refilled on `onStart` (a short flicker, album art looked up again).
- D-pad focus on a TV after a rebuild of the receiver rows (no stable ids across the parts) is not checked.
- The bind rule of the bottom stack (started and content on top) and `ReceiverLiveUpdates.forApp` are only reviewed, not unit-tested.
- `OpenHomeRendererTest.thePlayerIsOnlyTouchedOnItsOwnThread` failed now and then in a full run (no player created). Cause found and fixed in the test: the main thread edited the OpenHome queue while the async worker had not yet run the `play`, so on a busy machine the worker found the queue empty. The test waits for the worker after `play` now (shown with an artificially slowed worker: without the wait `expected:<1> but was:<0>`, with it green).

