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
