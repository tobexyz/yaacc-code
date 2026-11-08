# Security Review: issue #296 - generic music icon for an album without a cover, summary

Condensed: the verdict of every cycle and the blocking findings in one line each; the long texts and the suggestions are in the git history of this
file (the commits before the cleanup).

## Cycle 1 — 2026-11-06 — FAIL
No Critical. Warning:
- (Pre-existing, found while reading the code) the album id was validated with `Long.parseLong` but the cache file name was built from the raw text, so a LAN
  client could request one album under many spellings (`7`, `07`, `+7`, Unicode digits ...) and make the handler write a new `albumart<spelling>.jpg`
  each time: unbounded cache growth. Path traversal was not possible. Fixed: only the canonical spelling of an id is accepted (`canonicalAlbumId`); any other spelling gets the generic icon and no file is written.

## Cycle 2 — 2026-11-06 — PASS
No Critical, no Warning; the cycle 1 Warning is fixed.
