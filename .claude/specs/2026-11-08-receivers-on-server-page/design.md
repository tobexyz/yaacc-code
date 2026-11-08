# Design: receivers on the top-level page

## Page (fragment_server_root.xml, new layout only)
The fixed "Servers" header row leaves the layout; the `serverList` RecyclerView fills the space above the footer. One `ConcatAdapter`:

```
SectionHeaderAdapter  "Servers"   [broadcast icon] [refresh]
BrowseDeviceAdapter               the servers (unchanged)
PickCardAdapter                   "Play from this device" (unchanged)
SectionHeaderAdapter  "Receivers" [devices icon]   (line above)
DeviceSheetAdapter                receivers + their players (the sheet's adapter, normal mode)
SectionHintAdapter                "No other receivers found yet." while only this phone is listed
SectionHeaderAdapter  "Players"   [list icon]      (line above; hidden while no player runs)
MiniPlayerBar.adapter()           the running players, the rows of the mini-player
```
The classic layout (`fragment_server_list.xml`) keeps `BrowseDeviceAdapter` + `PickCardAdapter` only.

## New classes (package `de.yaacc.browser`)
- `SectionHeaderAdapter`: one item or none (`server_list_section_header.xml`: optional line above, icon, label as accessibility heading, optional refresh button; `setVisible`).
  The label comes from a string resource, the icon from a drawable resource; the refresh action is a `Runnable`.
- `SectionHintAdapter`: one text item, shown only while `setVisible(true)`; `noRemoteReceiver(ids)` decides (the local device is always in the list).
- `Coalescer`: runs the refresh of the receiver rows once after a burst of device events (300 ms); `ReceiverLiveUpdates.watched` caps the listed and polled receivers at 64.
- `AdapterPositions`: `bindingPosition` and `laidOutRows` - positions in the owning adapter, for the parts of a `ConcatAdapter`.
- `ReceiverLiveUpdates`: the live subscriptions of the device sheet, extracted from `DeviceSheetFragment` so that the sheet and the page share
  them: `RendererStatusMonitor` listener, `YxcRowFeed` (group state), player list listener, the 2 s poll (`refreshLiveState`) and the volume
  updater (`startLiveVolume` / `stopLiveVolume`). `start(boolean listIsFresh)` / `stop()`; idempotent; callbacks after `stop()` do nothing. It
  `devicesChanged()` (receivers that came after the start: the monitor and the group feed took a snapshot) re-subscribes with the current receivers. It works against a small `Target` interface that `DeviceSheetAdapter` implements (refresh, onStatusChanged, onGroupStateChanged,
  refreshLiveState, startLiveVolume, stopLiveVolume), so it is tested with a fake target.

## Changes in existing classes
- `MiniPlayerBar`: a second constructor (page mode): no list, the rows are `adapter()`, the visibility is reported to a `Consumer<Boolean>`.
- `BrowserActivity`: receiver bar and mini-player are in `browserBottomStack` (activity_browser.xml), shown and bound only while a `ContentListFragment` is on top (and the activity is started), so the top level runs no second set of subscriptions.
- `DeviceSheetFragment`: uses `ReceiverLiveUpdates` (same behaviour, pick mode unchanged).
- `DeviceSheetAdapter`: `visibleReceiverRows()` finds the rows through the holders of the attached RecyclerView (`getBindingAdapter() == this`,
  `getBindingAdapterPosition()`, helper `AdapterPositions.laidOutRows`), not through `findViewHolderForAdapterPosition(i)` with a position local to the adapter: in a
  `ConcatAdapter` the positions are global. It implements `ReceiverLiveUpdates.Target`.
- `ServerListFragment` (top-level layout only): builds the ConcatAdapter, creates the `DeviceSheetAdapter`, refreshes it when a device
  is added/removed/updated (on the main thread) and on start, runs `ReceiverLiveUpdates` between `onStart` and `onStop`, releases the
  adapter in `onDestroyView`. The one refresh button (Servers header) calls `upnpClient.searchDevices()` as before. The search toast stays.
- `BrowseDeviceAdapter` (D-pad handler) and `ServerListClickListener`: the tapped server is found by `getBindingAdapterPosition()`, not by
  `getChildAdapterPosition()`: with a header row before the servers the list position is one off (found while building; test
  `ServerListClickListenerTest`).
- `ContentListFragment`: tooltip (`TooltipCompat`) on `contentListServersButton` with its content description.
- Strings: `browser_receivers_header`, `browser_receivers_none` in the seven locales.

## Behaviour notes
- Receivers are the devices providing AVTransport, in the order of the sheet (local first, by name), sessions under their receiver.
- A page that is stopped (a folder opened: the content list replaces it, a player activity on top) drops all subscriptions; coming back
  rebuilds the list once, because players may have started or ended meanwhile.
- While the sheet is open over the page both are subscribed (the status monitor serves several listeners); the volume reads of the page
  and the sheet overlap then. Accepted: short, only while both are visible.

## Risks
- The sheet adapter in a ConcatAdapter: every use of a position must be adapter-local (`getBindingAdapterPosition`), the one global use is fixed.
- Focus: the first focusable item moves from the fixed header into the list; the D-pad order stays top to bottom.
- A long receiver list (rows are tall) pushes the footer content off nothing: the footer is outside the list, the list scrolls.
