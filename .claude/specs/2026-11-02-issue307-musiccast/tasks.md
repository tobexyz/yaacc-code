# Tasks: MusicCast support (#307) - slices 1, 1b and 2 done, group link (Group 3) next

Plan: `.claude/specs/2026-11-02-issue307-musiccast/requirements.md` and `design.md`.
Rules: tests first for the pure parts; all new strings in values + de/es/fr/nl/pt/zh; license headers with a closing ` */`; no brand names in class / package names (`de.yaacc.yxc`); commits carry `(#307)`; verify with `ANDROID_HOME=/root/android-sdk ./gradlew --offline --rerun-tasks :yaacc:cleanTestDebugUnitTest :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug`.

## Group 1: Research (P0), no production code
- [x] Verify the YXC behavior from the open-source libraries and write it to `docs/tech.md` (in our own words) | `docs/tech.md` (done 2026-11-02: section "YXC (the API of Yamaha MusicCast devices)", 11 points to verify on real devices are listed at its end)
  - **Accept**: the notes cover URL scheme and `X-AppName` / `X-AppPort`, the UDP event format and the keys we use, `getDeviceInfo`, `getFeatures` (what it reports per zone: inputs, sound programs, ranges), `getStatus`, `getDistributionInfo`, the full group call sequence (link, extend, reduce, unlink, rename) with the checks the library waits for, the discovery tag in the UPnP description. Each item cites the file in the library it was read from.
  - **Verify**: every statement has a source reference; nothing is copied verbatim.
  - **Constraints**: no text or tables from Yamaha documents; license of each source library named.
- [ ] Capture answers of the owner's devices as scrubbed fixtures | `yaacc/src/test/resources/yxc/` (tools ready in `.claude/specs/2026-11-02-issue307-musiccast/capture/`, waiting for the owner to run them)
  - **Accept**: `getDeviceInfo`, `getFeatures`, `getStatus`, `getDistributionInfo` (unlinked, server, client), and a few UDP events, with MAC / serial / names / IPs replaced.
  - **Verify**: a grep for MAC / IP patterns finds nothing.
  - **Constraints**: owner's devices are the source; no model names in file names.
- [x] Experiment on real devices: does the server's volume propagate to linked clients? (decision 7) | result: NO, not through the API (see design.md); yaacc propagates relatively

## Group 2: Client, model, discovery, events (P1, read only)
- [x] `YxcClient` (HTTP/JSON calls as methods, immutable result objects), tests with a fake YXC server on loopback (Host = localhost, see RangedResponseOverHttpTest) | `yaacc/src/main/java/de/yaacc/yxc/`
- [x] `YxcDiscovery` (done: `YxcDiscovery`, `YxcHttpDescriptionFetcher`, `YxcEndpoint`): fetch the UPnP description of each renderer once, detect the control URL, cache per UDN; never talk to addresses that did not come from our own discovery
- [x] `YxcModel` (done as `YxcDeviceState` + the parsed answers) (device, zone status, features, group info) + parsing of the fixtures
- [x] `YxcEvents` (done: `YxcEventListener`, `YxcEventPlan`, `YxcMonitor`): UDP listener with lifetime rules (start / stop), applies events to the model, polling fallback; tests with a test socket
- [x] Show MusicCast status (power, input, group role) in the receiver row (done: `YxcRowText`, `YxcRowFeed`, `yxc_text` in the row; strings in 7 languages) of a supported device
  - **Accept (group)**: with the fixtures the model and the row show the same state as the device; the sheet follows events from the fake server.

## Group 3: Link and unlink (P2, option A) - spec of slice 3 in design.md; owner decisions 2026-11-02
- [x] 3.1 Group id in `YxcDeviceState`; pure `YxcGroupDirectory` (server of a client, clients of a server, by group id and addresses) | `yaacc/src/main/java/de/yaacc/yxc/`, tests with the captured fixtures (`a_distInfo_server_pair`, `c_distInfo_client`, event fixtures)
  - **Accept**: a client resolves its server and a server its clients from states of two watched devices; unresolved members give the generic text; the row text of a client no longer uses the client's own group name.
  - **Verify**: `YxcGroupDirectoryTest`, `YxcRowTextTest` pass.
- [x] 3.2 Pure `YxcLinkCandidates` (picker content and the disabled reasons) | `yaacc/src/main/java/de/yaacc/yxc/`
  - **Accept**: devices in another group are disabled with a reason; members of the current group are pre-selected; `client_max` is respected; unreachable or non-group devices are not offered.
  - **Verify**: `YxcLinkCandidatesTest` pass.
- [x] 3.3 `YxcGroupOperations`: members from endpoints, run on a worker, remember and restore the previous input, busy marking, complete read afterwards | `yaacc/src/main/java/de/yaacc/yxc/`, tests with `SimulatedYxcNetwork`
  - **Accept**: link, extend, reduce, unlink as in the service tests plus: input restored after a leave, busy state set and cleared (also after a failure), late role is no failure, group id of a working server is never replaced.
  - **Verify**: `YxcGroupOperationsTest` pass.
- [x] 3.4 UI (code done; the run on the owner's devices is still to do): "Link devices" picker, "Unlink" / "Leave group", busy state in the row, failure message; strings in 7 languages | `yaacc/src/main/java/de/yaacc/browser/`, `res/`
  - **Accept**: on the owner's devices: link two, play on the server, both in sync; extend by a third; reduce; unlink; the sheet follows (events) and the client is back on its earlier input.
  - **Verify**: build, tests, lint; owner's run.
- [x] 3.5 Playback rule: send to a linked client goes to its server with a hint; picker title "plays via <server>" | pure rule + tests, `PickTitle`/receiver code
  - **Accept**: tests for the redirect rule (resolved, unresolved, server itself, unlinked); the hint is shown.
- [x] 3.6 Review gate: reviewer PASS (cycle 2, before the UI rework), security-reviewer PASS (cycle 2, covers the whole slice incl. volume and group volume). The general review of the later code (icon toggle, link sheet, volume access, group volume) found one Critical and several Warnings in cycle 1 and one Warning in cycle 2; all are fixed.

## Group 4: Group as virtual device (P3, option B)
- [ ] Virtual receiver "Group <name>" (precedent: `UpnpClient.LocalDummyDevice`): receiver id resolution (`getDevice`, `setReceiverDeviceIds`, `ShareAddressResolver`, mini player), playback to the server, members shown inside
- [x] Group volume per decision 7 (done on the server row of a group: `YxcGroupVolume`, slider in the row; the row's own sliders use `YxcVolumeAccess`): a second "group volume" slider on the server's row (the device does not propagate by itself), relative change applied to every member, clamped per device; tests with the simulated network

## Group 5: Settings per device (P4) - spec of slice 4 in design.md; owner decisions 2026-11-03
- [x] 5.1 Client calls, `YxcNameText`, pure `YxcSettings` + `YxcSettingsWriter` for step 1 (power, input, sound program) | `yaacc/src/main/java/de/yaacc/yxc/`, tests with the captured features / status of devices a, b, c
  - **Accept**: only what the device reports is offered; `mc_link` is not offered as an input; a change outside the options is refused before anything is sent.
  - **Verify**: `YxcSettingsTest`, `YxcSettingsTest (the writer is tested there)`, `YxcClientTest` pass.
- [x] 5.2 Settings sheet (code done; the run on the owner's devices is still to do) and the icon in the row, strings in 7 languages (step 1) | `yaacc/src/main/java/de/yaacc/browser/`, `res/`
  - **Accept**: on the owner's devices: switch power, change input (cd, tuner, hdmi ...), change the sound program; the sheet shows what the device did.
- [x] 5.3 Step 2: equalizer (mode, low / mid / high) and sleep timer | model, writer, sheet, strings
- [x] 5.4 Step 3: link control, audio delay, audio quality | model, writer, sheet, strings
- [x] 5.5 Review gate: reviewer PASS (cycle 2), security-reviewer PASS (cycle 2).
- [x] (moved into Group 7 on 2026-11-04) Playback controls / repeat / shuffle of network sources where the device reports them

## Group 6: Documentation update
- [x] README / docs: user description with the trademark notice, `docs/tech.md` final, CHANGELOG
  - **Accept**: no stale references; the notice from requirements.md is in place.
  - **Verify**: `grep -r 'TODO\|FIXME\|PLACEHOLDER' README.md docs/ || true` shows nothing plan-related.

## Group 7: Net sources, presets, tuner and more settings (slice 5 in design.md; owner decisions 2026-11-04)
- [x] 7.0 Captures and experiments by the owner: `capture.sh` (+ presets) while net radio / the tuner play, `source_test.sh` (playback, repeat, shuffle, recall preset, tuner seek) | `capture/`, `docs/tech.md`, `yaacc/src/test/resources/yxc/captured/`
  - **Accept**: the real answers are in docs/tech.md and scrubbed fixtures; the open points of the slice are answered.
  - **Verify**: files exist and `scrub.py` ran over them.
- [x] 7.1 Now playing on the row (`YxcNowPlaying`, tuner play info and preset info in the monitor, row text) | `yxc/`, `browser/`
  - **Accept**: tests with the captured play infos: text for net radio, streaming, tuner, none for hdmi / off / client; text cleaned and capped.
- [x] 7.2 Source controls (code done; to try on the owner's devices) (calls, `YxcSourceControls`, icons on the row, row play / pause / stop to the device's source while yaacc plays nothing) | `yxc/`, `browser/`
  - **Accept**: controls offered only as the play info allows; fresh read before each action; no double send; UPnP transport unchanged while yaacc plays.
- [x] 7.3 Presets (code done; to try on the owner's devices) (`YxcPresets`, calls, star icon, `YxcPresetsSheet`) | `yxc/`, `browser/`, `res/`
  - **Accept**: empty slots hidden, recall only of a listed non-empty slot, names cleaned and capped, at most 100.
- [x] 7.4 (device level only: display brightness and automatic standby; the zone extras wait for a device that lists them) More device settings (dimmer, auto power standby, network standby, tone control, dialogue, subwoofer, switches) | `YxcSettings`, `YxcSettingsWriter`, sheet, strings
  - **Accept**: only what the device reports; ranges checked with `(value - min) % step == 0`.
- [x] 7.5 Review gate: reviewer PASS, then security-reviewer PASS | `review.md`, `security-review.md`
- [x] 7.6 Documentation: README / docs, docs/tech.md as built, CHANGELOG, strings in 7 languages | `README.md`, `docs/`
