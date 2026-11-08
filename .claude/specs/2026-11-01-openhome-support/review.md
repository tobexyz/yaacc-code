# Review: OpenHome support (#301), summary

Condensed on 2026-11-08: the verdict of every cycle and the blocking findings (Critical and Warning) in one line each; the long
texts, the suggestions and the notes of what was checked are in the git history of this file (the commit before the cleanup).
Cycles 1 to 3 reviewed the renderer, cycles 4 to 7 the controller and the reverse sync, cycles 8 to 10 the hardening. Cycle 10 ended FAIL with one Warning (the subscription time limit was tested only through its helper); it was fixed with tests at both call sites and read by security cycle 6, there was no further general cycle.

## Cycle 1 — 2026-11-06

Reviewing: Groups 2, 3 and 4 (renderer: pure model, Cling services, coordinator and player changes), commits 8da319c4, dedcbe40,

Critical:
- [yaacc/src/main/java/de/yaacc/upnp/server/openhome/OpenHomeRenderer.java:313-342, 233-241 with yaacc/src/main/java/de/yaacc/player/LocalMediaSessionPlayer.java:588-613] `refresh()` runs on the `openhome-renderer` daemon thread (and `seek …

Warning:
- [OhRendererCore.java:352-376 (Insert, DeleteId, DeleteAll) with OpenHomeRenderer.java:149-310 and OhLocalService.java:119-141] The queue edit and the matching player edit are two separate steps with no common lock, and the actions are no …
- [yaacc/src/main/java/de/yaacc/player/AbstractPlayer.java:72 (`items` is a plain `ArrayList`), insertItems/removeItem(int,boolean); LocalMediaSessionPlayer.java:366] The new callers (`insertItems`, `removeItem`, `playItemAt`) run on Cling …
- [yaacc/src/test/java/de/yaacc/upnp/server/openhome/OhDeviceDescriptionTest.java:79-89] The test `buildingTheServicesAgainDoesNotKeepTheOldOnesListening` proves nothing: it builds twice, sets the transport state, and asserts `stateValue(. …
- [LocalMediaSessionPlayer.java:445-462] The decision "apply the insert to ExoPlayer or drop it" (`player.getMediaItemCount() == sizeBefore`, including the case 0 == 0 that `ExoPlaylistSync.canAppend` deliberately excludes) is inlined in a …

Suggestions: 10 (taken or recorded in `decisions.md` where they mattered).

Verdict: FAIL

## Cycle 2 — 2026-11-06

Reviewing: fix commit 9247cde3 (single worker thread `OpenHomeRenderer.Threads` / `AndroidOhThreads`, player only through `onPlayer`, `sync()`

Warning:
- [AndroidOhThreads.java:86-106] On `TimeoutException` (or interrupt) the posted `FutureTask` is not cancelled. The main thread runs it later, but the worker has already thrown, so `OpenHomeRenderer` did not update its bookkeeping: e.g. `s …
- [OpenHomeRenderer.java:329-337, 299-305, 412-418, 496] A stale `startId` survives `DeleteAll` when there is no player: `sync()` returns at once for `alivePlayer() == null`, before the `wanted.isEmpty()` branch that resets `startId`. Sequ …
- [OpenHomeRendererTest.java, OpenHomeRenderer.Threads] The thread-affinity requirement of the cycle 1 Critical is not tested: `InlineThreads.onPlayer` and the one in the parallel test just call the lambda, and `FakePlayer` accepts any thr …

Suggestions: 7 (taken or recorded in `decisions.md` where they mattered).

Verdict: FAIL

## Cycle 3 — 2026-11-06

Reviewing: Group fixes for cycle 2 (commit 22db10c3)

Suggestions: 4 (taken or recorded in `decisions.md` where they mattered).

Verdict: PASS

## Cycle 4 - Controller (groups 5-6) and reverse sync

Reviewing: Groups 5 and 6 (controller: `OhRemote`, `ClingOhControl`, `OpenHomePlayer`, `OpenHomeChoice`, `OpenHomeReceivers`, `OpenHomeVolume`,

Warning:
- [yaacc/src/main/java/de/yaacc/upnp/callback/openhome/ClingOhControl.java:123-132] A device that does not answer is never reported as `NO_ANSWER`. In the real path `SendingAction` sets `new UpnpTransportException("Connection error or no r …
- [yaacc/src/main/java/de/yaacc/player/OpenHomePlayer.java:131-145, 540-557, 172] `pendingEdits` is incremented in `moveInPlayOrder` but decremented only inside the job. `command()` runs `ensureReady()` before the job; when it throws (`pre …
- [yaacc/src/main/java/de/yaacc/player/OpenHomePlayer.java:548-553] The worker computes the move from `ids`, but `ids` is only rebuilt by `refresh()`, which is skipped while `pendingEdits > 0`. Two drags in quick succession (the second pos …
- [yaacc/src/main/java/de/yaacc/player/OpenHomePlayer.java:426-441] `feedFiles` sets `fileCurrent` (426) and `fileSent` (433) before `remote.append` (436) has succeeded. If the append fails (timeout, 5xx, full queue), the exception is caug …

Suggestions: 9 (taken or recorded in `decisions.md` where they mattered).

Verdict: FAIL

## Cycle 5 - fixes of cycle 4 — 2026-11-08

Reviewing: commit fd24389b (fixes of the 4 Warnings of cycle 4)

Warning:
- [yaacc/src/main/java/de/yaacc/upnp/server/openhome/OpenHomeRenderer.java:385] The `idByItem` clean-up added to `reconcile` (`idByItem.values().remove(playerIds.remove(i))`) does nothing for ids above 127. `idByItem` is an `IdentityHashMa …
- [yaacc/src/test/java/de/yaacc/upnp/server/openhome/OpenHomeRendererTest.java:483-495] `anEditAtThePlayerIsTakenOverBeforeAControlPointEditIsSynced` does not fail without the fix. Traced on the code before fd24389b: the player has [a,b] ( …

Suggestions: 6 (taken or recorded in `decisions.md` where they mattered).

Verdict: FAIL

## Cycle 6 - fixes of cycle 5 — 2026-11-08

Reviewing: commit f8b91bf7 (fixes of the 2 Warnings of cycle 5, plus the changed condition in `takeOverFromPlayer`)

Warning:
- [yaacc/src/main/java/de/yaacc/upnp/server/openhome/OpenHomeRenderer.java:539] The new condition (`id == null` only, line 521) keeps a known track in `newIds` although a control point deleted it from the queue, and step 3 still sets `prev …

Suggestions: 3 (taken or recorded in `decisions.md` where they mattered).

Verdict: FAIL

## Cycle 7 - fix of cycle 6 — 2026-11-08

Reviewing: commit defd4024 (the one-line fix for the Warning of cycle 6 in `takeOverFromPlayer`, its test and the decisions.md entry); extra cycle beyond the cap of three, approved by the owner.

Suggestions: 2 (taken or recorded in `decisions.md` where they mattered).

Verdict: PASS

## Cycle 8 - hardening (six suggestions) — 2026-11-08

Reviewing: HEAD ade16c7d "size, time and count limits for everything the network sends" (the six suggestions of security review cycle 4)

Warning:
- [AndroidOhThreads.java:60-77, OpenHomeRenderer.java:148-157 and 163-170, BoundedWork.java] The 500-task cap also drops the life-cycle tasks `start()` and `shutdown()`, which go through the same `work()`. Failure scenario: a host floods t …

Suggestions: 8 (taken or recorded in `decisions.md` where they mattered).

Verdict: FAIL

## Cycle 9 - fix of cycle 8 — 2026-11-08

Reviewing: HEAD 776af0ed "start and end of the renderer are never dropped, limits for images and album art relaxed" (the Warning of cycle 8 and its suggestions)

Suggestions: 6 (taken or recorded in `decisions.md` where they mattered).

Verdict: PASS

## Cycle 10 - answers to security cycle 5 — 2026-11-08

Reviewing: commit d48ed80b (`git show HEAD`): subscription timeout clamp and per-host cap, `HttpRequestSender` abort / per-request limit, `runGuarded` OOM, `AlbumArt` in the browse list and the AVTransport wrapper, new tests.

Warning:
- [ReceivingSubscribeTimeoutTest.java:37-46, ReceivingSubscribe.java:172 and :205] The fix for security Warning 1 is the *use* of `clampTimeout` in `processRenewal` and `processNewSubscription`. The test only exercises the static helper. I …

Suggestions: 6 (taken or recorded in `decisions.md` where they mattered).

Verdict: FAIL
