# Review: MusicCast support (#307)

## Slice 2 — cycle 1 — 2026-11-02
Reviewing: Slice 2 (commits 514c345e and 520dcd08): device state, event plan, discovery, UDP listener, monitor, receiver row wiring and strings.

Verified: `ANDROID_HOME=/root/android-sdk ./gradlew --offline :yaacc:testDebugUnitTest --tests 'de.yaacc.yxc.*' --tests 'de.yaacc.browser.YxcRowTextTest'` is BUILD SUCCESSFUL, 0 failures, 0 errors in every result file (YxcMonitorTest 23, YxcDeviceStateTest 9, YxcDiscoveryTest 9, YxcEventListenerTest 4, YxcRowTextTest 10, plus the slice-1 yxc tests). The working tree is clean.

What is right (checked, no finding):
- Locking: all monitor state is guarded by `this`; network calls (discovery, reads) run outside the lock; `fire()` copies the listener list under the lock and calls the listeners outside it, on the I/O or event thread (never on the scheduler thread, which only enqueues). The only place a listener runs under the lock is the test setup, where the executor is `Runnable::run`.
- Generation handling: `stopIfUnused` and `startLocked` both bump `generation`; `readLoop`, `poll` and `flush` re-check `active` and the generation after every unlocked section, and a stale read neither writes state nor touches `reading` (`startLocked` resets `reading` and `pending`, so a read left over from the old generation cannot block the new one). `pollTask` and the debounce handles are cancelled on stop. A `stopIfUnused` that is already running when `subscribe` arrives returns because the listener list is not empty.
- `reading` flag: set only in `requestLocked`, cleared only in `readLoop` on the paths "no endpoint" and "no pending left"; work requested during a read is picked up by the loop (`again`), so a burst makes one read.
- Debounce: restarts per flag-carrying event, `play_time`/`signal_info_updated` alone ask for nothing, power/status/dist/play flags map to the matching calls. Matches the measured bursts in docs/tech.md section 10 (track-change burst of five datagrams -> one `getPlayInfo`; pause/play -> `play_info_updated` -> `getPlayInfo`; `dist_info_updated` twice 70-200 ms apart -> one `getDistributionInfo`).
- Trust: events count only from the address of a device found by our own discovery and with a matching `device_id`; the endpoint host is the registry's URL host, a host named inside the description is not followed (tested).
- UI: the feed posts to the main thread and the row binder reads `getState(udn)` fresh on rebind, so out-of-order notifications (they are fired outside the lock from two threads) cannot leave a stale row. Subscribe/unsubscribe is paired (`onResume`/`onPause`, `startLiveUpdates`/`stopLiveUpdates`, `onDestroyView` backstop). Pick mode uses a checkbox row without `yxc_text`, so it needs no feed.
- Translations: the seven `yxc_*` strings exist in values and in de, es, fr, nl, pt, zh with the same `%1$s` placeholders; the layout is guarded against a missing view (`holder.yxcText == null`).

### Critical
- none

### Warning
- [yaacc/src/main/java/de/yaacc/yxc/YxcMonitor.java:350-407 (create at 381, read at 385, apply at 392; submit at 347)] `readLoop` has no guard against an unchecked exception. `transports.create(...)` builds a `YxcHttpTransport`, whose constructor throws `IllegalArgumentException` for a host that is not a plain name or IP (for example an IPv6 literal with a zone id such as `fe80::1%wlan0`, or a name with an underscore; the host comes from the descriptor URL the registry got from SSDP). The exception escapes on a pool thread: `device.reading` stays `true` for the rest of the generation (that device is never read again, polls and events are ignored for it), and on Android an uncaught exception on any thread ends the process. Fix: wrap the body of `readLoop` in `try { ... } catch (RuntimeException e) { log } finally { reset reading under the lock if still the same generation }` (or treat the failure like "no endpoint": clear `reading`, log, return), and add a test with a transport factory that throws.
- [yaacc/src/main/java/de/yaacc/yxc/YxcMonitor.java:355-379 together with 188-193] A stale endpoint can become permanent. If `subscribe` changes a device's `descriptorUrl` while `readLoop` is inside `discovery.find(...)` for the old URL (first discovery or after a failed one, up to about 8 s), `subscribe` clears `device.endpoint`, then the loop's second synchronized block (376-379) stores the endpoint found for the OLD url. From then on `endpoint = device.endpoint` (line 362) is non-null, so discovery is never asked again; neither a second `subscribe` with the same new URL (equal to `descriptorUrl`, no reset) nor stop/start (`startLocked` clears `client` but not `endpoint`) repairs it; the device shows "Not reachable" until the process dies. Fix: remember the URL used in the first block and, in the second block, discard the result (and loop again) when `device.descriptorUrl` is no longer that URL; optionally clear `device.endpoint` in `startLocked` as well. Narrow window, but there is no recovery.
- [yaacc/src/main/java/de/yaacc/yxc/YxcEventPlan.java:59-61] `dist_info_updated` asks only for `getDistributionInfo`. Linking or unlinking a client also switches its input to or from `mc_link` (`YxcDeviceState.isLinkInput`), and `YxcRowText.describe` shows or hides the input name from that field. docs/tech.md section 10 says the events of a linked client are not captured yet, and a link/unlink event carries no input or `status_updated` flag on the server. So on a client the row can say "On - Net radio - Linked with X" (or "On - Linked" after an unlink while the input is still `mc_link`) until the next poll, up to 30 s. Fix: let `dist_info_updated` also add `ZONE_STATUS` (the event is rare, one extra read after the debounce), and extend `YxcDeviceStateTest.whatEventsAskToReadAgain` / `YxcMonitorTest.aGroupEventReadsTheGroupAndTheRowFollows` (assert the input of the row after the group change).
- [yaacc/src/test/java/de/yaacc/yxc/YxcMonitorTest.java:81 (executor `Runnable::run`)] The riskiest logic of the monitor is not exercised: with a synchronous executor a read can never be "in flight", so none of these paths has a test: stop/start while a read is running (stale generation dropped, `reading` reset, no write of stale state), events or `refreshNow` arriving during a read (the `again` branch of `readLoop`), the descriptor URL changing during a read/discovery (previous Warning), an unchecked exception in the transport factory (first Warning), the `MAX_DEVICES` cap, `refreshNow`, and an event that arrives before the first read of a device finished. Tests per `~/.claude/rules/testing.md` should cover them (edge cases are Warnings). Fix: give the test a queue executor (`tasks.add(runnable)`, run on demand) next to the direct one, and write these cases deterministically with the existing `FakeClock`.

### Suggestion
- [yaacc/src/main/java/de/yaacc/yxc/YxcEventPlan.java:39-63] docs/tech.md section 10 (volume bullet) recommends re-reading `getStatus` once after volume events have been quiet, because UDP can reorder the burst of a slider drag. Volume and mute events only update the state now, and an in-flight read (`YxcMonitor.java:392`) that started before an event can overwrite the event's value with an older one. The row does not show volume or mute, so there is no visible effect in this slice; it will matter for the group volume slider (group 4). Consider adding `ZONE_STATUS` for volume/mute events too (the debounce makes it one read), or note the decision in decisions.md.
- [yaacc/src/main/java/de/yaacc/yxc/YxcMonitor.java:246-248] `stopIfUnused` sets `stopTask = null` unconditionally. If `subscribe` + `unsubscribe` ran between the timer firing and the lock being taken, the newly scheduled stop task is forgotten and the monitor stops up to 30 s early (and a later `subscribe` cannot cancel the forgotten task, which then only returns because of the `listeners` check). Compare the task before clearing, or pass a token.
- [yaacc/src/main/java/de/yaacc/yxc/YxcMonitor.java:321-326] The sender address is compared as text (`getHostAddress()` of the datagram against the host of the descriptor URL). An IPv6 sender prints in the long form and never equals a compressed URL host, so events of an IPv6-only device are dropped (polling still works). Compare `InetAddress.getByName(endpoint.getHost())` (only for literals) with `from` instead.
- [yaacc/src/main/java/de/yaacc/yxc/YxcMonitor.java:180-187] `watched` never shrinks and every renderer of the registry is put in it (the cap of 64 is then final for the process). Devices that left the network are polled every 30 s and a user who changes networks can fill the map. Drop entries that are not in the latest `devices` map of the last subscriber when the monitor stops, or prune on `stopIfUnused`.
- [yaacc/src/main/java/de/yaacc/yxc/YxcMonitor.java:231-236] When the event source cannot be opened, it is never tried again while the monitor stays active; the port 0 is baked into the clients. Retry opening at the next poll (and reset `device.client`) so a short socket failure does not cost the events for the whole session.
- [yaacc/src/main/java/de/yaacc/yxc/YxcDiscovery.java:50,77,80,89] `retryAt == 0` means "final" while `retryAt` is derived from `System.nanoTime()` (arbitrary origin, can be negative or, very unlikely, exactly 0), and `now` is taken before the fetch that can take 8 s. Use a separate boolean `final` and compute the retry time after the fetch.
- [yaacc/src/main/java/de/yaacc/browser/DeviceSheetFragment.java:466 and DeviceSheetAdapter.java:203] The sheet calls `groupFeed.start(...)` once in `startLiveUpdates`; `adapter.refresh()` (player added/removed) can list receivers that appeared later without watching them, whereas `ReceiverListFragment.populateReceiverDeviceList` restarts the feed. Today the normal sheet does not learn about new devices except through player events, so the effect is small; call `groupFeed.start(upnpClient.getDevicesProvidingAvTransportService())` after `adapter.refresh()` in `playerListListener` for consistency.
- [yaacc/src/main/java/de/yaacc/browser/YxcRowText.java:44] A device that was unreachable from the very first read (role still UNKNOWN) shows no line at all, while one that becomes unreachable later shows "Not reachable". Acceptable (the device may simply not be a MusicCast device that is awake), but worth a line in decisions.md.
- [yaacc/src/main/java/de/yaacc/browser/ReceiverRowBinder.java:63] The new import `de.yaacc.yxc.YxcMonitor` sits between two `de.yaacc.util` imports; move it after them.
- [yaacc/src/test/java/de/yaacc/yxc/YxcEventListenerTest.java] Uses real loopback UDP with 5 s latches; fine and not flaky in practice (loopback does not drop single datagrams), `calls[0]++` is only touched by the listener thread. No change needed.

### Tests
- [x] All tests passing (filters from the task: 0 failures in all result files)
- [ ] Coverage adequate for the change (state, plan, discovery, listener and row text are covered well; the monitor's concurrency/generation paths and the failure paths named in the Warnings are not)

### Verdict: FAIL

## Slice 2 — cycle 1 follow-up (fixes by the orchestrator)
- W1 read loop without guard: fixed, `YxcMonitor.readLoop` catches `RuntimeException`, resets the flag and client, marks the device unreachable. Test: `aTransportThatCanNotBeCreatedDoesNotBlockTheDeviceForever`.
- W2 stale endpoint after a URL change during discovery: fixed (result discarded and read repeated; `startLocked` forgets the endpoint). Test: `anAddressThatChangedWhileItWasLookedAtIsLookedAtAgain`.
- W3 `dist_info_updated` also reads the zone: fixed in `YxcEventPlan`; tests updated.
- W4 tests with reads in flight: added (stop/start during a read, event during a read, event before the first read, URL change, throwing factory, device cap).
- Suggestion taken: stop task carries a sequence number so an out-of-date stop does nothing.
- Suggestions left open on purpose: volume/mute re-read (needed with the group slider, slice 4), IPv6 sender comparison, pruning `watched`, retrying a failed event source, re-subscribing the sheet when receivers appear later (same limit as `RendererStatusMonitor`).

## Slice 2 — cycle 2 — 2026-11-02
Reviewing: the fixes of commit ff5cbf82 for the four Warnings of cycle 1.

Verified: `ANDROID_HOME=/root/android-sdk ./gradlew --offline :yaacc:testDebugUnitTest --tests 'de.yaacc.yxc.*' --tests 'de.yaacc.browser.YxcRowTextTest'` is BUILD SUCCESSFUL, 0 failures and 0 errors in every result file (YxcMonitorTest 29, YxcDeviceStateTest 9, YxcDiscoveryTest 9, YxcEventListenerTest 4, YxcRowTextTest 10, slice-1 yxc tests unchanged).

Cycle 1 Warnings:
- W1 (readLoop without guard): fixed. `readLoop` wraps `readUntilDone`; the catch resets `reading` and `client` and marks the device unreachable only when `active && readGeneration == generation`, so a stale read cannot touch the new generation's flag. `fire` is called outside the lock. The exception cannot leave the lock held (the `transports.create` call is inside a `synchronized` block that unwinds). Test `aTransportThatCanNotBeCreatedDoesNotBlockTheDeviceForever` covers it (it fails without the guard because the second poll would never read).
- W2 (stale endpoint after URL change during discovery): fixed in `readUntilDone` (url compared to `device.descriptorUrl` under the lock after the unlocked section, result discarded, `what` put back into `pending`, loop again). No endless loop: every extra turn needs a new `subscribe` that changes the URL again, and the work of a turn is the same pending set, so the loop is bounded by external changes. `reading` stays true across the `continue` (correct, the loop still owns it); generation checks come first. The `YxcDiscovery` cache is keyed by UDN plus URL, so the second `find` really asks again. Test `anAddressThatChangedWhileItWasLookedAtIsLookedAtAgain` fails without the check (one fetch only). But see the new Warning about the `startLocked` part of this fix.
- W3 (`dist_info_updated` also reads the zone): fixed in `YxcEventPlan`; `YxcDeviceStateTest.whatEventsAskToReadAgain` and `YxcMonitorTest.aGroupEventReadsTheGroupAndTheRowFollows` (one extra `main/getStatus`) updated. The test does not assert the input of the row after the change (the fixture has the same input before and after), which is acceptable.
- W4 (tests with reads in flight): fixed with `QueueExecutor`, `duringFetch` and `FakeDevice.onStatus` hooks: stop/start during a read, event during a read (the `again` branch), event before the first read, URL change, throwing factory, device cap. Not covered: `refreshNow`, and the restart case described in the new Warning.
- Suggestion taken, `stopSequence`: correct. `subscribe` and `unsubscribe` both bump it, a stop task that was already running when the next `subscribe`/`unsubscribe` pair happened sees a different number and returns without clearing the newer `stopTask`. No test for it (not blocking).

### Critical
- none

### Warning
- [yaacc/src/main/java/de/yaacc/yxc/YxcMonitor.java:242 with 212 and 410-413] New problem introduced by the cycle 1 fix: `startLocked` now sets `device.endpoint = null` for every watched device, but `getState(udn)` returns `null` whenever `endpoint == null` (the "not a device of this kind" signal that hides the row line). After a restart of the monitor (a subscribe more than 30 s after the last unsubscribe, i.e. every return to the Receiver tab or the sheet after a while) all devices are "not a protocol device" until the I/O thread has passed the second synchronized block of `readUntilDone` (a cached discovery makes that milliseconds, an expired entry or a slow executor up to the 8 s fetch). A row that is bound in that window (`ReceiverRowBinder.java:419` on the main thread, `populateReceiverDeviceList` posts the adapter at about the same time as `groupFeed.start`) gets no line. When the read ends the state is usually `equals` to the kept state (it contains no play time), so `readUntilDone` fires no notification (line 427) and nothing rebinds the row: the line stays missing until the state really changes or the user leaves and returns. The clearing is also not needed for W2 any more, because the URL comparison at 401 and the URL-change reset in `subscribe` (192-193) already cover it, and `YxcDiscovery` re-checks its own cache by URL. Fix: do not clear `endpoint` in `startLocked` (keep `client = null`); or, if re-asking is wanted, fire a notification when `device.endpoint` goes from null to non-null at 410-413 regardless of `equals`. Add a test: subscribe, unsubscribe, `STOP_DELAY_MILLIS`, subscribe again with `executor.deferred = true`, assert `getState(YXC_UDN) != null` before `runAll()` (or that a notification arrives after it).

### Suggestion
- [yaacc/src/test/java/de/yaacc/yxc/YxcMonitorTest.java] No test for `refreshNow` and for the `stopSequence` race; cheap to add with the new `QueueExecutor`/`FakeClock`.
- The open Suggestions of cycle 1 (volume/mute re-read, IPv6 sender comparison, pruning `watched`, retrying a failed event source, re-subscribing the sheet, `retryAt == 0`) stay open as decided; the volume one must be settled before slice 4.

### Tests
- [x] All tests passing
- [ ] Coverage adequate for the change (the restart case that the new Warning describes is untested)

### Verdict: FAIL

### Cycle 2 fix
- New Warning (restart hides the row line): fixed by not clearing `endpoint` in `startLocked`; the URL comparison in `readUntilDone` and the URL-change reset in `subscribe` cover the stale-address case. Test: `aRestartKeepsTheKnownStateVisibleWhileItIsReadAgain`.

## Slice 2 — cycle 3 — 2026-11-02
Reviewing: commit 94f17156 (the Warning of cycle 2).

Verified: `ANDROID_HOME=/root/android-sdk ./gradlew --offline :yaacc:testDebugUnitTest --tests 'de.yaacc.yxc.*' --tests 'de.yaacc.browser.YxcRowTextTest'` is BUILD SUCCESSFUL, 0 failures and 0 errors (YxcMonitorTest 30, YxcRowTextTest 10, the other yxc suites unchanged).

- Cycle 2 Warning (row line missing after a restart): fixed. `startLocked` keeps `device.endpoint` (only `client` is dropped), so `getState` stays non-null while the restarted monitor reads again. Test `aRestartKeepsTheKnownStateVisibleWhileItIsReadAgain` does the real sequence (subscribe, unsubscribe, `STOP_DELAY_MILLIS`, subscribe with a deferred executor) and asserts `getState != null` before the read runs; it would fail with the old `endpoint = null`.
- Stale address without the clearing: still covered. (1) `subscribe` with a different descriptor URL clears `endpoint` and `client` (YxcMonitor.java:190-195), also after a stop, because the URL comes from the registry on every subscribe. (2) `readUntilDone` discards a discovery result whose URL no longer equals `device.descriptorUrl` and reads again (401-405); `anAddressThatChangedWhileItWasLookedAtIsLookedAtAgain` covers it. (3) `YxcDiscovery` re-checks its cache by UDN and URL. A changed address of a device with an unchanged URL cannot occur, since the endpoint host is the URL host. A URL change during the call phase of a read is handled by the `again` branch (pending is refilled, the next turn finds `endpoint == null` and asks discovery).
- No new locking, `reading`-flag or generation issue: the commit removes one line and adds one test.

### Critical
- none

### Warning
- none

### Suggestion
- Unchanged from cycle 2 (tests for `refreshNow` and the `stopSequence` race; the open cycle 1 Suggestions, volume/mute re-read before slice 4).

### Tests
- [x] All tests passing
- [x] Coverage adequate for the change

### Verdict: PASS
