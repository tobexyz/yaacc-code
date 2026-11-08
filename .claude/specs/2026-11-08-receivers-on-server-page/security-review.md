# Security Review: receivers and players on the top-level page, summary

Condensed: the verdict of every cycle, the blocking findings in one line each and the suggestions that are still open; the long texts are in
the git history of this file. All cycles read the code; none ran Gradle or a device. Attack surface: the page shows and polls what hosts of the
network announce (names, icons, tracks, any number of devices); no new exported component, permission or intent.

## Cycle 1 — FAIL
- Warning: the number of polled receivers was not bounded on the landing screen (one poll chain per receiver in the registry, every device event
  rebuilt the rows): at most 64 receivers are listed, watched and polled (`ReceiverLiveUpdates.watched`), device events rebuild the rows once per
  300 ms (`Coalescer`).

## Cycle 2 — PASS
No Critical, no Warning. The cap is applied to the rows, the status monitor and the group feed alike; a cancelled or late runnable cannot touch a dead view.

## Cycle 3 — Players section and bottom stack — PASS
No Critical, no Warning. The rows are the unchanged mini-player rows (cleaned text, `setText`, `IconDownloadTask`); each `MiniPlayerBar` has its own
executor created in `bind()` and shut down in `unbind()`; the two bars are never bound for long together.

## Suggestions still open (none blocks)
- `RendererStatusMonitor` never drops the poll chain of a device that left the registry: the cap bounds the receivers listed now, not the ones ever polled (pre-existing).
- `ReceiverRowBinder` logs the icon URL, friendly name and `e.getMessage()` uncleaned (lines 242, 263, 266, 1162; `LogText.clean` exists) (pre-existing).
- The cap keeps this phone first, then by name: a host can name fake receivers to sort ahead of real ones (availability only).
- The Players rows are emptied on stop and refilled on start (flicker, art looked up again).
