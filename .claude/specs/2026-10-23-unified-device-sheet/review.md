# Review: Unified device sheet

## Cycle 1 — 2026-10-23
Reviewing: Group 1 tasks

Scope: `docs/tech.md` ("Unified device sheet"),
`yaacc/src/main/java/de/yaacc/browser/DeviceSessionGrouper.java` (stub),
`yaacc/src/test/java/de/yaacc/browser/DeviceSessionGrouperTest.java`, and the
orchestrator's follow-up edits to `design.md` (Risks) and `tasks.md` (new
Group 2 listener task, Group 3 constraints).

Spot-checks performed against the cached
`material-1.14.0.aar` (`javap -c -p`, `res/values/values.xml`,
`res/layout/design_bottom_sheet_dialog.xml`), all **confirmed**:
- `setFitToContents(boolean)` bytecode: `setStateInternal(fitToContents && state == 6 ? 3 : state)`.
- `BottomSheetDialog.getThemeResId` falls back to `R.style.Theme_Design_Light_BottomSheetDialog` when `?attr/bottomSheetDialogTheme` is unset.
- `Base.V14.Theme.MaterialComponents.Light.Bridge` has 68 items, none bottom-sheet related; no other style in the `DayNight.Bridge` chain sets it; `AppTheme` (`styles.xml:21`) doesn't either.
- `design_bottom_sheet` container is `layout_height="wrap_content"`.
- `BottomSheetDialog.onStart()` only moves `STATE_HIDDEN` -> `STATE_COLLAPSED`; `getBehavior()` lazily calls `ensureContainerAndBehavior()`; `cancel()` matches the description; no `onKeyDown`/`dispatchKeyEvent` in `BottomSheetDialog`.
- Player-tab trace (`PlayerListFragment.java:57-93`, `PlayerService.java:106-125, 445-448, 519-525`, `UpnpClientListener.java`) is accurate: no add/remove notification exists; refresh happens only on view creation and `onResume`; the double-`addPlayer` observation is correct.

### Critical
- None.

### Warning
- [yaacc/src/test/java/de/yaacc/browser/DeviceSessionGrouperTest.java:177-199] `primarySession` is not tested with **two playing sessions on the same device**, and design.md ("Playing session first, else highest playerId") does not say which one wins. This is the main real-world case behind finding 3: image show + background music on the local device are both playing. The input list will come from `getCurrentPlayers()`, which is `HashMap.values()` order, so an implementation that returns "the first playing session in input order" passes every current test but reproduces finding 3's nondeterminism. Fix: state the tie-break in design.md "Interfaces & Contracts" (recommended: highest playerId among playing sessions) and add a test with e.g. `player(3, LOCAL, true), player(7, LOCAL, true), player(5, LOCAL, false)` in both input orders, expecting player 7.
- [yaacc/src/test/java/de/yaacc/browser/DeviceSessionGrouperTest.java:117-140] `unknownDevicesGetNoReceiverRowAndTheirSessionsStayContiguousPerDevice` does not discriminate "grouped per device" from "trailing sessions sorted by playerId only". With ids GONE_TOO=4, GONE=6, GONE=9, a plain global sort (4, 6, 9) happens to keep both GONE sessions adjacent, so a Group 2 implementation that never groups by device passes. Fix: interleave the ids, e.g. GONE_TOO=7 with GONE=6 and 9, so a global sort would split the GONE sessions and fail the adjacency assertion.
- [docs/tech.md "Unified device sheet" §6; design.md Risks; tasks.md Group 2 listener task] The re-entrant **remove** path is not documented. `PlayerService.shutdown(Player)` (`PlayerService.java:519-525`) removes the player from the map and then calls `player.onDestroy()`; `AbstractPlayer.onDestroy()` (`AbstractPlayer.java:958-965`, reached by `AVTransportPlayer` and `LocalMediaSessionPlayer` via `super.onDestroy()`) calls `playerService.removePlayer(this)` again. A listener hook placed in both `shutdown(Player)` and `removePlayer` would fire twice for one removal. The note warns only about the duplicate `addPlayer`. A Group 2 unit test with a mocked `Player` would not catch this, because the mock's `onDestroy()` does not call back. Fix: add this to §6 and the design.md Risk ("fire on remove only when `currentActivePlayer.remove(id)` returns non-null"), and add to the Group 2 task's Accept that the test makes the mocked `onDestroy()` call `removePlayer` and asserts exactly one notification.

### Suggestion
- [yaacc/src/test/java/de/yaacc/browser/DeviceSessionGrouperTest.java:128] With the stub this test fails with `IndexOutOfBoundsException` from `rows.subList(0, 2)` rather than an assertion, and so would any Group 2 implementation that returns too few rows. Assert `rows.size()` (expected 5) before taking sublists so the failure message is useful.
- [.claude/specs/2026-10-23-unified-device-sheet/design.md "Interfaces & Contracts"] Two contract decisions now live only in the test and in `docs/tech.md` §7: trailing unknown-device sessions get **no** RECEIVER row (the adapter labels them from `Row.deviceId()`), and with no receivers every session is still listed. design.md still says "go in a trailing group under their device id", which can be read as a header row. Copy the decision into the contract Javadoc so Group 2 and Group 3 read it from the same place.
- [yaacc/src/main/java/de/yaacc/browser/DeviceSessionGrouper.java:94] The value of `Row.playerId()` for a RECEIVER row is unspecified. Document a sentinel (e.g. `0`, which `PlayerService.addPlayer` already treats as "no player").
- [yaacc/src/main/java/de/yaacc/browser/DeviceSessionGrouper.java:57-67] `PlayerRef` accessors (`playerId()`, `deviceId()`, `playing()`) go beyond the literal design.md snippet. They are necessary and documented in tech.md §7, so this is fine, but mirror them in design.md for completeness.
- Unspecified edge cases that Group 2 may want to pin down: a `PlayerRef` with a null `deviceId`, and a duplicate id in `receiverDeviceIds`.

### Tests
- [x] All tests passing: `./gradlew :yaacc:testDebugUnitTest` reports 170 tests, 10 failed, and all 10 are in `DeviceSessionGrouperTest`. This is the expected red phase: 9 fail on assertions and 1 on `IndexOutOfBoundsException` (see Suggestion). `emptyReceiversAndPlayersYieldNoRows` and `primarySessionIsNullWhenDeviceHasNoSessions` pass against the stub by design. No other suite fails.
- [ ] Coverage adequate for the change: cases (a)-(g) are present, but the two test Warnings above leave room for incorrect Group 2 implementations to pass.

Other checks: the stub and the test have no `android.*` imports and no records. The stub's public API matches design.md (plus the `PlayerRef` accessors). `material:1.14.0` is at `yaacc/build.gradle:20` and no dependencies were added.

### Verdict: FAIL

## Cycle 2 — 2026-10-23
Reviewing: Group 1 tasks + Fix Group 1 (commits 9cfdb3c2, 3d42fac8)

Resolution of cycle 1 findings:
- **W1 (multiple playing sessions): resolved.** design.md "Interfaces & Contracts" and "Fix for finding 3" now say: highest playerId among the playing sessions, else highest playerId overall, else null, regardless of input order. The new test `primarySessionPicksHighestPlayerIdAmongSeveralPlayingSessionsRegardlessOfInputOrder` (ids 4 playing, 8 playing, 9 idle, in two input orders, expecting 8) rejects every wrong implementation I tried: first-playing-in-input-order (gives 4 in order 1), last-playing-in-input-order (gives 4 in order 2), lowest playing id (4), and highest id overall (9). The existing primarySession tests (prefer playing over a higher idle id, fallback 3/7/5 -> 7, other devices ignored, null) still match the new rule.
- **W2 (per-device grouping not discriminated): resolved.** GONE_TOO is now 7, between GONE 6 and 9. A global playerId sort gives 6, 7, 9, so `gone6 + 1 == gone9` fails. The assertion now checks contiguity and order through indices, not just presence.
- **W3 (double removal): resolved.** `docs/tech.md` §6 documents both directions, citing `PlayerService.java:519-525` and `AbstractPlayer.java:958-965`, which I checked against the source in cycle 1. design.md Risks and the Group 2 listener task now say "added" fires only when `put` returns null and "removed" only when `remove` returns non-null. Verify now requires a test where `onDestroy()` calls back into `removePlayer`.
- **Suggestion (IndexOutOfBounds): resolved.** A `rows.size() == 5` assertion now comes before `subList`. All 11 red failures are `java.lang.AssertionError` (checked in the test-results XML).

### Critical
- None.

### Warning
- None.

### Suggestion
- [yaacc/src/main/java/de/yaacc/browser/DeviceSessionGrouper.java:109] The stub's `primarySession` Javadoc still has the old wording ("Playing session first, else highest playerId"), which no longer matches design.md. The Fix Group deliberately left the stub alone, so the Group 2 implementer should copy design.md's Javadoc in when implementing.
- Carried over from cycle 1, still open and non-blocking: copy the "no RECEIVER row for unknown devices / no receivers still lists every session" decisions into design.md's contract Javadoc, and document the value of `Row.playerId()` for RECEIVER rows.

### Tests
- [x] All tests passing: `./gradlew :yaacc:testDebugUnitTest` reports 171 tests, 11 failed, all in `DeviceSessionGrouperTest`. All 11 are `AssertionError`s, which is the expected red phase. No other suite fails.
- [x] Coverage adequate for the change: cases (a)-(g) are covered, and the cycle 1 gaps that let an incorrect Group 2 implementation pass are closed.

### Verdict: PASS

## Cycle 1 — 2026-10-23
Reviewing: Group 2 tasks (commits 0e02f2cf, 0817afd4; `git diff 8423351b..HEAD -- yaacc docs`)

What I checked:
- Old `BrowseReceiverDeviceAdapter` (at 8423351b) against the new adapter plus `ReceiverRowBinder`, line by line. Unchanged: volume/mute support checks, the `DeviceVolumeStateLoadTask`/`DeviceMuteStateLoadTask` calls (3-arg in `bind`, 2-arg in `refreshVolumeAndMute`), icon and album-art binding, status-map handling in `bind`/`updateStatus`/`updateLocalDeviceStatus`, remote AVTransport actions, `ViewHolder` view lookups, click/key listeners, and recycling. Everything is still bound on every `onBindViewHolder` as before; the `statusMap` and executor moved into the binder, one per adapter, as before.
- Checkbox order (`upnpClient.add/removeReceiverDevice` now runs before the adapter's selection list is updated): there is no visible effect. `fireReceiverDeviceAdded/Removed` reaches only `ReceiverListFragment` (empty callbacks) and `ContentListFragment` (updates the footer text from `upnpClient`). Neither reads the adapter's `selectedDevices`.
- Notifier: every write to `currentActivePlayer` goes through `putAndNotify`/`removeAndNotify` (`PlayerService.java:119, 132, 553`). `addPlayer`'s id-0 early return neither puts nor notifies, which is consistent. The added/removed decision comes from the atomic `ConcurrentHashMap.put`/`remove` return value on the mutating thread. Only delivery is posted to the main looper, and a queued delivery skips listeners that have since been unregistered. `CopyOnWriteArrayList` tolerates a listener removing itself mid-callback. Adding the `"playing"` property listener after `putAndNotify` is harmless, because delivery is asynchronous and independent of it.
- `ConcurrentHashMap`: no caller can pass a null key or value. `addPlayer` dereferences `player.getId()`, `getPlayer(int)` autoboxes, and `getCurrentPlayerById(null)` is now guarded. No caller relies on iteration order, which `HashMap` never guaranteed either.
- `DeviceSessionGrouper`: the implementation matches design.md and the security constraints (duplicate, null and spoofed `LOCAL_UID` ids), with no `android.*` imports and no records. The `primarySession` Javadoc was updated as cycle 2 asked.

### Critical
- None.

### Warning
- [yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java:323] **Behavior change on the yaacc renderer row.** `openPlayerActivity` now returns early on `isLocalDevice(device)` (`instanceof LocalDevice || LocalDummyDevice`). The old code only skipped devices for which no `AVTransportPlayer` matched, which in practice meant only the `LOCAL_UID` dummy. yaacc's own MediaRenderer is a cling `LocalDevice` with its own UDN (`<uuid>-renderer`, or the root UDN when only the renderer is enabled) and an AVTransport service (`YaaccUpnpServerService.java:599-605, 638`). It appears in the Receiver tab through `getDevicesProvidingAvTransportService()`, and playing to it creates an `AVTransportPlayer` keyed on that UDN (`PlayerService.java:397`). Before the extraction, tapping that row opened the player activity; now the tap does nothing. The builder was right to keep the `LOCAL_UID` row's tap a no-op, which is what "no behavior change" requires, but the check that does it is wrong. Fix: guard on `UpnpClient.LOCAL_UID.equals(deviceId)` instead of `isLocalDevice(device)`. `executeAction` keeps its pre-existing `instanceof` check, which is fine because the old code had it too and `primaryPlayer` still resolves the renderer's `AVTransportPlayer` by UDN.
- [.claude/specs/2026-10-23-unified-device-sheet/design.md "Fix for finding 3" and "Interfaces & Contracts"; ReceiverRowBinder.java:337-341] **"Highest playerId = newest" is false.** I missed this in Group 1 cycle 1. `AVTransportPlayer` ids are `Math.abs(UUID.randomUUID().hashCode())` (`AVTransportPlayer.java:158`). Local player ids are fixed per type through `NotificationId`: music 1, image 3, multi 4 (`LocalMediaSessionPlayer.java:472`, `LocalImagePlayer.java:335`, `MultiContentPlayer.java:135`). `primarySession` is deterministic, which fixes finding 3, but it does not pick the newest session:
  - On a renderer with an idle music player and an idle image player, the row shows a random one of the two.
  - On the local device, "multi > image > music" wins by type, not by recency.
  - The sheet's "ascending playerId" session order (Group 3) will look random for remote devices.

  The orchestrator needs to choose one of two fixes:
  - (a) Correct design.md, the `DeviceSessionGrouper` Javadoc and the binder comment to "deterministic, by player id", and drop "newest". This is a documentation-only change.
  - (b) Give each player a monotonically increasing creation sequence in `PlayerService` (assigned on the first successful `put`) and build `PlayerRef` from that instead of `getId()`.

  Either way, the spec's stated behavior and the code must agree before Group 3 builds the session ordering on top of it.

### Suggestion
- [yaacc/src/main/java/de/yaacc/player/PlayerService.java:119, 132, 553] **Same-id replacement (pre-existing map bug, now visible to listeners).** `MultiContentPlayer` always has id 4, and the mixed-content branch of `createPlayer` creates a new one without shutting down the old one. `put` then silently replaces the old player: no "removed" for the old object, no "added" for the new one. When the orphaned old player later exits, `removeAndNotify(map, 4)` removes the *new* player and fires "removed" for it while it is still playing. `HashMap` behaved the same before, but the Group 3 sheet would now drop a live session row. Suggested fix: use `currentActivePlayer.remove(id, player)` (the two-argument `ConcurrentHashMap` remove) in `removePlayer`/`shutdown`, and in `putAndNotify` treat `previous != null && previous != player` as removed(previous) plus added(player). Add one notifier test for each.
- [yaacc/src/main/java/de/yaacc/player/AVTransportPlayer.java:158] Pre-existing: `Math.abs(hashCode())` can return 0, which `addPlayer` silently ignores, or `Integer.MIN_VALUE`, which stays negative. The odds are negligible, but switching to option (b) above would remove both cases.
- [yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java:343-353] The Player-to-`PlayerRef` mapping in `primaryPlayer` has no automated test, because it is Android-bound. Keep it in the Group 3 manual check: start an image show plus background music on the local device, and the row should show and control the same session on every rebind.

### Tests
- [x] All tests passing: `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug` reports BUILD SUCCESSFUL, with 186 tests, 0 failures and 0 errors (`DeviceSessionGrouperTest` has 20 tests, `PlayerServiceListenerTest` has 8). Lint flags 8 issues in the changed files (`MissingSuperCall` and 3 `UnsafeOptInUsageError` in `PlayerService`, `UseCompatLoadingForDrawables` in `ReceiverRowBinder:180`, 3 `NotifyDataSetChanged` in the adapter). All of them are in pre-existing code that moved or shifted lines, so there are no new lint issues.
- [x] Coverage adequate for the change: the notifier covers double add, the re-entrant remove, unknown remove, unregistration (including before a queued delivery), duplicate registration and self-removal mid-callback. The grouper covers ordering, unknown and null devices, duplicate ids and the `LOCAL_UID` spoof. The binder is verified by manual check only (see Suggestion).

### Verdict: FAIL

## Cycle 2 — 2026-10-23
Reviewing: Group 2 tasks + Fix Group 2 (commit 4b9feab7; `git diff 077e69ec..HEAD`)

Resolution of cycle 1 findings:
- **W1 (renderer tap): resolved.** `ReceiverRowBinder.openPlayerActivity` now skips only the device whose UDN equals `UpnpClient.LOCAL_UID`. yaacc's own `LocalDevice` renderer goes through `primaryPlayer` again, and that resolves its `AVTransportPlayer` by UDN, so the tap opens the player as it did before the extraction. `isLocalDevice` is now used only for the icon and in `executeAction`, both of which predate this work.
- **W2 ("newest = highest id"): resolved, via decisions.md option (b).**
  - `PlayerListChangeNotifier` keeps a creation sequence per player instance (a `ConcurrentHashMap` keyed by identity; no `Player` class overrides `equals`/`hashCode`) and draws it from an `AtomicLong`.
  - `PlayerRef` carries it.
  - `group` sorts oldest first by sequence, then by playerId.
  - `primarySession` ranks playing before idle, then the higher sequence, then the higher playerId.
  - Tests use sequences that run against id order (id 900 is older than id 3), so an id-based implementation fails them.
  - design.md "Fix for finding 3", "Interfaces & Contracts", the `DeviceSessionGrouper` and `PlayerRef` Javadoc, and tech.md §7 now describe the sequence, not the player id.
- **Escalated suggestion (same-id replacement): resolved.** `putAndNotify` returns early when the map already holds this exact instance. When the map held a different instance under the same id, it drops that player's sequence and fires removed(previous) and then added(new). `removeAndNotify` uses `remove(id, player)`, and `removePlayer` and `shutdown` now pass the player itself rather than only its id. So an orphaned `MultiContentPlayer` exiting later neither removes its replacement nor fires. Both cases are tested.

Sequence lifecycle, as requested:
- **Genuine add:** gets a new sequence. `computeIfAbsent` runs before `put`, so a reader never sees an active player without one.
- **Re-add of the same instance:** keeps its sequence (`reAddingSameInstanceKeepsItsSequence`).
- **Remove or replace:** the sequence is dropped (`removedOrReplacedOrUnknownPlayerHasNoSequence`).
- **Add again after a removal:** gets a newer sequence.
- **id 0:** `addPlayer` returns before `putAndNotify`, so no sequence is assigned and no event fires.

`primarySession` safety: it only ever chooses from the device-filtered refs, which the binder builds from the `getCurrentPlayers()` snapshot. A sequence of 0 or a tie can only change which of that device's listed players wins. It can never pick a player from another device. The id fallback keeps the result independent of input order. The one way to bind a just-removed player is a removal that lands between the snapshot and the lookup. That gets sequence 0, so the player ranks oldest, and the removal notification triggers a rebind. Building from a live snapshot had the same window before Group 2.

`UpnpClient.getPlayerCreationSequence` returns 0 when `playerService` is null, and `creationSequence(null)` returns 0 before calling `ConcurrentHashMap.get`. That adds no null-pointer path. It follows the same unbound-service pattern as `getCurrentPlayers()`, which returns an empty list in that case, so the binder never asks for a sequence while the service is unbound.

### Critical
- None.

### Warning
- None.

### Suggestion
- [.claude/specs/2026-10-23-unified-device-sheet/design.md:186-188; docs/tech.md §6 (~line 739-744)] Both still give the old rule: "added" only when `put` returns null, and "removed" only when `remove` returns non-null. The code now also fires removed+added when `put` returns a *different* instance, and it removes by `(id, player)`. The contract sections are correct; only these two explanatory passages lag behind. Update them in Group 4's documentation task, or sooner. Group 1/2 task Accept lines in tasks.md (lines 25, 38) are history and can stay.
- [yaacc/src/main/java/de/yaacc/player/PlayerListChangeNotifier.java:83-96, 106-111] There is a narrow race. A re-add of an instance (`onServiceConnected`, on the main thread) that runs concurrently with that same instance's removal (`shutdown` from a background `createPlayer`) can leave the player in the map with its sequence already dropped, so it reports 0. The effect is limited to that player ranking as "oldest", with no wrong-device pick and no crash. If it's worth closing, do the sequence bookkeeping and the map mutation under one lock, or use `players.compute(...)`.

### Tests
- [x] All tests passing: `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug` reports BUILD SUCCESSFUL, with 194 tests, 0 failures and 0 errors. Lint shows the same 8 pre-existing issues in the changed files at shifted lines, and no new ones (`UpnpClient.java` has none in the added method).
- [x] Coverage adequate for the change: sequence order versus id order, ties, re-add, remove, replacement, re-add after removal, null/unknown players, and both replacement notification paths are covered. The binder tap fix is verified by code reading; the Group 3 manual check should still tap the yaacc renderer row.

### Verdict: PASS

## Cycle 1 — 2026-10-23
Reviewing: Group 3 tasks (commit a42c8854; `git diff 02751ed0..a42c8854`)

What I checked:
- **Shared `RendererStatusMonitor` and the Receiver tab (deviation 1).** For the tab, polling and status delivery are equivalent to before, and in two ways stricter:
  - Same listener body (`bDeviceAdapter.updateStatus`), same 10 s chain per device, subscribed in `onResume` and unsubscribed in `onPause`.
  - The old `stopMonitoring()` only cleared handler callbacks, so a request already in flight still notified the tab and rescheduled itself. The new generation counter stops that.
  - The old `startMonitoring()` on every resume could stack duplicate chains; `polledDevices` now dedupes them.

  The one difference is that a second subscriber gets no immediate poll for devices that are already being polled. The tab and the sheet can't both be subscribed in practice: ViewPager2 caps off-screen tabs at STARTED, so the Receiver tab is paused whenever the Content tab is shown, and the sheet is modal. Neither one can unsubscribe the other, because subscriptions are per listener and polling stops only when the last one leaves. Each subscribe has a matching unsubscribe (tab `onResume`/`onPause`; sheet `onViewCreated`/`onDestroyView`), so I found no leaked subscription. The change is acceptable under "tabs unchanged", since nothing visible changed, but see the Warning about tests and the Suggestion about decisions.md.
- **`UpnpClient` player-list listeners (deviation 2).** Duplicate calls are ignored in `UpnpClient` (`contains` check). `PlayerService` re-adds listeners on (re)bind with `addIfAbsent`, so a rebind registers nothing twice. On disconnect `playerService` is nulled and the listener list kept, so the dead service's notifier goes with it. All callers run on the main thread, and the list is a `CopyOnWriteArrayList`. The sheet's listener is removed in `onDestroyView`. Any caller that never removes its listener would be held for the app's lifetime, as the Javadoc warns.
- **Layout anchors.** Portrait: `contentListBottomSeperator` sits above `contentListCurrentReceivers`, which is aligned to the parent bottom and never set GONE. `contentList` lies between the top and bottom separators, unchanged from before (the GONE top separator is a pre-existing case that `RelativeLayout` handles). Landscape: the bar now anchors only to its parent (start, end and bottom). The old `toStartOf="@id/contentListTopSeperator"` was a GONE anchor, because the vertical divider is hidden at root in `hideFolderNavigationControls`, and it is gone. `contentListBottomSeperator` sits above the bar. The divider's own `alignEnd` points at `contentListCurrentFolderName`, which is always VISIBLE. No anchor targets a view that can be GONE, and `grep contentListCurrentProvider yaacc/src/main` finds nothing.
- **Sheet state.** `setFitToContents(false)` comes before `setHalfExpandedRatio`, `setSkipCollapsed(true)` and `setState`. The container is set to MATCH_PARENT. The state is applied once, in `onStart` after `super.onStart()`. It is `STATE_EXPANDED` when the UI mode is TV or the activity decor isn't in touch mode, and `HALF_EXPANDED` otherwise. When not in touch mode, initial focus goes to the first receiver's checkbox.
- **Theme.** `ThemeOverlay.App.DeviceSheet` has parent `ThemeOverlay.MaterialComponents.BottomSheetDialog`. In the 1.14.0 AAR that overlay sets only window and `bottomSheetStyle` items, no colors. `Theme.MaterialComponents.DayNight.Bridge` resolves to `...Bridge` (dark `colorSurface`) under `values-night-v8` and to `...Light.Bridge` otherwise. So the sheet follows the app's night mode.
- **No-receiver prompt.** It is wired into play, play-all (`BrowseContentItemAdapter`) and the item tap (`ContentListClickListener`), before `ContentItemPlayTask` starts or the loading spinner shows. Nothing else opens UI: the renderer path (`AvTransportMediaRendererPlaying` → `initializePlayers`) and the share-intent path in `TabBrowserActivity` don't call it, and the `PlayerService` toast is unchanged.
- **Untrusted strings and art.** Untrusted strings go through `setText` only: the session name, title and orphan device id, and the receiver bar via `ReceiverBarLabel`. No layout has `autoLink`, and nothing calls `Html`/`Linkify`. Album art loads only through `IconDownloadTask`, with a per-row tag check.
- **Recycling.** There are two view types with separate holders. Session binds reset both optional TextViews in both branches and set the art before the async load. Receiver rows are bound fully by `ReceiverRowBinder`. Stable ids (`R:`/`P:` prefixes) and DiffUtil keep focus across refreshes.
- **Session order.** The sheet lists `DeviceSessionGrouper.group` rows in order, i.e. ascending creation sequence, **oldest first**. The code matches design.md; the builder's "newest first" was a wording slip, and that wording appears nowhere in the repo.
- **Binder executor.** The executor is shut down in `onDestroyView` (`adapter.release()`), and `executeAction` checks `isShutdown()`, so a tap after close can't cause a `RejectedExecutionException`.

### Critical
- None.

### Warning
- [yaacc/src/main/java/de/yaacc/browser/DeviceSheetFragment.java:144-146, 160-165] **The sheet keeps polling while the app is in the background.** The monitor subscription and the 2 s local poller are tied to the view's lifetime (`onViewCreated`→`onDestroyView`), not to visibility. A DialogFragment keeps its view when the activity stops. So if the user leaves the sheet open and presses Home, or taps a session (which opens the player activity on top), the app keeps polling every renderer over the network every 10 s and runs the local poll every 2 s, indefinitely. The Receiver tab, which this replaces, stops in `onPause`. Fix: subscribe and start the poller in `onStart` (or `onResume`), unsubscribe and stop in `onStop` (or `onPause`), and keep the `onDestroyView` cleanup as a backstop. Re-subscribing triggers an immediate poll, so the sheet is fresh again when the user returns.
- [yaacc/src/main/java/de/yaacc/browser/RendererStatusMonitor.java:95-121] **The shared subscribe/unsubscribe and generation logic has no tests,** although it now drives an existing screen (the Receiver tab) as well as the sheet. The cases that matter: per-device dedupe, polling stopping only when the last listener leaves, in-flight results dropped after a generation bump, and a listener removed mid-delivery. I verified these by reading only. Follow the `PlayerListChangeNotifier` precedent: move the listener/device/generation bookkeeping into a plain-Java helper (scheduling passed in as an `Executor`/scheduler) and unit-test it.

### Suggestion
- [.claude/specs/2026-10-23-unified-device-sheet/decisions.md] Record both builder deviations: the new app-wide `RendererStatusMonitor.getShared()` (there was no existing instance to reuse, as design.md had assumed) with the Receiver tab switched to it, and `UpnpClient` holding player-list listeners across rebinds. The planning workflow wants mid-flight decisions logged.
- [yaacc/src/main/java/de/yaacc/browser/DeviceSheetAdapter.java:110-170] The sheet builds its receiver list only when it opens and when players are added or removed. A renderer that appears or disappears while the sheet is open isn't reflected, and a new device isn't added to the monitor subscription. Consider registering an `UpnpClientListener` (`deviceAdded`/`deviceRemoved`, posted to the main thread) that calls `refresh()` and re-subscribes.
- [yaacc/src/main/java/de/yaacc/browser/BrowseContentItemAdapter.java:592-594] "Add to playlist" with no receiver selected and no player running still falls through to `initializePlayers`, which hits the `PlayerService` toast. It is outside this task's Accept list (play, play-all, row tap), but for consistency it could open the sheet too.
- [yaacc/src/main/java/de/yaacc/browser/DeviceSheetFragment.java:73-74] The comment says the listener "holds only the adapter field". As an anonymous inner class it captures the whole fragment. That is harmless because it is unregistered in `onDestroyView`, but the comment is wrong.
- Builder's manual checklist: change "newest first" to "oldest first (start order)" before the phone and TV checks, so the tester knows what to expect.

### Tests
- [x] All tests passing: `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug` reports BUILD SUCCESSFUL, with 201 tests, 0 failures and 0 errors (`ReceiverBarLabelTest` adds 7). Lint's total is unchanged at 191. All hits in the changed files are on pre-existing lines (checked with `git blame`), so there are no new lint issues.
- [ ] Coverage adequate for the change: there are no tests for the shared monitor bookkeeping (see Warning). The sheet, adapter and layouts are Android UI and are covered by the pending manual phone and TV checks in tasks.md, which still need to happen before the tasks are marked `[x]`.

### Verdict: FAIL

## Cycle 2 — 2026-10-23
Reviewing: Group 3 tasks + Fix Group 3 (commit 49181bc7; `git diff 60c1bd7f..HEAD`)

Resolution of cycle 1 findings:
- **W1 (sheet polls while hidden): resolved.** `startLiveUpdates()` runs in `onStart` and `stopLiveUpdates()` in `onStop`, guarded by `liveUpdatesRunning`. `onDestroyView` still calls `stopLiveUpdates()` as a backstop. While the sheet is stopped, nothing keeps it subscribed: the monitor subscription, the player-list listener and the local poller are all released, and the poller also checks `liveUpdatesRunning` before rescheduling itself.
- **W2 (untested monitor bookkeeping): resolved.** Listener set, polled-device dedupe, generation and delivery now live in the plain-Java `MonitorSubscriptions`. `RendererStatusMonitor` delegates to it, with `pollDevice` passed in as the `PollStarter`. Its 10 tests cover the cases listed in cycle 1: per-device dedupe, a second subscriber not restarting chains, duplicate subscribe, last-unsubscriber stop with in-flight results dropped, re-subscribe in a new generation, removal during delivery (by itself and by another listener), plus the unknown/no-op and null cases.

Is the monitor's behavior unchanged? Yes, apart from the two stated no-ops:
- Subscribe, `isCurrent` and `deliver` match the old inline code exactly. A stale result is still dropped without rescheduling, and a current one still delivers and then schedules the next poll.
- **Unsubscribing an unknown listener:** the old code bumped the generation whenever the listener list was empty afterwards. The new code skips the bump only when nothing was subscribed and nothing was polled. In that state the old bump had no effect, because no chain or callback existed to invalidate. If devices are polled with no listener (`subscribe(null, devices)`), the new code still stops them, as before.
- **Null device list:** the old code threw `NullPointerException`; the new code ignores it. Neither caller can pass null: the Receiver tab and the sheet both pass `getDevicesProvidingAvTransportService()`, which always returns a list.
- Neither caller relied on the old behavior. The tab's `onPause` unsubscribes unconditionally, and with the sheet still subscribed the list isn't empty, so old and new both do nothing.

Lifecycle edge cases:
- **Rotation with the sheet open:** the old instance runs `onStop` (unsubscribe, which stops monitoring when it is the last listener) and then `onDestroyView` (a no-op, since the flag is false). The new instance goes `onViewCreated` (list built, `listFresh`) → `onStart` (first start, no second refresh, subscribe gives an immediate poll). There is no double subscribe, and the old instance gets no callback after its view is destroyed. `sheetStateApplied` belongs to each instance, so the sheet returns to its initial half or full state after rotating (see Suggestion).
- **Player activity on top, then back:** `onStop` stops everything, and `onStart` sees `listFresh == false` and calls `adapter.refresh()` before re-subscribing. So players that started or ended meanwhile appear, and the drag state is kept.
- **Dismiss or destroy while stopped:** `stopLiveUpdates()` is already a no-op, `adapter.release()` shuts down the binder executor, and the adapter is nulled.
- **Late callbacks:** a status or player event queued before `stopLiveUpdates()` is dropped. The monitor's `deliver` and the player-list notifier both skip unregistered listeners, and every callback also checks `adapter != null`.
- **`listFresh`:** set only in `onViewCreated` and cleared on the first start, so every later start refreshes. A player event in the gap between `onViewCreated` and the first `onStart` would be missed, but that gap is a single main-thread lifecycle pass, so I accept the risk.

### Critical
- None.

### Warning
- None.

### Suggestion
- [yaacc/src/main/java/de/yaacc/browser/DeviceSheetFragment.java:156-158] After rotation the sheet goes back to its initial state, because `sheetStateApplied` is per instance. If keeping the drag state matters, save `behavior.getState()` in `onSaveInstanceState` and restore it.
- Still open from cycle 1, non-blocking: record the shared-monitor and `UpnpClient` listener deviations in decisions.md (it still has only three entries); refresh the sheet on `deviceAdded`/`deviceRemoved`; optionally gate "Add to playlist" too; and correct the manual checklist's "newest first" to "oldest first".

### Tests
- [x] All tests passing: `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug` reports BUILD SUCCESSFUL, with 211 tests, 0 failures and 0 errors (`MonitorSubscriptionsTest` adds 10). Lint's total is unchanged at 191, with no hits in `DeviceSheetFragment`, `MonitorSubscriptions` or `RendererStatusMonitor`.
- [x] Coverage adequate for the change. The fragment lifecycle wiring is Android UI and is covered by the pending manual phone and TV checks. Those checks should now include rotating with the sheet open, opening a session and pressing Back, and pressing Home and returning.

### Verdict: PASS

## Cycle 1 — 2026-10-24
Reviewing: Group 4 (uncommitted `git diff` of `docs/index.md`, `CHANGELOG.md`, `docs/tech.md`, `tasks.md`)

I checked each claim against the code on `feat/unified-device-sheet`. These hold as built:
- **Ordering.** `DeviceSessionGrouper.group` lists sessions oldest first by creation sequence. `primarySession` picks the newest playing session, otherwise the newest overall. Ties go to the higher player id.
- **Receiver order.** `getDevicesProvidingAvTransportService` puts the local device first, then the rest by name. Each receiver is resolved through `getDevice(id)`. Sessions on a device that isn't a receiver go last.
- **Sheet height.** On touch devices the sheet opens at half height. It opens fully expanded on TV or when the decor view isn't in touch mode, and focus then goes to the first receiver's checkbox. Drag-down and Back dismiss it through `setSkipCollapsed(true)` with a cancelable dialog.
- **Polling.** The shared monitor polls every 10 s and the local poll runs every 2 s. Live updates start in `onStart` and stop in `onStop`, with `onDestroyView` as a backstop, and the list is rebuilt once on return.
- **Listeners.** `UpnpClient` re-attaches player listeners on (re)bind without duplicates.
- **Receiver row tap.** The `LOCAL_UID` row does nothing. yaacc's own renderer opens its player.
- **Theme overlay.** `ThemeOverlay.App.DeviceSheet` is declared as documented.
- **No-receiver gate.** Play, play-all and item tap check for a receiver first. "Add to playlist" isn't gated.
- **Receiver bar.** `contentListCurrentProvider` is gone from both layouts and the code. The bar is never `GONE`, and its label comes from `ReceiverBarLabel`. The path label still starts with the server name, as on the base branch.

Nothing descoped is documented: no phase 2/3 work and no tab removal. Nothing claims the manual checks were done. `tech.md` §5 says "Still to confirm in the manual TV check", and the `tasks.md` status says the checks are pending.

The CHANGELOG "Fixed" entry is accurate in substance. The base `BrowseReceiverDeviceAdapter#getPlayerForDevice` and `openPlayerActivity` took the first match in `HashMap` order: for `LOCAL_UID` that was any non-AVTransport player. Now `ReceiverRowBinder#primaryPlayer` uses `primarySession`, and the local players' `getDeviceId()` returns `LOCAL_UID`. See the Suggestion below on the wording "controls".

The CHANGELOG format matches the existing sections: `## [..]`, `###` subsections, bold lead-ins and a `---` separator. The `docs/index.md` link `#receiver-controls` resolves through kramdown auto-ids (`markdown: kramdown` in `docs/_config.yml`) to `### Receiver controls`. Heading levels, bullet style and tone match the surrounding text. The placeholder grep returns nothing.

### Critical
- None.

### Warning
- [docs/tech.md:745-748] The doc says "the sheet and `ReceiverRowBinder` copy it, `new ArrayList<>(...)`". Only `DeviceSheetAdapter#refresh` copies. `ReceiverRowBinder#primaryPlayer` (`ReceiverRowBinder.java:372`) iterates `upnpClient.getCurrentPlayers()` directly. The code is safe, because the view is a `ConcurrentHashMap` view with weakly consistent iteration (§6, "Group 2: threads…"). The doc is still wrong about the code, and someone could rely on it. Fix: say that the sheet copies the view, and that `ReceiverRowBinder` iterates the weakly consistent `ConcurrentHashMap` view directly on the main thread.

### Suggestion
- [CHANGELOG.md:6] "The Receiver and Player tabs are unchanged" contradicts the "Fixed" entry, which changes the Receiver tab's rows. Consider "The Receiver and Player tabs stay."
- [CHANGELOG.md:6] "instead of only showing an error": on these Content-tab paths no error toast is shown any more. "instead of showing an error" is more accurate.
- [CHANGELOG.md:12] For a remote renderer, the row's play/pause/stop go to the renderer's AVTransport, not to a player. The primary pick decides the album art and the fallback badge, which player a row tap opens, and the controls only for the local device and yaacc's own renderer. Consider "now always shows and opens the most recently started one…".
- [docs/tech.md:748] "The future listener must ignore them" is stale now that the notifier exists. The bullets below it already describe the as-built behavior, so consider "The notifier ignores them".

### Tests
- [x] All tests passing (docs-only change; Group 3 cycle 2 recorded 211 tests green, and no code changed since)
- [x] Coverage adequate for the change (n/a, docs only)

### Verdict: FAIL

## Cycle 2 — 2026-10-24
Reviewing: Group 4 (Fix Group 4) (uncommitted `git diff -- docs/tech.md CHANGELOG.md`)

Resolution of cycle 1 findings:
- **W1 (`getCurrentPlayers()` copy claim): resolved.** `docs/tech.md` §6 now says only the sheet (`DeviceSheetAdapter#refresh`) copies the view, and that `ReceiverRowBinder#primaryPlayer` iterates it directly on the main thread, which is safe because the backing map is a `ConcurrentHashMap`. I checked this against the code:
  - `PlayerService.java:64` declares `currentActivePlayer = new ConcurrentHashMap<>()`. `getCurrentPlayers()` (`:484-486`) returns `Collections.unmodifiableCollection(currentActivePlayer.values())`, a live, weakly consistent view that never throws `ConcurrentModificationException`. `UpnpClient.getCurrentPlayers()` (`:837-842`) delegates to it, or returns an empty list while unbound.
  - `DeviceSheetAdapter.java:129` iterates `new ArrayList<>(upnpClient.getCurrentPlayers())`, so it copies. `ReceiverRowBinder.java:372` iterates `upnpClient.getCurrentPlayers()` directly, so it does not.
  - Every caller of `primaryPlayer` runs on the main thread: `bind` (`:150`, from `onBindViewHolder`), `localDeviceStatus` (`:306`, from the main-looper 2 s pollers in `DeviceSheetFragment` and `ReceiverListFragment`), `openPlayerActivity` (`:327`, click), and `executeAction` (`:415`, click listener; only the AVTransport calls go to the executor, after the player lookup). The claim "on the main thread" is therefore accurate.
  - The Fix Group's Verify, `grep -n 'ReceiverRowBinder. copy' docs/tech.md`, returns nothing.
- **Suggestion (CHANGELOG "unchanged"): applied.** It now reads "The Receiver and Player tabs stay."
- **Suggestion (CHANGELOG "instead of only showing an error"): applied.** It now reads "instead of showing an error".
- **Suggestion (CHANGELOG "controls"): applied.** It now reads "now always shows and opens the most recently started one…".
- **Suggestion (tech.md "The future listener must ignore them"): applied.** It now reads "The notifier ignores them." The bullet below it describes the as-built `put`/`remove(id, player)` rules, which also closes the Group 2 cycle 2 suggestion about the stale "added only when `put` returns null" wording.

No new inaccuracies. I re-checked every other "as built" claim in the diff against the source:
- §4: `applyInitialSheetState` runs once from `onStart()` after `super.onStart()` (guarded by `sheetStateApplied`), uses `setLayoutParams`, and picks `STATE_EXPANDED` when not touch input. `getTheme()` returns `R.style.ThemeOverlay_App_DeviceSheet`, declared in `styles.xml:48` with parent `ThemeOverlay.MaterialComponents.BottomSheetDialog`.
- §5: `isTouchInput()` checks `UI_MODE_TYPE_TELEVISION` and the activity decor's `isInTouchMode()`. `requestInitialFocus()` makes `onBind` post `checkBox::requestFocus`. Stable ids `R:`/`P:` and `DiffUtil` are present.
- §6: the listener API (`PlayerListChangeNotifier.Listener` with `onPlayerAdded`/`onPlayerRemoved`, main-thread posting) matches. `UpnpClient` add/remove re-attaches on bind. The sheet registers in `onStart` and unregisters in `onStop`, with `onDestroyView` as a backstop.
- §7: `PlayerRef.sequence()` exists.
- The intro's caveat that cited line numbers may have moved is warranted. For example, `UpnpClient.java:829-834` is now at 837-842, and `shutdown(Player)` is no longer at 519-525. The §4 and header citations (`styles.xml:21`, `build.gradle:20`) are still exact.

CHANGELOG format still matches the existing sections.

### Critical
- None.

### Warning
- None.

### Suggestion
- [docs/tech.md:459-460] The intro says the line numbers in "sections 6 and 7" may have moved. §7 cites no line numbers, while §4 (`styles.xml:21`) and the header (`build.gradle:20`) do, though both are still correct. Consider "line numbers in section 6".
- [yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java:365] (Code comment, outside this docs group.) The `primaryPlayer` Javadoc still says `getCurrentPlayers()` "iterates in HashMap order". The map is now a `ConcurrentHashMap`, and its order is still unspecified, so the point still holds, but "in map order" would be accurate. Fix it the next time the file is touched.

### Tests
- [x] All tests passing (docs-only change; Group 3 cycle 2 recorded 211 tests green, and no code has changed since: `git status` shows only `docs/`, `CHANGELOG.md` and spec files modified)
- [x] Coverage adequate for the change (n/a, docs only)

### Verdict: PASS

## Cycle 1 — 2026-10-24
Reviewing: Group 5 (phase 2) (commits e80480e7 red tests and stubs, 581205fc `docs/tech.md` "Classic and new layout" plus tasks.md/decisions.md amendments)

Verification run: `./gradlew :yaacc:testDebugUnitTest --offline` gives 239 tests, 26 failures, 0 errors. All 26 are `java.lang.AssertionError` (`BrowserLayoutChoiceTest` 11, `MiniPlayerPickerTest` 15); the other 213 tests are green. The 26 are not the 12 + 16 the tasks.md status line implies: `BrowserLayoutChoiceTest#defaultStoredValueIsClassic` and `MiniPlayerPickerTest#inputListIsNotModified` pass against the stubs (harmless, see Suggestions). Stubs are plain Java (no `android.*` imports) and `MiniPlayerPicker` reuses `DeviceSessionGrouper.PlayerRef`.

Research check: I verified about 30 file:line citations against the checkout and found them accurate. These include the four dead `getParent()` branches (`PlayerListFragment:105`, `ReceiverListFragment:157`, `ContentListFragment:551`, `ServerListFragment:162`), `ServerListClickListener:49-51`, `ServerListFragment:238`, the share handler and `setCurrentTab` (`TabBrowserActivity:257-331, 315, 334-341`), the manifest filters (`AndroidManifest.xml:46-66`), `Yaacc.java:74` (`setDefaultValues(..., false)`), the three launches (`Yaacc:172`, `PlayerService:106, 199`), the shutdown timer in `Yaacc:65-66, 194-226` and its wiring in `ServerListFragment:249-299`, the static `MediaProjectionHelper.stopCallback` (`:53-59`), request code 2001, the four `SettingsActivity` entry points, the per-instance idle timer (`TabBrowserActivity:378-401`) and the four other `LAUNCHER`-without-`MAIN` activities. I also fetched the AOSP `android-14.0.0_r1` sources and confirmed `Activity.java:8753-8761` (NoDisplay `IllegalStateException`), `Intent.java:7557, 7572` (copy keeps flags and ClipData), `ActivityStarter.java:605, 1750` and `ActivityRecord.java:3967, 5164`. Activity 1.8.0 and fragment 1.5.4 are in the Gradle cache.

Key conclusion holds: `Activity#getParent()` is only non-null for `ActivityGroup` children. The repo has no `ActivityGroup`/`TabActivity`/`LocalActivityManager`, and `TabBrowserActivity` is a plain `AppCompatActivity` hosting its fragments in its own `FragmentManager`, so the four branches never run. Deleting only the `if (getParent() ...)` block leaves each handler's `return true` (and the `try/requireActivity()` log in `deviceAdded`) intact, so classic behavior is unchanged. The doc labels sources honestly ([code]/[javap]/[docs]/[aosp]/[reasoned]). The trampoline URI-grant claim sits in a separate "Reasoned from AOSP (not stated in the public docs)" subsection and in on-device check 1, and six on-device checks are listed. The placeholder grep on `docs/tech.md` finds one hit (`:1103`, the quoted code comment `//FIXME dirty`), which is not a plan placeholder.

### Critical
- None.

### Warning
- [yaacc/src/test/java/de/yaacc/browser/MiniPlayerPickerTest.java:28-31, 100-117] The Javadoc says fixtures use ids that run against the sequence so an id-based implementation fails, but that holds for one test only. In `amongPlayingSessionsTheHighestSequenceWins` (seq 1/7/4 with ids 3/900/50) and `whenNothingPlaysTheHighestSequenceWins` the ids are in the same order as the sequences, so a comparator that falls back to `playerId` instead of `sequence` passes both. The only discriminating case is `sessionOnUnknownDeviceCompetesLikeAnyOther` (seq 9/id 5 vs seq 1/id 6), and it is non-playing only. Recommended fix: give the newest session in the among-playing test the lowest id (for example seq 7/id 3, seq 1/id 900, seq 4/id 50), and do the same for one non-playing case. Group 8 will otherwise go green with a weaker test than documented.
- [design.md:213-216] Phase 2 still says "Fragments stop casting their host to `TabBrowserActivity` ... `BrowserHost` (`showServers()`, `showContent()`, `showPlayers()` or equivalent) ... In the new layout players/receivers map to the sheet". That contradicts the amended tasks.md Group 6 and decisions.md (2026-10-24), where four of the five fragment branches are dead and are deleted, not wired, and only `ServerListClickListener` and the share handler call the host. design.md was not amended in 581205fc. Recommended fix: reword to "the two live sites go through `BrowserHost`; the four dead `getParent()` branches are removed (classic back stays as is)".
- [tasks.md:117, 143 vs 109/119; BrowserLayoutChoice stub] Group 6 and Group 9 disagree about where the "new" behavior and the preference key live. Group 6 requires the Group 5 tests green, and `resolve("new")` must return `NEW` there (`storedNewResolvesToNew`), yet Group 6 says "until Group 7 exists `NEW` resolves to classic" and Group 9 says `BrowserLayoutChoice` "now returns `NEW` when `"new"` is stored". Also, Group 6's launch helper and `onResume` check must read the layout preference, but the key string resource is only added in Group 9 (`preference key added as a string resource`). Recommended fix: state that `resolve()` is final in Group 6 and that the "until Group 7" fallback lives in the android-side launch helper; add the key (and the `values` default-less read, `getString(key, null)`) in Group 6, and drop "now returns NEW" from Group 9. Related: the doc's trampoline sketch calls `BrowserLayoutChoice.targetClass(this)`, which would need `android.*` in a class whose stub and tests are explicitly plain Java; it should name the separate launch helper (the doc's other snippet already uses `LaunchHelper.browserIntent`).
- [tasks.md:123 vs docs/tech.md:1135-1144] The research says the process-wide idle timer is "Out of scope to fix in classic unless the owner asks; required for the new activity's design", but Group 7 makes one timer "serve both activities", i.e. it changes classic, and Group 6 says classic is unchanged "apart from fixing the leaks". decisions.md lists "One process-wide idle timer" with no note that the owner agreed to touch classic. Recommended fix: either record the owner's OK in decisions.md and add the classic change to Group 6/7 wording, or make Group 7 keep the classic timer as is.

### Suggestion
- [docs/tech.md:895] `appcompat:1.8.0` is cited at `build.gradle:53`; it is line 50 (53 is `preference:1.2.1`). Material is at line 20.
- [docs/tech.md:1396-1420] The subsection "Verified from documentation" contains an `[aosp]`-tagged item (`Activity.java:8753-8761` enforcement). The tag makes it honest, but it belongs under the AOSP heading.
- [tasks.md:109, docs/tech.md:960-963] `providesShutdownTimer()` has no consumer: Group 7 builds a separate top-level server fragment instead of reusing `ServerListFragment`, so nothing needs to hide the classic timer row. `showReceivers()`/`showServers()` have no classic caller either. Consider dropping the capability from the Group 6 Accept until Group 8 needs it.
- [tasks.md:109-111] `ServerListFragment.java:238` (`v.getContext() instanceof TabBrowserActivity` guard, site 6) is neither removed nor kept explicitly. The Group 6 grep only covers `getParent() instanceof`. Say "leave as is" or "drop the pointless guard".
- [yaacc/src/test/java/de/yaacc/browser/MiniPlayerPickerTest.java:174-183, BrowserLayoutChoiceTest.java:77-80] Two tests (`inputListIsNotModified`, `defaultStoredValueIsClassic`) are green against the stubs, so the red phase does not prove them. Fine for a no-mutation guard, but update the tasks.md status to "26 failures (11 + 15)".
- [docs/tech.md:1539-1590] (f) says the browsers "can be `exported="false"`" and (g) then recommends leaving `TabBrowserActivity` exported without filters. The decision is consistent with decisions.md; a one-line pointer in (f) would avoid confusion.

### Tests
- [x] All tests passing (the only failures are the 26 intentional red assertions; 213 other tests green, 0 errors)
- [x] Coverage adequate for the change (see Warning 1: tie and id-vs-sequence discrimination among playing sessions is weaker than documented)

### Verdict: FAIL

## Cycle 2 — 2026-10-24
Reviewing: Group 5 (phase 2) (commit bf40ced6, fixes for cycle 1 W1-W4 plus the applied suggestions)

Verification run: `./gradlew :yaacc:testDebugUnitTest --offline` gives 239 tests, 26 failures, 0 errors. All 26 are `java.lang.AssertionError` (`BrowserLayoutChoiceTest` 11, `MiniPlayerPickerTest` 15); the other 213 tests are green. This matches the corrected tasks.md status line (11 + 15).

W1 (fixtures) resolved. In `amongPlayingSessionsTheHighestSequenceWins` and `whenNothingPlaysTheHighestSequenceWins` the newest session (seq 7) now has the lowest id (3), the oldest (seq 1) the highest (900), the middle seq 4/id 50. An implementation that ranks by `playerId` (alone, or after the playing check) returns the seq-1/id-900 session and fails both `assertSame(newest, ...)`; so does the old order-agnostic stub default. `resultDoesNotDependOnInputOrder`, `playingSessionBeatsNewerNonPlayingSession` and `sessionOnUnknownDeviceCompetesLikeAnyOther` already ran against the id order. The Javadoc claim now holds. Tie-break tests (equal seq, id decides) are unchanged and still correct.

W2 (design.md) resolved: the BrowserHost bullet now names the two live call sites, the deletion of the four dead branches, `OnBackPressedDispatcher`, and receivers -> sheet / players -> mini-player. It agrees with tasks.md Group 6 and decisions.md.

W3 (Group 6 vs 9) resolved: `BrowserLayoutChoice` stays plain Java and `resolve("new")` returns `NEW` in Group 6; the Android-dependent `BrowserLauncher` carries the `NEW_LAYOUT_AVAILABLE = false` gate; the preference key string resource is in Group 6's file list and Group 9 only adds the visible preference, entries and strings. Leftover grep over tasks/design/decisions/requirements for "NEW resolves to classic", "exactly one exported", "CLEAR_TASK" and "fragments ask the host" finds only the Group 9 constraint "no CLEAR_TASK" (intended). The "until Group 7" wording survives only in the `BrowserLauncher` clause.

W4 (idle timer) resolved: the process-wide timer is now in Group 6's Accept and Constraints, Group 7 says "uses the process-wide idle timer from Group 6", and decisions.md 2026-10-24 records the reason (relaunch leaves the old instance's 5-minute `Yaacc.exit()` queued) and that it is flagged to the owner.

Applied suggestions verified: `build.gradle:50` is the appcompat line; Group 6 Accept covers `ServerListFragment:238` (guard becomes `instanceof BrowserHost`); `providesShutdownTimer()` is gone from the Group 6 Accept ("no speculative ones"); the status line counts are corrected.

### Critical
- None.

### Warning
- None.

### Suggestion
- [docs/tech.md:1002, 1268] `providesShutdownTimer()` is still described as an optional Group 8 default method. Acceptable: tasks.md Group 6 Accept is the authority and no longer lists it, the tech.md text says "Optional", and the as-built update is Group 10. Consider a one-line "superseded: not in Group 6" note so a Group 8 implementer does not add a consumerless method.
- [docs/tech.md:1141-1144] Still says the process-wide idle timer is "out of scope to fix in classic unless the owner asks". tasks.md Group 6 and decisions.md 2026-10-24 now make it in scope (flagged to the owner). Same status: research record, fix in Group 10 or annotate.
- [docs/tech.md:1501] The trampoline sketch calls `BrowserLayoutChoice.targetClass(this)`, but the plan now puts the Android-dependent lookup in `BrowserLauncher` (the sketch at `:1152` uses a third name, `LaunchHelper`). Rename in the as-built pass.
- [tasks.md:117 vs 123] Group 6 says `NEW_LAYOUT_AVAILABLE` is "flipped in Group 7"; Group 7 says it is flipped only at the end of Group 8. Group 7's wording is the intended one; align Group 6.
- [tasks.md:108] The status line says "213 other tests green", which includes the two new tests that pass against the stubs (211 pre-existing + 2). Cosmetic.

### Tests
- [x] All tests passing (the only failures are the 26 intentional red assertions; 213 other tests green, 0 errors)
- [x] Coverage adequate for the change

### Verdict: PASS

## Cycle 1 — 2026-10-24
Reviewing: Group 6 (phase 2), commits d23da4b2 (part A) and 16eac1a1 (part B)

Verification run: `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug --offline --continue`.
Build OK; 298 tests, 15 failures, all `AssertionError` in `MiniPlayerPickerTest` (Group 8 stub), nothing else (checked per testcase in the result XML); lint 189 issues (72 errors / 117 warnings), no issue id that is not already in the saved baseline, none located in the new files.
`grep "getParent() instanceof TabBrowserActivity"` and `grep "new Intent(getIntent())|new Intent(in)"` over `yaacc/src/main/java` find nothing.

### Critical
- none

### Warning
- none

### Suggestion
- [ShutdownTimerController wiring, ServerListFragment.java: `shutdownTimerDisplay` field] The display lambda captures the fragment's views and the field survives `onDestroyView`, so a destroyed view hierarchy stays reachable until the fragment itself is destroyed (ViewPager2 keeps the instance only while attached, so the window is short). Null the field in `onDestroyView`, or build the display in `onStart`.
- [ShareIntentHandler.java:handle / TabBrowserActivity.java:onCreate] `onPlayersStarted` is `this::showPlayers` of the activity that received the share. If that activity is destroyed during the 3-6 s wait (rotation), the share still plays (graceful `shutdown()` runs scheduled tasks; JDK default `executeExistingDelayedTasksAfterShutdown=true`) but the tab switch goes to the dead instance, and the instance is held for those seconds. The old code did exactly the same, so this is not a regression; a note for the "share feature improvements" backlog item.
- [ShareUriPolicyTest.java] Add `"http:"` (scheme, no host; currently allowed, then harmless in `createPlayableItem`), `"javascript:\nhttp://x"` and a text with an embedded newline after a valid `http://` prefix, to document the intended behavior of the edge cases.
- [BackgroundIdleTimer.exitIfIdle, StreamingPermissionHelper.requestPending] The "no players and no server/renderer/proxy enabled" condition and the pending-request gating live in Android classes and have no JVM test. The counter (the part with real logic) is covered. Optional: extract `shouldExit(boolean players, boolean server, boolean renderer, boolean proxy)` into a plain class. Not blocking: the condition is a line-for-line copy of the old one (same four checks, same preference keys, same `Yaacc.exit()`).
- [StreamingPermissionHelper.requestPending] After process death during the permission dialog the result is now ignored (the static flag is false in the new process). Before, the grant was stored but nothing was enabled either (the pending audio/video flags are static too), so the user-visible result is the same except that the next tap prompts again. Accepted; worth one line in decisions.md.
- [decisions.md] The ClipData-URI narrowing in the share handler and the launcher is only in tasks.md Accept; log it with the pending http/https owner decision so it is not lost. tasks.md Group 6 checkboxes are still `[ ]` and the group has no status line (Group 5 has one); update after the gates.
- [on-device, from docs/tech.md (f)] Launcher re-tap with a finished trampoline root, Theme.NoDisplay splash on Android 12+, and the share sheet landing in the sharer's task are reasoned, not run. Keep them in the owner's on-device checklist.

### Findings by check (all satisfied)
1. Build/tests/lint as expected (see above).
2. Classic behavior preserved. The four removed `getParent()` branches are the only lines removed from `PlayerListFragment`, `ReceiverListFragment`, `ContentListFragment` (each handler still returns `true` / continues as before). In `ServerListFragment`, the `deviceAdded` try/catch protected only the dead branch and went with it; the `v.getContext() instanceof BrowserHost` guard and `ServerListClickListener` use the host. `TabBrowserActivity` implements `BrowserHost` through the unchanged `setCurrentTab`; saved-tab restore intact. `onResume` order: super, relaunch check (part B, first), swipe preference, `LocalServerLifecycle.apply(this, SettingsActivity.consumeLeftSettings())`. `apply` equals the old block (on: stop first only after a settings visit, then start foreground; off: stop), and the flag is reset on every path as before; an early relaunch return leaves the flag for the next browser, as designed. All six `startActivity(new Intent(..., SettingsActivity.class))` sites go through `SettingsActivity.onPause`, which still sets the flag.
3. `ShareIntentHandler`: 3 s receiver wait, 3 s player-service wait, "start in N seconds" toast and the "no receiver found using local device" fallback unchanged; worker `Exception`s caught and logged by class name only; payload never logged; main-looper handler; text only reaches `Uri.parse` -> `createPlayableItem`; `readAndClearSharedText` clears EXTRA_TEXT/ClipData like the old code so a recreated activity does not replay it. `ShareUriPolicy` extracts the scheme exactly as `Uri.getScheme()` (text before the first ':'), ASCII case-insensitive, nothing trimmed, 2048 cap (dropped, not truncated); the tests cover `HTTP://`, leading space/tab/newline, `javascript:`, `file:`/`content:`, bare paths, `/sdcard/http://x`, exact cap and cap+1. Host application context only.
4. Idle timer: counter arms only on the 1 to 0 transition, `onStart` cancels, unbalanced stop ignored, expiry re-checks the count; rotation (stop, destroy, create, start) and layout switch (new start before old stop) are tested in both orders. `BackgroundIdleTimer` keeps only the application context and re-reads preferences and `getCurrentPlayers()` when it fires; the exit condition is identical to the old runnable.
5. `Yaacc` listener list is a `CopyOnWriteArrayList` with add-if-absent, per-listener exception isolation, main-thread start/ticks; `ShutdownTimerController` attaches in `onStart`, detaches in `onStop`, holds no Context/View itself. `MediaProjectionHelper` is multi-listener, each owner removes exactly its own callback, delivery on the main looper (explicit handler; the old `null` handler depended on the creating thread). `StreamingPermissionHelper`: result Intent goes straight into the helper's static fields, extras not logged, accepted only for `REQUEST_CODE_MEDIA_PROJECTION` with a pending request; the pending flag is static, so a rotation (new fragment, new helper) still receives its answer. Denial path unchanged (clear pending flags, both preferences false, adapter refresh). The process-wide state reset plus per-screen UI refresh (registered `onStart`, removed `onStop`) is sound; a missed refresh while stopped is repaired by `ServerListFragment.onResume` (`populateDeviceList` rebinds from preferences).
6. Manifest diff is exactly: `LauncherActivity` added with the three filters verbatim (VIEW+MAIN+DEFAULT+LAUNCHER, MAIN+LEANBACK_LAUNCHER, SEND text/plain), `Theme.NoDisplay`, label; `TabBrowserActivity` has no filters and stays `exported="true"`. `LauncherActivity` extends `android.app.Activity`, builds a fresh `Intent`, forwards only a SEND/`text/plain` with a capped non-empty text (decided by the tested `LauncherForwardPolicy`), reads inside `try/catch(RuntimeException)`, `finish()` in `finally`, never touches flags/data/ClipData URIs/other extras. `BrowserLauncher.effectiveLayout` is the single function (default `null`, `NEW_LAYOUT_AVAILABLE=false` makes everything CLASSIC), used by both the launch helper and the resume check; `RelaunchGuard` is synchronized, monotonic clock, denied calls do not extend the window. The three internal launches (`Yaacc.java`, `PlayerService` x2) use `BrowserLauncher.targetClass`; PendingIntent flags unchanged. Share path traced: SEND -> `LauncherActivity` -> fresh SEND/text/plain with EXTRA_TEXT -> `TabBrowserActivity.onCreate` -> `shareIntentHandler.handle(getIntent(), this::showPlayers)`; `onNewIntent` is also wired.
7. Tests cover the Accept criteria: `StartedBrowserCounterTest` (11 cases incl. all required scenarios), `ShareUriPolicyTest`, `RelaunchGuardTest` (14), `LauncherForwardPolicyTest` (16), `BrowserLayoutSwitchTest`, `BrowserLayoutChoiceTest` (green now), `ShutdownTimerControllerTest` (8, mocks). Android-only classes (`ShareIntentHandler`, `BackgroundIdleTimer`, `StreamingPermissionHelper`, `LauncherActivity`) keep their decisions in the tested plain-Java classes; no untested critical path found.

### Tests
- [x] All tests passing (the only failures are the 15 intentional red `MiniPlayerPickerTest` assertions for Group 8; 283 other tests green, 0 errors)
- [x] Coverage adequate for the change

### Verdict: PASS

## Cycle 1 — 2026-10-24
Reviewing: Group 7 (phase 2), commit e95c9a2b

Verification run: `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug --offline --continue`.
Build OK; 306 tests, 15 failures, all `AssertionError` in `MiniPlayerPickerTest` (Group 8 stub), nothing else; lint 189 issues (72 errors / 117 warnings), no issue id outside the existing set, none located in the new files (`BrowserActivity`, `activity_browser.xml`, `fragment_server_root.xml`, `menu/activity_browser.xml`, `ic_baseline_timer_24.xml`); the one `BatteryLife` hit moved with the code into `BrowserActivitySupport` (one before, one after). Merged debug manifest: `BrowserActivity` `exported="false"`, no intent filter, no `launchMode`, no `taskAffinity`, no `parentActivityName`; `TabBrowserActivity` unchanged.

### Critical
- none

### Warning
- none

### Suggestion
- [fragment_server_root.xml:`serverListLocalServerEnabled`] The yaacc server `SwitchMaterial` has no accessible name (the label is a separate non-focusable `TextView`), so TalkBack announces only "switch, on". Classic has the same gap, but this layout is new: add `android:contentDescription="@string/upnp_server"` or `android:labelFor` on the label. Same file: the provider/renderer/proxy icons are `importantForAccessibility="no"`, so their on/off state is invisible to TalkBack (classic gave them the wrong description, "Settings"). Consider a state-aware description set in `setLocalServerState`. The header `TextView` could carry `android:accessibilityHeading="true"`.
- [BrowserActivity.java:`toggleShutdownTimer` ASK_DURATION] After the picker is confirmed the timer is not started; the user must tap again. Recorded in decisions.md as "revisit after the owner tries it"; keep it in the on-device checklist (Group 9 manual checks already list "shutdown timer in the toolbar").
- [BrowserActivity.java:`onCreate`] A share that arrives in an instance that relaunches from `onCreate` is dropped (the comment says so); classic consumes it first and then relaunches. Only reachable if the setting changes within the few milliseconds between the launcher decision and `onCreate`, so acceptable; mention it in the Group 10 as-built note so nobody expects replay.
- [BrowserLauncher.java:imports] `java.util.Locale` is placed between the `android.*` and `androidx.*` blocks. Cosmetic.
- [ContentListFragment.java:`isAtRoot` / UpnpClient.java:`removeUpnpClientListener`] No unit test. `isAtRoot` is the old expression moved verbatim and the back decision built on it is covered by `BrowserBackNavigationTest`; the listener removal is a one-line `CopyOnWriteArrayList.remove`. Optional: a small `Navigator`-based test for `isAtRoot` if `Navigator` is plain Java.
- [TabBrowserFragmentStateAdapter.java / ContentListFragment.java `bItemAdapter`] Pre-existing, not caused by this commit: the adapter caches one fragment instance per tab, so a re-added `ContentListFragment` keeps its old `bItemAdapter` (built for the previous `RecyclerView`) while `initBrowsItemAdapter` only builds one when null. The new `cancelRunningTasks()` in `onDestroyView` does not make this worse (the next `onResume` cancels and reloads exactly as before), but if classic ever shows a blank Content tab after tab hopping, this is where to look. The new layout creates a fresh `ContentListFragment` per server tap, so it is not affected.
- [on-device] Not run on a device (the activity is unreachable until Group 8 flips `NEW_LAYOUT_AVAILABLE`): D-pad order of header/refresh/list/footer, toolbar item crowding with the running timer text on a narrow phone, back stack after rotation with content on top, process-death restore. Keep them in the Group 9 manual checks.

### Findings by check (all satisfied)
1. Build, tests and lint as expected (see above).
2. Classic unchanged. `BrowserActivitySupport` is verbatim: the three permission arrays (TIRAMISU, R, default) identical line by line, request code 101, "all permissions granted" log, the battery prompt (empty intent plus swallowed `ActivityNotFoundException`) and the four menu items (exit without `finish()`, settings, about, log). `TabBrowserActivity.onCreate` keeps the order permissions then battery prompt; `onResume` is super, `BrowserLauncher.relaunchIfOtherLayout(this, CLASSIC)`, swipe preference, `LocalServerLifecycle.apply`: same order and same early return as before; the helper computes the effective layout once and derives the target from it (it computed it twice before), and keeps the guard, the log, `overridePendingTransition(0, 0)` and `finish()`. `ServerListFragment` in classic still inflates `fragment_server_list` (`providesShutdownTimer()` is a `default false`, only `BrowserActivity` overrides it) and the whole timer block runs unchanged inside `if (!hostProvidesShutdownTimer)`. `ContentListFragment#isAtRoot` equals the old expression (the local copy only avoids a second field read). Listener changes: `UpnpClient.listeners` is a `CopyOnWriteArrayList`; every iteration is either over `new ArrayList<>(listeners)` or a plain for-each (snapshot iterators), no `Iterator.remove`/`sort`, so no `ConcurrentModificationException` and no `UnsupportedOperationException`; `add` is not de-duplicated and `remove` takes one registration, so the pair `init` (add) / `onDestroyView` (remove) is balanced for a re-created view and a double `onDestroyView` cannot remove a stranger. `ContentListFragment.onDestroyView` cancels the load tasks before `super`; the classic pager re-adds the same instance, whose next `onViewCreated` and `onResume` re-register and reload, so the cancel only stops work nobody can see (`loading`/`allItemsFetched` are reset by the cancel exactly as `populateItemList` does).
3. `BrowserActivity`. Implements all four `BrowserHost` methods plus `providesShutdownTimer`. `showContent`: main thread, skips when finishing or state saved, `executePendingTransactions` in try/catch (a second tap sees the first), no-op when content is already on top, `replace(...)` with `addToBackStack`. `showServers`: state-saved guard, `popBackStack(name, INCLUSIVE)` (no-op without an entry). `showReceivers` uses `DeviceSheetFragment.show`, which has its own saved-state/duplicate guard. Back: `OnBackPressedCallback` registered once with the lifecycle owner, no `onBackPressed` override; the decision is `BrowserBackNavigation.decide(onServerList, atRoot)`, which matches tech.md (b): below the root the fragment's own `onBackPressed()` (no dead `getParent()` branch, `navigator` cannot be null there because `isAtRoot()` is true for null), at the content root `showServers()`, on the server list `Yaacc.exit()` plus `finish()`; no content fragment or no fragment at all falls to EXIT. Lifecycle: layout check is the first thing in `onCreate` (before `setContentView`, before any intent read) and in `onResume` right after `super`; `finish()` in `onCreate` skips `onStart`/`onResume`, so the idle counter is never unbalanced and the second check cannot double-trip the guard; counter `browserStarted` in `onStart`, `browserStopped` after `super.onStop()`; `ShutdownTimerController.attach` in `onStart`, `detach` before `super.onStop()`; `shareIntentHandler.shutdown()` in `onDestroy`; the share is handed over only after the check, with a null callback, and `onNewIntent` is the same handler (idempotent, no other path). No `getReferrer`/`getCallingPackage`/`startActivity`/`PendingIntent` in the file; `getIntent()` is read once, for the share handler. Rotation and process death: `ServerListFragment` is added only when `savedInstanceState == null`; `ContentListFragment` re-obtains `upnpClient` from `Yaacc` in its own `init`, restores its `Navigator` from its own saved state (`CONTENT_LIST_NAVIGATOR`) and reads the provider from the client, so it needs nothing from the activity; `Yaacc.upnpClient` is created in `Yaacc.onCreate`, so it is not null in a restored process. After process death the provider is gone and the content shows an empty root, from which back leads to the servers (acceptable). Timer item: state is stored in fields and applied whenever the item exists (created lazily, nulled in `onDestroy`), updated in place per tick, checked plus always-visible text while running, label and content description carry the remaining time, picker in the overflow (`menu_shutdown_timer_set`). `ShutdownTimerToggle`: running -> STOP, stopped with duration -> START, stopped without -> ASK_DURATION, so a duration of 0 can never exit the app by a tap.
4. `ServerListFragment` reuse. Every `findViewById` in the fragment is satisfied by `fragment_server_root.xml`: `serverList`, `serverListRefreshButton`, `serverListLocalServerEnabled`, `serverListLocalServerSettings`, `serverListProviderEnabled`, `serverListReceiverEnabled`, `serverListProxyEnabled` (the three timer ids are only read inside the skipped block; `onSaveInstanceState` no longer touches views). Footer per design: label, switch (48dp min height), gear (48dp target, description `settings`), provider/renderer/proxy icons; refresh sits in the "Servers" header as a 48dp button with description `search_devices`. Order top to bottom is a sensible D-pad order (header refresh, list, switch, gear; label and icons non-focusable). The server row, its stream buttons and `IconDownloadTask`/`setText` handling are in the untouched `BrowseDeviceAdapter`, so the Group 6 requirement for LAN names and icons holds. `shutdown_timer_set` and `browser_servers_header` are present exactly once in values, -de, -es, -fr, -nl, -pt, -zh.
5. `BrowserLauncher`: `targetClass(Layout)` has the `NEW -> BrowserActivity` branch, `targetClass(Context)` delegates through `effectiveLayout`, `NEW_LAYOUT_AVAILABLE` is still false (so `effectiveLayout` is CLASSIC for every stored value and `BrowserActivity` is unreachable). `relaunchIfOtherLayout` computes the effective layout once, relaunches only on mismatch, goes through the shared `RelaunchGuard` (when it trips the activity stays and logs) and sends a fresh `new Intent(activity, target)`; the two browsers own different layout constants, so a stable setting cannot ping-pong.
6. Tests: `BrowserBackNavigationTest` (4 cases: pop, show servers, exit, content state ignored on the server list) and `ShutdownTimerToggleTest` (3 tests, 5 assertions: stop with and without duration, start, ask for 0 and negative) cover both decision functions completely. The remaining logic is Android view/fragment wiring (transactions, menu item state) that has no JVM seam; no untested critical decision found.

### Builder deviations (decisions.md 2026-10-24 "Group 7 as built")
1. Timer tap opens the picker when no duration is stored: correct. Starting with 0 would call `exit()` on the first tick; the classic switch can do that, a toolbar icon must not. Suggestion only (above).
2. About and Log in the new menu: correct (parity with classic, same shared handler, same ids as `menu/activity_main.xml`).
3. Classic listener-leak fix: correct and safe (see check 2). It changes classic only by no longer calling destroyed fragments.
4. In-screen back arrow postponed to Group 8: correct; the system back already reaches the server list, and the Group 8 task in tasks.md carries the arrow explicitly.
Reusing `ServerListFragment` through `providesShutdownTimer()` instead of a separate `ServerRootFragment` is also sound (no duplicated adapter wiring, classic path untouched) and is the one thing the tasks.md Group 7 second task title does not literally say; decisions.md records it.

### Security-review "Group 7 must" list (security-review.md Group 6 cycles 1-2)
- not exported, no filters, no `launchMode`/`taskAffinity`: met (merged manifest checked).
- `getIntent()` only to `ShareIntentHandler` with a null callback; no other extra/data/ClipData read; no `startActivity`/`PendingIntent` derived from the intent; no deep link; `onNewIntent` only the share handler: met.
- effective layout computed once, target derived from it, check before the share handler, same `RelaunchGuard`, fresh intent: met.
- idle counter in `onStart`/`onStop`, `shareIntentHandler.shutdown()` in `onDestroy`, no `getReferrer()`/`getCallingPackage()`: met.
- LAN-supplied names/icons as plain text / `IconDownloadTask`: met (adapter unchanged).

### Tests
- [x] All tests passing (the only failures are the 15 intentional red `MiniPlayerPickerTest` assertions for Group 8; 291 other tests green, 0 errors)
- [x] Coverage adequate for the change

### Verdict: PASS


## Cycle 1 — 2026-10-24
Reviewing: Group 8 (phase 2)

Scope: `git show fdb8dee9` (MiniPlayerPicker, MiniPlayerPresenter, MiniPlayerBar, ReceiverBarController, BrowserActivity, BrowserHost, BrowserLauncher, ContentListFragment, ServerListFragment, layouts, 7 locales, MiniPlayerPresenterTest) against tasks.md Group 8, design.md Phase 2 and the "Group 8 must" list of security-review.md (Group 7 cycle 1).

### Verified
1. Build and tests: `:yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug` succeed. Tests re-run with `--rerun`: 328 tests, 0 failures, 0 errors (51 suites). Lint: 189 issues, same id set as before; none in `mini_player_bar.xml`, `activity_browser.xml`, `fragment_server_root.xml`, `MiniPlayer*`, `ReceiverBarController`. The commit touches no manifest or gradle file; merged manifest has `BrowserActivity` `exported="false"`, no filter, no new exported component.
2. `MiniPlayerPicker` is plain Java, does not modify its input, skips null entries, ordering as tested (16/16 green). `MiniPlayerPresenter.cleanText`: whitespace/space-separator runs collapse to one space, leading/trailing dropped, `Cc` and U+202A-202E, U+2066-2069, U+200E/F, U+061C removed, cap 200 counted in chars per code point (a pair is never split, no trailing space left at the cut). Correct for the cases tested.
3. `MiniPlayerBar`: featured session from a copy of `getCurrentPlayers()` (`new ArrayList<>`), `PlayerRef(id, deviceId, playing, creation sequence)`; text via `setText` only; art only via `IconDownloadTask(art, key)` with the tag compared in `onPostExecute` and cleared in `unbind`; commands on a single-thread executor created in `bind()` and shut down in `unbind()` (no leak over onStart/onStop cycles; `isShutdown()` check plus `RejectedExecutionException` catch); listener, 2 s poll and 500 ms refresh are removed in `unbind()` and `refresh()`/poll ignore events while `!bound`; `BrowserActivity` binds in `onStart`, unbinds in `onStop` and `onDestroy`; tap uses `ReceiverRowBinder.openPlayer` (`getNotificationIntent()`, empty `Intent`, null and `CanceledException` handled); bar is GONE without players; play/pause decision is `MiniPlayerPresenter.toggleAction`, same play/pause/stop calls as `ReceiverRowBinder`. Accessibility: content descriptions on all four buttons (play/pause switched with state), 48dp buttons, card focusable before its buttons, custom click label, theme attributes only, no hard-coded colors.
4. `ReceiverBarController`: classic `ContentListFragment` behavior matches `HEAD~1` (same tint, same accessibility delegate and label, same refresh on receiver add/remove, initial update, bar opens the sheet as child fragment); bind in `init`, unbind in `onDestroyView` (once per view); `BrowserActivity` binds in `onStart`, unbinds in `onStop`/`onDestroy`; callbacks hop to the main thread; `update()` ignores calls after `unbind()`. No-receiver prompt: `openDeviceSheetIfNoReceiverSelected` -> `showDeviceSheet` -> `host.showReceivers()` -> `DeviceSheetFragment.show(getSupportFragmentManager())` which has the state-saved and duplicate guard.
5. Layouts: portrait `contentListBottomBar` is never GONE, the list anchors to it, separator and bar sit inside; classic look unchanged. Landscape: nothing anchors to the separator or bar, sort toggle uses `layout_alignParentTop` (back button is no longer an anchor). `activity_browser.xml` vertical order container, separator, mini-player, receiver bar; a GONE mini-player cannot move an anchor. `fragment_server_root.xml`: switch name, status-icon descriptions, heading flag. The 8 new strings exist in all 7 locales with correct translations; the reused `settings_local_server_*_on/off` strings exist in all locales and read sensibly.
6. `BrowserHost` defaults are false; only `BrowserActivity` overrides them; the back arrow at the content root calls `host.showServers()` (`isAtRoot()` is null-safe); classic keeps the arrow GONE at the root.
7. `NEW_LAYOUT_AVAILABLE = true`: only the constant and its comment changed; `targetClass` returns `BrowserActivity` only for effective NEW; effective layout still computed once in `relaunchIfOtherLayout`; guard untouched; `settings_browser_layout_key` is not used by any preference screen (no UI). `BrowserActivity` has all pieces (servers, content, bar, mini-player, toolbar). Safe.
8. Builder deviations: reusing the settings strings for status icons is fine (translated, accurate); adding `browser_back` is fine (only the new layout uses it; classic keeps its description); not adding bidi stripping to `ReceiverBarLabel` is acceptable (carried-forward item, `ReceiverBarLabelTest` unchanged, receiver names still plain `setText`), but note the mini-player now cleans text and the receiver bar does not.

### Critical
- None.

### Warning
- [yaacc/src/test/java/de/yaacc/browser/MiniPlayerPresenterTest.java:98, 147, 170] The test source contains raw, invisible bidi control characters (U+202E, U+202C, U+2066, U+2069, U+200E, U+200F, U+061C) inside string literals. They cannot be reviewed in an editor or diff, reorder the displayed code line (Trojan-Source pattern, flagged by GitHub and scanners) and are easy to lose in a reformat. Write them as `\u202e` etc. escapes (the test then reads as intended and passes unchanged).

### Suggestion
- [MiniPlayerBar.java:206-214, 236] `AbstractPlayer#getCurrentItemTitle` reads a plain `ArrayList` (`size()` then `get()`) that `clear()`/`setItems` modify on worker threads. A race throws `IndexOutOfBoundsException` on the main thread inside the permanent 2 s poll, which would crash the app. The window is tiny and classic code has the same pattern, but a `try/catch (RuntimeException)` around the per-player reads in `refresh()` is cheap.
- [MiniPlayerBar.java:343] The command task posts the delayed refresh from the worker thread even after `unbind()` (harmless: `refresh()` returns when unbound, and a rebind just gets one extra refresh). Check a volatile `bound` before posting, or post through the main handler.
- [MiniPlayerBar.java:255-275] The receiver name is cached per device id and never refreshed; a renamed device keeps the old name until another device is featured. Reset the cache in `unbind()`.
- [MiniPlayerPresenter.java:93-97] U+0085 (NEL) is dropped instead of becoming a space ("a<NEL>b" -> "ab"); invisible format characters (U+200B, U+2060, U+FEFF) survive, so a title of only U+200B counts as non-blank and hides the fallback. Treat `Cc` whitespace-like characters as separators and drop `Character.FORMAT` characters. Not covered by tests: NBSP/U+2028, leading whitespace, whitespace run longer than the cap.
- [mini_player_bar.xml:60] Mark the decorative art `importantForAccessibility="no"` so TalkBack does not read "Album art" before every title.
- [activity_browser.xml] In landscape on a phone the bottom stack (separator, 56dp mini-player, 48dp bar) plus toolbar takes about 150dp of roughly 360dp; check on a device, consider hiding the separator or compacting the mini-player in `layout-land`.
- [decisions.md] No "Group 8 as built" entry: record the three builder deviations (reused settings strings, `browser_back`, no bidi stripping in `ReceiverBarLabel`) and the 500 ms/2 s timing. The security-review carried item "bidi-control stripping in ReceiverBarLabel" is still open.
- [tests] `MiniPlayerBar`, `ReceiverBarController` and the host back/receiver wiring have no JVM seam; the decision logic (picker, toggle, visibility, text cleaning) is covered. No untested critical decision logic found. Not run on a device.

### Tests
- [x] All tests passing (328, 0 failures)
- [x] Coverage adequate for the change

### Verdict: FAIL
Zero Critical, one Warning (raw bidi characters in the test source). Everything else on the checklist is met; after the escapes are applied (no behavior change) this should pass in cycle 2.


## Cycle 2 — 2026-10-24
Reviewing: Group 8 (phase 2)

Scope: `git show c78fb5d6` (fix commit for cycle 1) on top of `fdb8dee9`.

### Verified
1. Warning from cycle 1 resolved. `grep -nP '[^\x00-\x7F]' MiniPlayerPresenterTest.java` now returns only visible characters (lines 103 and 121: CJK, en dash, e-acute, check mark, music-note emoji). A scan of `git diff HEAD~2` for U+200B-200F, U+202A-202E, U+2060-2069, U+FEFF, U+061C, U+0085, U+2028/2029 finds nothing in code, resources or tests. (The `grep -P '\x{...}'` form from the task fails in this locale with "code point too large"; the scan was done with a Python code point check instead.) One hit was found in this file: cycle 1's own Warning text contained a raw U+202E; it is now written as `\u202e` (documentation only, no code touched).
2. The escaped literals test the intended code points: `"\u202eevil\u202c\u2066.\u2069m\u200ep\u200f3\u061c"` is the same seven characters as before (202E, 202C, 2066, 2069, 200E, 200F, 061C); `"\u202e\u0000"` and `"TV\n\u202eevil"` likewise. No `\u000a`/`\u000d`/`\u0022` escapes that Java would translate before lexing. New tests: `\u0085` (NEL) gives "a b"; `\u200b\u2060\ufeff` gives "ab"; the family emoji `\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67` is kept intact (ZWJ not stripped).
3. Production changes correct. `cleanText`: `0x85` is tested in the whitespace branch before the control-character removal, so it becomes a collapsed space; `isBidiControl` adds 200B/2060/FEFF only, ZWJ (200D) and ZWNJ stay, so emoji sequences and Persian/Indic text survive. A title of only U+200B is now blank and falls back. `MiniPlayerBar.refresh()`: still returns early when unbound; the body moved unchanged into `refreshFromPlayers()`, wrapped in `try/catch (RuntimeException)` logging the class simple name only (no titles/names); the poll is re-posted by the caller outside the try, so a skipped refresh retries on the next poll. `bound` is `volatile`; the delayed refresh from the worker is posted only if `bound` (a rare race between the check and `unbind()` is harmless because `refresh()` re-checks). `unbind()` resets `shownReceiverDeviceId`/`shownReceiverName` (the cache is read only on the main thread, same as before). `importantForAccessibility="no"` is on the art ImageView only; the card and buttons keep their descriptions. No behavior regression found.
4. Build and tests: `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug --offline --continue` BUILD SUCCESSFUL; 331 tests, 0 failures, 0 errors, 0 skipped (result XMLs, 328 + 3 new). Lint 189 issues, same count as cycle 1, none located in `MiniPlayer*` or `mini_player_bar.xml`.

### Critical
- None.

### Warning
- None.

### Suggestion
- [MiniPlayerPresenter.java:73-77] The Javadoc line wrap after "zero-width" is uneven; cosmetic only.
- [open from cycle 1] Landscape height of the bottom stack, the missing "Group 8 as built" entry for the three builder deviations in `decisions.md` (the commit added 8 lines there; confirm they cover it), bidi stripping in `ReceiverBarLabel`, and the lack of a JVM seam for `MiniPlayerBar` remain as non-blocking suggestions. Not run on a device.

### Tests
- [x] All tests passing (331, 0 failures)
- [x] Coverage adequate for the change

### Verdict: PASS



## Cycle 1 — 2026-10-24
Reviewing: Group 9 (phase 2), preference task

Scope: `git show 72f337cf` (`preference.xml`, `values/browser_layout.xml`, `strings.xml` x7, `tasks.md` checkbox). Read in addition: `BrowserLauncher`, `BrowserLayoutChoice`, `BrowserLayoutSwitch`, `RelaunchGuard`, `StartedBrowserCounter`, `BackgroundIdleTimer`, `TabBrowserActivity`, `BrowserActivity`, `BrowserActivitySupport`, `SettingsActivity`, `SettingsFragment`, `LauncherActivity`, `Yaacc`/`PlayerService` notification intents, `YaaccUpnpServerService#onSharedPreferenceChanged`. The second Group 9 task (manual checks with the owner) is intentionally open and not reviewed.

### Verified
1. **preference.xml: as specified.** `ListPreference` first item of the Appearance category: `key="@string/settings_browser_layout_key"` (exists since Group 6 in `setting_strings.xml`, `translatable="false"`), `defaultValue="classic"`, `entries=@array/browser_layout_entries`, `entryValues=@array/browser_layout_values`, `summary="%s"`, title string. `browser_layout_values` is `translatable="false"` with exactly `classic`, `new` = `BrowserLayoutChoice.STORED_CLASSIC/STORED_NEW`. Entries reference the two strings, so each locale picks its own text; no other preference or listener was touched.
2. **Strings.** `settings_browser_layout_title/_classic/_new` exist once each in `values` and `values-de/es/fr/nl/pt/zh` (grep count 3 per file). Translations are sensible: de "Darstellung" (the category is "Erscheinungsbild", so no clash) / "Klassisch (Tabs)" / "Neu (ein Bildschirm)"; es "Diseño" / "Clásico (pestañas)" / "Nuevo (una pantalla)"; fr "Mise en page" / "Classique (onglets)" / "Nouveau (un seul écran)"; nl "Indeling" / "Klassiek (tabbladen)" / "Nieuw (één scherm)"; pt "Layout" / "Clássico (abas)" / "Novo (tela única)"; zh "布局" / "经典（标签页）" / "新（单屏）". No markup or format args.
3. **Single reader.** `grep settings_browser_layout` over `src/main/java`: only `BrowserLauncher.effectiveLayout(Context)` (default `null`, `RuntimeException` caught, `NEW_LAYOUT_AVAILABLE` kept). The stored string is only compared in `BrowserLayoutChoice.resolve` (exact `"new"`), never a class name, action or extra. Key absent (existing installs) -> `null` -> classic. Opening Settings persists the XML default `classic` through `ListPreference.onSetInitialValue`; it resolves to classic as well, so nothing changes for existing installs.
4. **Switch trace.** Settings entry points: `BrowserActivitySupport.handleCommonMenuItem` (both browsers), `MusicPlayerActivity`, `MultiContentPlayerActivity`, `AVTransportPlayerActivity`, `ImageViewerActivity`, `YaaccUpnpServerControlActivity`. None checks the layout itself and none needs to: the browser is below them in the task, and its `onResume` does the check when the user gets back to it.
   - classic -> new: `SettingsActivity.onPause` sets `leftSettings`; `TabBrowserActivity.onResume` -> `relaunchIfOtherLayout(CLASSIC)` -> effective NEW -> guard allows -> fresh `new Intent(activity, BrowserActivity.class)` (no extras/flags, no `CLEAR_TASK`), `overridePendingTransition(0,0)`, `finish()`, returns before `LocalServerLifecycle.apply`. `consumeLeftSettings()` is therefore not consumed by the dying activity; `BrowserActivity.onResume` consumes it once and applies the server settings. Good.
   - new -> classic: `BrowserActivity.onResume` does the same towards `TabBrowserActivity`. A fresh `BrowserActivity.onCreate` also checks first and returns before building the receiver bar, mini-player, share handler or timer controller; `onStart/onStop/onDestroy/onCreateOptionsMenu` use null checks for those.
   - `SettingsFragment`: only dark-mode, log-level, interface and renderer listeners; none reads or reacts to the new key. `YaaccUpnpServerService#onSharedPreferenceChanged` branches on specific keys only; the new key falls through.
   - State: nothing stops `PlayerService`, players or the UPnP client on an activity switch (the browsers only unbind their own views/listeners in `onStop/onDestroy`). `BackgroundIdleTimer`: `StartedBrowserCounter` counts; the usual order is old `onPause`, new `onCreate/onStart/onResume`, then old `onStop`, so the count goes 1 -> 2 -> 1 and never reaches 0; if the old `onStop` ever ran first, the new `onStart` cancels the 5 min timer. `onStart` of a relaunching `TabBrowserActivity` is paired with its `onStop`, so the count stays balanced.
   - Loop safety: the 2 s process-wide guard allows one relaunch per window; a denied relaunch keeps the current layout and logs. Realistic Settings round trips take longer than 2 s.
5. **BrowserActivity vs classic, reachable differences.** The toolbar menu has the same settings/log/about/exit ids (shared handler) plus the shutdown timer; the Receiver/Player tabs map to the sheet and the mini-player (design); `onCreate` has the permission request and battery-optimization check; share intents go through `ShareIntentHandler`; `LocalServerLifecycle.apply` is on resume. `onKeyDown` in classic only delegates to `super`. I found nothing the classic activity does that the new one lacks and that a user can reach.
6. **No manifest/gradle change.** `git diff 85ae9d2a HEAD -- AndroidManifest.xml yaacc/build.gradle gradle` is empty; no `exported`, `allowBackup` or permission change.
7. **Build.** `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug --offline --continue`: BUILD SUCCESSFUL. Result XMLs: 331 tests, 0 failures, 0 errors, 0 skipped. Lint: 189 issues (same count as Group 8); the one Error `MissingTranslation` is the old `toggle_view_mode`, none of the new keys or arrays is reported.

### Critical
- None.

### Warning
- None.

### Suggestion
- [BrowserLauncher.java:relaunchIfOtherLayout] Stale launch targets can leave two instances of the new browser. `Yaacc.createYaaccGroupNotification` (built once at start-up, `FLAG_IMMUTABLE` only) and the session-activity PendingIntent in `PlayerService.createSessionActivity` keep the class of the layout that was active when they were built. After a switch, tapping them starts the old layout's activity, which bounces to the current one (works; guard-limited; share text dropped on purpose) but, with the standard launch mode, the bounce creates a second instance on top of the existing one (`[B_old, B_new]`, back goes to the old, stopped instance). Not data-losing. Consider `FLAG_ACTIVITY_REORDER_TO_FRONT` on the fresh relaunch intent (still no `CLEAR_TASK`), or rebuild the group notification when the layout changes; decide after the owner's manual check.
- [res/xml/preference.xml, BrowserLayoutChoice] Nothing ties the `browser_layout_values` array to `STORED_CLASSIC/STORED_NEW`. A typo in the array would silently make "new" unselectable or store a value that resolves to classic, and lint cannot see it. A plain JUnit test that parses `src/main/res/values/browser_layout.xml` (or `preference.xml`) and asserts the two values and the key/default would pin it. Not blocking: the resolver itself is covered by `BrowserLayoutChoiceTest`.
- [values/strings.xml] The new title/entries sit in `strings.xml` (and the arrays in a new `browser_layout.xml`) while the other `settings_*` strings and arrays live in `setting_strings.xml`; consider moving them there for consistency (cosmetic).
- [BrowserLauncher.java:68-70] The Javadoc says existing installs never had a default written; opening Settings now persists `classic`. Harmless (resolves to classic); reword when convenient.
- [tasks.md Group 9 / decisions.md] No "Status (Group 9)" line (the other groups record test count, lint count, manifest state, "not run on a device") and no decisions.md entry. Add both with the next docs commit; also record the `FLAG_ACTIVITY_REORDER_TO_FRONT` question above.
- [Android TV] The setting is visible on TV; the new layout's D-pad reach is unverified (design risk, owner's manual checks, if hardware exists). Default stays classic, so TV users are unaffected until they switch.

Forward-looking requirements:
- Group 9 manual checks (owner): switching in both directions (including from a player screen's Settings menu and from the server-control screen), stale notification tap after a switch, share-to-yaacc in the chosen layout, rotation right after a switch, and `adb shell dumpsys activity activities` showing a single browser instance after classic -> new -> classic.
- Group 10 must: document the Layout setting, the resume-time switch, the guard window and the stale-PendingIntent behavior in `docs/tech.md`/`docs/index.md`; re-run the merged-manifest check after the last code change.
- Carried, owner decision still open: `android:allowBackup` / backup exclude for the default preferences file (it now also holds the user-visible layout).

### Tests
- [x] All tests passing (331, 0 failures)
- [x] Coverage adequate for the change (resolver, switch decision, guard and counter are unit tested; the new part is resources only, see the Suggestion on the values/constants link)

### Verdict: PASS
Zero Critical, zero Warning. The preference is a fixed-value `ListPreference` in Appearance, read only through `BrowserLauncher`/`BrowserLayoutChoice`, translated in all seven locales, with no manifest/gradle change; the switch in both directions goes through `relaunchIfOtherLayout` only and leaves players, the UPnP client and the idle counter untouched. The first Group 9 task may stay `[x]`; the manual-check task stays open.

## Cycle 1 — 2026-10-24
Reviewing: Group 10 (phase 2) documentation update, commit 40294bcf (`docs/index.md`, `docs/settings.md`, `docs/tech.md`, `CHANGELOG.md`, `README.md`, `tasks.md`). Docs only; Gradle tests not rerun (last code state: 331+ unit tests green per Group 9). Only `./gradlew :yaacc:processDebugManifest --offline` was run.

Checked against the code: `BrowserActivity`, `BrowserHost`, `ServerListFragment`/`ContentListFragment`, `MiniPlayerBar`/`MiniPlayerPicker`/`MiniPlayerPresenter`, `ShareIntentHandler`/`ShareUriPolicy`/`LauncherForwardPolicy`/`LauncherActivity`, `BrowserLauncher`/`RelaunchGuard`, `BackgroundIdleTimer`, `ShutdownTimerController`, `res/menu/activity_browser.xml`, `fragment_server_root.xml`, `activity_browser.xml`, `mini_player_bar.xml`, `preference.xml`, strings, the merged manifest.

Verified true as built: top level server list with "Servers" header and refresh button; stream buttons on the yaacc row; gear next to the server switch in the footer (footer only on the top level); status icons; tap a server opens its content; back order folder -> content root -> servers -> exit, in-screen arrow at the content root (`showsBackAtContentRoot`); toolbar (timer toggle with picker when no duration, "Set shutdown timer" in overflow, settings, log, about, always-visible exit); receiver bar on every level opening the sheet; mini-player featured session rule, four buttons, tap opens the player, hidden when no player; Layout under Settings > Appearance (first item), entries "Classic (tabs)" / "New (single screen)", applies on return to a browser screen, classic default also on TV; no pull-to-refresh claim; no TV certification claim (honestly "not checked with a TV remote"). Merged manifest has exactly the 12 exported components listed in tech.md, `BrowserActivity` and `SettingsActivity` not exported, `allowBackup` unset, 21 `uses-permission`. `BrowserHost` table, share handler (cap 2048, graceful shutdown, callback cleared, max 3 queued, clip text only, URI never used), launcher forward policy, idle timer (5 min, counter), mini-player (2 s poll, 500 ms refresh, art key, `getIcon()` once per key, `cleanText`) and Layout preference text match the code. `docs/index.md` anchors (`#new-layout-single-screen`, `#receiver-controls`, `#live-streaming-android-10`, `#media-server`, `#content-browsing`, `#shutdown-timer`) all resolve to existing headings; no relative links added; heading levels consistent; no TOC to update. Placeholder grep returns only the quoted `//FIXME dirty` (tech.md:1556, pre-existing quote of old code). The stale research-time terms (Recommended/Proposed/ServerRootFragment/providesShutdownTimer/LaunchHelper/`new Intent(getIntent())`/copy/cancels it) occur only in the explicitly labelled "Research notes (Group 5)" history section (which says the as-built text wins) or in correct as-built statements ("There is no `ServerRootFragment`"). Nothing documents Backlog 1 items as done (they appear under known limitations as open); no `[!]` items exist; screenshots untouched; tasks.md status line accurate (Group 9 manual checks stay open).

### Critical
- None.

### Warning
- [CHANGELOG.md Unreleased > Fixed "Shutdown timer after rotation"; docs/tech.md:241-243 "As built: shutdown timer and streaming permission" ("it showed blank before")] The claim that the Server tab's timer "was blank before" a rotation is not supported by the pre-Phase-2 code. At 3a289b8f `ServerListFragment.onSaveInstanceState` saved the remaining-time text and `init` restored it and re-registered the listener when `savedInstanceState != null` (lines ~207-211, 250-253); the switch is a view with an id, so its checked state is restored by the framework. After a rotation the old tab showed the running timer. What was really wrong before: the application held the destroyed fragment as the single listener (leak), and a fragment view recreated without saved state could show the configured duration and an unchecked switch while a timer ran (wrong, not blank). Fix: reword the CHANGELOG entry (and the tech.md parenthesis) to what is true and verifiable, e.g. "The Server tab's shutdown timer (switch and remaining time) is now taken from the running timer, so it is correct whenever the screen is recreated or the layout is switched", and drop "it was blank before". (The decisions.md line 133 origin is the same unverified statement; correct it or leave it, but do not repeat it in user-facing text.)

### Suggestion
- [docs/index.md "Media proxy" note] "Only links that start with `http://` or `https://`": the code (`ShareUriPolicy`) checks only the scheme before the first `:` (so `http:foo` passes, as tech.md and Backlog 1 item 8 already say). User-facing wording is fine; consider "start with http or https" and that the shared text must be just the link (text with words before the URL is ignored), since that is what users hit when sharing from apps that send "title + URL".
- [CHANGELOG.md Changed "Sharing into yaacc"] "Shared file links" is ambiguous (reads like links to files on a server, which still work as http); say "`file:` links and shared files/URIs".
- [CHANGELOG.md Added "New single-screen layout"] One very long bullet; the Device sheet bullet is similar, so style is consistent, but a short second sentence split (mini-player) would read better. Not blocking.
- [docs/tech.md mini-player "Text"] "cached per device id once resolved until `unbind()`" is a one-slot cache (it is replaced when the featured receiver changes); say "last resolved receiver".

### Tests
- [x] All tests passing (docs-only commit; no code changed since Group 9's 331+ green unit tests; Gradle intentionally not rerun)
- [x] Coverage adequate for the change (n/a, documentation)

### Verdict: FAIL
One Warning: a user-facing changelog claim ("shutdown timer ... was blank before" a rotation) that the previous code does not support, repeated in tech.md. Everything else checked is true as built. Fix the wording in CHANGELOG.md and docs/tech.md:241-243, then re-review (no other change needed).

## Cycle 2 — 2026-10-24
Reviewing: Group 10 (phase 2), commit cb869510 (re-review of the cycle 1 Warning and wording suggestions; `CHANGELOG.md`, `docs/tech.md`, `docs/index.md`, `decisions.md`, `review.md`). Docs only; Gradle not rerun (no code changed).

Checked against the old code (`git show 3a289b8f:.../ServerListFragment.java`, `Yaacc.java`) and the current code (`ShutdownTimerController`, `Yaacc`, `ServerListFragment`):
- Old `ServerListFragment` saved only the remaining-time text in `onSaveInstanceState` and restored it in `init`; it registered itself with `Yaacc.setShutdownTimerListener(this)` (a single listener field) and has no `onDestroyView`/clearing of that listener, so a destroyed fragment stayed registered. The new wording ("restored only the remaining-time text and held the destroyed fragment as the single listener (a leak)") is true.
- Current code: `Yaacc` keeps `shutdownTimerRunning` and `shutdownTimerRemainingMillis` and a `CopyOnWriteArrayList` of listeners; `ShutdownTimerController.attach` registers and publishes the state from `Yaacc` immediately, `detach` removes it; `ServerListFragment` attaches/detaches around its view lifecycle. "Takes the state from the running timer, correct whenever the view is recreated (rotation, tab re-creation, layout switch), destroyed screen no longer kept alive" is true.
- `grep -rn -i blank CHANGELOG.md docs/ decisions.md`: no remaining claim about the timer; only tech.md:1268 (unrelated "blank fillers" Unicode characters) and Doxyfile comments. No other "showed/was empty" timer claims.
- Cycle 1 suggestions applied correctly: docs/index.md share note ("shared text should be just that link"), CHANGELOG "`file:` links, shared files, and URIs that arrive as clip data", tech.md one-slot cache wording. All consistent with the code per cycle 1.
- Diff is limited to the five files above; no code, anchors, links or headings changed.

### Critical
- None.

### Warning
- None.

### Suggestion
- [docs/tech.md mini-player "Text"] The reworded line is now longer than the surrounding wrapped lines; cosmetic only.
- Carried from cycle 1, still open and not blocking: split the long "New single-screen layout" CHANGELOG bullet.

### Tests
- [x] All tests passing (docs-only commit; no code changed since Group 9's 331+ green unit tests)
- [x] Coverage adequate for the change (n/a, documentation)

### Verdict: PASS
The cycle 1 Warning is resolved: the shutdown timer claim now matches the old and the current code, and no other place repeats the "blank" claim.

## Cycle 1 — 2026-10-25
Reviewing: Group 11 (commits 15b38840 single toolbar timer entry, d884898a classic static footer restored, 385bf1b9 mini-player lists all players, fa6224d2 docs)

Environment: this container has no Android SDK and no cached AGP 9.4.0. `./gradlew --offline` fails at configuration (AGP not in cache), and the online run is refused (HTTP 429 from the repository proxy, no SDK either). `assembleDebug`, the full `testDebugUnitTest` (340) and `lintDebug` (189, no new ids) are therefore **unverified** here. What was run instead: the five pure-JVM test classes of this group (`MiniPlayerRowsTest`, `MiniPlayerArtKeysTest`, `ShutdownTimerToggleTest`, `MiniPlayerPresenterTest` and the sources they need) compiled with `javac` and run with JUnit 4: 51 tests, 0 failures. All changed XML files (layouts, menu, 7 strings files, dimens) parse as well-formed XML. `git diff 5cb69905 -- yaacc/src/main/AndroidManifest.xml yaacc/build.gradle` is empty. The review of everything else is static.

### What was checked and holds
1. Timer. `menu/activity_browser.xml` has only `menu_shutdown_timer`; `shutdown_timer_set` / `menu_shutdown_timer_set` exist nowhere in `yaacc/src` (all 7 locales; remaining hits are docs and review history only). `ShutdownTimerToggle.decide(running)` returns STOP / ASK_DURATION only; `BrowserActivity.toggleShutdownTimer` stops on STOP, else opens `showPicker(fm, title, true)`. `showPicker(fm, title, startOnConfirm)` stores the duration and starts only for `startOnConfirm && millis > 0L`; the two-argument overload (used by classic `ServerListFragment`) delegates with `false`, so classic is unchanged. Callers of `Yaacc.startShutdownTimer`: `ShutdownTimerController.start()` / `setConfiguredMillis` (restart only when already running) and the classic switch; the new toolbar can no longer start a 0 ms timer (running -> STOP; stopped -> picker; the picker cannot be open while a timer runs because the icon is the only starter in this layout). Test updated to the two-outcome decision.
2. Classic footer, compared with `origin/feat/issue287`. Portrait: provider and receivers TextViews are attribute-identical to the original (start margin, drawable padding, `focusable=false`, `minLines=2`, `singleLine=false`, same drawables and text appearance), now inside a `contentListBottomBar` LinearLayout (`alignParentBottom`) that the list anchors to; the container is never GONE, so nothing visible anchors to a GONE view. Landscape: the original chain separator -> provider -> receivers (`alignParentBottom`) is kept, `toStartOf contentListTopSeperator` replaced by `alignParentEnd` + 1dp end margin (that divider is set GONE in `hideFolderNavigationControls`, so the earlier landscape bug cannot return); `contentListCurrentFolderName` is anchored to `contentListSortToggle` (never hidden); nothing outside the chain anchors to the footer views; `contentListTopSeperator` aligns to the label, not to a footer view. `ContentListFragment`: `initFooter()` decides by `host == null || !host.providesReceiverBar()`; classic tints both drawables as the original did, sets provider text on provider selection / init and receivers text on init and `receiverDeviceAdded/Removed` ("; " join, same moments as the original); the new layout sets the three footer views GONE. `openDeviceSheetIfNoReceiverSelected()` is false unless the host provides the bar, so the three guard calls (`ContentListClickListener`, `BrowseContentItemAdapter` play / play-all) are no-ops in classic; `PlayerService` is byte-identical to the origin in the toast path (`error_no_receiver_selected`). `ReceiverBarController` and `DeviceSheetFragment` are no longer referenced by `ContentListFragment` (only `BrowserActivity`, `DeviceSheetAdapter`). Every `R.id` used by `ContentListFragment` exists in both layouts (`contentListViewModeToggle` is absent in landscape, as in the original, and null-guarded).
3. Mini-player. `MiniPlayerRows.order` reuses `DeviceSessionGrouper.sortedOldestFirst` (copy, nulls skipped), dedups ids, never mutates the input; `artKey` = `playerId:artUri`. `MiniPlayerAdapter`: stable ids, `DiffUtil` on player id with content compare incl. art key, item animator is null in the controller (no holder re-creation on change), `getIcon()` read only when `MiniPlayerArtKeys.isNewKey` is true (once per player+key), recycled / rebound holders use the one stored icon plus an in-memory `IconDownloadCacheHandler` lookup (no request), `IconDownloadTask(view, artKey)` drops late results on the tag compare and `onViewRecycled` clears the tag; art keys and icons are pruned to the live ids in `submit` and fully cleared in `clear()`. A row that is not bound (below the cap) causes no request. `MiniPlayerBar`: one single-thread executor created in `bind()` and shut down in `unbind()`; commands ignored when unbound or rejected; the 500 ms follow-up refresh is skipped when unbound and `refresh()` itself returns when unbound; listener and the 2 s poll removed; `refreshFromPlayers` works on copies, wrapped in try/catch with class-name-only logs; receiver names via `upnpClient.getDevice` with a per-device cache cleared on unbind; open uses the player's own PendingIntent with an empty fill-in (`ReceiverRowBinder.openPlayer`). `MaxHeightRecyclerView`: cap = min(180dp, 40% of the offered height) as AT_MOST, EXACTLY respected, wrap_content below the cap. Row layout: theme attributes only (no `#` / `@color`), 48dp buttons, card focusable before its four buttons (D-pad order row -> prev -> play/pause -> next -> stop), content descriptions on all buttons (play/pause switches), click action label "open" via accessibility delegate; texts via `setText` only, no Html / Linkify / autoLink anywhere in the mini-player files. `MiniPlayerPicker` and its test are deleted, no references remain. Docs mention nothing stale about it except as labelled history.
4. Docs (`docs/index.md`, `CHANGELOG.md`, `docs/tech.md`, README line) match the code: classic keeps the static footer and no sheet, one timer entry with the picker starting the timer, mini-player lists every player oldest first with about three rows visible; the old "Receiver bar" Changed bullet is gone; internal anchors all resolve; no `shutdown_timer_set` / "Set shutdown timer" outside labelled history (tech.md "Owner feedback" explains the removal); placeholder grep only finds the quoted `//FIXME dirty` in tech.md.

### Critical
- None.

### Warning
- [yaacc/src/main/java/de/yaacc/browser/MiniPlayerPresenter.java:45-48, yaacc/src/test/java/de/yaacc/browser/MiniPlayerPresenterTest.java:40-52] `MiniPlayerPresenter.isVisible(PlayerRef featured)` is dead code since 385bf1b9 (its only caller was the single-bar controller; visibility is now `rows.isEmpty()` in `MiniPlayerBar.refreshFromPlayers`) and its Javadoc ("when there is a featured session") and the test name `barIsHiddenWithoutAFeaturedSession` keep the retired "featured" notion alive in the code. The Group 11 constraint says to delete what the retired picker left behind rather than leave dead code, and the docs pass states no stale "featured" claims. Fix: delete `isVisible` (and the now unused `PlayerRef` import in the presenter if nothing else needs it), delete the three `isVisible` tests, update the class Javadoc ("whether it shows" part).

### Suggestion
- [yaacc/src/main/java/de/yaacc/browser/ShutdownTimerController.java:146] The rule "a duration of 0 never starts the timer" is the one piece of decision logic in Group 11 that no JVM test pins (it sits in the picker's confirm lambda). Consider a tiny pure helper next to `ShutdownTimerToggle` (e.g. `shouldStartAfterPicker(startOnConfirm, millis)`) with a test for 0, negative and positive values; a regression would exit the app at once through `Yaacc.startShutdownTimer(0)`.
- [yaacc/src/main/java/de/yaacc/browser/MaxHeightRecyclerView.java:61-69] The cap formula (min of 180dp and 40% of the offered height, EXACTLY untouched) is untested; extracting it as a static int function would make it JVM-testable. Device check needed in any case (see below).
- [yaacc/src/main/java/de/yaacc/browser/ContentListFragment.java:386-396, 435] `initFooter()` / `openDeviceSheetIfNoReceiverSelected()` classic-vs-new decisions are verified only by reading (needs Robolectric or the device). Acceptable for this group; keep on the manual check list.
- [yaacc/src/main/res/layout/mini_player_row.xml] Row = 6dp + 48dp art + text + 4 x 48dp buttons + padding is about 260dp before the text column; on a 320dp-wide phone the title and receiver get roughly 50dp. Not a defect in the code, but worth a look on a narrow device or large font scale.
- Security note (Group 8 rule per row): with N rows a hostile renderer that changes its art URI each poll now causes up to 2 requests per changed key per player (same per-player rate as the Group 8 worst case, bounded by the number of players the user started). Holds; mentioned only so the multiplication by N is explicit.

### Needs the device (not verifiable here)
- The 180dp / 40% cap in portrait and landscape phones, and with more than three players (inner scroll, scrollbar, row clipping with `cardElevation=6dp`).
- Narrow phone / large font: title and receiver ellipsis, button reachability.
- D-pad / Android TV: focus order across rows and buttons, scrolling the list by D-pad beyond three rows.
- Classic footer pixel parity in portrait and landscape (and during a device switch in landscape); toolbar timer icon states and picker flow in the new layout.

### Tests
- [ ] All tests passing (UNVERIFIED for the full suite: no Android SDK / no dependency access in this container; the 51 pure-JVM tests of this group pass; the implementer reports 340 green, lint 189)
- [x] Coverage adequate for the change (ordering, art keys, timer decision and presenter are tested; the footer decision, the cap and the picker's 0 ms guard are not, see Suggestions)

### Verdict: FAIL
One Warning (dead `MiniPlayerPresenter.isVisible` and its "featured" wording left behind by the picker removal). Everything else in Group 11 matches the spec; build, full test run and lint could not be executed here and must be re-run (`./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug --continue`, expect 340 tests, lint 189 with no new ids) once the Warning is fixed.

## Cycle 2 — 2026-10-25
Reviewing: Group 11 (fix for the cycle 1 Warning, commit 91ae4563)

### Critical
- None.

### Warning
- None. The cycle 1 Warning is resolved: `MiniPlayerPresenter.isVisible`, its three tests and the `PlayerRef` imports are gone from `MiniPlayerPresenter.java` and `MiniPlayerPresenterTest.java`; the class Javadoc and the test Javadoc no longer say "whether it shows". `grep -rn "isVisible\|PlayerRef"` over `yaacc/src` finds no reference in `MiniPlayerPresenter`/`MiniPlayerPresenterTest` and no caller of `MiniPlayerPresenter.isVisible` anywhere. `assertTrue`/`assertFalse` imports in the test are still used.

### Suggestion
- [.claude/specs/2026-10-23-unified-device-sheet/design.md:199] Still describes the retired "featured session" mini-player. It is the original design text, superseded by decisions.md (2026-10-24, "mini-player must show all players"); add a one-line "superseded" note if the spec folder is kept. Not code or user docs, so not blocking. `tasks.md:139/177/180` and `docs/tech.md:1264/1481` mention "featured" only as labelled history of what was replaced, which is accurate.
- [tasks.md:181] The Status line says "340 tests green" (implementer's count before the removal) and then "full suite expected 337"; consistent, no change needed.

### Checks performed (static and plain JVM; no Android SDK in this container)
- `MiniPlayerBar` visibility is `list.setVisibility(rows.isEmpty() ? GONE : VISIBLE)` (line 190, initial GONE at 135); every symbol it uses from the presenter (`title`, `receiverLabel`, `toggleAction`, `TransportAction`) still exists; `PlayerRef` is imported from `DeviceSessionGrouper` where it is defined. Compiles by inspection.
- javac (main: DeviceSessionGrouper, MiniPlayerRows, MiniPlayerArtKeys, MiniPlayerPresenter, ShutdownTimerToggle, BrowserLayoutChoice) plus JUnit 4.13.2 run from `/home/user/yaacc-code/yaacc`: MiniPlayerPresenterTest 22, MiniPlayerRowsTest 16, MiniPlayerArtKeysTest 8, ShutdownTimerToggleTest 2, BrowserLayoutChoiceTest 12, BrowserLayoutValuesTest 2 = 62 tests, `OK (62 tests)`, 0 failures.
- `featured` outside labelled history: none in main code or tests; only the history/superseded mentions listed above (and an unrelated Doxyfile URL).
- `@Test` count over `yaacc/src/test`: 337 (no `@Ignore`, `@Parameterized`, `@RunWith`), equal to the expected Gradle count of 337 (340 before the three removed tests).

### Tests
- [ ] All tests passing (UNVERIFIED for the full suite: no Android SDK in this container; the 62 pure-JVM tests of the six named classes pass and the static @Test count matches 337)
- [x] Coverage adequate for the change

### Verdict: PASS
Conditional on the full build being repeated where the Android SDK exists: `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug --continue` (expect 337 tests, lint 189 with no new ids). The full assemble/test/lint run could not be performed here.
