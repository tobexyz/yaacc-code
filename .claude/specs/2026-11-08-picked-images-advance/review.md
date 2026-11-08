# Review: Images of picked files and folders change to the next image

## Cycle 1 - 2026-11-08
Reviewing: the uncommitted change on `prepareRelease` (`QueueItems`, `PlayerService.createQueuePlayers`, `QueueItemsTest`,
`QueueShareBinderTest`, CHANGELOG, docs/index.md). Gate taken from the caller (not re-run): build ok, 3400 tests, 0 failures, lint 0.

Checked and correct:
- Remote path (AVTransport, the owner's bug). `AbstractPlayer.loadItem(previous, next)` passes `items.get(nextIndex)` (the list item, not the
  device-optimized copy) to `doPostLoadItem`, which starts `startTimer(duration + silence)` when `duration > -1`, more than one item
  (or repeat ONE) and the player is playing. The only writers of the duration of these items are `SharedItemFactory.create` (sets 0, then
  `QueueItems.create` overwrites it) and `QueueShareBinder.bind`, which overwrites only when `ContentDuration` returns > 0 and
  `ContentDuration` is -1 for images; `target.setItem/setUri` do not touch it. The copy at `AVTransportPlayer` line 625
  (`new PlayableItem(optimizedItem, (int) duration)`) is only used to send the device-optimized resource in `startItem`; it never feeds
  the timer, and the values (-1 .. 60000) fit the cast anyway. `AbstractPlayer.setItems` / `addItem` do not reset durations. So the value
  reaches `doPostLoadItem` for a picked queue on an AVTransport receiver.
- Setting values: "never" = -1 gives no timer, the image stays (as intended). 0 gives timer = 0 + silence duration (default 2000 ms), which is
  exactly what browsed images do (`PlayableItem(item, getDefaultDuration())`), so picked and browsed images behave the same; with both
  settings at 0 an image changes at once, as it does for browsed items. Not a regression, consistent.
- A queue of one image: `items.size() > 1 || repeat ONE` is unchanged, so a single image stays (repeat ONE restarts it, as for browsed).
- Audio and video: still -1 until bound; unchanged. `PickedQueue` only puts `image/...` in the image queue after lower-casing/validating the
  type, so `startsWith("image/")` matches the right entries. Items of other kinds in a non-image list cannot get the image duration
  in practice (the lists are per type); the check is a harmless safeguard.
- Tests: `QueueItemsTest` asserts per entry for images / audio / video, "never" and 0; it would fail on the old code (no such signature,
  all -1). `anImageKeepsItsDurationWhenItsShareIsBound` proves the bind does not reset the placeholder's duration. 

### Critical
- none

### Warning
- [docs/index.md (new bullet), CHANGELOG.md, spec requirements.md "alike", QueueItemsTest.imagesOfThePhonesOwnPlayerGetTheDefaultDurationToo,
  PlayerService.java:~433] The change does not reach this phone's own image show, but the text says it does. `createQueuePlayers` sets
  `direct = local && !video`, and the image player for the local device is `LocalImagePlayer`, which does not extend `AbstractPlayer`:
  its `setItems` hands only the URIs to `ImageViewerActivity` (`item.getUri()`), the duration of the `PlayableItem` is never read, and the
  slideshow runs on the image viewer's own setting (`image_viewer_settings_duration_key`, `ImageViewerViewModel.setDurationMs`). So on this
  phone the images advanced before this change (viewer slideshow) and still advance by the viewer's duration, not by the "default
  duration" setting. The new docs sentence ("changes to the next image after the default duration of the settings") and the CHANGELOG are
  therefore only true for other receivers, and the test named "...ThePhonesOwnPlayerGetTheDefaultDurationToo" asserts a value nothing
  reads. Fix: say "on other receivers" in docs/index.md and CHANGELOG (and that this phone's image viewer uses its own slideshow time), drop
  or rename the test and the "alike" goal in requirements.md; optionally do not pass the duration to `QueueItems.direct` (the second
  parameter is then dead for images there) or keep it and comment that it is unused by `LocalImagePlayer`. Please also confirm on the
  owner's setup which of the two cases the bug report was (the fix only helps the receiver case).

### Suggestion
- [PlayerService.java:~433] `upnpClient.getDefaultDuration()` is read from the preferences inside the per-device loop for every device and
  every type; read it once before the loop (it parses a string preference and throws `NumberFormatException` on a bad value, as
  elsewhere).
- [QueueItems.java Javadoc] Mention that a timer for audio/video is still decided by the receiver's transport state, and that the
  value is milliseconds as `UpnpClient.getDefaultDuration()` returns them (the Javadoc says so for the setting only).
- A single test on `PlayerService.createQueuePlayers` is not possible on the JVM (player service, devices); the wiring of the argument is
  reviewed only.

### Tests
- [x] All tests passing (caller's gate: 3400, 0 failures; not re-run)
- [x] Coverage adequate for the change (the `QueueItems` and bind logic are covered; the player wiring is reviewed only)

### Verdict: FAIL
One Warning: the docs, CHANGELOG, the goal in requirements.md and one test name claim the default duration also applies to this phone's own
image show, which ignores the item duration (`LocalImagePlayer` -> `ImageViewerActivity` slideshow setting). The code change itself is
correct for the receiver (AVTransport) case; correcting the wording makes this a PASS.

## Cycle 2 - 2026-11-08
Reviewing: the fixes for cycle 1 (uncommitted). Gate taken from the caller (not re-run): build ok, 3400 tests, 0 failures, lint 0.

Cycle 1 Warning: fixed.
- `QueueItems.direct(entries)` has its old signature again and passes -1 explicitly (comment: the phone's image viewer uses its own slideshow
  time); only `placeholders(entries, imageDurationMillis)` gives `image/*` entries the duration. The single production caller
  (`PlayerService.createQueuePlayers`) uses `direct(entries)` for the phone and `placeholders(entries, imageMillis)` otherwise; no other
  caller exists. The Javadoc of `QueueItems` now says the same.
- `upnpClient.getDefaultDuration()` is read once before the device loop.
- CHANGELOG ("on other receivers ... This phone's own image viewer was not affected"), docs/index.md ("On other receivers ... This phone's
  own image viewer uses the slideshow time of its own settings"), requirements.md and tasks.md match the code. "Other receivers" is exact:
  a local queue player is `direct` unless it is video, and video entries never carry an image duration.
- Tests: `itemsOfThePhonesOwnPlayersHaveNoDuration` replaces the dead assertion and asserts -1 for all kinds via `direct`; "never" and 0 now
  use `placeholders`, the two cases the code path really has; `anImageKeepsItsDurationWhenItsShareIsBound` is unchanged.
- No new problem found: the remote path analysis of cycle 1 (list item reaches `doPostLoadItem`, nothing resets the value) is untouched by the
  edit.

### Critical
- none

### Warning
- none

### Suggestion
- `getDefaultDuration()` is now parsed also for queues without images (before the loop, not per device); the preference is a list preference
  with numeric values, so a `NumberFormatException` is not realistic. Could be read only `if (!image-entries.isEmpty())`; optional.
- The `PlayerService` wiring cannot be unit tested on the JVM; it is reviewed only (as in cycle 1).

### Tests
- [x] All tests passing (caller's gate: 3400, 0 failures; not re-run)
- [x] Coverage adequate for the change

### Verdict: PASS
