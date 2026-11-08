# Tasks: OpenHome support (#301)

Plan: `requirements.md`, `design.md`, `decisions.md`. Rules: tests first for the pure parts; every group: build -> `reviewer` PASS ->
`security-reviewer` PASS (sequential, max 3 cycles); commits as Tobe <noreply> with `(#301)`; no PR (the owner opens it).
Gate: `ANDROID_HOME=/root/android-sdk ./gradlew --offline :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug`, lint count 0.

## Group 1: Spec, fixtures, capture
- [x] 1.1 Requirements, design, decisions, tasks | this folder
- [x] 1.2 OpenHome service descriptions as fixtures with the MIT notice | `yaacc/src/test/resources/openhome/spec/`, `THIRD_PARTY.md`, `licenses/MIT-OpenHome.txt`
- [x] 1.3 Capture tools for a real device or the YAACC renderer | `capture/`

## Group 2: Renderer, the pure model
- [x] 2.1 `OhIds`, `OhQueue` (+ errors), `OhTransportState`, `OhVolume`, `OhSourceXml`, `OhTrackList`, `OhEscape` | `yaacc/src/main/java/de/yaacc/openhome/`
  - **Accept**: tests green: ids never reused, insert after id / at start / full / unknown id, delete, delete all, id array codec round trip and hostile text, read list XML escaped, volume mapping bounds, source XML.
  - **Verify**: `./gradlew --offline :yaacc:testDebugUnitTest --tests 'de.yaacc.openhome.*'`
- [x] 2.2 Review gate: reviewer PASS, then security-reviewer PASS

## Group 3: Renderer, the Cling services
- [x] 3.1 Playlist, Info, Time, Volume, Product services (annotated Cling services, evented variables, delayed `IdArray` event) | `yaacc/src/main/java/de/yaacc/upnp/server/openhome/`
  - **Accept**: every action and state variable of the fixtures exists with the same name, direction and type (test over the real description); SOAP round trips through the service harness; events sent.
- [x] 3.2 Register the services on the renderer device (`createMediaRendererServices`), device description check | `YaaccUpnpServerService`
- [x] 3.3 Review gate

## Group 4: Renderer, the coordinator and the player
- [x] 4.1 `AbstractPlayer.insertItems`, `playItemAt`, `PlayOrder.insert`, `LocalMediaSessionPlayer` overrides | `player/`
- [x] 4.2 `OpenHomeRenderer` (queue <-> player, ids, events, repeat / shuffle, volume, time) | `upnp/server/openhome/`
- [x] 4.3 Review gate; AVTransport renderer tests stay green

## Group 5: Controller, basics
- [x] 5.1 Action and subscription callbacks, `OpenHomeClient`, state model | `upnp/callback/openhome/`, `de.yaacc.openhome`
- [x] 5.2 `OpenHomePlayer`: source and standby, queue operations, transport, position | `player/`
- [x] 5.3 Discovery, choice in `PlayerService.createPlayer`, per-device protocol choice
- [x] 5.4 Review gate

## Group 6: Controller, the rest
- [x] 6.1 Volume and mute, repeat and shuffle, the device queue in the playlist dialog, row and mini player | `browser/`
- [x] 6.2 Review gate

## Group 7: Documentation and real devices
- [x] 7.1 docs/index.md, tech.md as built, CHANGELOG, strings in 7 languages
- [x] 7.2 Tests against real devices (owner / tester), captures as fixtures

## Status 2026-11-08: done
- All groups are finished; the reviews passed (`review.md` cycle 9 PASS; its cycle 10 Warning was fixed and read by `security-review.md` cycle 6 PASS; both files are condensed) and the owner tested with real devices (Home Assistant, the Docker test device in `testdevice/`, Upplay as a second controller).
- Added after the plan (see `decisions.md`): standby of the renderer as an app state, the Radio facade and the root-device services for Home Assistant, files and folders picked on the phone, volume in the row, reorder by dragging, edits at the phone's own player reach the queue, a device that plays already is shown and its playlist opened by a tap, limits for everything the network sends, translated notifications.
- Not done, by decision: Songcast, radio and tuner sources, queues of more than 1000 tracks, GENA subscriptions for the controller (it polls every two seconds), a total deadline for images and request bodies, an atomic subscriber count (list in `decisions.md`).
