# Decisions: Sort by Date / Recently Added for UPnP folders (issue #252)

## 2026-09-25 — Local Android SDK setup for this session

**Context**: This cloud environment ships no Android SDK and its default
network policy blocked `dl.google.com` (Android Gradle Plugin / Google's
Maven repo) and `jitpack.io` (the `com.github.chrisbanes:PhotoView`
dependency), so Gradle could not even configure, let alone compile or run
tests.

**Decision**: with the user's help, opened both hosts in the environment's
network settings, then installed a minimal Android SDK for this session:
command-line tools unzipped to `/home/user/android-sdk/cmdline-tools/latest`,
`platform-tools`, `platforms;android-34`, and `build-tools;34.0.0`/`35.0.0`
via `sdkmanager`, and pointed Gradle at it via
`/home/user/yaacc-code/local.properties` (`sdk.dir=/home/user/android-sdk`).

**Rationale**: this SDK install and `local.properties` live outside the git
repo (not committed — `local.properties` is a per-machine file, standard
Gradle/Android convention, already gitignored) and only persist for this
container's lifetime. Later groups' subagents in this same session reuse
this setup rather than re-installing.

Maven Central (`repo.maven.apache.org`) also intermittently returned 429
(Too Many Requests) through the proxy during dependency resolution — this
resolved on retry with a short backoff (~15-20s), not a persistent block.
