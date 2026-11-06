# Security Review: Issue #296 follow-up - the renderer survives fast clicks on play / pause

## Cycle 1 - 2026-11-05
Reviewing: `git diff develop..HEAD` at 96397382 (Paused / Stopped / NoMediaPresent `pause()`, Paused `seek` / `next` / `previous`, `SeekTarget`, tests, CHANGELOG, docs/tech.md)

Threat model: the renderer is an unauthenticated UPnP AVTransport service; every device on the LAN is a potential hostile control point. UPnP has no authentication, so "may a LAN device pause / seek / stop" is not a finding here; the question is whether the change widens what hostile input can do.

### Critical
- none

### Warning
- [yaacc/src/main/java/de/yaacc/upnp/server/avtransport/SeekTarget.java:35-46] The new shared helper is the single validation point for the hostile Seek `Target`, and it has no bounds or format checks. It hands the text to a lenient `SimpleDateFormat("HH:mm:ss").parse(String)` and returns whatever comes out. Measured on a JVM (same pattern, GMT):
  - Negative values are accepted: `-5:00:00` -> -18 000 000 ms, `00:00:-9999` -> -9 999 000 ms.
  - Huge values are accepted: `99999999999:00:00` -> 4.37e15 ms, `2147483647:59:59` -> 7.7e15 ms; `4294967296:00:00` wraps to 0 (int truncation). `99:99:99` -> 362 439 000 ms (lenient).
  - Trailing text is ignored (`00:00:20junk` -> 20 000), so the text is not validated as a whole.
  - A very long digit string is parsed in roughly quadratic time: a 1 000 000 character Target took 3.5 s of CPU, 5 000 000 characters 40 s (desktop JVM; Android will be slower). The HTTP side has no body limit (`BasicAsyncEntityConsumer` without a cap in `YaaccUpnpServerProtocolRequestHandler.prepare`), so the request is accepted. The parse runs inside `StateMachineInvocationHandler.invoke` under `synchronized (this)` (see review.md, cycle 1), so for that time no other command of the transport (Pause, Stop, Play, events of the state) is served: a single unauthenticated request stalls the renderer.
  - The values reach `player.seekTo(long)` unchanged. `LocalMediaSessionPlayer.seekTo` posts `exoPlayer.seekTo(ms)` to the main looper (no bound check in yaacc; I did not verify how media3 1.11.1 treats a negative or 1e15 position, so a crash on the main thread cannot be ruled out from here). `AVTransportPlayer.seekTo` formats the value and forwards it to a remote renderer.
  Not a regression of the Playing path (the old code had exactly the same parse and the same values reached `seekTo`), which is why this is not Critical. It is a Warning because (a) the change moves this logic into a new class that is meant to be the shared, reviewed entry, (b) Paused used to refuse Seek with 701 before any parsing, so the change adds a second hostile-reachable entry to the parse, and (c) `~/.claude/rules/security-standards.md` / checklist require bounds and type checks on external input.
  Fix (a few lines, tests stay valid): in `SeekTarget.millis` reject (return `null`, the seek is then ignored and the debug line is logged) a target longer than a small limit (e.g. 32 characters) and one that does not match the whole of `\d{1,4}:[0-5]\d:[0-5]\d` (optionally `(\.\d{1,3})?`, UPnP `REL_TIME` allows a fraction); compute `h*3_600_000 + m*60_000 + s*1_000` by hand instead of `SimpleDateFormat`. That removes the lenient parse, the time zone dependency, the negative and 1e15 values and the quadratic cost, and caps the result below 10 000 h. Add `SeekTargetTest` cases for `-5:00:00`, `99:99:99`, `00:00:20junk`, a 100 000 character digit string (all `null`). Keep `null` -> 0L and `00:00:20` -> 20 000 as they are. Optionally clamp in `Paused.seek` / `Playing.seek` to `>= 0`.

### Suggestion
- [YaaccUpnpServerProtocolRequestHandler.java:121] Pre-existing and not part of this diff: the whole request body (so any Seek `Target`, the transport URI, metadata) is logged at verbose level, and the request body size is unbounded. The log lines added by this change (`pause: paused already`, `seek`, `next`, `previous`, `pause: nothing plays`, `unable to parse target time string`) contain no hostile or personal text, so no log injection or personal data is added. A body size cap on the UPnP request consumer would be the real fix for the DoS class above.
- [AvTransportMediaRendererPaused.java:113-123] Seek while paused changes the playback position for any LAN device. It is the same authority as Seek / Pause / Stop / Play already have and the pause stays (no Play is issued, covered by `times(1)` play in the test). Accepted by design of UPnP; no action.

### Verified (no finding)
- State-machine abuse: `pause()` in Paused / Stopped / NoMediaPresent and `next` / `previous` / `seek` in Paused return `null`. Cling's `StateMachineInvocationHandler` runs `onExit` / `onEntry` only for a returned state class, so no transition, no event, no new timer or thread (the `TrackInfoUpdater` start / stop belongs to the transitions), no player call except `seekTo` in Paused. Alternating Pause / Play / Seek cannot reach a stuck or inconsistent state because every signal is serialised and the new methods hold no state. The result can only be less error answers (701 before), so no new resource growth or event growth. A flood of Seek requests posts one main-looper runnable each, identical to Playing.
- Accepting Pause in Stopped / NoMediaPresent does not weaken anything: no player exists there, nothing is changed, nothing is leaked (the answer is the plain success of every other no-op action). It cannot mask a real failure.
- Thread safety of `Paused.seek`: `upnpClient.getCurrentPlayers(getTransport())` returns a new list (empty if the service or the transport is not there) and is called under the transport's state-machine lock, like `Paused.onEntry`. `null` entries are skipped. The Playing path uses the `players` field set in `onEntry` (cycle 1 of review.md); no new race.
- Injection: the Target is never used in a log line, a query, a command or a file name; it only goes through `SimpleDateFormat`. No SQL / command / XSS path.
- Test hygiene: `SeekTargetTest` sets `TimeZone.setDefault(Asia/Kolkata)` and restores the captured default in `@After`; `AvTransportFastClicksTest` closes its harnesses in `@After`; test URI is `192.0.2.1` (documentation range). No secrets, no network access beyond the in-process harness.
- Supply chain / config: no change to `build.gradle`, version catalogs, `AndroidManifest.xml` or permissions. No new dependency.
- License / headers: `SeekTarget.java`, `SeekTargetTest.java`, `AvTransportFastClicksTest.java` carry the repo's GPL header (2026, Tobias Schoene); `SeekTarget` is a package-private final class with a private constructor.
- Working tree clean; no secrets or debug leftovers in the diff.

### Verdict: FAIL
One Warning (input validation of the Seek `Target` in the new `SeekTarget` helper: negative / huge values and the unbounded, quadratic parse that holds the transport's lock). No Critical findings. The Pause / Next / Previous / no-op part of the change is clean. After the `SeekTarget` hardening and its tests, this should PASS in cycle 2.

## Cycle 2 - 2026-11-05
Reviewing: commit 5379522d (strict `SeekTarget`, new `SeekTargetTest` cases, docs/tech.md). Only `SeekTarget.java`, `SeekTargetTest.java`, `docs/tech.md` and this file changed since cycle 1; no manifest, build or dependency change.

### Critical
- none

### Warning
- none (the cycle 1 Warning is fixed, verified below)

### Suggestion
- [SeekTarget.java] Behaviour change worth knowing, not a defect: single-digit minutes / seconds (`0:0:5`) and a fraction with a trailing dot (`00:00:20.`) are now ignored; the old lenient parser took them. UPnP `REL_TIME` requires `MM:SS` with two digits, so conforming control points are not affected. The result is capped at 9999:59:59.999 (35 999 999 999 ms), so a seek beyond the media length is left to the players (ExoPlayer clamps / ends), as for any valid time code.
- Unchanged and out of scope, as agreed: the unbounded UPnP request body size (the coordinator reports it to the owner).

### Verified
- Format: `matches()` on `(\d{1,4}):([0-5]\d):([0-5]\d)(?:\.(\d{1,3}))?` anchors the whole text, so trailing text, leading / trailing blanks and a trailing newline are refused (`00:00:20\n` -> null, measured). `\d` is ASCII only (no `UNICODE_CHARACTER_CLASS`): an Arabic-Indic digit is refused (measured), so `Long.parseLong` never sees a non-ASCII digit and cannot throw `NumberFormatException` (groups are 1-4 / 2 / 2 / 1-3 ASCII digits).
- Sign, size and leniency: `-5:00:00`, `+1:00:00`, `00:00:-9999`, `99:99:99`, `00:60:00`, `99999:00:00`, `4294967296:00:00` all -> null (test cases plus my own run of the class on a JVM).
- Backtracking / CPU: the pattern has no nested or ambiguous quantifier (every group is separated by a literal and has a fixed small bound), so matching is linear; input is additionally cut at 32 characters before the matcher is created. Measured: 600 000 adversarial calls (32-character digit runs, near-misses) took 182 ms in total; a 100 000 character text returns null at the length check without being scanned.
- Range: the largest accepted value is `((9999*60+59)*60+59)*1000+999` = 35 999 999 999 ms, about 3.6e10, computed in `long` (all operands are `long`, `Long.MAX_VALUE / 3.6e6` is about 2.56e12 hours, so no overflow). The value is never negative (digits only, no sign), so `player.seekTo` cannot receive a negative or overflowing number from the Seek action, via Playing or Paused. `null` target still gives 0 and `HH:MM:SS` of the earlier tests gives the same values as before (the time zone is no longer involved).
- Callers: Paused and Playing still treat `null` as "ignore the seek" (debug line only, no text logged); the diff of the callers is unchanged from cycle 1.
- Tests: `SeekTargetTest` now covers fractions, the 9999 h maximum, the hostile list above and the 100 000 character case with a time bound; the time zone is still restored in `@After`.

### Verdict: PASS
