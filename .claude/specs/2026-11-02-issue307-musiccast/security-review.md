# Security review: MusicCast support (#307), summary

Condensed on 2026-11-04: the verdict of every cycle and the blocking findings in one or two lines each. The full texts of the
cycles are in the git history of this file (commit before the cleanup) and in the branch `backup/feat-issue307-before-squash`
(while it exists). Suggestions that were not taken stay listed in `decisions.md` or in the last cycle below.

## Slice 2 — cycle 1 — 2026-11-02

Verdict: FAIL

Critical:
- [yaacc/src/main/java/de/yaacc/yxc/YxcClient.java:187 (with YxcMonitor.java:359-361)] **Remote crash of the app by a hostile device: no nesting-depth bound on the JSON answers, and a StackOverflowError is not caught.** Since slice …

Warning:
- [yaacc/src/main/java/de/yaacc/yxc/YxcEventParser.java:128-150, 65 and YxcEventListener.java:83] **The datagram depth guard can be bypassed, and the parse runs before the trust check.** `nestingDepth` treats only `"` as a string de …
- [yaacc/src/main/java/de/yaacc/yxc/YxcHttpDescriptionFetcher.java:52-67, YxcHttpTransport.java:145-146, YxcMonitor.java:149] **No overall deadline on a read; …
- [yaacc/src/main/java/de/yaacc/yxc/YxcMonitor.java:302-305 and yaacc/src/main/java/de/yaacc/browser/YxcRowFeed.java:67-73] **Every state change posts to the main thread; …

## Slice 2 — cycle 1 follow-up (fixes by the orchestrator)

Verdict: -

## Slice 2 — cycle 2 — 2026-11-02

Verdict: FAIL

Warning:
- [yaacc/src/main/java/de/yaacc/yxc/YxcHttpDescriptionFetcher.java:53-56 and YxcHttpTransport.java:141-148] **The 15 s deadline does not cover the response headers, so a slow-drip peer still holds a worker thread (cycle 1 Warning "n …

## Slice 2 — cycle 3 — 2026-11-02

Verdict: PASS

## Slice 3 — cycle 1 — 2026-11-02

Verdict: FAIL

Warning:
- [yaacc/src/main/java/de/yaacc/yxc/YxcGroupDirectory.java:44-57, YxcPlaybackRedirect.java:80, player/YxcPlayTargets.java:138-149] **A device that claims the group id of a client can capture the playback meant for that client, and t …
- [yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java:529-533 (start levels), YxcDeviceState.java:109-124, YxcGroupVolume.java:35-44, 70-77, YxcMonitor.java:438-442] **The group volume slider writes absolute volumes compute …
- [yaacc/src/main/java/de/yaacc/yxc/YxcVolumeAccess.java:93-94, 110, 134, 181, 209; YxcMonitor.java:236-242, 267; YxcDiscovery.java:100-119; …
- [yaacc/src/main/java/de/yaacc/player/YxcPlayTargets.java:67-101, YxcMonitor.java:257-286, 292-330] **No overall time budget on the playback path; …

## Slice 3 — cycle 2 — 2026-11-02

Verdict: PASS

## Slice 4 — cycle 1 — 2026-11-03

Verdict: FAIL

Warning:
- [yaacc/src/main/java/de/yaacc/browser/YxcSettingsSheet.java:243-247 and 305-310 (`chooseFrom`, `optionLabel`); YxcSettings.java:190-207, 235-237, 272-274; YxcZoneFeatures.java:71-79; …

## Slice 4 — cycle 2 — 2026-11-03

Verdict: PASS

## Slice 5 - cycle 1 - 2026-11-04

Verdict: FAIL

Warning:
- [.claude/specs/2026-11-02-issue307-musiccast/capture/scrub.py:10-17 (`SECRET_KEYS`), 36-42 (`scrub_json`); …

## Slice 5 - cycle 2 - 2026-11-04

Verdict: PASS

Suggestions left open:
- [capture/scrub.py:19] The key pattern names the keys of the three devices captured so far. A free-text key with another name (for example `preset_name`, `service_name`, `ensemble_name`; …
- [capture/scrub.py:62-72] Only `.json` and `.xml` files are scrubbed. The output of `source_test.sh` and `listen_events.py` saved to a `.txt` file (or the `raw` fallback of an event) is not; …
- Cycle 1 suggestions are unchanged and still open (state caps for `YxcPlayInfo` / `YxcTunerInfo`, one `yxc-source` thread, overlapping scan chains, repeated `getFeatures` after a non-transport failure, star on a group client, cosme …
- Not rated as work for the implementer: the real station name in the history (1c96f3af, 394b364a, on `origin/feat/issue307`) stays; the owner decides whether to rewrite it (sensitivity low, public broadcaster).
