# Design: receivers and players on the top-level page

## Page (fragment_server_root.xml, new layout only)
The `serverList` RecyclerView fills the space above the footer (a line, the server switch and the status icons). One `ConcatAdapter`:

```
SectionHeaderAdapter  "Servers"   [broadcast icon] [refresh]
BrowseDeviceAdapter               the servers (unchanged)
PickCardAdapter                   "Play from this device" (unchanged)
SectionHeaderAdapter  "Receivers" [devices icon]   line above
DeviceSheetAdapter.receiversOnly  the receivers with their controls (the sheet's adapter, no player session rows)
SectionHintAdapter                "No other receivers found yet." while only this phone is listed
SectionHeaderAdapter  "Players"   [playlist icon]  line above; hidden while no player runs
MiniPlayerBar.adapter()           the running players, the rows of the mini-player with the spacing of the other cards
```
The classic layout (`fragment_server_list.xml`) keeps `BrowseDeviceAdapter` + `PickCardAdapter` only. Section names are 22sp, icons 36dp.

## Classes (package `de.yaacc.browser`)
- `SectionHeaderAdapter`: one item or none (`server_list_section_header.xml`: optional line above, icon, label as accessibility heading,
  optional refresh button; `setVisible`).
- `SectionHintAdapter`: one text item; `noRemoteReceiver(ids)` decides (the local device is always in the list).
- `ReceiverLiveUpdates`: the live subscriptions of the sheet, extracted from `DeviceSheetFragment` and shared by sheet and page: status
  monitor listener, `YxcRowFeed` (group state), player list listener, 2 s poll, volume updater. `start(refreshFirst)` / `stop()` /
  `devicesChanged()` (the monitor and the feed take the receivers of the start as a snapshot, so receivers found later are added);
  idempotent, nothing is delivered after `stop()`. It works against `Target` (implemented by `DeviceSheetAdapter`) and `Sources` (fake in
  the tests, `forApp` for real). `MAX_RECEIVERS = 64` and `watched()` cap the receivers that are listed, watched and polled.
- `Coalescer`: runs the refresh of the receiver rows once per 300 ms for a burst of device events (the first build is immediate).
- `AdapterPositions`: `bindingPosition` and `laidOutRows`: positions in the owning adapter, for the parts of a `ConcatAdapter`.

## Changes in existing classes
- `DeviceSheetFragment`: uses `ReceiverLiveUpdates` (same behaviour, pick mode unchanged).
- `DeviceSheetAdapter`: implements `ReceiverLiveUpdates.Target`; `receiversOnly(...)` and `listsRow(kind, pickMode, showSessions)` (the page lists
  no player sessions); `visibleReceiverRows()` through `AdapterPositions.laidOutRows`; `receiverIds()`, `setOnRefreshed` (the hint follows every rebuild).
- `MiniPlayerBar`: a page-mode constructor (no list, the rows are `adapter()`, visibility reported to a `Consumer<Boolean>`); `MiniPlayerAdapter`
  has a `cardSpacing` mode (`cardUseCompatPadding`, no margins - the relative start/end margins must be cleared too).
- `BrowserActivity`: receiver bar and mini-player are in `browserBottomStack` (activity_browser.xml); `updateBottomStack()` shows and binds
  them only while a `ContentListFragment` is on top and the activity is started (back stack listener, `onStart`, `onStop`).
- `ServerListFragment` (top-level layout only): builds the ConcatAdapter, creates the receiver adapter and the page-mode mini-player, runs both
  between `onStart` and `onStop`, rebuilds the receiver rows when a device is added/removed/updated (coalesced), releases everything in `onDestroyView`.
- `BrowseDeviceAdapter` (D-pad) and `ServerListClickListener`: the tapped server is found by `AdapterPositions.bindingPosition`: with a header
  before the servers the position in the list is one off (test `ServerListClickListenerTest`).
- `ContentListFragment`: tooltip on the server button. Strings in the seven locales.

## Behaviour notes
- Receivers are the devices providing AVTransport (and OpenHome-only devices), in the order of the sheet (local first, then by name).
- A stopped page (a folder opened, a player screen on top) drops all subscriptions; coming back rebuilds the rows once.
- While the sheet is open over the page both are subscribed (the status monitor serves several listeners): the volume reads overlap, short.

## Risks
- Positions in a `ConcatAdapter` are global in the list: every position-based use must go through `getBindingAdapterPosition`.
- `ConcatAdapter` with the default config ignores stable ids: D-pad focus may jump when rows are inserted above it (not checked on a TV).
- The page polls receivers as long as it is started (battery, hostile hosts): capped at 64 and coalesced.
