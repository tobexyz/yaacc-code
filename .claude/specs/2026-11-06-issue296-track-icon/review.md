# Review: Music track without a cover gets the music note icon (issue #296)

## Cycle 1 - 2026-11-06
Reviewing: commit 98c51c4b (single change, `YaaccUpnpServerContentHttpHandler.lookupAlbumArt` + shipped PNG + `GenericMusicIconResourceTest`)

Verified: `:yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug` -> BUILD SUCCESSFUL, EXIT 0; 2960 unit tests, 0 failures, 0 errors, 0 skipped
(`GenericMusicIconResourceTest` 2/2); lint `<issue ` count 0.

Checked and found correct
- <= P branch: empty cursor / cursor == null -> initial generic icon; several rows -> last row wins exactly as before; `dataUri != null` -> `ContentHolder(image/png, dataUri, ...)` unchanged
  (and, being the `String uri` constructor, `cacheSeconds` stays 0); `dataUri == null` -> generic icon. The old per-album jpg write is gone in this branch.
- >= Q branch: a cover is still written to `cacheDir/albumart<id>.jpg` and served as `image/jpeg` (same as the old `mimeType`, same file path, same range handling through the `String uri` holder).
  `loadThumbnail` `IOException` now returns the generic icon without writing a file (the old code wrote the logo as jpg). Other exceptions from `loadThumbnail` behave as before.
- Cache-Control: `cacheSeconds` is a per-instance field, set only in `genericMusicIcon`, and the header is set per response from that holder. Live-stream holders never get it, and the MJPEG branch
  sets `no-cache...` after it (the later `setHeader` replaces it). Cover/file/SAF/thumbnail holders keep `cacheSeconds == 0` -> no new header. Nothing static carries it, so no leak.
- byte[] holder: `RangeResponsePlan.of(ranges, content.length)` and the `content != null` entity branch (copyOfRange for a partial window) are the same path the old `getDefaultIcon()` bytes took; HEAD/range
  behaviour is unchanged (Content-Length/Content-Range from the plan).
- Memo: `private static volatile byte[]` holding an immutable-by-convention byte array (never mutated, `AsyncEntityProducers.create(byte[])` / `copyOfRange` do not write to it); a race only reads the
  4 KB resource twice. The failure case is not memoized, so a later request retries. No Context is leaked (bytes only).
- `getDefaultIcon()` is still used (`lookupThumbnail`, and the fallback in `genericMusicIcon`). `R.raw.album_art_music_generic` is a direct code reference, so resource shrinking keeps it
  (no `shrinkResources`/`minifyEnabled` is configured in the module anyway).
- The non-numeric branch is unreachable over HTTP (`handle()` already answers 403 for a non-numeric album id); it is harmless defence in depth.
- PNG viewed: 512 x 512, one gray (#8A8A8A) eighth-note glyph, centered, transparent surround, 4338 bytes; readable on light and dark backgrounds. Good.
- CHANGELOG entry is under Unreleased > Fixed and matches the behaviour; `docs/tech.md` entry matches the code. License header present on the new test file; PNG needs none.

### Critical
- none

### Warning
- [YaaccUpnpServerContentHttpHandler.java:569, 637-647, 876-880] The generic icon is always cacheable for 24 h (`genericMusicIcon` sets `cacheSeconds` unconditionally), but it is also the answer for situations
  that are NOT "this album has no cover": (a) the Q+ branch where a cover WAS loaded but writing `albumart<id>.jpg` throws `IOException` (disk full / cache dir problem) - `result` keeps the initial
  generic icon, now with `max-age=86400`, so the control point shows the music note for a real cover for a day (the old code answered the logo without a cache header); (b) the early returns for the
  `settings_local_server_chkbx` switch off / `albumId == null`; (c) the fallback to the app logo when the raw resource cannot be read (the logo is then cached for a day under the "generic icon" lifetime).
  Recommended fix: cache only the real "no cover" answers - make the initial `result` and these error/disabled paths non-cacheable (e.g. `genericMusicIcon(ranges, boolean cacheable)` or reset
  `result.cacheSeconds = 0` on those paths), and set `cacheSeconds` only where `loadThumbnail` threw, `dataUri == null`, or the id is not a number.
- [yaacc/src/test/.../GenericMusicIconResourceTest.java] The behaviour change itself is untested: the test only decodes the PNG file. Not covered: `lookupAlbumArt` returning the generic icon (PNG bytes,
  `image/png`) for no cover / `IOException` / non-numeric id, the cover path still returning `image/jpeg` + file, `Cache-Control: public, max-age=86400` present only for the generic icon and absent
  for a cover and for live streams, no `albumart<id>.jpg` written for an album without a cover, and the fallback when `R.raw` cannot be read. `RangedResponseOverHttpTest` /
  `SmallFileRangeCompatTest` already drive the handler over HTTP and can be extended (or a focused test of `genericMusicIcon`/`ContentHolder.cacheSeconds` and the header decision added). Per
  `~/.claude/rules/testing.md` untested edge cases on the changed path are a Warning.

### Suggestion
- [YaaccUpnpServerContentHttpHandler.java:27] `import android.graphics.BitmapFactory;` is now unused (its only two uses were removed). Remove it.
- [YaaccUpnpServerContentHttpHandler.java:867] `java.io.InputStream` is written fully qualified although `java.io.InputStream` is already imported (line 65); use `InputStream`. `read(buffer) > 0` could be
  `!= -1` (convention; no behaviour difference for a 4 KB buffer).
- [YaaccUpnpServerContentHttpHandler.java:878] If the raw resource cannot be read and `getDefaultIcon()` also returns null, the holder has neither bytes nor uri and `getEntityProducer()` answers the
  "Resource not found" HTML with status 200 and image/png; very unlikely, could answer 404 (return null) instead.
- [docs/tech.md, CHANGELOG.md] "a non-numeric album id answers it too (was a NumberFormatException)" is correct for the method but cannot happen over HTTP (403 before the lookup); say so or drop the remark.
- [.claude/specs/2026-11-06-issue296-track-icon/] Only `requirements.md` exists (no design.md/tasks.md); acceptable for this size, noted for the workflow rules.
- [GenericMusicIconResourceTest.java:31] The test locates the PNG by the relative path `src/main/res/raw/...`, which depends on the Gradle working directory (module dir); fine under Gradle, fragile in other runners.

### Tests
- [x] All tests passing (2960 run, 0 failures; lint 0 issues; assembleDebug OK)
- [ ] Coverage adequate for the change (resource only; handler behaviour and the header are untested - see Warning 2)

### Verdict: FAIL
Two Warnings (cacheable generic icon also on error/disabled paths; handler behaviour untested). No Critical findings; build, tests and lint are green.

## Cycle 2 - 2026-11-06
Reviewing: commit 137080da (fixes for the two cycle 1 Warnings)

Verified: `:yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug` -> BUILD SUCCESSFUL; 2966 unit tests, 0 failures, 0 errors, 0 skipped
(`AlbumArtFallbackTest` 6/6 ran, none skipped by the `assumeTrue`); lint `<issue ` count 0.

Cycle 1 findings
- W1 (cache only the real "no cover" answer): fixed. `genericMusicIcon(ranges, cacheable)` sets `cacheSeconds` only when `cacheable && generic` (the real PNG was read). `cacheable == true` only at
  `ALBUM_ART == null` (<= P) and the `loadThumbnail` `IOException` (>= Q). The initial `result` (disabled server, missing id, <= P `cursor == null`, an unknown album with no row, the Q+ file-write failure)
  is non-cacheable, and so is the app-logo stand-in. No picture at all returns null -> 404 (handle() null path; the <= P loop can overwrite `result` with null, harmless). Memo/race behaviour unchanged.
- W2 (tests): fixed for the <= P branch; the >= Q branch stays reviewed-only (SDK_INT is 0 on the JVM) and is stated in the test Javadoc and docs/tech.md - accepted, the Q+ change is two lines
  (`return genericMusicIcon(ranges, true)`) that I read and found correct.
- Suggestions: unused `BitmapFactory` import and the fully qualified `InputStream` are gone; the docs now say a non-numeric id cannot reach the code over HTTP. (Line 878 empty-holder case: now returns null/404.)

Mutation check (scratch copy outside the repo, production code of the repo untouched): replacing `if (cacheable && generic)` by `if (true)` (the cycle 1 behaviour) makes
`aDisabledServerOrAMissingIdGetsTheIconButNotACacheHeader` and `anUnknownAlbumGetsTheIconButNotACacheHeader` FAIL (2 of 6); the cover test and the no-cover test pass as they should. So the
tests do fail without the fix.

### Critical
- none

### Warning
- none

### Suggestion
- [AlbumArtFallbackTest.java whenThePictureCannotBeReadTheAnswerIsNeverCacheable] On the JVM the stand-in (`getDefaultIcon()`) is null, so the test only exercises the "no picture -> null" path; the `generic` flag
  (a readable logo stand-in must not be cacheable) is not really pinned: dropping `&& generic` would still pass. A test with a mocked drawable path is awkward; acceptable, or drop `generic` from the
  condition by restructuring so the stand-in never gets `cacheable == true`.
- [AlbumArtFallbackTest.java theIconIsReadOnceAndIsNotTheAppLogo] The name promises "not the app logo" but the assertion is only `length != 0`; rename (e.g. `theIconIsReadOnce`) or compare with the logo bytes.
- [docs/tech.md] The one-line entry is now very long; consider splitting it into sub-bullets.

### Tests
- [x] All tests passing (2966, 0 failures; lint 0 issues; assembleDebug OK)
- [x] Coverage adequate for the change (<= P branch and the cache decision covered and shown to fail without the fix; >= Q branch reviewed only, documented)

### Verdict: PASS
