# Review: MusicCast support (#307), summary

Condensed on 2026-11-04: the verdict of every cycle and the blocking findings in one or two lines each. The full texts of the
cycles are in the git history of this file (commit before the cleanup) and in the branch `backup/feat-issue307-before-squash`
(while it exists). Suggestions that were not taken stay listed in `decisions.md` or in the last cycle below.

## Slice 2 — cycle 1 — 2026-11-02

Verdict: FAIL

Warning:
- [yaacc/src/main/java/de/yaacc/yxc/YxcMonitor.java:350-407 (create at 381, read at 385, apply at 392; submit at 347)] `readLoop` has no guard against an unchecked exception. …
- [yaacc/src/main/java/de/yaacc/yxc/YxcMonitor.java:355-379 together with 188-193] A stale endpoint can become permanent. …
- [yaacc/src/main/java/de/yaacc/yxc/YxcEventPlan.java:59-61] `dist_info_updated` asks only for `getDistributionInfo`. …
- [yaacc/src/test/java/de/yaacc/yxc/YxcMonitorTest.java:81 (executor `Runnable::run`)] The riskiest logic of the monitor is not exercised: with a synchronous executor a read can never be "in flight", so none of these paths has a tes …

## Slice 2 — cycle 1 follow-up (fixes by the orchestrator)

Verdict: -

## Slice 2 — cycle 2 — 2026-11-02

Verdict: FAIL

Warning:
- [yaacc/src/main/java/de/yaacc/yxc/YxcMonitor.java:242 with 212 and 410-413] New problem introduced by the cycle 1 fix: `startLocked` now sets `device.endpoint = null` for every watched device, but `getState(udn)` returns `null` wh …

## Slice 2 — cycle 3 — 2026-11-02

Verdict: PASS

## Slice 3 — cycle 1 — 2026-11-02

Verdict: FAIL

Critical:
- [yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java:510-513] The picker shows no checkboxes as soon as one device is unavailable. `AlertDialog.Builder.setMessage(...)` is combined with `setMultiChoiceItems(...)`; …

Warning:
- [yaacc/src/main/java/de/yaacc/yxc/YxcLinkCandidates.java:115-118 with YxcGroupOperations.java:149-162, 174-178 and ReceiverRowBinder.java:488-493] Extending a group while one of its members is unreachable (or not read yet) silentl …
- [yaacc/src/main/java/de/yaacc/yxc/YxcGroupOperations.java:104-110, 160-162, 211-213, 263] The "busy" set is built before the members are known: `busyChanged(...)` is called once in `guarded` with the server and the picked devices, …
- [yaacc/src/main/java/de/yaacc/player/YxcPlayTargets.java:66-78 (with YxcMonitor.snapshot) ] The redirect only works for devices the monitor has watched in this process. `known` is `monitor.snapshot()`; …

## Slice 3 — cycle 2 — 2026-11-02

Verdict: PASS

## Slice 3 — final review — 2026-11-02

Verdict: FAIL

Critical:
- [yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java:501-581, reached from 436-462 `bindGroupState` -> 489 `bindGroupActions` -> `bindGroupVolume`, called by `bindStatus` (271-273)] The state of a drag (`start`, `startProg …

Warning:
- [yaacc/src/main/java/de/yaacc/browser/YxcLinkSheet.java:83-117 with yaacc/src/main/java/de/yaacc/yxc/YxcLinkCandidates.java (members that are not reachable / not ready are `selected=true`, disabled)] A group can no longer be ended …
- [yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java:544-557 and yaacc/src/main/java/de/yaacc/yxc/YxcVolumeAccess.java:59-62, 143-159] Blocking on slow members. …
- [yaacc/src/test/java (no test for the drag state or the toggle states); ReceiverRowBinder.java:501-581] The riskiest new logic is untested because it sits in an anonymous listener of the binder (no Robolectric/instrumentation in t …
- [yaacc/src/test/java/de/yaacc/yxc/YxcVolumeAccessTest.java:98-120 vs YxcVolumeAccess.java:143-159, 190-205] The central coalescing branch is not tested: a newer value that arrives WHILE a send is in flight (the loop must send it, …

## Slice 3 — final review, cycle 2 — 2026-11-02

Verdict: FAIL (one Warning: the new pools are not shut down with the binder; everything else is closed)

Warning:
- [yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java:495-505 and 814-816] The two new pools are never shut down. …

## Slice 4 — cycle 1 — 2026-11-03

Verdict: FAIL (no Critical; 5 Warnings: failure path of the sheet, settings button hidden for role UNKNOWN, worker thread / dialog not released with the host, ScrollView inside the bottom sheet, missing edge-c …

Warning:
- [yaacc/src/main/java/de/yaacc/browser/YxcSettingsSheet.java:359-375 and 390-395] The failure path does not do what its javadoc says ("the sheet shows the device's real state again"). …
- [yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java:481 with YxcLinkCandidates.actionsFor] The settings button is unreachable for a device that answers but whose role is `UNKNOWN`. …
- [yaacc/src/main/java/de/yaacc/browser/YxcSettingsSheet.java:72-76, 104 with ReceiverRowBinder.java:495 and 841-846] Thread left behind when the sheet is not dismissed. …
- [yaacc/src/main/java/de/yaacc/browser/YxcSettingsSheet.java:100-102] The rows are in a platform `ScrollView` inside a `BottomSheetDialog` (`setFitToContents(true)`, expanded). …
- [yaacc/src/test/java/de/yaacc/yxc/YxcSettingsTest.java, YxcClientTest.java] The edge cases the slice rules name are not tested (testing.md: missing edge-case tests are a Warning). …

## Slice 4 — cycle 2 — 2026-11-03

Verdict: PASS

## Slice 5 - cycle 1 - 2026-11-04

Verdict: FAIL (no Critical; 3 Warnings: real station names in docs/tech.md, row play / pause / stop routed to the device source while a yaacc player is paused, source icons offered while yaacc streams to the d …

Warning:
- [docs/tech.md, section 12, "Tuner calls need the tuner input" paragraph and "DAB service next / previous" paragraph] The documentation commits contained the names of real radio programmes and the ending of a real DAB service name …
- [ReceiverRowBinder.java `ownSourceMapped`, used by `bindSourceControls` and `executeAction`] The row's play / pause / stop are routed to the device's own source when `player == null || !player.isPlaying()`. …
- [YxcSourceControls.available / ReceiverRowBinder.bindSourceControls] The previous / next / repeat / shuffle icons are offered whenever the zone input is a network source with a matching play info, including the `server` input that …

## Slice 5 - cycle 2 - 2026-11-04

Verdict: PASS

Suggestions left open:
- [YxcSourceControls.available(state, yaaccActive)] The gate hides every control while a yaacc player is playing or paused, also when the user has meanwhile switched the device to its tuner or a net radio with the manufacturer's app …
- The binder's `yaaccActive` itself is not covered by a test (Android player objects); the rule that uses it is.
- All Suggestions of cycle 1 stay open (shared worker and no test for the 300 ms gate, band toggle after a dropped call, preset cap by the reported number, 200 character line cap, repeated `getFeatures` on error, star on a group cli …
