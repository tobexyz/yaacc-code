# Tasks: video galleries (#318)

Plan: `requirements.md`, `design.md`, `decisions.md`. Rules: tests first; build -> `reviewer` PASS -> `security-reviewer` PASS (sequential,
max 3 cycles); commits as Tobe <noreply> with `(#318)`; no PR (the owner opens it).
Gate: `ANDROID_HOME=/root/android-sdk ./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug`, lint count 0.

## Group 1: Spec and tests first
- [x] 1.1 Spec files | this folder
- [x] 1.2 Tests: ids, routing, `VideoGalleries`, the browsers against a fake cursor, the strings | `yaacc/src/test/.../contentdirectory/`

## Group 2: Implementation
- [x] 2.1 `ContentDirectoryIDs`, `VideoGalleries`, `VideoBrowser` | `contentdirectory/`
- [x] 2.2 The four browsers, `VideoItemBrowser` on `VideoBrowser`, routing | `contentdirectory/`, `YaaccContentDirectory`
- [x] 2.3 Strings in seven languages | `res/values*/strings.xml`
- [x] 2.4 Review gate: reviewer PASS, then security-reviewer PASS

## Group 3: Documentation
- [x] 3.1 `docs/index.md`, `CHANGELOG.md`
