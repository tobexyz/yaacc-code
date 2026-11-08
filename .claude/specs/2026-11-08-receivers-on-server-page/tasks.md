# Tasks: receivers and players on the top-level page (done)

Rules: tests first; build -> `reviewer` PASS -> `security-reviewer` PASS (sequential); branch `prepareRelease` (owner's decision), no PR.
Gate: `ANDROID_HOME=/root/android-sdk ./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug`, lint 0. At the end: 3395 tests, 0 failures.

## Group 1: receivers on the page
- [x] 1.1 Tests first: `ReceiverLiveUpdatesTest`, `ServerRootResourcesTest`, `ServerListClickListenerTest`
- [x] 1.2 `ReceiverLiveUpdates` (+ `DeviceSheetFragment` uses it), section/hint adapters, layouts, strings, `ServerListFragment`, tooltip
- [x] 1.3 Fixes of review cycle 1: late receivers (`devicesChanged`), the hint rule, `AdapterPositions` (+ tests), docs wording
- [x] 1.4 Fixes of security review cycle 1: cap of 64 receivers, `Coalescer` (+ tests)

## Group 2: owner's changes after trying the page
- [x] 2.1 One refresh button, the Players section (`MiniPlayerBar` page mode), the bottom stack below the content only, lines between the sections (+ `SectionHeaderAdapterTest`)
- [x] 2.2 Larger section names; the player rows with the spacing of the cards; no player rows under the receivers (`listsRow`, `DeviceSheetListedRowsTest`); side margins

## Group 3: documentation and gates
- [x] 3.1 `docs/index.md`, `docs/tech.md`, `CHANGELOG.md`
- [x] 3.2 Reviews: reviewer cycles 1 FAIL, 2, 3, 4 PASS; security reviewer cycles 1 FAIL, 2, 3 PASS (the style changes of group 2.2 had no review cycle)
