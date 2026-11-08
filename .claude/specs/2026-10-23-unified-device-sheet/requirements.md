# Unified device sheet, layouts, sharing: requirements

Scope: branch `feat/unified-device-sheet` (issue #287). Phases 1-3 are built; Phase 4 (consolidating the share code) is open.
Decisions and rationale: `design.md`, `decisions.md`; as-built facts: `docs/tech.md`. Planning history is in git.

## Problem
Playing something took four tabs (Server, Content, Receiver, Player). The receiver tab comes after the content tab, but a
receiver has to be picked before playing, so the choice was set up front and forgotten, or found wrong only when playback
landed on the wrong device. Receivers carry real controls (transport, volume, mute) and running players open full player
screens, so neither can become a one-line picker. Users also wanted to send a file or link from another app to their
receivers without first browsing to it.

## Goals
1. **Device sheet (Phase 1).** Reach every receiver, with its full controls, from the content screen without a tab switch;
   show each running player under the receiver it plays on (receivers and players are not 1:1); replace the two-line footer
   with one receiver bar that opens the sheet.
2. **Classic and new layout (Phase 2).** Keep the four-tab browser as "classic" and add a tab-less "new" layout (server list,
   then content, receiver bar and a mini-player on every level), chosen by a setting; nothing is lost for classic users.
3. **Share into yaacc (Phase 3).** Accept one audio, video or image file (and an http/https link) from another app, serve it
   to the chosen receivers through the media proxy without changing the user's settings, and pick the receivers per action
   (play, play all, add to playlist, share) in the new layout.
4. **Consolidate the share code (Phase 4, open).** Fewer, more cohesive classes in one package `de.yaacc.share`, with the
   same behaviour, the same tests and the same security properties.

## Non-goals
- Changing how many player objects `PlayerService` creates per device.
- `SEND_MULTIPLE`, several files per share, other file types, `file:` shares.
- Making the classic layout offer the sheet (decided: new layout only; classic gets the picker only for a shared file).
- Phase 4: any feature, any security change, any new dependency, manifest change, string or preference key; drive-by fixes of
  the known risks (`known-risks.md`).

## Success metrics
- Phase 1: a receiver is selectable and its volume changeable in at most two taps from the content screen; every player of
  the player tab is listed under exactly one device (`DeviceSessionGrouperTest`).
- Phase 2: switching the setting works in both directions without interrupting players; the share path lands in the chosen
  layout.
- Phase 3: nothing is opened, copied or registered before the user confirms receivers; a share is reachable only with its
  random key (and, by default, from the chosen receivers' addresses).
- Phase 4: test count 1472 + the Group 17 characterization tests (+1 zone-id case), 0 failures; lint unchanged; manifest
  and public API of `UpnpClient`/`ShareIntentHandler` unchanged; SI-1 ... SI-19 (`design.md`) still pinned by tests.

## Constraints
- Android, Java, Material Components 1.14.0; no new dependency in any phase.
- D-pad usable (Android TV support exists since 5.4.0).
- One receiver-row binder shared by the sheet and the receiver tab.
- Android types are allowed in the share classes (owner, 2026-10-26); tests mock them with Mockito, there is no Robolectric.
