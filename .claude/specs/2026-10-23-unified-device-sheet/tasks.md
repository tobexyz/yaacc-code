# Tasks: Unified device sheet (phase 1)

Plan: `specs/2026-10-23-unified-device-sheet/requirements.md` and `specs/2026-10-23-unified-device-sheet/design.md`

## Group 1: Research and test skeletons
- [x] Research and document SDK/framework APIs | `docs/tech.md`
  - **Packages**: `com.google.android.material:material:1.14.0` (already a dependency - no new packages)
  - **Accept**: `docs/tech.md` has a "Unified device sheet" section with verified usage for `BottomSheetDialogFragment` and `BottomSheetBehavior` in 1.14.0: half-expanded start state, `fitToContents`/`halfExpandedRatio`, draggable to full height, and how focus/D-pad and Back behave inside the dialog
  - **Verify**: each documented pattern cites a source (doc URL or the library's class source/signature)
  - **Constraints**: implementation tasks reference `docs/tech.md`; no code against unverified APIs
- [x] Trace how player add/remove is observed | `yaacc/src/main/java/de/yaacc/player/PlayerService.java`, `yaacc/src/main/java/de/yaacc/browser/PlayerListFragment.java`
  - **Accept**: `docs/tech.md` records how the Player tab learns about new/ended players (listener, broadcast, or polling) and whether `DeviceSheetFragment` can reuse it; if there's no mechanism, the note says so and names the method to add to `PlayerService`
  - **Verify**: `grep -rn "addPlayer\|removePlayer\|shutdown(" yaacc/src/main/java/de/yaacc/player/PlayerService.java` output is cited in the note
- [x] Write `DeviceSessionGrouperTest` (red) | `yaacc/src/test/java/de/yaacc/browser/DeviceSessionGrouperTest.java`
  - **Accept**: tests compile against the `DeviceSessionGrouper` API in design.md and fail; cases: (a) receiver with no sessions, (b) local device with image + music sessions, ascending playerId, (c) remote renderer with music + image sessions, (d) session whose deviceId isn't a receiver -> trailing group, never dropped, (e) receiver order preserved, (f) empty inputs, (g) `primarySession` prefers playing, else highest playerId, else null
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests '*DeviceSessionGrouperTest*'` (expected: fails to compile or fails assertions)
  - **Constraints**: plain JVM, no Robolectric, no `android.*` imports

## Fix Group 1: Address review cycle 1
- [x] Tighten `DeviceSessionGrouperTest` | `yaacc/src/test/java/de/yaacc/browser/DeviceSessionGrouperTest.java`
  - **Accept**: (W1) a test with two playing sessions on one device expects the higher playerId, run with both input orders; (W2) the unknown-device grouping test uses interleaved playerIds across devices, so "just sort all unknown sessions by playerId" fails it; (Suggestion) list-index assertions are preceded by a size assertion, so every red failure is an `AssertionError`, not an `IndexOutOfBoundsException`
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest` - only `DeviceSessionGrouperTest` fails, every failure an `AssertionError` (grep the test-results XML)
  - **Constraints**: test file only; the stub stays a stub
- [x] Correct `docs/tech.md` on double removal | `docs/tech.md`
  - **Accept**: the "Unified device sheet" player add/remove notes say removal can also happen twice (`shutdown(Player)` → `onDestroy()` → `removePlayer`, `AbstractPlayer.java:958-965`) and that the listener must fire only when `remove` returns non-null
  - **Verify**: `grep -n "958" docs/tech.md`

## Group 2: Pure logic and shared binder
- [ ] Implement `DeviceSessionGrouper` | `yaacc/src/main/java/de/yaacc/browser/DeviceSessionGrouper.java`
  - **Accept**: all `DeviceSessionGrouperTest` cases pass
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests '*DeviceSessionGrouperTest*'`
  - **Constraints**: no `android.*` imports; no Java records (keep plain classes)
- [ ] Extract `ReceiverRowBinder` from `BrowseReceiverDeviceAdapter`, fix first-match bug | `yaacc/src/main/java/de/yaacc/browser/BrowseReceiverDeviceAdapter.java`, `yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java`
  - **Accept**: Receiver tab looks and behaves the same; `getPlayerForDevice` is gone and the row's player comes from `DeviceSessionGrouper.primarySession(...)` built from `Player#getDeviceId()` (not `instanceof` checks)
  - **Verify**: `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest`
  - **Constraints**: no visual or behavior change to the Receiver tab beyond the deterministic session pick; depends on `DeviceSessionGrouper`, so do it after the task above within this group
- [ ] Add a player-list change listener to `PlayerService` | `yaacc/src/main/java/de/yaacc/player/PlayerService.java`, `yaacc/src/test/java/de/yaacc/player/PlayerServiceListenerTest.java`
  - **Accept**: `addPlayerListChangeListener(...)` / `removePlayerListChangeListener(...)` exist; "added" fires exactly once per genuinely new player (only when the map `put` returns null - `addPlayer` can be called again for a player already present); "removed" fires exactly once per player (only when the map `remove` returns non-null - `shutdown(Player)` removes it and then `player.onDestroy()` calls `removePlayer` again, `AbstractPlayer.java:958-965`); callbacks are delivered on the main thread; a removed listener no longer fires
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest`, including a test where the player's `onDestroy()` calls back into `removePlayer` and the listener still fires only once
  - **Constraints**: touches only `PlayerService` (plus its test); existing Player tab behavior unchanged; if `PlayerService` can't be unit-tested on the plain JVM, extract the listener bookkeeping into a small plain-Java helper and test that instead

## Group 3: Sheet and entry points
- [ ] `DeviceSheetFragment` + `DeviceSheetAdapter` + session row layout | `yaacc/src/main/java/de/yaacc/browser/DeviceSheetFragment.java`, `DeviceSheetAdapter.java`, `yaacc/src/main/res/layout/device_sheet_player_session_item.xml`
  - **Accept**: sheet lists receivers via `ReceiverRowBinder`, sessions nested per `DeviceSessionGrouper`; tapping a session opens its player activity (same intent as `PlayerListItemClickListener`); receiver status and the session list update live while open (`RendererStatusMonitor` + the Group 2 `PlayerService` listener) and stop on dismiss; reuses the existing `RendererStatusMonitor`; follows the app's dark/light mode; opens half-expanded in touch mode and fully expanded when not in touch mode (D-pad/TV)
  - **Verify**: `./gradlew :yaacc:assembleDebug :yaacc:lintDebug`; manual on phone and TV emulator: open, select receiver, change volume, open a session, Back dismisses, D-pad reaches every control
  - **Constraints**: follow `docs/tech.md` "Unified device sheet": call `setFitToContents(false)` before `setState(STATE_HALF_EXPANDED)`, size the sheet container to full height, and supply a DayNight bottom-sheet theme (`getTheme()` override or `bottomSheetDialogTheme` on `AppTheme`) since `AppTheme` doesn't set one; strings in all 7 locales (`values`, `-de`, `-es`, `-fr`, `-nl`, `-pt`, `-zh`); no new dependencies
- [ ] Replace the two-line footer with a receiver bar that opens the sheet | `yaacc/src/main/res/layout/fragment_content_list.xml`, `yaacc/src/main/res/layout-land/fragment_content_list.xml`, `ContentListFragment.java`
  - **Accept**: `contentListCurrentProvider` is removed from both layouts and from `ContentListFragment`; `contentListCurrentReceivers` shows the selected receivers' names with an expand indicator, and tapping or D-pad-selecting it opens the sheet in portrait and landscape; the bar updates after the sheet changes the selection
  - **Verify**: `./gradlew :yaacc:assembleDebug :yaacc:lintDebug`; `grep -rn contentListCurrentProvider yaacc/src/main` returns nothing; manual on phone (both orientations) and TV
  - **Constraints**: re-point every RelativeLayout anchor that referenced the provider view (portrait: `contentListBottomSeperator`'s `layout_above`; landscape: `contentListBottomSeperator`'s `layout_above` and `contentListCurrentReceivers`/`contentListCurrentProvider` chain) at views that are never `GONE` - see the GONE-anchor fixes from 5.4.0
- [ ] Open the sheet when playing with no receiver selected | `BrowseContentItemAdapter.java`, `ContentListClickListener.java`
  - **Accept**: with no receiver selected, play / play-all / row tap opens the sheet instead of starting `ContentItemPlayTask`; once a receiver is selected the user can tap play again; the `PlayerService` toast stays as the fallback for non-UI callers
  - **Verify**: `./gradlew :yaacc:assembleDebug`; manual: deselect all receivers, tap play

## Group 4: Documentation update
- [ ] Update documentation for plan changes | `docs/index.md`, `CHANGELOG.md`, `docs/tech.md`
  - **Accept**: `docs/index.md` "Receiver controls" describes the sheet and how to open it; `CHANGELOG.md` has an Unreleased entry; `docs/tech.md` reflects the as-built design
  - **Verify**: `grep -rn 'TODO\|FIXME\|PLACEHOLDER' docs/index.md docs/tech.md CHANGELOG.md || true` returns no plan-related placeholders
  - **Constraints**: don't document anything descoped or marked `[!]`
