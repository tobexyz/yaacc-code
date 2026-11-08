# Tasks: video galleries (#318)

Plan: `requirements.md`, `design.md`, `decisions.md`. Rules: tests first; build -> `reviewer` PASS -> `security-reviewer` PASS (sequential,
max 3 cycles); commits as Tobe <noreply> with `(#318)`; no PR (the owner opens it).
Gate: `ANDROID_HOME=/root/android-sdk ./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug`, lint count 0.

## Group 1: Spec and tests first
- [x] 1.1 Spec files | this folder
- [x] 1.2 Tests: ids, routing, `MediaGalleries`, the browsers against a fake cursor, the strings | `yaacc/src/test/.../contentdirectory/`

## Group 2: Implementation
- [x] 2.1 `ContentDirectoryIDs`, `MediaGalleries`, `VideoBrowser` | `contentdirectory/`
- [x] 2.2 The four browsers, `VideoItemBrowser` on `VideoBrowser`, routing | `contentdirectory/`, `YaaccContentDirectory`
- [x] 2.3 Strings in seven languages | `res/values*/strings.xml`
- [x] 2.4 Review gate: reviewer PASS, then security-reviewer PASS

## Group 3: Documentation
- [x] 3.1 `docs/index.md`, `CHANGELOG.md`

## Group 4: Image galleries list (owner's report: the app shows 1 gallery, Home Assistant 9)
- [x] 4.1 Tests first: `ImageGalleryListTest` (page of galleries, pages add up, total, sort without case, counts, same names, name in gallery = name in list); `MediaPathFilterBrowsersTest` adapted | `yaacc/src/test/.../contentdirectory/`
- [x] 4.2 `MediaGalleries` -> `MediaGalleries`; `ImagesByBucketNamesFolderBrowser` groups, sorts, pages; `ImagesByBucketNameFolderBrowser.browseMeta` takes name and count from the list | `contentdirectory/`
- [x] 4.3 `docs/index.md`, `CHANGELOG.md`, `decisions.md`
- [x] 4.4 Review gate: reviewer PASS (cycle 6), then security-reviewer PASS (cycle 4)

## Group 5: Images folder paging (owner asked)
- [x] 5.1 Test first: `ImagesFolderBrowserTest` (order, pages, past the end, huge count, sort); the past-the-end test failed on the old arithmetic | `yaacc/src/test/.../contentdirectory/`
- [x] 5.2 `ImagesFolderBrowser` uses `MediaGalleries.page` | `contentdirectory/`
- [x] 5.3 `CHANGELOG.md`, `decisions.md`
- [x] 5.4 Review gate: reviewer PASS (cycles 7 FAIL on a doc line, 8 PASS), then security-reviewer PASS (cycle 5)
