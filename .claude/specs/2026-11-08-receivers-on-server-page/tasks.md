# Tasks: receivers on the top-level page

Plan: `requirements.md`, `design.md`. Rules: tests first; build -> `reviewer` PASS -> `security-reviewer` PASS (sequential, max 3 cycles);
commits as tobexyz; branch `prepareRelease` (owner's decision), push there only, no PR.
Gate: `ANDROID_HOME=/root/android-sdk ./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug`, lint count 0.

## Group 1: tests first
- [x] 1.1 `ReceiverLiveUpdatesTest` (fake target, fake sources): start subscribes once, stop drops everything, restart, callbacks after stop ignored, refresh only when the list is not fresh
- [x] 1.2 `ServerRootResourcesTest`: layout has the list and the footer ids, no fixed header; header layout has icon/label/refresh; strings in 7 locales; tooltip string

## Group 2: implementation
- [x] 2.1 `ReceiverLiveUpdates` + `Target`; `DeviceSheetFragment` uses it
- [x] 2.2 `DeviceSheetAdapter`: visible rows in a ConcatAdapter
- [x] 2.3 `SectionHeaderAdapter`, `SectionHintAdapter`, layouts, strings (7 locales)
- [x] 2.4 `ServerListFragment` top-level page; tooltip in `ContentListFragment`; positions of the servers by binding position (`ServerListClickListenerTest`)
- [x] 2.5 Fixes of review cycle 1: `devicesChanged()` for late receivers, the hint rule (local device), `AdapterPositions` (+ tests), docs wording
- [x] 2.6 Fixes of security review cycle 1: cap of 64 receivers, `Coalescer` for the refresh (+ tests)
- [x] 2.7 Review gate: reviewer PASS (cycles 1 FAIL, 2 PASS, 3 PASS), security-reviewer PASS (cycles 1 FAIL, 2 PASS)

## Group 3: documentation
- [x] 3.1 `docs/index.md`, `docs/tech.md` (as built), `CHANGELOG.md`

## Group 4: Players section, one refresh button, lines (owner's request)
- [x] 4.1 Tests first: `SectionHeaderAdapterTest`, `ServerRootResourcesTest` (divider, players string, bottom stack container)
- [x] 4.2 One refresh button; `SectionHeaderAdapter` with line and `setVisible`; Players section with `MiniPlayerBar` in page mode; `BrowserActivity` shows the bottom stack below the content only
- [x] 4.3 `docs/index.md`, `docs/tech.md`, `CHANGELOG.md`, spec
- [x] 4.4 Review gate: reviewer PASS (cycle 4), security-reviewer PASS (cycle 3)

