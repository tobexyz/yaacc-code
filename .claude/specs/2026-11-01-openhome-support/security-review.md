# Security review: OpenHome support (#301), summary

Condensed on 2026-11-08: the verdict of every cycle and the blocking findings (Critical and Warning) in one line each; the long
texts, the suggestions and the notes of what was checked are in the git history of this file (the commit before the cleanup).
Cycle 3 ended FAIL, the cycle 4 PASS covers its fixes; cycles 5 and 6 reviewed the hardening. Final state: PASS. Suggestions that were
not taken are listed in `decisions.md`.

## Cycle 1 — 2026-11-06

Reviewing: Groups 2-4 (OpenHome renderer, branch feat/issue301, up to commit 22db10c3)

Warning:
- [OhRendererCore.java:369 `ReadList`, OhIds.java:91-96 `parseIdList`, OhQueue.java:159 `readList`, OhTrackList.java:27 `toXml`] **Remote out-of-memory by amplification (new; AVTransport has nothing like it).** `ReadList` accepts up to 100 …
- [OhQueue.java:36-38,96-117 `insert`, AndroidOpenHomeHost.java:65-92 `toPlayableItem`, OpenHomeRenderer.java `startFrom`/`reconcile`] **The queue is bounded per entry but not in total (new).** 1000 tracks x (4096 + 65536) chars = about 70 …

Suggestions: 7 (taken or recorded in `decisions.md` where they mattered).

Verdict: FAIL

## Cycle 2 — 2026-11-06

Reviewing: commit b7cb6c32 (fixes of cycle 1) and the controller side that cycle 1 did not cover: `openhome/{OhControl, OhControlException, OhXml, OhSources,

Warning:
- [OhXml.java:72-75 `text`, OhTrackListParser.java:45-47, OhSources.java:62-64] **Remote crash of the app by deeply nested XML in a device's answer (new, controller side).** `OhXml.text` calls `Element.getTextContent()`, which is recursive …
- [OhRemote.java:158-180 `queue`, OhTrackListParser.java:38-52, OhXml.java:36 `MAX_LENGTH`, OhPlayableItems.java:43-46 `fromTrack`, OpenHomePlayer.java:147-160 `rebuildItems`, OhIds.java:33 `MAX_LIST`] **Remote memory exhaustion by a devic …

Suggestions: 7 (taken or recorded in `decisions.md` where they mattered).

Verdict: FAIL

## Cycle 3 — 2026-11-06

Reviewing: commit f901bd41 (fixes for the two cycle 2 Warnings and the cycle 2 Suggestions), OpenHome controller side.

Warning:
- [OhXml.java:46-58 `parse`, OhTrackListParser.java:40, OhSources.java:53] **Cycle 2 Warning 1 (a deeply nested answer) is not resolved on the real runtime.** The fix removes the recursive `getTextContent()`, but the DOM is still built by …
- [OhRemote.java:179-188 `queue`, OpenHomePlayer.java:166-172 `refresh`] **Cycle 2 Warning 2 is fixed for memory, not for traffic.** Memory is bounded: ids cut to 1000, only asked-for entries, Uri <= 4096, Metadata <= 16384, 2 MiB per answ …

Suggestions: 4 (taken or recorded in `decisions.md` where they mattered).

Verdict: FAIL

## Cycle 4 — 2026-11-08

Reviewing: Groups 1-7 as a whole, with the fixes for the two cycle 3 Warnings (commit 1e5eef74) and everything after cycle 3 (commits f901bd41..b2adf6c3: OpenHome controller

Verdict: PASS

## Cycle 5 — 2026-11-08

Reviewing: commits ade16c7d (size, time and count limits for everything the network sends: the six suggestions of cycle 4) and 776af0ed (start and end of the renderer are never dropped, limits for images and album art relaxed), with decisions.md

Verdict: FAIL

## Cycle 6 — 2026-11-08

Reviewing: the fixes of cycle 5 (commits d48ed80b and 60470b53): subscription time limit and per-host cap, abort in `HttpRequestSender.send`, `readBounded` without close on failure, per-request limits, `runGuarded` OOM, `AlbumArt.of` at two more places.

Verdict: PASS
