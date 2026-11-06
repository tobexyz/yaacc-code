# Tasks: OpenHome support (#301)

Plan: `requirements.md`, `design.md`, `decisions.md`. Rules: tests first for the pure parts; every group: build -> `reviewer` PASS ->
`security-reviewer` PASS (sequential, max 3 cycles); commits as Tobe <noreply> with `(#301)`; no PR (the owner opens it).
Gate: `ANDROID_HOME=/root/android-sdk ./gradlew --offline :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug`, lint count 0.

## Group 1: Spec, fixtures, capture
- [x] 1.1 Requirements, design, decisions, tasks | this folder
- [x] 1.2 OpenHome service descriptions as fixtures with the MIT notice | `yaacc/src/test/resources/openhome/spec/`, `THIRD_PARTY.md`, `licenses/MIT-OpenHome.txt`
- [x] 1.3 Capture tools for a real device or the YAACC renderer | `capture/`

## Group 2: Renderer, the pure model
- [ ] 2.1 `OhIds`, `OhQueue` (+ errors), `OhTransportState`, `OhVolume`, `OhSourceXml`, `OhTrackList`, `OhEscape` | `yaacc/src/main/java/de/yaacc/openhome/`
  - **Accept**: tests green: ids never reused, insert after id / at start / full / unknown id, delete, delete all, id array codec round trip and hostile text, read list XML escaped, volume mapping bounds, source XML.
  - **Verify**: `./gradlew --offline :yaacc:testDebugUnitTest --tests 'de.yaacc.openhome.*'`
- [ ] 2.2 Review gate: reviewer PASS, then security-reviewer PASS

## Group 3: Renderer, the Cling services
- [ ] 3.1 Playlist, Info, Time, Volume, Product services (annotated Cling services, evented variables, delayed `IdArray` event) | `yaacc/src/main/java/de/yaacc/upnp/server/openhome/`
  - **Accept**: every action and state variable of the fixtures exists with the same name, direction and type (test over the real description); SOAP round trips through the service harness; events sent.
- [ ] 3.2 Register the services on the renderer device (`createMediaRendererServices`), device description check | `YaaccUpnpServerService`
- [ ] 3.3 Review gate

## Group 4: Renderer, the coordinator and the player
- [ ] 4.1 `AbstractPlayer.insertItems`, `playItemAt`, `PlayOrder.insert`, `LocalMediaSessionPlayer` overrides | `player/`
- [ ] 4.2 `OpenHomeRenderer` (queue <-> player, ids, events, repeat / shuffle, volume, time) | `upnp/server/openhome/`
- [ ] 4.3 Review gate; AVTransport renderer tests stay green

## Group 5: Controller, basics
- [ ] 5.1 Action and subscription callbacks, `OpenHomeClient`, state model | `upnp/callback/openhome/`, `de.yaacc.openhome`
- [ ] 5.2 `OpenHomePlayer`: source and standby, queue operations, transport, position | `player/`
- [ ] 5.3 Discovery, choice in `PlayerService.createPlayer`, per-device protocol choice
- [ ] 5.4 Review gate

## Group 6: Controller, the rest
- [ ] 6.1 Volume and mute, repeat and shuffle, the device queue in the playlist dialog, row and mini player | `browser/`
- [ ] 6.2 Review gate

## Group 7: Documentation and real devices
- [ ] 7.1 docs/index.md, tech.md as built, CHANGELOG, strings in 7 languages
- [ ] 7.2 Tests against real devices (owner / tester), captures as fixtures
