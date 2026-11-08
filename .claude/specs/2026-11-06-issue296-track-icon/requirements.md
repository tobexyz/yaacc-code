# Issue #296 (follow-up): a music track without a cover shows the app icon

## Problem Statement
The server's MediaStore music lists give every track the cover URL `http://<ip>:<port>/album/<albumId>`. When the album has no cover
(Android 10 and later: `loadThumbnail` throws; Android 9 and earlier: `ALBUM_ART` is empty) the handler serves the app icon
(`yaacc192_32`) as the cover, and writes it into the cache as `albumart<id>.jpg` for every album. Users see the YAACC logo in front of
some tracks and a generic music icon in front of others (SAF tracks and tracks of other servers have no cover URL). They ask why.

## Goals
- A music track without a cover gets a generic music-track icon (the glyph the app already uses for audio items), not the app logo.
- The icon is a fixed, small PNG shipped with the app (no rendering at run time, no file written per album) and is sent with a
  `Cache-Control` header so that a control point can cache it.
- A cover that exists is served as before.

## Non-Goals
- Photo thumbnails keep their fallback (the app icon): the report is about music.
- SAF tracks and tracks of other servers carry no cover URL; the client's own generic icon stays.
- No change of the cover lookup (MediaStore) or of the album art route.

## Success Metrics
- A resource test checks the shipped PNG (signature, size, transparency); the full gate stays green.
