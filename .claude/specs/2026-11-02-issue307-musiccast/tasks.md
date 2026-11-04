# Tasks: MusicCast support (#307) - DRAFT, nothing started

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
- [ ] Experiment on real devices: does the server's volume propagate to linked clients? (decision 7) | notes in `docs/tech.md`

## Group 2: Client, model, discovery, events (P1, read only)
- [ ] `YxcClient` (HTTP/JSON calls as methods, immutable result objects), tests with a fake YXC server on loopback (Host = localhost, see RangedResponseOverHttpTest) | `yaacc/src/main/java/de/yaacc/yxc/`
- [ ] `YxcDiscovery`: fetch the UPnP description of each renderer once, detect the control URL, cache per UDN; never talk to addresses that did not come from our own discovery
- [ ] `YxcModel` (device, zone status, features, group info) + parsing of the fixtures
- [ ] `YxcEvents`: UDP listener with lifetime rules (start / stop), applies events to the model, polling fallback; tests with a test socket
- [ ] Show MusicCast status (power, input, group role) in the receiver row of a supported device
  - **Accept (group)**: with the fixtures the model and the row show the same state as the device; the sheet follows events from the fake server.

## Group 3: Link and unlink (P2, option A)
- [ ] `YxcGroupService`: link / extend / reduce / unlink / rename with the expected-state checks and retry, tests with the fake server including failure and timeout cases
- [ ] Sheet UI: "Link devices" action on a supported device (choose the clients), "Linked with <server>" marker on members, "Unlink"; strings in all languages
- [ ] Play on a linked client: redirect to the server with a hint; extended title in the receiver picker; tests for the redirect rule (pure)
  - **Accept (group)**: on the owner's devices link two, play, both in sync, unlink; interruption of playback is accepted.

## Group 4: Group as virtual device (P3, option B)
- [ ] Virtual receiver "Group <name>" (precedent: `UpnpClient.LocalDummyDevice`): receiver id resolution (`getDevice`, `setReceiverDeviceIds`, `ShareAddressResolver`, mini player), playback to the server, members shown inside
- [ ] Group volume per decision 7 (propagate; second slider only if needed)

## Group 5: Settings per device (P4)
- [ ] Settings screen built from `getFeatures`: power, volume, mute, input, sound program, switches (pure direct, enhancer, ...), numbers (equalizer, subwoofer, dialogue level, ...), link control (audio delay / quality); only what the device reports
- [ ] Playback controls / repeat / shuffle of network sources where the device reports them

## Group 6: Documentation update
- [ ] README / docs: user description with the trademark notice, `docs/tech.md` final, CHANGELOG
  - **Accept**: no stale references; the notice from requirements.md is in place.
  - **Verify**: `grep -r 'TODO\|FIXME\|PLACEHOLDER' README.md docs/ || true` shows nothing plan-related.
