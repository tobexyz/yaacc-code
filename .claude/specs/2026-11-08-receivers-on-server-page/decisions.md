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

