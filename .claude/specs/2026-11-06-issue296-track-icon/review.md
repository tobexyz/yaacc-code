# Review: a music track without a cover gets the music note icon (issue #296), summary

Condensed: the verdict of every cycle and the blocking findings (Critical and Warning) in one line each; the long texts and the suggestions are in
the git history of this file (the commits before the cleanup).

## Cycle 1 — 2026-11-06 — FAIL
No Critical. Warnings:
- The generic icon was always cacheable for 24 h, but it is also the answer when a cover exists and only writing the cache file failed, when the server switch is off, or when the raw resource cannot be read: only the real "no cover" answers are cacheable now.
- The behaviour change was untested (the test only decoded the PNG): tests for `lookupAlbumArt` (generic icon for no cover, write failure and a non-numeric id; the cover path still gives `image/jpeg`) and the cache header.

## Cycle 2 — 2026-11-06 — PASS
No Critical, no Warning.
