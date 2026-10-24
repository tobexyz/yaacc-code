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
