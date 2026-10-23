# Tasks: Unified device sheet (phase 1)

Plan: `specs/2026-10-23-unified-device-sheet/requirements.md` and `specs/2026-10-23-unified-device-sheet/design.md`

## Group 1: Research and test skeletons
- [ ] Research and document SDK/framework APIs | `docs/tech.md`
  - **Packages**: `com.google.android.material:material:1.14.0` (already a dependency - no new packages)
  - **Accept**: `docs/tech.md` has a "Unified device sheet" section with verified usage for `BottomSheetDialogFragment` and `BottomSheetBehavior` in 1.14.0: half-expanded start state, `fitToContents`/`halfExpandedRatio`, draggable to full height, and how focus/D-pad and Back behave inside the dialog
  - **Verify**: each documented pattern cites a source (doc URL or the library's class source/signature)
  - **Constraints**: implementation tasks reference `docs/tech.md`; no code against unverified APIs
- [ ] Trace how player add/remove is observed | `yaacc/src/main/java/de/yaacc/player/PlayerService.java`, `yaacc/src/main/java/de/yaacc/browser/PlayerListFragment.java`
  - **Accept**: `docs/tech.md` records how the Player tab learns about new/ended players (listener, broadcast, or polling) and whether `DeviceSheetFragment` can reuse it; if there's no mechanism, the note says so and names the method to add to `PlayerService`
  - **Verify**: `grep -rn "addPlayer\|removePlayer\|shutdown(" yaacc/src/main/java/de/yaacc/player/PlayerService.java` output is cited in the note
- [ ] Write `DeviceSessionGrouperTest` (red) | `yaacc/src/test/java/de/yaacc/browser/DeviceSessionGrouperTest.java`
  - **Accept**: tests compile against the `DeviceSessionGrouper` API in design.md and fail; cases: (a) receiver with no sessions, (b) local device with image + music sessions, ascending playerId, (c) remote renderer with music + image sessions, (d) session whose deviceId isn't a receiver -> trailing group, never dropped, (e) receiver order preserved, (f) empty inputs, (g) `primarySession` prefers playing, else highest playerId, else null
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests '*DeviceSessionGrouperTest*'` (expected: fails to compile or fails assertions)
  - **Constraints**: plain JVM, no Robolectric, no `android.*` imports

## Group 2: Pure logic and shared binder
- [ ] Implement `DeviceSessionGrouper` | `yaacc/src/main/java/de/yaacc/browser/DeviceSessionGrouper.java`
  - **Accept**: all `DeviceSessionGrouperTest` cases pass
  - **Verify**: `./gradlew :yaacc:testDebugUnitTest --tests '*DeviceSessionGrouperTest*'`
  - **Constraints**: no `android.*` imports; no Java records (keep plain classes)
- [ ] Extract `ReceiverRowBinder` from `BrowseReceiverDeviceAdapter`, fix first-match bug | `yaacc/src/main/java/de/yaacc/browser/BrowseReceiverDeviceAdapter.java`, `yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java`
  - **Accept**: Receiver tab looks and behaves the same; `getPlayerForDevice` is gone and the row's player comes from `DeviceSessionGrouper.primarySession(...)` built from `Player#getDeviceId()` (not `instanceof` checks)
  - **Verify**: `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest`
  - **Constraints**: no visual or behavior change to the Receiver tab beyond the deterministic session pick; depends on `DeviceSessionGrouper`, so do it after the task above within this group

## Group 3: Sheet and entry points
- [ ] `DeviceSheetFragment` + `DeviceSheetAdapter` + session row layout | `yaacc/src/main/java/de/yaacc/browser/DeviceSheetFragment.java`, `DeviceSheetAdapter.java`, `yaacc/src/main/res/layout/device_sheet_player_session_item.xml`
  - **Accept**: sheet lists receivers via `ReceiverRowBinder`, sessions nested per `DeviceSessionGrouper`; tapping a session opens its player activity (same intent as `PlayerListItemClickListener`); status updates live while open and stop on dismiss; reuses the existing `RendererStatusMonitor`
  - **Verify**: `./gradlew :yaacc:assembleDebug :yaacc:lintDebug`; manual on phone and TV emulator: open, select receiver, change volume, open a session, Back dismisses, D-pad reaches every control
  - **Constraints**: strings in all 7 locales (`values`, `-de`, `-es`, `-fr`, `-nl`, `-pt`, `-zh`); no new dependencies
- [ ] Make the bottom-bar receiver area open the sheet | `yaacc/src/main/res/layout/fragment_content_list.xml`, `yaacc/src/main/res/layout-land/fragment_content_list.xml`, `ContentListFragment.java`
  - **Accept**: tapping or D-pad-selecting `contentListCurrentReceivers` opens the sheet in portrait and landscape; the label updates after the sheet changes the selection
  - **Verify**: `./gradlew :yaacc:assembleDebug`; manual on phone (both orientations) and TV
  - **Constraints**: don't anchor anything new to views that can be `GONE` (see the GONE-anchor fixes from 5.4.0)
- [ ] Open the sheet when playing with no receiver selected | `BrowseContentItemAdapter.java`, `ContentListClickListener.java`
  - **Accept**: with no receiver selected, play / play-all / row tap opens the sheet instead of starting `ContentItemPlayTask`; once a receiver is selected the user can tap play again; the `PlayerService` toast stays as the fallback for non-UI callers
  - **Verify**: `./gradlew :yaacc:assembleDebug`; manual: deselect all receivers, tap play

## Group 4: Documentation update
- [ ] Update documentation for plan changes | `docs/index.md`, `CHANGELOG.md`, `docs/tech.md`
  - **Accept**: `docs/index.md` "Receiver controls" describes the sheet and how to open it; `CHANGELOG.md` has an Unreleased entry; `docs/tech.md` reflects the as-built design
  - **Verify**: `grep -rn 'TODO\|FIXME\|PLACEHOLDER' docs/index.md docs/tech.md CHANGELOG.md || true` returns no plan-related placeholders
  - **Constraints**: don't document anything descoped or marked `[!]`
