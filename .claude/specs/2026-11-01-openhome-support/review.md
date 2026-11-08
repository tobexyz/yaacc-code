# Review: OpenHome support (#301)

## Cycle 1 — 2026-11-06
Reviewing: Groups 2, 3 and 4 (renderer: pure model, Cling services, coordinator and player changes), commits 8da319c4, dedcbe40,
10f34776, 79f82080, c164f2e3.

Method: spec (requirements, design, decisions, tasks) read against the code; the vendored Cling sources
(`yaacc/src/main/java/org/fourthline/cling/model/`) and the media3-exoplayer 1.11.1 classes read for the locking and threading
questions; full gate run: `ANDROID_HOME=/root/android-sdk ./gradlew :yaacc:testDebugUnitTest :yaacc:lintDebug`.

### Critical
- [yaacc/src/main/java/de/yaacc/upnp/server/openhome/OpenHomeRenderer.java:313-342, 233-241 with
  yaacc/src/main/java/de/yaacc/player/LocalMediaSessionPlayer.java:588-613] `refresh()` runs on the `openhome-renderer` daemon
  thread (and `seekSecondRelative` on a Cling request thread) and calls `player.getDuration()` / `getCurrentPosition()`. For the
  music player these go straight to `exoPlayer.getDuration()` / `getCurrentPosition()`. media3 1.11.1 `ExoPlayerImpl` calls
  `verifyApplicationThread()` in both and `throwsWhenUsingWrongThread` is set to `true` in its constructor (checked in the
  bytecode of the 1.11.1 aar; the setter is package private), so on a device every poll while a track is loaded throws
  `IllegalStateException: Player is accessed on the wrong thread`. `refreshSafely()` swallows it and logs a warning, which hides
  the failure: `durationSeconds(current)` throws before `core.setTrack(...)`, `core.setSeconds(...)` and the final
  `core.setTransportState(...)`, so `TransportState` never becomes `Playing`, `Info`/`Time` are never filled, `Id` is never
  evented, and a relative seek fails. The commands themselves (play, pause, insert) work, so the renderer looks alive but does
  not report. The JVM tests cannot see this because `FakePlayer` returns plain fields. The repo already knows the rule:
  `TrackInfoUpdater` takes a `playerThread` executor with the comment "the main thread: Media3 players insist on it".
  Fix: run the reads of `refresh()` (and the position read of `seekSecondRelative`) on the main looper, or give the `Host` a
  player-thread executor (`new Handler(Looper.getMainLooper())::post`) as `TrackInfoUpdater` does, and add a test with a
  player double that throws when called from any thread but the designated one. Note for the fix: `AndroidOpenHomeHost.createPlayer`
  -> `UpnpClient.initializeLocalPlayer` -> `waitForPlayerServiceComeUp()` busy-waits up to 10 s and is called from `startFrom`
  while holding the renderer `lock`; a main-thread `refresh()` that waits for that `lock` would block the main thread (ANR, and
  the service connection that ends the wait is delivered on the main thread). Create the player outside `lock`, or make the main
  thread side use `tryLock`.

### Warning
- [OhRendererCore.java:352-376 (Insert, DeleteId, DeleteAll) with OpenHomeRenderer.java:149-310 and
  OhLocalService.java:119-141] The queue edit and the matching player edit are two separate steps with no common lock, and the
  actions are not serialized: `OhLocalService.Executor` implements `ActionExecutor` directly, so unlike `AbstractActionExecutor`
  it never runs inside `ServiceManager.execute(...)` and the Cling service lock is not taken (this also means there is no lock
  order problem with the Cling lock, but `lockTimeoutMillis` only guards the event path). Consequences:
  1. Parallel `Insert`s: A inserts (index 3), B inserts at the start, B reaches `trackInserted(0)` before A reaches
     `trackInserted(3)`; A's index is stale (the track is at 4 in the queue) and the player's list differs from the queue for
     good (wrong track plays for an `Id`, `seekToTrack` hits the wrong item).
  2. `Play` racing with `Insert`: `startFrom()` takes `queue.snapshot()` (which already contains the new track), then
     `trackInserted` runs against the new player and inserts it a second time.
  3. `refresh()` (every second, under `lock`) reads `queue.idAt(player.getCurrentItemIndex())` while an action thread has
     changed the queue but is still waiting for `lock` to change the player: a wrong `Id`/`Info` (and `TrackCount` + 1) is evented
     once and corrected a second later. Likely for a delete or an insert before the playing track.
  4. An exception in `host.toPlayableItem` / `insertItems` after `queue.insert` makes the action fail (500) although the queue
     changed; a retrying control point then inserts twice.
  Fix: make "edit the queue and tell the player" one step under the renderer lock (e.g. `OhPlayback` gets the edit as a callback,
  or the core takes the renderer's lock; keep `refresh()` and `startFrom` under the same one), compute the index inside it, and
  catch player failures so the queue stays authoritative. Add a test with two threads (parallel Inserts, Insert during Play) and
  assert `queue.idArray()` equals the player's item order.
- [yaacc/src/main/java/de/yaacc/player/AbstractPlayer.java:72 (`items` is a plain `ArrayList`), insertItems/removeItem(int,boolean);
  LocalMediaSessionPlayer.java:366] The new callers (`insertItems`, `removeItem`, `playItemAt`) run on Cling request threads.
  Until now the list was edited from the UI thread, the same thread as the `Handler` runnables. `startItem`'s runnable iterates
  the live list (`for (PlayableItem item : getItems())`) on the main looper when it rebuilds ExoPlayer's list (first start of a
  new player: exactly when a control point sends `Play` and then `Insert`s the rest). A concurrent `items.addAll(...)` from a
  Cling thread throws `ConcurrentModificationException` on the main thread, which is an app crash. `OhPlayback`'s Javadoc says
  "an implementation hands the work to the player's own thread", but `OpenHomeRenderer` does not. Fix: hand the player edits to
  the main thread (the same executor as for the reads above), or iterate a copy taken under a lock; keep one rule for who
  touches the list.
- [yaacc/src/test/java/de/yaacc/upnp/server/openhome/OhDeviceDescriptionTest.java:79-89] The test
  `buildingTheServicesAgainDoesNotKeepTheOldOnesListening` proves nothing: it builds twice, sets the transport state, and asserts
  `stateValue(...)` equals "Playing", which is true with or without `clearListeners()`. The managers are also never initialized,
  so no listener is ever registered. The "device rebuilds: listeners cleared, renderer persists" requirement is therefore untested.
  Fix: build, force the manager init (`service.getManager().getPropertyChangeSupport()`), build again, assert the state has no
  listener of the old manager (`getPropertyChangeListeners().length == 0` before the new init, 1 after), and that an event
  reaches a subscription on the new service only; also assert the queue and ids survive a rebuild.
- [LocalMediaSessionPlayer.java:445-462] The decision "apply the insert to ExoPlayer or drop it" (`player.getMediaItemCount() ==
  sizeBefore`, including the case 0 == 0 that `ExoPlaylistSync.canAppend` deliberately excludes) is inlined in a main-looper
  runnable and has no test. The repo's pattern for `addItems` is to put this decision in `ExoPlaylistSync` and unit test it. This
  is the path that keeps ExoPlayer's list in step with the queue, and a wrong drop shifts every later `currentIndex`. Fix: move
  the check into `ExoPlaylistSync` (e.g. `canInsert(exoCount, sizeBefore)`) with tests for 0/0, equal, smaller, larger, and
  document why 0 is allowed here.

### Suggestion
- [OpenHomeRenderer.java:276-284] Deleting the track that plays stops playback, destroys the player (`exit()`, notification gone)
  and leaves the next track as the start of the next `Play`. This is a deliberate, tested behaviour
  (`deletingTheTrackThatPlaysStops...`) but it is not in `decisions.md`, and a control point that deletes the current track
  expects the playlist to go on with the next one (to be checked against ohPipeline `TrackDatabase`/`ProviderPlaylist`, I could
  not verify it here). Either record the decision or play the following track. Also, `removeItem(index, true)` returning false
  for an out-of-range index (drift) is treated like "the playing track", which discards a healthy player; distinguish the two.
- [OpenHomeRenderer.java:103-114, 129-137] The 1 Hz poll thread runs from service start for as long as the service lives, even
  with the renderer disabled afterwards, nothing playing and no subscriber; every tick also reads the system volume.
  `TrackInfoUpdater` was written to avoid exactly this (idle thread times out, nothing logged or done without work). Poll only
  while a player is alive or somebody is subscribed. `newDelayer()` creates a scheduled executor that is never shut down: one
  idle thread per service lifetime. Let `shutdown()` stop it.
- [OpenHomeRenderer.java:117-127] `shutdown()` is `synchronized` on `this`, not on `lock`, and calls `discardPlayer` (writes
  `player`/`reportedId`) while action threads may still be inside `lock`. The service also calls it before
  `registry.removeDevice(localDevice)` (`YaaccUpnpServerService.java:371-379`), so an action can still arrive and `startFrom`
  can create a player after the shutdown. Take `lock` in `shutdown()` and refuse new work afterwards, or remove the device first.
- [OpenHomeRenderer.java:313-342] `refresh()` fires the GENA events (`core.set...` -> `DefaultPropertyChangeListener` ->
  Cling manager `lock()`, up to `LOCK_TIMEOUT` 5 s) while holding the renderer `lock`. Collect the values under `lock` and
  publish them after releasing it. Also every command calls `refreshSafely()`, so a flood of `Next`/`Pause` requests bypasses the
  once-per-second `Seconds` event rate that design.md promises.
- [OhUriPolicy.java:36-47] `java.net.URI` rejects raw `|`, `[`, `]`, `^`, non-ASCII characters and host names with an underscore
  (`getHost()` is null); some media servers emit such addresses, and the whole `Insert` fails with 402. Consider normalising
  (percent-encode) before the check, and a test for each.
- [OhIds.java:56-60] The Javadoc says the id array is read "strictly", but `Base64.getMimeDecoder()` silently skips characters
  that are not base64. Use `getDecoder()` after removing whitespace if strict is meant.
- [YaaccUpnpServerService.java:1000-1015] The product name/room (`getLocalServerName()`) is captured when the renderer is first
  created and not updated when the server name setting changes; the other details are fixed. Update the `Product` state on the
  next `buildServices`.
- [PlayerService.createMusicPlayer / OpenHomeRenderer] The renderer's player is the app's one local music player slot
  (`createMusicPlayer` shuts down any existing `LocalMediaSessionPlayer`). An AVTransport `SetAVTransportURI` with audio, or the
  user playing music in the app, kills the OpenHome player (the renderer then reports Stopped and keeps the queue, which is
  right), and the reverse. Worth a line in `decisions.md`.
- [AndroidOpenHomeHost.java:62-86] `toPlayableItem` (hostile metadata fallback, mime type from the extension, address taken from
  the queue) has no test because of `MimeTypeMap`/`Uri`; the fallback logic could be extracted to a testable function.
- [OpenHomeRenderer.java:424] `discardPlayer(boolean stopIt)` is always called with `true`; drop the parameter. In `playItemAt`
  (`AbstractPlayer.java`) an ignored call while `isProcessingCommand()` still lets `SeekId`/`SeekIndex` answer success.

### What was checked and is fine
- Model: `OhQueue` ids (counter from 1, wrap skips used ids, never 0), 800/801/802/803 codes, 1000-track limit, length limits,
  insert after id / at start; `OhIds` codec round trip and bounds; `OhTrackList`/`OhEscape` escaping; `OhVolume` bounds.
- Services: actions, variables, directions and types are compared with the fixtures in
  `yaacc/src/test/resources/openhome/spec` by `OhServiceDefsTest`; SOAP round trips and events go through `ServiceHarness`; hostile
  input (huge metadata, 1001st track, unknown ids, non-numbers) is tested; the burst of `IdArray` edits collapses to one delayed
  event (flag is cleared before the ids are read, so no edit is lost).
- Locking: no lock-order inversion with the Cling service lock (actions do not take it; `OhServiceState` fires outside its own
  monitor; the manager lock never calls into the renderer).
- Rebuild: `OpenHomeRenderer` and the queue live with the service, `buildServices` clears the old listeners before new managers
  register lazily; a failure in `createOpenHomeServices` falls back to AVTransport and RenderingControl only.
- Backward compatibility: AVTransport/RenderingControl classes are untouched, the OpenHome services are appended; the whole
  existing renderer test suite is green.
- `PlayOrder.insert` / `AbstractPlayer.insertItems` (shuffle places new items behind the current one, indexes shift) and
  `removeItem(int, boolean)` are covered by `PlayOrderTest` and `AbstractPlayerPlaylistTest` additions.
- No secrets, no new dependency, errors reaching the control point carry codes and fixed texts only.

### Tests
- [x] All tests passing: `:yaacc:testDebugUnitTest` 3060 tests, 0 failures, 0 errors, 0 skipped; `:yaacc:lintDebug` 0 issues
  (`grep -c "<issue " yaacc/build/reports/lint-results-debug.xml` = 0).
- [ ] Coverage adequate for the change: not yet. Missing: a thread-affinity test for the player reads (the Critical finding), a
  concurrency test for queue/player sync, a real assertion for the rebuild/listener clearing, and the ExoPlayer insert decision.

### Verdict: FAIL

## Cycle 2 — 2026-11-06
Reviewing: fix commit 9247cde3 (single worker thread `OpenHomeRenderer.Threads` / `AndroidOhThreads`, player only through `onPlayer`, `sync()`
reconciliation, `ExoPlaylistSync.canInsert`, listener-count test, parallel-insert test, decisions.md entry).

Method: cycle 1 findings re-checked against the new code; `OpenHomeRenderer`, `AndroidOhThreads`, `AbstractPlayer.insertItems/removeItem/play/stop/exit`,
`PlayerService.createPlayerForLocalDevice/shutdown`, `UpnpClient.initializeLocalPlayer/waitForPlayerServiceComeUp`, `OhRendererCore` action handlers and
`YaaccUpnpServerService.onDestroy` read; full gate run: `ANDROID_HOME=/root/android-sdk ./gradlew :yaacc:testDebugUnitTest :yaacc:lintDebug`.

### Cycle 1 findings
- Critical (player read on the wrong thread): RESOLVED. Every player call (reads in `read()`, commands, `removeItem`, `insertItems`, `setPlayMode`,
  `exit`) goes through `Threads.onPlayer`, which runs on the main looper and waits; the exceptions are `host.createPlayer` (worker, as before; the
  ExoPlayer itself is still built on the main looper by `LocalMediaSessionPlayer`) and `host.isAlive` (a map lookup). The player list is now edited on the
  same thread as the `Handler` runnables that iterate it, so the ConcurrentModificationException risk is gone.
- Warning (non-atomic queue/player edit, parallel inserts, Play racing Insert, failure after `queue.insert`): RESOLVED. One serial worker, `sync()`
  compares ids and is idempotent; a late, doubled or reordered notification is harmless; `trackInserted` no longer takes an index.
- Warning (plain ArrayList edited from Cling threads): RESOLVED (see above).
- Warning (test that tested nothing): RESOLVED. `OhDeviceDescriptionTest` counts the listeners of each state before and after the rebuild.
- Warning (untested insert decision): RESOLVED. `ExoPlaylistSync.canInsert` + test (0/0, equal, smaller, larger).
- Deadlock analysis: no cycle found. The worker waits for the main thread (5 s, `FutureTask`); nothing on the main thread waits for the worker
  (`OpenHomeRenderer` public methods only `work(...)`; `shutdown()` is `work` + `executor.shutdown()`, both non-blocking, so `onDestroy` is safe); Cling
  action threads only enqueue. `createPlayer` busy-waits for the player service on the worker, whose bind callback arrives on the main thread, which the
  worker is not blocking. `OhRendererCore` fires GENA events from the worker; the Cling manager lock is never held by anyone who waits for the worker.
  After `removeDevice` a late action is dropped (`RejectedExecutionException` caught); `STPE.shutdown()` keeps queued one-shot tasks and cancels the
  periodic poll, so the queued `discardPlayer` still runs.

### Critical
- none

### Warning
- [AndroidOhThreads.java:86-106] On `TimeoutException` (or interrupt) the posted `FutureTask` is not cancelled. The main thread runs it later, but the
  worker has already thrown, so `OpenHomeRenderer` did not update its bookkeeping: e.g. `sync()` -> `insertItems` times out, `playerIds.add(i, id)` is
  skipped, the insert still happens on the main thread afterwards, and the next `sync()` inserts the same track a second time (player list longer than the
  queue, wrong track for an Id). Same for `removeItem` (player list loses a track the renderer still counts) and for `startFrom` (`player` set, mode/play
  not). A main thread stalled for more than 5 s is rare, but this is exactly the case the timeout is there for. Fix: `task.cancel(false)` before
  throwing (a task that has not started never runs; one that is running cannot be undone, so also let `sync()` rebuild `playerIds` from the player
  (`read(current).items`) after an `onPlayer` failure, or discard the player on any failure in `sync()`).
- [OpenHomeRenderer.java:329-337, 299-305, 412-418, 496] A stale `startId` survives `DeleteAll` when there is no player: `sync()` returns at once for
  `alivePlayer() == null`, before the `wanted.isEmpty()` branch that resets `startId`. Sequence: playing track P is deleted (player discarded,
  `startId` = next track N), then `DeleteAll`, then `Insert` of a new track: `refreshNow` reports `setCurrentId(startId)` = N, an id that is not in the
  queue, to every subscriber (and the `Id` of `Info`/`Playlist` is wrong until the next `Play`). `trackDeleted` has no equivalent problem because it fixes
  `startId` itself. Fix: `queueCleared()` sets `startId = 0` itself (before `sync()`), and add a test: delete the playing track, `DeleteAll`, insert, assert
  `core.stateValue("playlist","Id")` is 0 or the new track.
- [OpenHomeRendererTest.java, OpenHomeRenderer.Threads] The thread-affinity requirement of the cycle 1 Critical is not tested: `InlineThreads.onPlayer` and
  the one in the parallel test just call the lambda, and `FakePlayer` accepts any thread, so a new player call that bypasses `onPlayer` (the exact
  regression) would stay green. `AndroidOhThreads` (main thread inline, timeout, exception wrapping, shutdown semantics) has no test either. Fix: a
  `Threads` double with a designated "player thread" (single-thread executor, `onPlayer` submits and waits) and a `FakePlayer` that records/throws when
  called from another thread, then run play, pause, next, seek, insert, delete, DeleteAll, mode changes, shutdown and the poll through it. If a unit
  test for `AndroidOhThreads` needs Robolectric and the project has none, extract the "run on thread X and wait with timeout/cancel" part so it takes an
  `Executor`, and test that.

### Suggestion
- [OpenHomeRenderer.java:288-297] `trackDeleted(index, id)`: `index` is read in the Cling thread by `queue.indexOf(id)` and the delete is a second
  step, and `idAt(index)` is read later on the worker with another `size()` check in between. With parallel edits the "track behind the deleted one" can be
  the wrong one, and `idAt` can throw (then the whole task, including `sync()`, is skipped until the next notification). Compute the successor id
  inside `OhQueue.delete` (return it) and pass that.
- [OpenHomeRenderer.java:380-409, 170] `startFrom` calls the asynchronous `play()` and `refreshNow()` right after, which reports `Stopped` for up to one poll
  before `Playing` (a visible flap on every Play). Skip the stopped report while a start is in flight, or poll again shortly after a command.
- [OpenHomeRenderer.java:134-141] The 1 Hz poll and its thread run for the whole service life even when idle (cycle 1 suggestion, still open); the system
  volume is read every tick.
- [OpenHomeRenderer.java:272] `seekToTrack` / `playItemAt` ignored while `isProcessingCommand()` (cycle 1 suggestion, still open): the control point got
  success.
- [OhPlayback.java:22-24] The Javadoc says the core calls "on the thread of the control point's request" and the implementation hands over to the
  player's thread; now it hands over to the renderer's worker (which hands over to the player's thread). Update the wording.
- [OpenHomeRenderer.java:133-153] `AndroidOhThreads` is created before `start()`; if `createOpenHomeServices` fails after the renderer was created, its
  worker thread is never closed. Low impact (one idle daemon thread stays).
- Still open from cycle 1 and not blocking: URI normalisation (`OhUriPolicy`), strict base64 (`OhIds`), the product name captured once, the shared music
  player slot (`createMusicPlayer` shuts down any existing player) not in decisions.md, `toPlayableItem` untested, `discardPlayer(boolean)` parameter
  (now removed, good).

### What was checked and is fine
- `sync()` walk-through: delete several tracks (descending removal keeps the indexes valid), delete the playing track of an active player
  (`removeItem(i, true)` false -> player discarded, `startId` = first still wanted id behind it, including when later tracks were removed first), delete the
  current track of a stopped player (removed, `currentIndex` recomputed by `startIndex()`), insert before/after/at the current track of an active or stopped
  player (`insertItems` shifts `currentIndex` and `previousIndex` only when `hadItems && at <= currentIndex`, `playerIds` insert at the same index),
  DeleteAll with a player (discarded), identical ids twice (no-op). After the removals `playerIds` is a subsequence of the queue, so the one-pass insert
  loop is correct. Comparisons are on primitive `long`/`Long.equals` (no reference compare).
- `playItemAt` after `startFrom`; `stop()` then `Play` continues with the player's own start index; `Seek*` use the reconciled `playerIds`.
- Poll task: guarded (`guarded(...)` in `AndroidOhThreads`, `refreshNow` catches RuntimeException), started once (`stopPolling` check on the worker),
  cancelled in `shutdown()`; `scheduleWithFixedDelay` cannot pile up while the worker is blocked in `createPlayer`.
- Shutdown order in `YaaccUpnpServerService.onDestroy`: device removed first, then the renderer; late actions are dropped; `discardPlayer` failures are caught.
- No new dependency, no secrets, no new log of user data (only fixed texts).

### Tests
- [x] All tests passing: `:yaacc:testDebugUnitTest` 3064 tests, 0 failures, 0 errors, 0 skipped; `:yaacc:lintDebug` 0 issues
  (`grep -c "<issue " yaacc/build/reports/lint-results-debug.xml` = 0). BUILD SUCCESSFUL.
- [ ] Coverage adequate for the change: not yet. Missing: thread-affinity test (player touched only on the player thread), `AndroidOhThreads` behaviour
  (timeout/cancel), delete-playing-track + DeleteAll + insert (stale `startId`).

### Verdict: FAIL

## Cycle 3 — 2026-11-06
Reviewing: Group fixes for cycle 2 (commit 22db10c3)

### Cycle 2 Warnings
1. Late player calls / failed player call in `sync()`: resolved.
   - `OhCall.runAndWait` (new, plain JVM) cancels the `FutureTask` on timeout, interrupt (interrupt flag restored) and on a failing `execute`; an
     `ExecutionException` is wrapped with the cause. `AndroidOhThreads.onPlayer` uses it for the non-main case; the main-thread case runs inline.
   - `OpenHomeRenderer.sync()` wraps `reconcile()` in `try/catch (RuntimeException)` and calls `discardPlayer()`, so a call that was running and could not be
     undone (cancel(false)) no longer leaves `playerIds` out of step: the next Play rebuilds the player from the queue. `discardPlayer()` itself catches a
     failing `exit`.
   - `OhCallTest` covers result, failure (cause preserved) and "timed out call is cancelled and never runs when the thread gets free".
2. Stale `startId` after DeleteAll without a player: resolved. `sync()` now checks the empty queue first (discard + `startId = 0`) before the
   `alivePlayer() == null` return. Test `aDeleteAllOfADeletedPlayingTrackLeavesNoStaleStartTrack` follows the reported sequence and asserts the new track
   id after Play.
3. Thread affinity not tested: resolved. `FakePlayer` throws when used off `requiredThread`; `thePlayerIsOnlyTouchedOnItsOwnThread` runs the renderer with a
   separate worker and player thread through `OhCall.runAndWait` and drives play, insert, repeat/shuffle, seeks, next, pause, refresh, delete, DeleteAll.
   Also: the renderer is shut down when `createOpenHomeServices` fails (cycle 2 suggestion), and the `OhPlayback` Javadoc was updated.

### Critical
- none

### Warning
- none

### Suggestion
- [OpenHomeRendererTest.java thePlayerIsOnlyTouchedOnItsOwnThread] A violation is thrown, but `refreshNow()` (catch RuntimeException) and the new `sync()` catch
  swallow it, so a player call that bypasses `onPlayer` inside `read`/`refreshNow` would stay green; the test also asserts no player state. Let
  `FakePlayer.check()` also add the thread name to a `violations` list and assert it is empty (and assert e.g. the final `Stopped` state / the recorded calls).
  Violations from the command lambdas (play, pause, next, seek, applyMode, startFrom) do reach `failures` and are caught today.
- [OpenHomeRenderer.java startFrom] A failure after `player = created` (e.g. `applyMode` or `play` timing out) is not recovered; it is only repaired by the next
  `sync()`. Could discard the player in a catch like `sync()` does. Not blocking.
- After the recovery in `sync()` the next Play starts at `startId` (0 = first track), not at the track that played. Acceptable for a rare failure path.
- Still open and not blocking (earlier cycles): `trackDeleted` index/successor race, Stopped flap right after Play, 1 Hz poll while idle, `seekToTrack`
  ignored while processing, URI normalisation, strict base64, shared music player slot not in decisions.md.

### Tests
- [x] All tests passing: `ANDROID_HOME=/root/android-sdk ./gradlew :yaacc:testDebugUnitTest :yaacc:lintDebug` BUILD SUCCESSFUL; OhCallTest 3/3,
  OpenHomeRendererTest 28/28, no failures/errors; lint 0 issues (`grep -c "<issue " yaacc/build/reports/lint-results-debug.xml` = 0).
- [x] Coverage adequate for the change (cancel on timeout, stale start id, thread affinity on the command paths; `AndroidOhThreads` is a thin Android wrapper over the tested `OhCall`).

### Verdict: PASS

## Cycle 4 - Controller (groups 5-6) and reverse sync
Reviewing: Groups 5 and 6 (controller: `OhRemote`, `ClingOhControl`, `OpenHomePlayer`, `OpenHomeChoice`, `OpenHomeReceivers`, `OpenHomeVolume`,
`OpenHomeSettingsSheet`, receiver row, docs) and the latest change set (3603e7e2 reverse sync `adoptPlayerEdits` / `OhQueue.move`, 126670dc drag reorder).

Method: spec read against the code; Cling failure path read in `SendingAction` / `UpnpTransportException` / `ActionCallback`; tests read, no Gradle run
(the coordinator reports a green build at 3603e7e2: 3112+ tests, lint 0). Code only read, nothing modified.

### Critical
- none

### Warning
- [yaacc/src/main/java/de/yaacc/upnp/callback/openhome/ClingOhControl.java:123-132] A device that does not answer is never reported as `NO_ANSWER`. In the
  real path `SendingAction` sets `new UpnpTransportException("Connection error or no response received")`, which is an `ActionException` with
  `ErrorCode.ACTION_FAILED` (501), so `invocation.getFailure() != null` and `code[0]` becomes 501; `NO_ANSWER` stays only for a missing sender. Consequences:
  `OpenHomePlayer.countFailure` (line 164) never counts, so `unreachable` is never set and `ready` is never reset (a device that went to standby or was
  rebooted is not woken and switched to its playlist again); `OhRemote.readState` (438, 447) and `OpenHomeSettingsSheet.load` (200) treat a dead device like
  a device that refuses an optional service. The tests (`OhRemoteTest.aDeviceThatDoesNotAnswer...`, `OpenHomePlayerTest`) use a fake `OhControl` that throws
  `NO_ANSWER` directly, so the mapping is untested. Fix: in `failure()` map `invocation.getFailure() instanceof UpnpTransportException` (and a missing
  response) to `NO_ANSWER`, keep the device's code otherwise; extract that mapping into a small plain-JVM method and test it.
- [yaacc/src/main/java/de/yaacc/player/OpenHomePlayer.java:131-145, 540-557, 172] `pendingEdits` is incremented in `moveInPlayOrder` but decremented only
  inside the job. `command()` runs `ensureReady()` before the job; when it throws (`prepare()` fails: device did not answer, no playlist source, `ready` was
  reset after failures) the job never runs, the counter stays above 0 and `refresh()` returns at line 172 forever: the screen is frozen (no state, no
  queue, no unreachable flag) until the player is recreated. The same happens if `post` drops the work (executor shut down). Fix: decrement outside the job
  (wrap the whole posted runnable in try/finally, or let `command` take an `onDone` hook), and add a test with a control whose `prepare` fails.
- [yaacc/src/main/java/de/yaacc/player/OpenHomePlayer.java:548-553] The worker computes the move from `ids`, but `ids` is only rebuilt by `refresh()`, which is
  skipped while `pendingEdits > 0`. Two drags in quick succession (the second posted before the first job finished, so the first job's refresh is skipped)
  make the second job use the order from before the first move: the target id (`current.get(toPosition - 1)`) and the moved id refer to the wrong tracks
  and the device queue ends in a different order than the screen shows (the next poll then silently reverts the screen to the device order). Fix: update
  `ids` (and `deviceIds`) in the job after a successful `moveTrack` (apply the same remove/add, and the new id for the Playlist 1 insert+delete fallback), or
  serialize edits so the screen order is applied from `ids`. Add a two-drags test.
- [yaacc/src/main/java/de/yaacc/player/OpenHomePlayer.java:426-441] `feedFiles` sets `fileCurrent` (426) and `fileSent` (433) before `remote.append` (436) has
  succeeded. If the append fails (timeout, 5xx, full queue), the exception is caught in `refresh`, but the next poll sees `current == fileCurrent` and
  returns: the next picked file is never given to the device, playback stops after the current file although the screen lists more. Fix: assign
  `fileSent` and `fileCurrent` after the append (or reset them in a catch) so the next poll retries; test with a control that fails the first append.

### Suggestion
- [OpenHomeRenderer.java:363-402 and 448-466, reverse sync] Race between the two sync directions: `reconcile` removes and inserts by the index in
  `playerIds`; if the user edited the list at the player less than one poll (1 s) before a control point edit triggers `sync()`, the indexes no longer match
  the player and a wrong track is removed or inserted at the wrong place; the following `adoptPlayerEdits` then reads that as a user edit and deletes the
  control point's track from the queue. Call `adoptPlayerEdits` at the start of `sync()` (same worker thread) before reconciling.
- [OpenHomeRenderer.java:468-541] The Javadoc says edits of control points not synced yet are left alone, but a track a control point deleted that is still
  in the player is treated as "added by the user" and re-inserted with a new id when a user edit happens in the same window (queue.indexOf(id) < 0 branch in
  step 3). Skip items whose id was known (`idByItem`) but is gone from the queue, or fix the comment. Duplicate `PlayableItem` instances in the player make
  `inQueue.get(i)` throw (caught, the player is discarded); acceptable, but a comment would help.
- [OpenHomeRenderer.java:394-401] `reconcile` removes items from the player without removing them from `idByItem` (only `takeOverFromPlayer` and discard
  clean it); stale `PlayableItem` references stay until the player is discarded. Remove them on `playerIds.remove(i)`.
- [OpenHomePlayerTest.java:233] `moveIsNotOfferedAndNothingIsLocked` is stale since the drag reorder (126670dc): it only asserts on an empty list. Rename
  ("moveOnAnEmptyListIsRefused") or fold into `positionsOutsideTheListAreNotMoved`.
- [OpenHomePlayer.java:748-760, 131-145] `onDestroy` calls `stopWorkers()` before `super.onDestroy()`, so the `stop()` in `AbstractPlayer.onDestroy` is dropped
  ("work dropped" log) and the device keeps playing when the player screen/notification is closed, while `AVTransportPlayer` stops the device. If that is
  intended (the device owns the playlist) say so in decisions.md and docs; otherwise send the stop before shutting the worker down. Commands that were queued
  before the shutdown still run and end in `publish()` -> `showNotification()` after `cancelNotification()`; a flag checked in `publish()` would avoid a
  resurrected notification.
- [OpenHomePlayer.java:517-541] `adoptDeviceState` is documented "call from one thread only (the player's worker)" but `moveInPlayOrder` calls it on the UI
  thread. It is `synchronized`, so it is safe; correct the Javadoc of `AbstractPlayer.adoptDeviceState`.
- [OpenHomePlayer.java:544-546] The `fileQueue` branch of the move job does not re-check `fileSent` on the worker (the UI check used `fileSentForScreen`); if
  `feedFiles` advanced in between, a file the device already has can be reordered in the local list only. Re-check on the worker.
- [UpnpClient.java getReceivers / logDevicesOnce] The device summary is built and compared on every `getReceivers()` call (called from UI code); cheap, but
  logging friendly names at info level for every device change belongs behind debug unless the owner wants it in user logs.
- Still open from earlier cycles (not blocking): URI normalisation, strict base64, `trackDeleted` index race, Stopped flap after Play.

### What was checked and is fine
- `OhRemote`: bounded reads (batch 50, MAX_TRACKS, cache budget, unavailable set), `append` room calculation, `insertAfter` stops on 801, `replace`/`deleteAll`
  cache reset, `moveTrack` falls back to insert+delete only for code 401 (not for a refusal), wake loop is bounded and interruptible, volume max cached.
- `OhXml`/`OhTrackListParser`/`OhSources`: DTD refused, size and depth limits, only own text nodes read, ids validated against the asked set.
- `OpenHomePlayer`: one command worker, poll guarded by `pollPending` (no pile-up), `stopWorkers` cancels the poll and shuts both executors; state fields are
  volatile for the screen; worker-only fields are touched only on the worker (`fileQueue`, `ids`, `itemsById`); http(s)-only tracks; album art restricted
  to http(s) by `OhUriPolicy`.
- `OpenHomeSettingsSheet`: single-thread daemon pool that times out, `shutdownNow` on dismiss, results dropped when the dialog is gone, device strings
  cleaned and shown as plain text.
- `OpenHomeChoice`/`OpenHomeReceivers`/`OpenHomeVolume`: audio-only rule, per-device AVTransport choice, OpenHome-only devices listed once by UDN, volume only
  when there is no RenderingControl, all catch OhControlException and RuntimeException.
- 3603e7e2: `OhQueue.move` keeps the id and bumps the token and rejects unknown ids (800); `adoptPlayerEdits` runs on the worker, compares by identity, discards
  the player when it cannot take over; refused tracks are removed from the player from the highest index down; the player's items are the same instances
  that the renderer put in (checked in `PlayerService.createPlayerForLocalDevice`).
- Docs (`docs/index.md` OpenHome chapter, CHANGELOG): consistent with the code (1000 tracks, Move on Playlist 2 and insert+delete fallback, picked files two at
  a time, settings sheet, reverse sync); `Playlist2.xml` fixture contains `Move`. English comments, no dead code found in the reviewed files.

### Tests
- [x] All tests passing: not re-run in this cycle (coordinator: green build at 3603e7e2, 3112+ tests, lint 0).
- [ ] Coverage adequate for the change: no. Missing: the Cling failure mapping (`ClingOhControl.invoke`, the real unreachable path); `OpenHomePlayer` when
  `prepare()` fails (pendingEdits), two consecutive drags, a failing append in `feedFiles`, unreachable/recover cycle, `stopWorkers`; reverse sync with
  edit + control point edit in the same window, user edit + concurrent delete, and a refused playing track.

### Verdict: FAIL

## Cycle 5 - fixes of cycle 4 — 2026-11-08
Reviewing: commit fd24389b (fixes of the 4 Warnings of cycle 4)

### Critical
- none

### Warning
- [yaacc/src/main/java/de/yaacc/upnp/server/openhome/OpenHomeRenderer.java:385] The `idByItem` clean-up added to `reconcile`
  (`idByItem.values().remove(playerIds.remove(i))`) does nothing for ids above 127. `idByItem` is an `IdentityHashMap`, whose `values().remove(Object)`
  compares by reference (==), and the `Long` taken out of `playerIds` is a different box than the one stored in the map (`playerIds.add(i, entry.getId())` and
  `idByItem.put(item, entry.getId())` box separately). Checked with a 10-line program: id 5 is removed, id 1000 stays. The queue ids grow
  with every insert, so in a real session the stale `PlayableItem` references stay until the next player take-over or discard (the suggestion of cycle 4 is
  not really fixed, and no test covers it). Fix: remove by value, e.g. `long gone = playerIds.remove(i); idByItem.values().removeIf(v -> v == gone);`
  (unboxed compare), or look the item up by id; add a test with an id above 127 (or assert `idByItem` indirectly).
- [yaacc/src/test/java/de/yaacc/upnp/server/openhome/OpenHomeRendererTest.java:483-495] `anEditAtThePlayerIsTakenOverBeforeAControlPointEditIsSynced` does not
  fail without the fix. Traced on the code before fd24389b: the player has [a,b] (c deleted at the phone), `add(0, d)` runs `reconcile` with playerIds
  [a,b,c] and wanted [d,a,b,c]: nothing to remove, d is inserted at index 0, the other indexes still match, then `refreshNow` -> `adoptPlayerEdits` sees [d,a,b]
  against [d,a,b,c] and deletes c. Result [d,a,b] in queue and player, the same as asserted. The case that the fix is for needs a control point
  edit that goes by index: e.g. the user deletes a at the phone (player [b,c]) and a control point deletes b (queue [a,c], wanted ids {a,c}); old code removes
  index 1 of the player (that is c, not b), the new code first takes over the delete of a and then removes b: expected queue and player [c]. Replace or add
  this test so it fails on the old code.

### Suggestion
- [yaacc/src/test/java/de/yaacc/player/OpenHomePlayerTest.java:`twoQuickDragsEachCountFromTheOrderTheFirstOneMade`] The two `moveInPlayOrder` calls are
  back to back on the test thread, so job 1 normally is not finished when the second drag is posted (this is what makes the test fail without `ids = current`:
  the second drag would then move d after a, giving a d b c). If job 1 and its refresh happen to finish in between, the poll rebuilds `ids` and the test passes
  without the fix. Make it deterministic: let the control block on the first `Move` (latch) until the second drag was posted.
- [OpenHomePlayer.java:440-457] A retry of `feedFiles` after a failed `append` calls `moveToItems` again and, if the device had executed the Insert but the answer was lost
  (timeout), appends the next file twice. Rare; acceptable, a comment would help.
- [OpenHomePlayer.java `onDestroy`] The decision "closing the player leaves the device playing" is documented in a Javadoc only; decisions.md and the docs
  (OpenHome chapter) do not say it, which was part of the suggestion of cycle 4. Not blocking.
- [AbstractPlayer.java:786] The new Javadoc line is about 170 characters; wrap it like the lines around it.
- [ClingOhControl.java:35] The new import `de.yaacc.upnp.UpnpTransportException` sits after `de.yaacc.upnp.server.http...`; keep the imports sorted.
- Still open from earlier cycles (not blocking): reverse-sync race with a control point delete in the same window, URI normalisation, strict base64,
  `trackDeleted` index race, Stopped flap after Play.

### Checked and fine
- `ClingOhControl.codeOf`: `SendingAction` sets `UpnpTransportException` for "Connection error or no response received", so the transport failure now
  gives `NO_ANSWER` and `countFailure` / `unreachable` / `ready = false` work for the real path; a device fault keeps its code (501, 800). The test covers the three cases and fails on the old code (501).
- `OpenHomePlayer.command(..., always)`: `always` runs in `finally` (also when `ensureReady` throws) and before `refresh()`, so the poll is not blocked by a
  failed preparation; it also runs on the caller thread when the post is rejected. `aDragWhoseCommandCouldNotRunDoesNotFreezeThePlayer` fails on the old code
  (3 failures -> `ready = false` -> `prepare()` throws -> `pendingEdits` stays at 1 -> `refresh()` returns for ever).
- `moveInPlayOrder`: `ids` is replaced on the worker with the id returned by `OhRemote.moveTrack` (new id for the Playlist 1 insert+delete); `deviceIds` is left as it was, so the next
  poll sees a different IdArray and rebuilds the screen from the device. The trace of the test (d a b c, then d c a b) is right.
- `feedFiles`: `fileSent` and `fileCurrent` are set only after the append, so a failed append is tried again; `aFailedAppendOfTheNextPickedFileIsTriedAgainOnTheNextPoll` fails on the old code
  (no retry, queue stays at 2). The path without a share (`moveToItems` false) still marks `fileCurrent`, as before.
- `OpenHomeRenderer.sync`: `adoptPlayerEdits` first, `player == null` check, `wanted` read again, empty queue handled; no recursion (`queueEditedByPlayer` only schedules the IdArray update); exceptions go to the existing catch.
- `AbstractPlayer.adoptDeviceState` Javadoc now matches the `synchronized` use from the screen thread. `onDestroy` comment matches the code (`stopWorkers()` before `super.onDestroy()`, the `stop()` is dropped).
- Test rename `nothingIsLockedAndAMoveNeedsAtLeastTwoTracks` fits what it asserts.

### Tests
- [x] All tests passing: not re-run here (coordinator: `assembleDebug`, `testDebugUnitTest` 3196 tests, `lintDebug` all green at fd24389b).
- [ ] Coverage adequate for the change: no. The renderer test for "player edit first" passes on the old code (see Warning), and the `idByItem` clean-up has no test.

### Verdict: FAIL

## Cycle 6 - fixes of cycle 5 — 2026-11-08
Reviewing: commit f8b91bf7 (fixes of the 2 Warnings of cycle 5, plus the changed condition in `takeOverFromPlayer`)

### Critical
- none

### Warning
- [yaacc/src/main/java/de/yaacc/upnp/server/openhome/OpenHomeRenderer.java:539] The new condition (`id == null` only, line 521) keeps a known track in
  `newIds` although a control point deleted it from the queue, and step 3 still sets `previous = id` for it. The next track the user added behind it
  then calls `queue.insert(previous, ...)` with an id that is not in the queue: `OhQueue.insert` throws 800 (`idNotFound`, OhQueue.java:112-114),
  `takeOverFromPlayer` catches it as "refused" and the new track is removed from the player again, silently. Failure scenario: player [a,K], the user adds X
  behind K at the phone, a control point deletes K before the poll looked: `trackDeleted` -> `sync()` -> `adoptPlayerEdits` finds [a,K,X(null)]; K is no longer
  resurrected (good), but X is inserted after the deleted K, refused, and lost (every further new track behind K is lost as well). Before f8b91bf7 K was re-inserted and X survived,
  so the user's own edit is lost in this window now. Narrow race, but it is a data loss of a user edit and has no test. Fix: advance `previous` only while the
  track is in the queue (`if (queue.indexOf(id) >= 0) previous = id;`, so X follows the last live predecessor), and add a test next to
  `anEditAtThePlayerIsTakenOverBeforeAControlPointEditIsSynced` (player [a,K,X], `queue.delete(K)` then `trackDeleted`: expected queue and player [a,X]).

### Suggestion
- [OpenHomeRenderer.java:734-736] `trackedItemsForTest` is fine as a test hook; `anEditAtThePlayerIsTakenOver...` and the 130-track test both go through it/`player()`, no change needed.
- [OpenHomePlayer.java:440-457] (open since cycle 5) A retry of `feedFiles` after a failed `append` may append a file twice if the device executed the Insert but the answer was lost. A comment would help.
- Still open from earlier cycles (not blocking): URI normalisation, strict base64, `trackDeleted` index race, Stopped flap after Play, ohPipeline behavior for deleting the playing track not verified against a real device.

### Checked and fine
1. `idByItem` clean-up (OpenHomeRenderer.java:385-387): `long gone = playerIds.remove(i)` unboxes, `removeIf(id -> id == gone)` compares by value (`Long == long`), so ids above 127 are removed.
   `IdentityHashMap.values().remove/removeIf` semantics: the old call compared boxes by reference; `startFrom` boxes `entry.getId()` twice (`ids.add`, `idsByItem.put`), so for id 130 the boxes differ and the old
   code left the entry. `theRenderersKnowledgeOfTheItemsFollowsTheQueueAlsoForIdsAbove127` (130 tracks, delete id 130, `trackDeleted(129, last)`) asserts 129 tracked items; on the old code it stays 130, so it fails without the fix. The queue ids are sequential from 1 and `tracksMax` is 1000, so the setup is valid.
2. `anEditAtThePlayerIsTakenOverBeforeAControlPointEditIsSynced`: traced both versions. New code: player [a,b,c] playing c; user removes a (player [b,c]); control point deletes b (queue [a,c]); `sync` -> `adoptPlayerEdits` deletes a (not kept), b stays known, newIds [b,c], then `reconcile` removes b by its id: queue [c], player [c]. The pre-fd24389b code (reconcile only, by place, playerIds [a,b,c], wanted {a,c}) removes player index 1 (that is c) and leaves queue [a,c] / player [b]: the assertions fail. The assertion on queue AND player makes it index-sensitive. It also fails on the cycle-5 code (b would be re-inserted with a new id: queue [b',c]), so it covers the new condition too.
3. Condition `id == null` (regression check): a known track deleted by a control point is no longer re-inserted; the playing track deleted by a control point together with a user edit now ends in `reconcile` -> `removeItem` refused -> `discardPlayer` with `startId` = next wanted track (same path as without a user edit; before it was resurrected). Moves: step 2 filters `order` by `queue.indexOf(id) >= 0`, and `inQueue` is built from `orderSet`, so both lists have the same size and the deleted track cannot make `inQueue.get(i)` fail or call `queue.move` with an unknown id. Deletes: step 1 uses `deleteQuietly`, so an id already deleted is harmless. The order delete -> move -> insert is right (a deleted track cannot be moved, the insert anchors use the moved order). The cleanup of the stale entry is done by the `reconcile` that every control point delete triggers (`trackDeleted` / `queueCleared` -> `sync()`); `playerIds` keeps the stale id only until then. Only the `previous` anchor is wrong (see Warning).
4. `twoQuickDragsEachCountFromTheOrderTheFirstOneMade`: the test core has no `Move` handler, so `OhRemote.moveTrack` falls back to `Insert` + `DeleteId`; the gate holds the first job at `Insert` until both drags were posted (`pendingEdits` > 0 keeps the poll from rebuilding `ids` meanwhile). The second job therefore always starts from `ids = current` of job 1; without `ids = current` it would move d behind a (a d b c) and the assertions fail. Deterministic; gate is reset in `finally`, a 5 s timeout guards a hang, `stopWorkers` is called. It uses its own player (`other`), so the shared `player` field does not interfere.
5. decisions.md: the entry "Edits at the renderer's own player are taken over before the queue is synced" (identity compare, once a second and at the start of `sync()`, deletes/moves via `OhQueue.move`/adds http(s) only, a known deleted track never re-inserted) matches the code. The entry "Closing the OpenHome player leaves the device playing" matches `onDestroy`. No entry is needed for the `idByItem` fix.

### Tests
- [x] All tests passing: not re-run here (coordinator: `assembleDebug`, `testDebugUnitTest` 3197 tests, `lintDebug` 0 at HEAD f8b91bf7).
- [ ] Coverage adequate for the change: almost; the new `id == null` path has the delete case but no test for a user-added track behind a track a control point deleted (the Warning).

### Verdict: FAIL

## Cycle 7 - fix of cycle 6 — 2026-11-08
Reviewing: commit defd4024 (the one-line fix for the Warning of cycle 6 in `takeOverFromPlayer`, its test and the decisions.md entry); extra cycle beyond the cap of three, approved by the owner.

### Critical
- none

### Warning
- none

### Suggestion
- [OpenHomeRendererTest.java:499-511] The new test covers a deleted track in the middle of the list. The variant "the first tracks are gone too" (player [K1,K2,X], both deleted, X must end at the start of the queue) is
  correct by the code (see 2) but has no test of its own. Optional.
- Still open from earlier cycles (not blocking): `feedFiles` retry may append a file twice if only the answer was lost (comment wanted), URI normalisation, strict base64, `trackDeleted` index race,
  Stopped flap after Play, ohPipeline behavior for deleting the playing track not verified against a real device.

### Checked and fine
1. Fix (OpenHomeRenderer.java:539-541): `previous` advances only if `queue.indexOf(id) >= 0`. `newIds` still gets every known id (also a deleted one), so `playerIds` keeps matching the player's items by place and the
   `reconcile` that every control point delete triggers removes the stale entry by index. `queue.insert(previous, ...)` therefore never gets an id that is not in the queue: `previous` is either `OhIds.NONE` or
   the id of a track that was in the queue when it was set. An id inserted in this loop is in the queue, so new tracks chain correctly (X1, X2 behind each other). The only other way to leave the queue between two
   iterations would be a concurrent delete; `takeOverFromPlayer` runs on the worker thread like `sync`, and `OhQueue` is synchronized per call, so this cannot happen on the worker (a delete from the SOAP thread in
   between would be refused as 800 and the track leaves the player again, as before).
2. First tracks gone too: player [K1,K2,X] with K1, K2 deleted: step 1 deletes nothing (both kept in `found`), step 2 `order` is empty (the `indexOf >= 0` filter), step 3 leaves `previous = NONE` for K1 and K2,
   `OhQueue.insert(NONE, ...)` inserts at index 0 (OhQueue.java:110-116), so X is the first track; `reconcile` then removes K1, K2 from the player by index. With live tracks mixed in ([K1,a,X] or [K1,X,a]) X follows the last
   live predecessor, i.e. its place relative to the live tracks is the one the user gave it.
3. Test `aTrackAddedAtThePlayerBehindATrackAControlPointDeletedKeepsItsPlace`: traced on both versions. Setup: queue [a,k], player [a,k], x added at the player, `queue.delete(k)`, `trackDeleted(1,k)` (`id != startId`, `sync()`).
   New code: found [a,k,null], k stays in `newIds` but not as anchor, x is inserted behind a, `reconcile` removes k (wanted {a,x}): queue and player [a,x]. Old code: `previous = k`, `insert(k, ...)` throws 800, x is "refused" and
   removed from the player, `reconcile` removes k: queue and player [a]; both assertions (a,x) fail. So the test fails without the fix, and it checks queue and player. `InlineThreads` make the path deterministic.
4. Regressions: the change touches only the anchor; `newIds`, `refused`, `playerIds`, `idByItem` clean-up and `core.queueEditedByPlayer()` are as before. Cost of `indexOf` is O(n) per item with at most 1000 tracks, on the worker once per
   changed list: fine. Unchanged paths re-read: `sync` (adopt first, `player == null`, wanted again), `reconcile` (delete by id with by-value cleanup, then insert by place; the stale id is removed before the inserts so index
   alignment holds), `refreshNow` (adopt, rebuild on discard). The existing tests of this area (`anEditAtThePlayerIsTakenOverBeforeAControlPointEditIsSynced`, `aTrackAddedAtThePlayerEntersTheQueueBehindItsPredecessor`,
   `aTrackTheQueueCanNotHoldIsTakenOutOfThePlayerAgain`, `aTrackAControlPointAddedIsNotTakenForAnEditOfThePlayer`) do not depend on the anchor of a deleted track.
5. Overall controller group (5-6) and the reverse sync (cycles 4-7): the findings of cycles 4, 5 and 6 are all fixed (transport error code, `command(..., always)`, `moveInPlayOrder` ids, `feedFiles` retry, sync order
   adopt-then-reconcile, identity compare, `idByItem` by value for ids above 127, no resurrected deleted track, anchor). No Critical or Warning is left; the remaining items are Suggestions or unverified device behavior.
6. decisions.md: the new entry matches the code and says honestly that the fix was made after the cap.

### Tests
- [x] All tests passing: not re-run here (coordinator: `assembleDebug`, `testDebugUnitTest` 3198 tests, `lintDebug` 0 at HEAD defd4024).
- [x] Coverage adequate for the change: yes, the new test fails on the old code.

### Verdict: PASS

## Cycle 8 - hardening (six suggestions) — 2026-11-08
Reviewing: HEAD ade16c7d "size, time and count limits for everything the network sends" (the six suggestions of security review cycle 4)

### Critical
- none

### Warning
- [AndroidOhThreads.java:60-77, OpenHomeRenderer.java:148-157 and 163-170, BoundedWork.java] The 500-task cap also drops the life-cycle tasks `start()` and `shutdown()`, which go through the same
  `work()`. Failure scenario: a host floods the control port (or a control point loads a big playlist, one `Insert` = one task with `sync()` and player round trips) so that 500 tasks wait; the user stops
  the server (`YaaccUpnpServerService:385/1025` calls `openHomeRenderer.shutdown()`, which does `threads.work(discardPlayer)` and then `threads.close()`): the task is dropped with a warning, `discardPlayer()`
  never runs, the local player keeps playing without a renderer around it (and with `start()` dropped the poll never starts). Dropped control commands (`play`, `trackDeleted` with the `startId` update) are
  also lost silently after the SOAP call already answered success. Fix: let `start`/`shutdown` bypass the cap (e.g. `work(Runnable, boolean always)` or a separate `lifecycle()` entry that
  only checks `RejectedExecutionException`), or at least make `shutdown()` run `discardPlayer` independent of the cap; add a test for it (see Suggestion on `AndroidOhThreads`). Raising the cap
  (1000 tracks = 1000 `trackInserted` tasks is one legitimate burst that is as large as the cap allows) would also fit "no real use hits the limit" better.

### Suggestion
- [ImageDownloader.java:85-88, :157-170] Limits a real use can hit: 32 MiB is below the size of a 108/200 MP phone photo, and `IconDownloadTask`/`ImageDownloadTask` (browse thumbnails) fetch the item's own
  image resource when there is no album art, so such a thumbnail silently becomes "no image" (it did sample down before); the 10 s read timeout is shorter than a NAS or transcoding server (Plex, Serviio) needs for the
  first byte of a thumbnail. Consider 64 MiB and 20-30 s, or the strict limits only for album-art callers. There is also no total deadline (as `HttpRequestSender.readBounded` has): one byte per 9 s
  keeps the thread for hours, the byte limit alone does not stop that. No test covers `ImageDownloader` itself (timeouts, closing of the stream, OOM); only `BitmapSampling.readAllBytes(in, max)` is tested.
- [AlbumArt.java:43, OhUriPolicy] `OhUriPolicy.isAcceptable` is stricter than "http(s)": a host with an underscore (`http://nas_01/art.jpg`: `URI.getHost()` is null) and user info
  (`http://user:pw@host/art.jpg`) are now "no art" in the AVTransport renderer and controller, although `URL` fetched them before. Rare in a LAN, but a visible regression for those users; a plain
  scheme check (http/https, not blank) would be enough for album art. Checked and fine: local items' art is `http://<ip>:<port>/...` (ContentBrowser, `*FolderBrowser`/`*ItemBrowser`), the
  `UpnpClient` enrichment uses the resource address of an image item (http), embedded covers do not go through `AlbumArt.of` (`onMediaMetadataChanged`/`artworkData`), no `content://` or `file://`
  art source exists. Other art readers stay unfiltered (`AVTransportPlayerWrapper.getMediaItemAt` artwork URI, `BrowseContentItemAdapter.albumArtUri`); only an image is displayed, so low risk.
- [AndroidOhThreads.java:60-77] The wiring (`done()` in `finally`, in the `RejectedExecutionException` branch) is untested; `BoundedWorkTest` only tests the counter. Also one warning log line per
  dropped task is itself a flood under attack; log the first and then every Nth drop.
- [UpnpClient.java:333-406, ReceiverRowBinder.java:263] `LogText.clean` covers the device summary and fault texts only; `remoteDeviceAdded/Updated/Removed/DiscoveryFailed` and others still log
  `getDisplayString()` (friendly name of a remote device) raw. Same log-forging risk, so clean them too (or say in decisions.md that the summary was the only target).
- [OpenHomeRendererTest.java:529-542] The duplicate test covers an unknown item twice; the variant "a known item (already in the queue) twice in the player" is what the `orderSet.add(id)` in step 2
  is for (old code: `order` = [a,b,a] vs `inQueue` of size 2 gives `IndexOutOfBoundsException`, caught as "player is started again"). It is correct by the code but has no test of its own.
- [ReceivingSubscribe.java:170-175] Check and `addLocalSubscription` are not atomic (two simultaneous SUBSCRIBEs may overshoot by a few; harmless). A single host can take all 100 slots for the
  subscription timeout (up to 30 min) and so lock out real control points; a per-source-address cap would be better, but this is a hardening step, not a regression.
- [BoundedEntityConsumer.java:36-42] An over-long body ends in a plain `IOException` (HttpCore answers 500 and the exchange fails); 413 would be the exact answer. A declared `Content-Length` above the limit
  could be refused in `prepare()` already. Neither test goes through a real server (only the consumer is tested).
- [OpenHomePlayer.java:747-751, UpnpClient.java:550-551, ClingOhControl.java:118-127] Fully qualified names inside the code (`java.util.Map`, `de.yaacc.util.LogText`, `java.util.Collections`) instead of imports;
  inconsistent with the rest of the touched files (the repo does it in places, so not blocking).

### Checked and fine
1. (1) `ClingOhControl.runGuarded`: all `ActionCallback` runs of the class go through it (one call site); `StackOverflowError` is caught only around the action, the error leaves no half state (failure arrays are
   read after), the checked `OhControlException(NO_ANSWER)` is what the callers already handle. Test calls the real method with a throwing runnable; without the method it does not compile/fails.
2. (2) `getUriAsStream`: http(s) via `openConnection` with 5 s/10 s; other schemes unchanged (`url.getContent()`), so local `file:`/`content:` thumbnails of the app still work. The stream is now closed (try-with-resources;
   it leaked before). `OutOfMemoryError` is caught after `Exception` and returns null; the callers already handle a null bitmap. `artMayBeFetched`: synchronized, LinkedHashMap with `removeEldestEntry`, entries
   are written only on a start; a failed download is retried after 60 s, a success is served from the cache. Tests (two) fail without the method.
3. (3) `readBounded`: `entity == null` and `getContent() == null` give null as `EntityUtils.toByteArray` did; the content length check, the limit check before the write, the deadline after each read, the stream closed.
   Gzip is decoded before (limit on the decoded bytes). `createResponse` is the only former `EntityUtils` use. The only `readBounded` weakness is by design: a read that blocks is limited by the socket timeout,
   not by the deadline. 32 MiB/120 s are above any ContentDirectory answer the app can parse. `BoundedEntityConsumer`: counts before it delegates, both handlers use it. The content handler accepts GET and HEAD only
   (`YaaccUpnpServerContentHttpHandler:144`), there are no uploads; SOAP/GENA bodies of the OpenHome and AVTransport actions are a few KiB (metadata 16 KiB per track), event NOTIFYs from devices are small; 2 MiB is safe.
   Tests: the over-limit tests fail without the checks (endless stream runs into the test timeout/OOM, no exception).
4. (4) `LogText.clean`: null-safe, 200 characters, `\p{Cntrl}` to space; same behaviour as the old private `clean` in `ClingOhControl`. Tests fail without the class.
5. (5) `takeOverFromPlayer`: identity `seen` set; the repeat goes to `refused` (removed from the player in descending index order) and is not in `newIds`, so `playerIds` stays aligned to the player after the removal;
   `found` has the same id for both places of a known item, step 1 and `kept` are unaffected. If the repeat is the playing item, `removeItem` fails and the player is rebuilt (`discardPlayer`), acceptable. The new test
   fails on the old code (x is inserted twice into the queue).
6. (6) Subscriptions: the check is in `processNewSubscription` only (renewals are not blocked), after the validation of the headers; `getLocalSubscriptionCount` is `synchronized` like the other `RegistryImpl` methods
   and `maintain()` removes expired subscriptions, so the count shrinks. The service is compared by identity, which matches (`EventSendingSubscription` keeps the instance of the resource model). Tests:
   both fail without the check (status would be 200/500, not 503). `OhPlayableItems.fromTrack` and `null` Uri: every `getUri()` user reachable from `OpenHomePlayer` items is null safe or guarded:
   `OpenHomePlayer.tracks()` (`isSendable`), `UpnpClient.discardShareOf`/`shareKeysOf` (null check), `BrowseContentItemAdapter:664` (null check); `PlaylistItemAdapter`, `PlayableItemDiffCallback`,
   `AbstractPlayer`, `PlayerService`, `FileDownloader` (browse only), `LocalMediaSessionPlayer`, `AVTransportPlayer(Wrapper)` (other players, the renderer side uses `AndroidOpenHomeHost.toPlayableItem`, whose
   addresses the queue already limited to http(s)) do not see these items. `new PlayableItem()` already had a null Uri before. The mirrored track is still listed (title/id), moves use ids, not addresses.
7. decisions.md and CHANGELOG match the code (limits, the 500/100/30/2 MiB/32 MiB values).

### Tests
- [x] All tests passing: not re-run here (coordinator: `assembleDebug`, `testDebugUnitTest` 3221 tests, `lintDebug` 0 at HEAD ade16c7d).
- [ ] Coverage adequate for the change: for the counters, parsers and limits yes (each new test fails without its fix); not covered: `ImageDownloader` I/O, `AndroidOhThreads.work` wiring, a real server rejecting a long body.

### Verdict: FAIL
One Warning (life-cycle tasks `start`/`shutdown` fall under the drop cap, so an orphaned player and no poll are possible under a flood or a large playlist load). No Critical. Everything else is a Suggestion.

## Cycle 9 - fix of cycle 8 — 2026-11-08
Reviewing: HEAD 776af0ed "start and end of the renderer are never dropped, limits for images and album art relaxed" (the Warning of cycle 8 and its suggestions)

### Critical
- none

### Warning
- none

### Suggestion
- [AndroidOhThreads.java:59-66] `dropped` is never reset, so after a first flood of e.g. 150 drops a later small flood (fewer than 100 drops) may log nothing at all; and the fully qualified
  `java.util.concurrent.atomic.AtomicInteger` should be an import (the file already imports from `java.util.concurrent`). Overflow is harmless: after 2^31 drops the value wraps negative, `% 100` is then
  <= 0 and still hits 0 once per hundred.
- [AndroidOhThreads.java:85-92] `lifecycle()` itself is untested (the class needs `Looper`; no Robolectric test); the test only proves that `start`/`shutdown` call `lifecycle`. The branch is a three-line copy of
  `work()` without the counter, so the risk is low. Note the task still queues behind up to 2000 waiting tasks (FIFO), i.e. `shutdown` is late, not lost; acceptable.
- [BoundedWorkTest.java:47] `MAX_WAITING >= 200` no longer states the reason for the value (a 1000-track load); `>= 1000` would pin the decision made in cycle 8.
- [OpenHomePlayer.java:715 vs AlbumArt.java:46] The OpenHome device's own `getAlbumArt()` still uses `OhUriPolicy`, so a device with a host like `nas_01` gets no art in the notification of the OpenHome player, while the
  other players now show it. Intended (the policy is the one of the OpenHome mirror) but inconsistent; decide and say so in decisions.md or use the same scheme check.
- [decisions.md hardening entry, items 2 and 6] still say "10 s read timeout / 32 MiB" and "beyond 500 waiting ones"; superseded by the cycle 8 entry (64 MiB, 30 s, 2000), a reader of only the first entry is misled.
- [AlbumArt.java:46] With the scheme-only check the length, control character and user-info limits of `OhUriPolicy` no longer apply to album art of the AVTransport and local players (same as before the hardening
  commit; the Android `URL` fetch handles them). Fine; named only for completeness.

### Checked and fine
1. `Threads.lifecycle` default `work(work)`: inside the interface the parameter name does not shadow the method call (compiles, behaviour is `work`), so any other implementation (the test `Threads` at
   OpenHomeRendererTest:808 and :928 implement only the abstract methods and inherit the default) keeps working. `AndroidOhThreads.lifecycle`: not counted, wrapped by `guarded` (a RuntimeException does not end the
   worker), `RejectedExecutionException` logged at debug level. Counting is balanced: `work()` still releases in `finally` and on rejection; `lifecycle` never acquires, so never needs `done()`.
2. `OpenHomeRenderer.start/shutdown`: both use `lifecycle`; `shutdown` queues the task (stop poll, `discardPlayer`) and only then `threads.close()` -> `worker.shutdown()`, which runs queued tasks first
   (javadoc of `close`: "runs what is queued"), so the order "discardPlayer, then close" holds; periodic tasks are cancelled by `stopPolling.run()` and by the shutdown policy. No other caller of `threads.work` is a
   life-cycle task (`YaaccUpnpServerService:1015` only constructs; start/shutdown are the two entry points).
3. `BoundedWork` 2000: 1000 `trackInserted` tasks of a full playlist load plus normal traffic fit; memory bound stays small (a task is a closure). The drop log is `getAndIncrement() % 100 == 0`: first drop logged,
   then every hundredth, as the comment says.
4. `AlbumArt.of` users: `LocalMediaSessionPlayer:183,365,419` and `AVTransportPlayer:737` (all through `AlbumArt.of`, all now scheme-only); `OpenHomePlayer.getAlbumArt` keeps `OhUriPolicy` (see Suggestion).
   Scheme-only is safe for the fetch: `ImageDownloader.getUriAsStream` uses `openConnection` with timeouts for http(s), and `file:`, `content:`, `ftp:` can no longer reach the `url.getContent()` branch from album art.
   The test line (`http://nas_01:8200/art.jpg`) fails on the old code: `new URI(...)` parses it as registry authority, `getHost()` is null, `OhUriPolicy` returned false, `AlbumArt.of` null.
5. `ImageDownloader`: 64 MiB / 30 s read, connect timeout unchanged at 5 s; `readAllBytes(stream, max)` still bounds memory; OOM catch unchanged. No constant is referenced by a test with a literal (grep: only the class itself).
6. New tests fail without the fix: (a) `aKnownItemTwiceInThePlayerCountsOnceAndTheRepeatIsTakenOut`: player [a,b,a], without `orderSet.add(id)` `order` = [a,b,a] and `inQueue.get(2)` throws `IndexOutOfBoundsException`,
   `adoptPlayerEdits` catches it and `discardPlayer()`s, the repeat is never removed from the player (items stay [a,b,a], assertion on the titles fails). With the fix: `order` [a,b], the second a goes to `refused` via the
   identity `seen` set and is removed; queue and player [a,b]. (b) `theLifeCycleOfTheRendererRunsEvenWhenThreadsDropWork`: `work` is overridden to drop; without the `lifecycle` calls `ran` stays empty (and
   without the interface method the `@Override` does not compile); with them `ran` = [lifecycle, lifecycle]. (c) the underscore host: see 4.
7. `InlineThreads` changed from `final` to non-final only for the subclass in the test; no production effect.

### Tests
- [x] All tests passing: not re-run here (coordinator: `assembleDebug`, `testDebugUnitTest` 3223 tests, `lintDebug` green at HEAD 776af0ed).
- [x] Coverage adequate for the change: the three new tests each fail without their fix; `AndroidOhThreads.lifecycle` wiring is the one untested line group (Suggestion).

### Verdict: PASS
No Critical, no Warning. The Warning of cycle 8 is fixed: `start()` and `shutdown()` bypass the cap and keep their order; the remaining items are Suggestions.

## Cycle 10 - answers to security cycle 5 — 2026-11-08
Reviewing: commit d48ed80b (`git show HEAD`): subscription timeout clamp and per-host cap, `HttpRequestSender` abort / per-request limit, `runGuarded` OOM, `AlbumArt` in the browse list and the AVTransport wrapper, new tests.

### Critical
- none

### Warning
- [ReceivingSubscribeTimeoutTest.java:37-46, ReceivingSubscribe.java:172 and :205] The fix for security Warning 1 is the *use* of `clampTimeout` in `processRenewal` and `processNewSubscription`. The test only exercises the static helper. If either call site is reverted to `requestMessage.getRequestedTimeoutSeconds()` (the original bug: `Second-infinite` / `Second-0` fills the cap for good) all tests stay green; the security review of cycle 5 named exactly this gap ("no test covers a `Second-infinite` / `Second-0` SUBSCRIBE") and it is still open. Recommended fix: (a) renewal test: `registry.getLocalSubscription(id)` returns a mock `LocalGENASubscription`, request mock returns `Integer.MAX_VALUE` as timeout and a subscription id, `processRenewal(service, request)`, `verify(subscription).setSubscriptionDuration(1800)` (and `updateLocalSubscription` called); (b) new-subscription test through the success path (a minimal real `LocalService`, or a protected factory for `EventSendingSubscription`) with an `ArgumentCaptor` on `registry.addLocalSubscription` asserting `getActualDurationSeconds() == 1800` for infinite / 0.

### Suggestion
- [HttpRequestSender.java:208-209] The comment "The exception leaves the client, which drops the connection" is not true by itself: I probed httpclient5 5.3.1 / httpcore5 5.3.6 against a local chunked server (8 s of 1 KiB chunks): handler throws without `abort()` -> `execute` returned after 8067 ms (still drained); with `abort()` in the handler -> 244 ms and the pool shows `available: 0` (connection discarded). So the `abort()` in `send` is the load-bearing part, `readBounded` alone is not. Say so in the comment, because a later caller of `readBounded` without the abort would silently regain the drain.
- [HttpRequestSender.java:112-121] `send` now calls the private `createResponse(response, limit)`; the protected `createResponse(ClassicHttpResponse)` is no longer on the path of `send` (before: `this::createResponse`). No subclass or spy overrides it in main or test code (grep), and the direct callers in `HttpRequestSenderTest` still get the old behaviour (instance limit 32 MiB, instance deadline), so nothing breaks; but an override would now be bypassed silently. Optionally document or drop the protected overload.
- [LocalItems.java:65-79] `countSubscriptionsForHost` (host match case-insensitive, any of several callbacks counts once, null host / null callbacks) has no test; `ReceivingSubscribeTimeoutTest` mocks the count. A 10-line `LocalItems` test would cover it. Same for the 25-cap boundary (24 allowed): only "at the cap is refused" is tested.
- [ReceivingSubscribe.java:151-157] The per-host key is the host of the *callback header*, which the sender chooses (not the source address): a host can spread over many names, or park 25 subscriptions on a victim's host name. The total (100) and per-service (30) caps and the 30 min limit still bound it, so this is a soft limit only; fine, but state it in decisions.md.
- [LocalItems.java:66-70, BrowseContentItemAdapter.java:773] Fully qualified `java.util.List`, `java.net.URL`, `de.yaacc.player.AlbumArt` inline instead of imports (style only).
- [HttpRequestSenderRealServerTest.java:50-53] Per-connection serve threads are not daemon threads. They end within a write or two once the client has gone (about 100 ms), so no hang, but `setDaemon(true)` costs nothing.

### Checked and fine
1. **Successful requests still pool the connection.** Probed httpclient5 5.3.1 with the same handler shape (read to -1, `in.close()`, return): after a normal answer `leased: 0, available: 1`, three consecutive requests keep `available: 1` (reuse), i.e. stream closed after the complete read plus `EntityUtils.consume` in `execute` return the connection. `abort()` is only reached in the `catch (IOException | RuntimeException)`, so no successful path can be affected; the rethrown exception is the original one (`IOException: big` came out unchanged, not a `RequestAbortedException`), so callers of `send` see the same exception types. Null entity (204/HEAD) and empty body still return null / empty as before.
2. **Failure path.** Limit and deadline throw without closing the stream, the handler aborts, the connection is discarded (`available: 0`), the call returns in 244 ms in the probe instead of 8 s. An `IOException` from `in.read` takes the same path.
3. **Per-request limit.** ContentDirectory: `maxResponseBytes` (32 MiB), everything else `min(maxResponseBytes, 8 MiB)`; descriptors, SCPDs, SOAP answers of AVTransport / RenderingControl / OpenHome (playlist limited to the device's track max) are far below 8 MiB. The no-arg constructor keeps the old limits; `createResponse(ClassicHttpResponse)` keeps the old behaviour for the existing tests.
4. **Subscriptions.** `clampTimeout`: null, <= 0, infinite (Integer.MAX_VALUE) and > 1800 give 1800, below 60 gives 60, no overflow. The clamped value reaches the expiry: `setSubscriptionDuration` -> `setActualSubscriptionDurationSeconds`, `RegistryItems.addSubscription` / `updateSubscription` use `getActualDurationSeconds()`, and `LocalItems.maintain` expires on it; the response header carries the clamped value, so honest control points renew at the right time. Renewals are not counted against the cap (separate branch). The per-host check runs before the subscription object is built and before `addLocalSubscription`; `Registry` has one implementation (`RegistryImpl`, synchronized), no other implementer to break. 25 per host is above what one control point needs (one subscription per service, about 15 services at most).
5. **`runGuarded`** catches `OutOfMemoryError` next to `StackOverflowError`, both give `NO_ANSWER`; with the 8 MiB limit for non-ContentDirectory answers the DOM tree is bounded to a size the heap normally survives. New test throws a real `OutOfMemoryError` and asserts the code.
6. **`AlbumArt` public / browse list.** Behaviour change: null / empty address, relative address and any scheme except http/https (case-insensitive) now give no thumbnail. No legitimate use is lost: the app's own server builds every album art address as `http://ip:port/...` (`ImageAllItemBrowser`, `MusicAlbumFolderBrowser`, ... grep), remote servers must give absolute http(s) addresses for a client to fetch them, and a relative address or `file:` / `content:` could not be fetched by the downloader for a remote device anyway (and `file:` / `content:` would read from this phone). `albumArtUri` has two callers (`BrowseContentItemAdapter` :721, :739), both null-tolerant (`bindIcon` with null thumbnail). In `AVTransportPlayerWrapper` a null art URI was already possible (property missing) and is handled in the `MediaMetadata` builder. `OpenHomePlayer` keeps its own stricter `OhUriPolicy`.
7. **`HttpRequestSenderRealServerTest`.** Port 0 on loopback (no fixed port), acceptor thread is daemon, `@After` closes the server. The two cut-off tests would take about 8 s with the old code (reproduced in the probe: 8067 ms without the abort) and assert `< 4000` ms; with the fix they take about 0.25-0.6 s, so the margin is 3.4 s on both sides, not tight on CI; no sleep-based assertions on the client side, no reliance on ordering. The third test (an `/ok` answer after a cut-off one on the same sender) shows that the pool is usable after an abort. Runtime is under 2 s in total. `ReceivingSubscribeTimeoutTest.oneHostCanNotTakeAllTheSubscriptions` fails without the per-host check (would add the subscription); the `clampTimeout` test would not compile without the fix, but see the Warning for the wiring.
8. No secrets, no new dependency, no new permission; decisions.md entry matches the code (60..1800 s, 25 per host, 8 MiB, OOM, `AlbumArt`).

### Tests
- [x] All tests passing: not re-run here (instruction); orchestrator reports HEAD green, 3229 tests, lint green. I additionally reproduced the behaviour of the sender fix with a stand-alone httpclient5 5.3.1 probe (scratchpad, not part of the repo).
- [ ] Coverage adequate for the change: the sender fix is covered by a real-server test that fails on the old code; the subscription timeout is not covered where it is applied (Warning), the per-host count logic is not covered (Suggestion).

### Verdict: FAIL
One Warning: the clamp is unit tested only as a helper, not at its two call sites (renewal and new subscription), so the actual fix for security Warning 1 can be reverted without a red test. No Critical. The sender fix, the OOM guard, the per-host cap and the `AlbumArt` change are correct and verified; everything else is Suggestion.
