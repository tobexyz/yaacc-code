# Review: Issue #296 follow-up - the renderer survives fast clicks on play / pause

## Cycle 1 - 2026-11-05
Reviewing: commit eaf0a9e9 (Paused / Stopped / NoMediaPresent `pause()`, Paused `seek` / `next` / `previous`, `SeekTarget`, `AvTransportFastClicksTest`)

### Critical
- none

### Warning
- [docs/tech.md:2921] The note says "`AvTransportFastClicksTest` (6 of its 7 tests failed before)". Measured: the new test class run against the parent commit's production code (the four state classes restored, `SeekTarget` removed) fails 7 of 7, every one with a SOAP 500 (701) at `AvTransportFastClicksTest.java:65`. Fix: write "all 7".
- [yaacc/src/main/java/de/yaacc/upnp/server/avtransport/SeekTarget.java:35-46] Business logic moved out of `AvTransportMediaRendererPlaying.seek` into a shared helper, but no test covers it directly and nothing tests the Playing path at all (no Seek test exists for Playing before or after). The "behaviour-preserving" claim is therefore only checked by reading. Covered indirectly (Paused): `00:00:20` -> 20000 ms, `soon` -> ignored. Not covered: `null` target -> 0 ms, a value that is not `HH:mm:ss` but is partly parseable, and Seek through Playing. Fix: add a small `SeekTargetTest` (null -> 0L, `00:01:05` -> 65000L, `soon` -> null, `01:00:00` -> 3600000L i.e. GMT) and one Playing-seek case in `AvTransportFastClicksTest` (or a neighbouring renderer test) that checks `seekTo(20_000L)` while PLAYING. Per `testing.md` a missing edge-case test is a Warning.

### Suggestion
- [AvTransportFastClicksTest] The goal "no new events" is not asserted anywhere; only "pauses the player once" is. That assertion is meaningful (a self-transition would run `onExit` / `onEntry` and call `player.pause()` again, so `times(1)` fails), and no transition also means no event, but a check on the event stream (as `AvTransportEventTest` does) would pin the goal down. Same for pause while STOPPED / idle: assert that no player method is called.
- [AvTransportFastClicksTest:150] `thePossibleActionsOfThePausedStateAreAllAnswered` does not exercise Seek / Next / Previous (they are in the other tests), so the name promises more than it checks. Rename (e.g. `playPauseAndStopWhilePausedAreAnswered`) or add the three calls.
- [SeekTarget.java:42-46] The old code logged the `ParseException` (`unable to parse target time string`, `pex`); the callers now log the text without the exception. Harmless, only a little less diagnosable.
- [AvTransportMediaRendererPaused.java:117] Paused.seek uses `upnpClient.getCurrentPlayers(getTransport())` (never null: returns an empty list), while Playing uses its `players` field (set in `onEntry`, can be null if `onEntry` returned early for an empty track URI). Paused is the more robust of the two and the same lookup `Paused.onEntry` uses; no threading problem, since every signal runs under `synchronized (this)` of `StateMachineInvocationHandler.invoke`. No action needed.
- [Stopped / NoMediaPresent `pause()`] Accepting Pause when nothing plays goes beyond the strict AVTransport:1 state table (Pause is not a listed action there and `getPossibleTransportActions` does not list it), but the spec states it explicitly (Goals, bullet 1) and the behaviour is documented in `docs/tech.md` and the CHANGELOG. It cannot hide a real failure: no player exists in these states, so there is nothing that could have failed.

### Verified
- Cling semantics: `org.seamless.statemachine.StateMachineInvocationHandler.invoke` looks the signal up by name and parameter types on the current state class (`getMethodOfCurrentState`), throws `TransitionException` (-> 701 in `AVTransportService`) when missing, and only runs exit / entry when the returned value is a `Class` that is a known state. A `null` return is therefore no transition, no `onExit` / `onEntry`, and the action is answered normally (200). The proxy method is `void`, so the return value is not seen by the caller.
- No other renderer behaviour changes: only new public methods on three states; `AvTransportMediaRendererPlaying.seek` keeps the same logic. Full suite: 2965 tests, 0 failures, 0 errors, 0 skipped.
- `SeekTarget` against the old Playing code: `null` target -> 0 (old: `target != null && ... : 0L`), unparsable -> ignored with a debug line (old: `ParseException` caught), `HH:mm:ss` with GMT -> identical (`SimpleDateFormat.parse` never returns null, so the old `!= null` check was dead). Same default locale, same leniency.
- Test quality: all 7 tests fail without the change (reproduced), all pass with it. Assertions are meaningful (`times(1)` pause, `seekTo(20_000L)`, `times(1)` play so a seek does not resume, `never()` seekTo for an unreadable target, state read back through real `GetTransportInfo`).
- No leftovers (no debug code, no TODOs, working tree clean). License header of `SeekTarget.java` and the test follow the repo's 2026 header form. CHANGELOG entry sits in `[Unreleased]` / Fixed and matches the change.

### Tests
- [x] All tests passing (`:yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug` EXIT 0, 2965 tests, 0 failures; lint issues: 0)
- [ ] Coverage adequate for the change (SeekTarget null / Playing path untested, see Warning)

### Verdict: FAIL
Two Warnings, both small: the doc count "6 of 7" (is 7 of 7) and the missing test for the shared `SeekTarget` / Playing seek path. No Critical findings; the production change itself is correct.

## Cycle 2 - 2026-11-05
Reviewing: commit e36a7e8f (fixes for the two cycle 1 Warnings)

### Critical
- none

### Warning
- none

### Suggestion
- "No new events" stays unasserted. Accepted: the claim is carried by the no-transition path (a `null` return runs neither `onExit` nor `onEntry`; Cling's handler is read and verified in cycle 1), and `times(1)` on `player.pause()` proves that path for Paused. An event-count assertion would depend on the asynchronous event pacing and could be flaky, so leaving it out is the right trade-off. Not blocking.
- [SeekTargetTest] `aTimeCodeIsReadAsMillisecondsFromTheStartIndependentOfTheTimeZone` does not change the JVM default time zone, so it only guards the GMT line when the test JVM is not already on GMT/UTC. Setting `TimeZone.setDefault` (restored in `@After`) to e.g. `America/Los_Angeles` would make the name true on every machine. Not blocking.

### Verified
- Cycle 1 Warning 1: `docs/tech.md` now says "`AvTransportFastClicksTest` (all 7 failed before), `SeekTargetTest`" - matches the measurement.
- Cycle 1 Warning 2: `SeekTargetTest` (3 tests) covers `00:00:00`, `00:00:20`, `00:01:05`, `01:00:00`, `null` -> 0, and `soon` / empty / `20` -> null. New `seekWhilePlayingMovesThePlayersAndKeepsPlaying` goes through the real SOAP path and checks `seekTo(65_000L)` and state PLAYING, so the refactored Playing path is covered. The renamed `pausePlayPauseStopFollowEachOtherWithoutAnError` now matches what it does.
- Only test files and docs changed in e36a7e8f; production code is unchanged since cycle 1. Working tree clean, nothing uncommitted.

### Tests
- [x] All tests passing (`:yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug` EXIT 0; 2969 tests, 0 failures, 0 errors, 0 skipped; `AvTransportFastClicksTest` 8/8, `SeekTargetTest` 3/3; lint issues: 0)
- [x] Coverage adequate for the change

### Verdict: PASS
