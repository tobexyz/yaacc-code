# Security Review: Issue #296 - generic music icon for an album without a cover

## Cycle 1 - 2026-11-06
Reviewing: `git diff develop..HEAD` (3 commits: handler change, 2 test classes, shipped PNG, CHANGELOG, docs/tech.md)

### Critical
- none

### Warning
- [yaacc/src/main/java/de/yaacc/upnp/server/http/YaaccUpnpServerContentHttpHandler.java:638 (and :618-628 for the parse)] The album id is only validated with `Long.parseLong(albumId)` (doService, line ~187, and again at line 623), but the cache file name is built from the raw string (`"albumart" + albumId + ".jpg"`), not from the parsed `long id`. `Long.parseLong` accepts unbounded leading zeros (`0007`, `00000007`, ...), a leading `+` or `-`, and non-ASCII Unicode digits, and the thumbnail URI is built from the parsed value, so every spelling resolves to the same album. A hostile LAN client can therefore request `/album/7`, `/album/07`, `/album/007`, `/album/+7`, ... for one album that has a cover and make the handler write a new `albumart<variant>.jpg` (up to 1024 x 1024 JPEG q90, typically 100 KB or more) per spelling into `getCacheDir()`. The number of spellings is bounded only by the file name length limit, so cache/storage growth is effectively unbounded and the file is rewritten on each request. Path traversal is NOT possible: after `parseLong` only digits, `+`/`-` remain (so no `/`, `..` or `%2e`, the path segments are decoded before the check). This is pre-existing code (the old branch wrote the same name) and the change does not make it worse, but the requirement "no per-album file or cache growth any more" holds only for albums WITHOUT a cover (generic answer, nothing written, confirmed). For albums with a cover the growth remains and is amplified by the id spellings. Fix (one line, no behaviour change for valid ids): use the parsed id, `new File(context.getCacheDir(), "albumart" + id + ".jpg")`, and optionally reject ids that are not canonical (`!Long.toString(id).equals(albumId)` -> 403/404) in doService. On Android 9 and earlier no file is written and the SQL uses `selectionArgs`, so this applies to Android 10 and later only.

### Suggestion
- [YaaccUpnpServerContentHttpHandler.java:632-636] On Android 10 and later a non-existing album id also makes `loadThumbnail` throw an `IOException` (FileNotFound), so it takes the cacheable branch and gets `Cache-Control: public, max-age=86400`. docs/tech.md states that "an album the table does not know" gets no header; that is true only for the Android 9 branch (empty cursor). Impact is low (a cached generic icon for an id that did not exist, or for an album whose cover is added later, stays stale on a client or LAN proxy for up to a day, no data exposure), but either correct the documentation or accept it knowingly. `private` or a shorter max-age would also reduce the effect on shared LAN caches.
- [YaaccUpnpServerContentHttpHandler.java:632] Only `IOException` is caught around `loadThumbnail`; a `SecurityException`/`IllegalArgumentException` from the resolver would propagate (pre-existing, unchanged). Consider catching `RuntimeException` and answering the non-cacheable generic icon.
- [YaaccUpnpServerContentHttpHandler.java:637-645] The cover cache file is rewritten for every request without a lock or temp-file-and-rename, so a concurrent request for the same album can serve a truncated JPEG (pre-existing). Skipping the write when the file already exists would also cut disk I/O from LAN input.
- Dedicated tests for the Android 10 and later branch (`loadThumbnail` throwing, id spellings such as `007`) are not possible with SDK_INT 0 unit tests; the warning above should get a Robolectric or reflection-free refactor test if it is fixed (e.g. extract the file-name building).

### Checked and found sound
- **Input path (all SDKs)**: route `/album/<id>` is reached only after `pathSegments.size()` is 2 or 3, the server provider preference is on, and `Long.parseLong` succeeded; otherwise 403. `lookupAlbumArt` is package-private now, but only called from doService. On <= P the id is bound via `selectionArgs` (no SQL injection). On >= Q the parsed `long` goes into `ContentUris.withAppendedId`; a new `NumberFormatException` guard returns the non-cacheable generic icon instead of throwing.
- **Reflection of the id**: the 404 body and the debug log echo the id, which after `parseLong` can contain only digits, `+`, `-` (or Unicode digits): no HTML/XSS or log injection.
- **Cache-Control**: `cacheSeconds` defaults to 0 and is set only inside `genericMusicIcon(..., cacheable=true)` when the bundled PNG itself was read (`generic` true). It is never set on the cover holders (`ContentHolder(mimeType, uri/path, ...)` on both SDK branches), so a real cover never carries the header and a generic answer replaces a real cover only when MediaStore says none exists. Non-cacheable: disabled server, null id, unknown id (<= P), NumberFormatException, failed cover write, and the app-logo stand-in when the resource is unreadable. The header contains no user data and is set before the LiveStream branch, which still overrides it with `no-cache` for its own holder. A hostile client can only poison its own cache or an explicit shared LAN cache, with the same static public icon.
- **Static memo / raw resource**: `volatile byte[]` filled once from `openRawResource(R.raw.album_art_music_generic)`; the source is a 4338 byte packaged resource, the 4096-byte buffer loop is bounded by it, so no unbounded read or memory growth. A double read in a race is harmless. The shared array is only read by `ContentHolder` (length, range slicing, entity producer; no writes found). Range header parsing is unchanged and tolerant.
- **DoS by many album ids**: for albums with no cover/unknown ids nothing is allocated or written per id (the old per-album fallback JPEG file is gone: confirmed in the diff). See the Warning for albums with a cover.
- **Logging**: new log lines contain only the validated numeric id or the album URI (`content://media/...` with a numeric id); no personal data, tokens or paths added. `ALBUM_ART` file path logging is pre-existing.
- **Shipped PNG** (`yaacc/src/main/res/raw/album_art_music_generic.png`, 4338 bytes, 512 x 512 RGBA 8 bit): chunk walk shows only `IHDR`, three `IDAT`, `IEND`; no `tEXt`/`iTXt`/`zTXt`/`eXIf`/`tIME`/`pHYs`/ICC chunks, and `strings` finds no text. No metadata or EXIF leak.
- **License**: the glyph is the path of `ic_baseline_audiotrack_48` (Material icon, Apache-2.0, already in the app); the PNG is a derivative of it, no new third-party asset. No separate attribution is needed beyond what the app already ships.
- **Manifest / permissions / dependencies**: the diff touches no `AndroidManifest.xml`, Gradle file, version catalog or ProGuard file; a `res/raw` file referenced from code is not stripped. The removed `BitmapFactory` import reduces the code. No new dependency.
- **Test hygiene**: `AlbumArtFallbackTest` closes the `mockStatic(PreferenceManager)` in `@After` (null-guarded) and resets the static `genericMusicIconBytes` memo before and after each test; `assumeTrue` skips instead of failing off-branch. `GenericMusicIconResourceTest` only reads a repo file. No secrets or network in the tests.

### Verdict: FAIL
One Warning (pre-existing, but it contradicts the stated "no cache growth" property for albums with a cover and is a one-line fix): derive the cache file name from the parsed `long id` (and/or reject non-canonical ids). Zero Critical. Everything specific to the new generic-icon and Cache-Control behaviour is sound.

## Cycle 2 - 2026-11-06
Reviewing: commit 0875ae00 (canonical album id) on top of cycle 1

### Critical
- none

### Warning
- none (the cycle 1 Warning is fixed)

### Suggestion
- [YaaccUpnpServerContentHttpHandler.java:577-582] A non-canonical spelling (`007`, `+7`) is answered 200 with the generic icon (non-cacheable), although doService's looser `Long.parseLong` gate lets it through. A 404/403 would be more honest to a client and would cost nothing; low value, not blocking.
- [YaaccUpnpServerContentHttpHandler.java:~640] The cover file is still rewritten on every request without a lock. Concurrent requests for the same album write the same single file name, so there is no growth, but a reader can see a truncated JPEG (pre-existing, availability/integrity glitch only). Writing to a temp file and renaming, or skipping the write when the file exists, would remove it.
- A test that asserts the cache file name (`albumart<id>.jpg`) on the Android 10+ branch is still not possible with SDK_INT 0 unit tests; the helper test plus the "no cursor interaction" test cover the guard.

### Verification
- `canonicalAlbumId` (line 861): rejects null, empty, length > 19, then `Long.parseLong`, requires `id >= 0` and `Long.toString(id).equals(text)`. Equality with the canonical print makes only the plain ASCII decimal spelling possible: no sign (`-0` parses to 0 but prints `0`, so it is rejected), no leading zeros, no Unicode digits (Arabic-Indic and full-width digits parse but print as ASCII, so they fail the equality), no blanks (parseLong throws). Overflow (`9223372036854775808`, 20+ digits) throws and gives null. `Long.MAX_VALUE` (19 chars) is accepted in its single spelling. The guard sits before the preference-dependent query/file code and before any cursor or ContentResolver use; the generic result it returns is the non-cacheable one.
- `albumId` is reassigned to `canonicalId.toString()` before both SDK branches, so the Android 9 selection argument and the Android 10+ file name `albumart<id>.jpg` use the canonical text only (the Android 10+ branch uses `long id = canonicalId`). Path traversal remains impossible (digits only).
- One cache file per album: file name is a pure function of the canonical id, so each album maps to exactly one name. Ids with no cover or no album make `loadThumbnail` throw and write nothing, so file count is bounded by the number of albums with a cover (device-owned data, not attacker-controlled). Very large ids (up to `Long.MAX_VALUE`) are only one name each and, if the album does not exist, no file. Concurrent requests overwrite the same name (see Suggestion for the integrity glitch); no extra files, no temp files are created.
- Tests: `onlyTheCanonicalSpellingOfAnIdIsAnAlbumId` (7, 0, Long.MAX_VALUE accepted; null, "", 007, +7, -7, -0, blanks, 7.0, Arabic-Indic, full-width, abc, overflow, ../7, 7/../8, 23 zeros rejected) and `anotherSpellingOfAnIdGetsTheIconWithoutAnyLookupOrCacheHeader` (`verifyNoInteractions(cursor)`, `cacheSeconds == 0`). Mock and memo hygiene unchanged from cycle 1. Gate result (2968 tests, 0 failures, lint 0) as reported by the coordinator; not re-run by me.
- docs/tech.md now states that on Android 10 and later an unknown album id gets the cacheable generic icon (cycle 1 Suggestion addressed).
- Manifest, Gradle files and dependencies still untouched by this commit.

### Verdict: PASS
