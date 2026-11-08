# Review: images of picked files and folders change to the next image, summary

Condensed: the verdict of every cycle and the blocking findings (Critical and Warning) in one line each; the long texts and the suggestions are in
the git history of this file (the commit before the cleanup).

## Cycle 1 — 2026-11-08 — FAIL
No Critical. Warning:
- The fix reaches other receivers only: this phone's own image show (`LocalImagePlayer`, direct items) never reads the item duration (it uses the slideshow time of the
  image viewer's own settings and already advanced), but the docs and the goal said it did. Fixed: `QueueItems.direct` is unchanged, the docs, the CHANGELOG and the spec say
  "other receivers"; the dead assertion of `QueueItemsTest` is replaced, the default duration is read once in `PlayerService.createQueuePlayers`.

## Cycle 2 — 2026-11-08 — PASS
No Critical, no Warning.
