# Security Review: images of picked files and folders change to the next image, summary

Condensed: the verdict, the blocking findings and the suggestions that are still open; the long text is in the git history of this file.

## Cycle 1 — 2026-11-08 — PASS
No Critical, no Warning. The duration comes from the user's own setting (a numeric list preference, -1 to 60000 ms), not from a file or the network; a share is held
only while its item plays (at most the current and the next one per player), so a short timer cannot pile up shares.

## Suggestions still open (none blocks)
- With the default duration and the silence duration both at 0, a queue of images on a receiver changes as fast as the receiver and the share bind allow (and loops with repeat all):
  browsed images behave the same way. A floor such as one second would harden it.
- `UpnpClient.getDefaultDuration()` could fall back to its default on a `NumberFormatException` (the value comes from a fixed list; only a tampered preference file would fail).
- Pre-existing: the alarm of `AbstractPlayer.startTimer` is an implicit broadcast that carries only the player id; `setPackage(...)` would keep other apps from seeing it.
