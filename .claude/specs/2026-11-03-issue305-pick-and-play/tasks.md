# Tasks: pick and play (#305)

Plan: `specs/2026-11-03-issue305-pick-and-play/requirements.md` and `design.md`. Owner decisions of 2026-11-03: folder only, 500 files, media files split by type into up to three players started together.

## Group 1: Research and test skeletons
- [x] Verify the platform facts and write them to `docs/tech.md` (section "Pick and play (#305)") | `docs/tech.md`
  - **Accept**: documented with source and date: `ActivityResultContracts.OpenMultipleDocuments` / `OpenDocumentTree` signatures in the pinned androidx.activity version, the grant lifetime of their results, `DocumentsContract.buildChildDocumentsUriUsingTree` columns, whether ExoPlayer in the pinned media3 version reads `content://` URIs by default.
  - **Verify**: each statement has a source URL or an `inspect` of the jar; no code written against unverified APIs.
- [x] Test skeletons (red) for `PickedQueue`, `QueueShareWindow`, `FolderListing` | `yaacc/src/test/java/de/yaacc/share/`
  - **Accept**: tests encode: split by type, sort by name per type, 500 cap over all queues, non-media skipped, notices; acquire/release reference counts, at most current+next per player, registry-full path, idempotent release, two players on different indexes; bounded listing without recursion.
  - **Verify**: tests compile; they fail for the missing classes only.

## Group 2: Pure parts
- [x] `PickedQueue`, `FolderListing` (bounded provider query behind a seam), `QueueShareWindow` | `yaacc/src/main/java/de/yaacc/share/`
  - **Accept**: the Group 1 tests pass.
  - **Verify**: `ANDROID_HOME=/root/android-sdk ./gradlew --offline :yaacc:testDebugUnitTest --tests 'de.yaacc.share.*'`
  - **Constraints**: no Android classes in the decision logic; provider calls only through `BoundedCall` / `ProviderCallGate`; no log of names, URIs, keys.

## Group 3: Play path
- [x] `PlayableItem` binding, window hook in `AbstractPlayer` (share the current item, release on advance / previous / stop / destroy; see decisions.md), queue factory | `player/`, `share/`
  - **Accept**: existing player tests green; new tests: a queue of 20 items on a fake player never holds more than 2 shares; two players; shuffle and repeat all; stop releases all.
- [x] Local receiver: audio and image with the `content:` URI directly, video through the window (no player file changed: they already read content uris, see decisions.md) | `player/LocalMediaSessionPlayer`, `LocalImagePlayer`, `MultiContentPlayer`
  - **Accept**: decided by the Group 1 findings; fallback documented in decisions.md.
- [x] Hand-over: confirm captures receiver ids once, addresses fixed, `ReceiverPickController` offers the queue | `browser/`
  - **Accept**: cancel, rotation, busy and timeouts behave like browse actions (existing tests pattern).

## Group 4: UI
- [x] Two buttons (new layout server header, classic server tab), pickers, notices, strings in 7 languages, icons | `browser/`, `res/`
  - **Accept**: lint 0 findings; fr apostrophes escaped; notices only fixed texts with counts.

## Group 5: Reviews (sequential, each max 3 cycles)
- [x] reviewer PASS (cycle 3), then security-reviewer PASS (cycle 2) | `review.md`, `security-review.md`

## Group 6: Documentation update
- [x] docs/index.md section, docs/tech.md as built + SI additions, CHANGELOG, README, store description | `README.md`, `docs/`
  - **Accept**: no stale statement ("only a single file" in "Sharing to YAACC" is true for the share menu only and says so); trademark notice unchanged.
  - **Verify**: `grep -r 'TODO\|FIXME\|PLACEHOLDER' README.md docs/ || true` shows nothing plan-related.
