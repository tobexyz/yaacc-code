# Tasks: images of picked queues advance

- [x] 1 Tests first: `QueueItemsTest` (images get the duration, audio/video -1, "never", 0), `QueueShareBinderTest` (an image keeps its duration when bound)
- [x] 2 `QueueItems.placeholders(entries, imageDurationMillis)` (`direct` unchanged); `PlayerService.createQueuePlayers` reads `upnpClient.getDefaultDuration()` once
- [x] 3 `CHANGELOG.md`, `docs/index.md`
- [x] 4 Review gate: reviewer cycle 1 FAIL (docs said the phone's own viewer was covered), cycle 2 PASS; security reviewer cycle 1 PASS
