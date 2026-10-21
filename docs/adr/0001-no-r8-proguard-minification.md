# ADR 0001: Do not enable R8/ProGuard minification

## Status

Accepted — 2026-10-17

## Context

While investigating the build after a recent Dependabot batch, we checked
whether code shrinking/obfuscation is configured for release builds.

Findings:

- No `buildTypes` block exists in `yaacc/build.gradle` (or anywhere else in
  the project), so the implicit `release` build type keeps AGP's default:
  `minifyEnabled false`. R8 does not currently run on this project at all.
- `yaacc/proguard-project.txt` was leftover from the pre-Gradle
  Eclipse/ADT/Ant toolchain (its own comments reference `project.properties`
  and `${sdk.dir}/tools/proguard/proguard-android.txt`, neither of which
  exist in the Gradle/AGP build). It was never wired in via
  `proguardFiles(...)`, contained no project-specific rules, and did
  nothing. Removed as dead weight (see this ADR's commit).
- The vendored Cling UPnP stack (`org.fourthline.cling`) binds UPnP
  services through reflection driven by annotations (`@UpnpAction`,
  `@UpnpStateVariable`, etc.) — `AnnotationLocalServiceBinder` plus the
  `AbstractAVTransportService` / `AbstractContentDirectoryService` /
  `AbstractAudioRenderingControl` family account for 119 reflection-related
  call sites across 5 files.
- `jakarta.enterprise.cdi-api` (a dependency) is also an
  annotation/reflection-driven DI framework.

## Decision

Leave R8/ProGuard minification disabled for now, and do not re-introduce a
`proguard-rules.pro` without a dedicated effort to write it correctly.

Enabling `minifyEnabled true` without exhaustive, hand-written `-keep` rules
for the Cling reflection surface and for CDI would very likely strip or
rename classes/methods that are only ever reached reflectively. That failure
mode is invisible at compile time and in unit tests — it only shows up at
runtime, on a real device, in exactly the way the `httpclient5`/`httpcore5`
Android-incompatibility crash did (see the `fix/develop-httpclient5-android-crash`
and `fix/develop-build-media3-1.11-migration` branches around this same
date). We do not want to introduce another bug class with that signature on
top of the one we just fixed.

## Consequences

- Release APKs remain unshrunk and unobfuscated. No code-size or
  reverse-engineering-hardening benefit from R8.
- If APK size or source protection becomes a real goal later, enabling R8
  should be its own dedicated piece of work: write explicit `-keep` rules
  for `org.fourthline.cling.**` (especially anything reached via
  `AnnotationLocalServiceBinder`), for `jakarta.enterprise.**`, and for any
  `androidx.media3` reflection use, then test the resulting release build
  on a real device before merging — not something to flip on as a quick
  toggle.
- The dead `yaacc/proguard-project.txt` file is removed; there is currently
  no `proguardFiles` reference anywhere in the build, which is correct for
  this decision.
