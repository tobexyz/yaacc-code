# Review: Receivers on the top-level page (new layout)

## Cycle 1 - 2026-11-08
Reviewing: Groups 1-3 (tests, implementation, documentation), uncommitted tree on `prepareRelease`.
Gate taken from the caller (not re-run): assembleDebug ok, 3368 tests / 0 failures, lint 0 issues.

Checked and found correct (no finding):
- Position handling inside the ConcatAdapter. `ServerListClickListener` and the D-pad handler of `BrowseDeviceAdapter` use
  `getChildViewHolder(v).getBindingAdapterPosition()` (adapter-local, NO_POSITION handled). `DeviceSheetAdapter` uses only adapter-local
  positions (`entries`, `receiverPosition`, `entryAt`, `notifyItemChanged`, `onBindViewHolder` position); its one list-global use
  (`visibleReceiverRows`) now goes through `getChildViewHolder` + `getBindingAdapter() == this` + `getBindingAdapterPosition()`.
  `PickCardAdapter` has no position logic. Other `getChildAdapterPosition` / `getChildAt` users (`BrowseReceiverDeviceAdapter`,
  `ContentListClickListener`, `BrowseContentItemAdapter`, `PlayerListItemClickListener`) sit on lists that are not in this ConcatAdapter.
  Classic layout: no header, same adapters, the binding position equals the list position there, so it behaves as before; its refresh
  button path (`R.id.serverListRefreshButton`, null-guarded) still works.
- Lifecycle of the page: `receiverLive` is created in `init` (before `onStart`), started in `onStart`, stopped in `onStop` and in
  `onDestroyView` (before `release()`); `receiverAdapter`, `receiversHint` and `receiverLive` are nulled together in `onDestroyView`, so
  a `populateDeviceList` runnable that runs after `onDestroyView` sees `receiverAdapter == null` and skips the receivers
  (`refreshReceivers` guard); a view recreated after the back stack builds a new adapter set. `refreshReceivers` runs only on the UI
  thread (inside `runOnUiThread`); the network-thread callbacks only call `populateDeviceList`. The live callbacks are guarded by
  `running`, a queued poll does not re-schedule itself after `stop`, the player listener and the status listener are removed.
- `DeviceSheetFragment` after the extraction: pick mode untouched (`live == null`, `onStart` returns before `startLiveUpdates`),
  `listFresh` semantics kept (`live.start(!listFresh)`, refresh before subscribing as before), `onDestroyView` stops the live updates
  before `adapter.release()`; `Target` methods were widened to `public` only because of the interface.
- Resources: strings in all 7 locales (values, de, es, fr, nl, pt, zh); new layouts use theme attribute colours only
  (`colorOnSurface`, `colorOnSurfaceVariant`, `colorControlNormal`); no hard-coded strings; label is an accessibility heading, icon is
  `importantForAccessibility="no"`, refresh button has a content description, 48dp, focus ring; label and hint are not focusable.
- Test red/green: `ServerListClickListenerTest` case 1 fails against the old code (`getChildAdapterPosition` returns 1, so the second
  server is picked, `assertSame(first, ...)` fails), case 2 (NO_POSITION) also failed (old code called `getItem(-1)`).

### Critical
- [yaacc/src/main/java/de/yaacc/browser/ReceiverLiveUpdates.java:121-135 and ServerListFragment.java:112-114, 397-403] Receivers that
  appear while the page is started are never subscribed to the status monitor or the group feed. `start()` hands
  `upnpClient.getDevicesProvidingAvTransportService()` to `RendererStatusMonitor.subscribe` and `YxcRowFeed.start` once, as a snapshot.
  The sheet is short-lived and opened late, so that was enough there; the page is the landing screen and lives long. On a cold start
  `onStart` runs before discovery has found anything (the snapshot holds only the local dummy device), so every network receiver that
  shows up afterwards gets a row (via `deviceAdded -> populateDeviceList -> refreshReceivers`) but no status badge, no track text, no
  highlight and no group/power/source state, until the page is stopped and started again (`MonitorSubscriptions` only polls devices that
  were passed to `subscribe`). This breaks the stated goal "the receiver rows are live like in the sheet" in the main flow. The Receiver tab
  handles this explicitly (`ReceiverListFragment.populateReceiverDeviceList`: `groupFeed.start(receiverDevices)` "a receiver that came
  while the tab is shown is watched too"). Fix: give `ReceiverLiveUpdates` a method (e.g. `devicesChanged()`) that, while running,
  calls `sources.subscribeStatus(listener)` again (it only adds devices) and `sources.startGroupFeed(...)` again with the current list
  (make `forApp` pass the devices fresh each time), and call it from `refreshReceivers()` after `receiverAdapter.refresh()`. Add a
  `ReceiverLiveUpdatesTest` case (devices changed while running re-subscribes; not while stopped).

### Warning
- [ServerListFragment.java:401-402, ServerListFragment.java:200 doc claims in CHANGELOG.md, docs/index.md, requirements.md] The hint
  "No receivers found yet." can practically never show. `UpnpClient.getDevicesProvidingAvTransportService()` always appends the local
  dummy device (`result.add(getLocalDummyDevice())`), `DeviceSheetAdapter.refresh()` resolves it through `getDevice(LOCAL_UID)`, so
  `receiverAdapter.getItemCount()` is at least 1. The CHANGELOG ("without receivers the section says that none was found yet") and
  docs/index.md describe behaviour the page does not have. Decide: either count only receivers other than `UpnpClient.LOCAL_UID`
  (hint "no other receivers found yet", reword the 7 strings) or drop the hint and the claims. Also, the hint state is only recomputed in
  `refreshReceivers()`; refreshes through `ReceiverLiveUpdates` (player added/removed, `start(true)`) change the count without updating
  it, and `getItemCount()` includes player-session rows. An `RecyclerView.AdapterDataObserver` on `receiverAdapter`, or computing the
  hint from the receiver rows only, removes both problems. No test covers this logic at all.
- [ServerListFragment.java:197 (ConcatAdapter default config), DeviceSheetAdapter.java:~142 `setHasStableIds(true)`] The sheet adapter's
  stable ids are silently dropped: `new ConcatAdapter(parts)` uses `StableIdMode.NO_STABLE_IDS`, which ignores (and logs a warning
  about) the sub-adapter's ids. The class comment says "Stable ids keep D-pad focus on the same row across refreshes"; in the page, a
  refresh that inserts or removes a receiver or session row above the focused row can move TV focus to the row that takes over the
  position (RecyclerView restores focus by position without stable ids). docs/tech.md records "no stable ids across the parts" as a
  decision but not the consequence for TV focus. Either use `ConcatAdapter.Config` with `ISOLATED_STABLE_IDS` (then every part,
  `BrowseDeviceAdapter`, `PickCardAdapter`, `SectionHeaderAdapter`, `SectionHintAdapter`, must call `setHasStableIds(true)` and
  return stable ids) or state and accept the limitation explicitly in decisions.md. Confidence medium (needs a TV check).
- [yaacc/src/main/java/de/yaacc/browser/DeviceSheetAdapter.java:155-169, no test] `visibleReceiverRows()` is the one place the design
  names as the ConcatAdapter risk, and it is untested; the volume/mute live update of the page rows depends on it (wrong rows ->
  volume shown on the wrong receiver). It can be unit tested with mocks (a RecyclerView mock with `getChildCount/getChildAt/
  getChildViewHolder`, holders whose `getBindingAdapter()` is the adapter or another adapter, binding positions offset by a header).
  Same for the D-pad branch in `BrowseDeviceAdapter` (`getBindingAdapterPosition`), which has the same one-off bug class as the click
  listener and no test.
- [docs/index.md "The rows update live while this level is shown", CHANGELOG.md "update live while the level is shown"] Not true
  for receivers discovered after the page was started (see Critical). Re-check the wording once that is fixed.

### Suggestion
- [SectionHintAdapter / server_list_section_hint.xml] Make the hint a polite live region (`accessibilityLiveRegion="polite"`) or
  announce it, so TalkBack users learn when it appears/disappears; it is not a heading and not focusable, which is fine.
- [ServerListFragment.java:313-315] `contentList.getItemAnimator()` only turns off change animations; the sheet uses
  `setItemAnimator(null)`. Insert/remove/move animations remain on the page. Fine if intended; say so in the comment ("no flashing" is
  covered, moves are animated).
- [ServerListFragment.java onStart/populateDeviceList] `onStart` refresh (`start(true)`), `onResume`'s `populateDeviceList` and every
  `deviceUpdated` each run `receiverAdapter.refresh()`: three rebuilds at first show. Harmless (DiffUtil), but `deviceUpdated` fires
  often; consider refreshing receivers only when the receiver set changed.
- [ServerListFragment.java:318, `Drawable icon;`] The declaration was moved up and is first assigned much later (line ~331) after the
  `hostProvidesShutdownTimer` block; declare it where it is first used.
- [ServerListFragment.searchDevices] `getActivity()` is called twice (null check, then use inside the runnable, which may run after the
  fragment detached); copy the activity into a local. Same pattern existed before, so not new.
- [ReceiverLiveUpdatesTest] No test for `forApp` (group feed restarted on a second `startGroupFeed`, `stopGroupFeed` idempotent); small
  but it holds the only stateful glue to `YxcRowFeed`.
- [ServerRootResourcesTest] Task 1.2 lists "tooltip string" but the test does not check `server_list_button` exists in 7 locales (it
  does in the repo already; `ServerListButtonResourcesTest` may cover it). The XML `contains` checks are weak but proportionate.

### Tests
- [x] All tests passing (taken from the caller's gate: 3368, 0 failures; not re-run)
- [ ] Coverage adequate for the change: lifecycle of `ReceiverLiveUpdates` and the click-listener position are well covered; late-arriving
  devices (Critical), the hint logic, `visibleReceiverRows` and the D-pad position are not.

### Verdict: FAIL
One Critical (receivers discovered after the page started get no live status/group state), three Warnings (hint effectively never shown
and docs claim it; stable ids/TV focus; untested `visibleReceiverRows` and D-pad position), plus a doc claim to re-check.

## Cycle 2 - 2026-11-08
Reviewing: the fixes for cycle 1 (uncommitted, same tree). Gate taken from the caller (not re-run): build ok, 3380 tests, 0 failures, lint 0.

Cycle 1 items:
- Critical (late receivers): fixed. `ReceiverLiveUpdates.devicesChanged()` re-calls `subscribeStatus` and `startGroupFeed` while running and
  does nothing while stopped; `refreshReceivers()` calls it after `refresh()`. Verified the real calls are idempotent:
  `MonitorSubscriptions.subscribe` uses `addIfAbsent` and polls only devices not yet polled, `YxcMonitor.subscribe` adds only unknown
  devices (same URL is a no-op), `YxcGroupController.addBusyListener` uses `addIfAbsent`, `forApp.startGroupFeed` keeps one `YxcRowFeed`
  and re-starts it with the current receivers (`stopGroupFeed` still drops it). The group callback is now one shared `groupCallback`, so
  the restarted feed still delivers. Three tests cover it (re-subscribe while running, delivery after restart, ignored while stopped).
- Warning 1 (hint): fixed. `SectionHintAdapter.noRemoteReceiver(ids)` (local id ignored), `DeviceSheetAdapter.receiverIds()` counts receiver
  rows only (no session rows), `setOnRefreshed` runs at the end of `refresh()`, after `diff.dispatchUpdatesTo(this)`, so it is not inside
  the dispatch and not inside a RecyclerView layout pass (refresh runs from the UI-thread runnable, `onStart`, or the player listener,
  as before); the callback is null-guarded for `receiversHint`/`receiverAdapter` after `onDestroyView` and is also called for player-event
  refreshes. `setVisible` notifies only on change; a hint adapter not yet in the ConcatAdapter has no observers and then reports the right
  count when added. 7 locale strings reworded ("other"), CHANGELOG, docs/index.md, tech.md match. `ReceiversHintTest` covers the rule.
- Warning 2 (stable ids): accepted limitation recorded in decisions.md (the option offered in cycle 1); docs/tech.md says the same. Not a finding.
- Warning 3 (untested positions): fixed. `AdapterPositions.bindingPosition/laidOutRows` is used by `ServerListClickListener`, the
  `BrowseDeviceAdapter` D-pad handler and `DeviceSheetAdapter.visibleReceiverRows`; `AdapterPositionsTest` covers header offset, NO_POSITION,
  null holder, other adapter, other type. `ServerListClickListenerTest` still passes through the same helper.
- Docs claim about live rows: now true (also receivers found after the screen was opened).

### Critical
- none

### Warning
- none

### Suggestion
- [ServerListFragment.refreshReceivers] `refreshReceivers` runs on every device event of any kind (servers included, `deviceUpdated` is
  frequent) and `devicesChanged()` evaluates `getDevicesProvidingAvTransportService()` twice more (sort, registry reads) on top of
  `refresh()`. Cheap and idempotent, so fine; it could be limited to events that change the receiver set.
- [RendererStatusMonitor / MonitorSubscriptions, existing behaviour] A device that left the network stays in `polledDevices` and is polled
  (and fails) every 10 s while the page stays started; the page lives much longer than the sheet or tab did, so this now matters a little
  more. Pre-existing and out of scope here, worth a follow-up issue.
- [ReceiverLiveUpdatesTest] `forApp` (restart with current devices, one feed instance) is still not tested; the fake Sources only prove the
  call sequence.
- [SectionHintAdapter] Still not a polite live region (cycle 1 suggestion, optional).

### Tests
- [x] All tests passing (caller's gate: 3380, 0 failures; not re-run)
- [x] Coverage adequate for the change

### Verdict: PASS

## Cycle 3 - 2026-11-08
Reviewing: what changed since the cycle 2 PASS (commits c98d4bce, 4c63964d on `prepareRelease`): `ReceiverLiveUpdates.MAX_RECEIVERS` / `watched()`
(used in `forApp.subscribeStatus`, `forApp.startGroupFeed`, `DeviceSheetAdapter.refresh`), `Coalescer` (+ `CoalescerTest`) in
`ServerListFragment`, docs/decisions. Gate taken from the caller (not re-run): build ok, 3386 tests, 0 failures, lint 0.

Checked:
- Cap. `watched()` keeps the first 64 of `getDevicesProvidingAvTransportService()`, whose order is the local dummy device first, then the
  rest by name, so the local device and the existing order survive the cut; below 64 the list is returned unchanged (tested, incl. empty
  and "local first"). The same list feeds the rows, the status monitor and the group feed, so a listed row is always a watched row. 64
  equals `YxcMonitor.MAX_DEVICES`. The sheet in pick mode uses the same `refresh()`, so the picker also lists at most 64 receivers; the
  Receiver tab of the classic layout is untouched (uncapped, as before). Sessions of a receiver beyond the cap are listed as sessions of
  an unknown device (the existing "orphan" row with the device id), nothing breaks.
- Coalescer. Main-thread only and used only there: `populateDeviceList`'s `runOnUiThread` body triggers it, the `Handler` is the main
  looper's. `pending` is cleared before the action runs, so a trigger from inside the action schedules the next run; `cancel()` removes
  the exact runnable. First build (adapter == null) calls `refreshReceivers()` directly, only later events are coalesced; `onStart`'s
  `start(true)` refresh stays immediate. A late runnable cannot act on a dead view: `onDestroyView` cancels and nulls `receiverRefresh`,
  and `refreshReceivers()` returns when `receiverAdapter` is null; a recreated view builds a new Coalescer with the new adapter. While the
  page is stopped, events still schedule a rebuild (as before the change), `devicesChanged()` is a no-op then. The 300 ms delay counts from
  the first event, so a continuous flood gives at most one rebuild per 300 ms, as the Javadoc says.
- Tests: `CoalescerTest` (burst = one post with the right delay, re-trigger after a run, cancel, cancel without trigger) proves the
  logic; the cap tests prove `watched` (size, first and last element, order, empty). The glue (`forApp`, `DeviceSheetAdapter.refresh`,
  the fragment wiring) is not testable on the JVM and is reviewed only.
- Docs: tech.md (64 receivers, coalesced rebuild per 300 ms) and decisions.md (cap, coalescing, noted follow-ups) match the code.

### Critical
- none

### Warning
- none

### Suggestion
- The cap bounds the receivers that are listed now, not the ones ever polled: `RendererStatusMonitor` (`MonitorSubscriptions.polledDevices`)
  adds each new device and drops it only when the last listener unsubscribes, so on a page that stays started over a long time with a
  changing set of devices the polled set can exceed 64. This is the pre-existing follow-up already noted in decisions.md and in
  cycle 2; `YxcMonitor` has its own 64 limit on the watched map.
- In pick mode a receiver that was ticked before and now lies beyond the cap is not shown and not handed out (`pickedIds()` returns only
  listed receivers). Only matters with more than 64 receivers; no action needed.
- CHANGELOG / docs/index.md do not mention the limit of 64 receivers; fine for a limit nobody meets, mention it if the owner wants it
  documented for users.

### Tests
- [x] All tests passing (caller's gate: 3386, 0 failures; not re-run)
- [x] Coverage adequate for the change

### Verdict: PASS

## Cycle 4 - 2026-11-08
Reviewing: the owner's follow-up (uncommitted on top of d6642b03): one refresh button (Servers header only), receiver bar + mini-player only
below the content of a server (`browserBottomStack`, `BrowserActivity.updateBottomStack`), the mini-player rows as a third page section
"Players" (page-mode `MiniPlayerBar`, hideable `SectionHeaderAdapter`), a line above the Receivers and Players headers, `browser_players_header`
in 7 locales, docs. Gate taken from the caller (not re-run): build ok, 3391 tests, 0 failures, lint 0.

Checked:
- Bottom stack. State is computed from the fragment manager (`findFragmentById(browserContainer) instanceof ContentListFragment`) plus the
  `started` flag on every back stack change, `onCreate`, `onStart`, and `onStop` clears it, so there is no stored "shown" state to drift:
  fast back/forward, a quick second tap (`showContent` executes pending transactions first) and `popBackStack` all end in the state of
  the fragment that is on top. Rotation or process recreation with content on top: `super.onCreate` restores the fragments before
  `updateBottomStack()`, the container is made visible, `started` is false so nothing binds until `onStart`. Early returns in `onCreate`
  (other layout, no client) leave `bottomStack` and the controllers null; every use is null-guarded. Double bind is harmless:
  `ReceiverBarController.bind` and `MiniPlayerBar.bind` both return when already bound, and `unbind` of both is idempotent (listener,
  poll, command executor, accessibility delegate, click listener are dropped). `onStop` and `onDestroy` unbind; hidden container, bar
  not clickable, nothing subscribed at the top level, so the page's own subscriptions are the only ones there. During a pop the page
  (bound in its `onStart`) and the activity's bar (unbound by the back stack listener) can overlap for a moment; that is two light
  listeners, not a leak.
- Nothing else depended on the bar at the top level: `R.id.miniPlayerBar` / `browserReceiverBar` / `browserBottomSeparator` are used only
  in `BrowserActivity` and the layout; `showReceivers()` is reached from the bar and from the content footer (both below a server);
  `showPlayers()` is a no-op in the new layout; pick and share flows use `ReceiverPickController` and their own sheet. The classic
  activity and layouts are untouched; `ServerListFragment` creates `playersBar` only in the `hostProvidesShutdownTimer` block.
- Page-mode `MiniPlayerBar`. `MiniPlayerAdapter` has no list-position logic (rows are looked up through `holder.row`), so it is safe in
  the ConcatAdapter; its stable ids are ignored there like for the receiver adapter (accepted, decisions.md). Order in `refreshFromPlayers`:
  `adapter.submit(rows)` first, then `visibility.accept(...)`, so the header appears above rows that already exist and disappears after the
  rows are gone; `SectionHeaderAdapter.setVisible` sets the flag before `notifyItemInserted/Removed`. `unbind()` clears the adapter and reports
  "not visible", so a stopped page shows no stale section. `playersBar`/`playersHeader` are nulled together with the other view-bound
  fields in `onDestroyView` (after `unbind`), the visibility lambda is null-guarded, and `populateDeviceList` reads `playersBar` only when
  `receiverAdapter != null` (both are set and cleared in the same UI-thread steps). Bound in `onStart`, unbound in `onStop` and
  `onDestroyView`, poll and callbacks on the main handler, the command executor is shut down on unbind: no duplicate or leaked pollers.
- Resources/docs: divider has `importantForAccessibility="no"` and a theme colour role; the Receivers/Players headers have no refresh
  (`hasRefresh` false), Servers has the one; `ic_baseline_playlist_play_32` exists; strings in 7 locales; index.md, tech.md, CHANGELOG match
  the behaviour (they now say the bars belong to the content of a server).
- Tests: `SectionHeaderAdapterTest` (visibility, notifications guarded by the observer shim, refresh/divider flags), the layout test for
  the stack order and ids, the locale test. They would fail on the previous tree (new classes/ids).

### Critical
- none

### Warning
- none

### Suggestion
- `updateBottomStack` and the page-mode visibility callback are not unit tested (Activity/Handler are not available on the JVM). The rule
  "bound iff started and content on top" could be a one-line pure function with a test; optional.
- Translations of `browser_players_header` (es "Jugadores", fr "Joueurs", pt "Jogadores") follow the existing tab title
  `title_activity_player_list`, but read as "game players"; "Reproductores / Lecteurs / Reprodutores" (as in `mini_player_open`) would be
  clearer. Cosmetic, consistent with the existing strings.
- The top level no longer has the 1 dp separator above the footer (it lived in the bottom stack); if the owner wants a line between the
  list and the server footer it belongs in `fragment_server_root.xml`. Cosmetic.

### Tests
- [x] All tests passing (caller's gate: 3391, 0 failures; not re-run)
- [x] Coverage adequate for the change (the lifecycle glue is reviewed, not testable on the JVM)

### Verdict: PASS
