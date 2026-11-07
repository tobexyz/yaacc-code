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
