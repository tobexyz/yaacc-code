# Design (draft): MusicCast support

## Facts (read from the open-source `aiomusiccast` library and the Home Assistant integration, not from Yamaha's own documents)
- **Transport:** plain HTTP, JSON, no login: `http://<device>/YamahaExtendedControl/v1/...` (GET for reads and most setters; some setters POST a JSON body, e.g. the group calls).
- **API families** (classes of the library): `System` (device info, features, network status, location, name, party mode, link control and audio delay, bluetooth, HDMI/dimmer/speaker settings, ...), `Zone` (status, power, volume, mute, input, sound program, equalizer, tone control, direct / pure direct, enhancer, surround, dialogue level, subwoofer, bass extension, ...), `NetUSB` (play info, playback controls, presets, lists / browsing of the network sources, repeat, shuffle), `Tuner`, `CD`, `Dist` (the group = "link"), `Clock`.
- **Discovery:** the UPnP device description of a MusicCast device contains `<yamaha:X_yxcControlURL>/YamahaExtendedControl/v1/</yamaha:X_yxcControlURL>` (the library uses exactly this string to recognise a Yamaha device). Cling ignores that element, so the raw description has to be fetched (the registry knows the description URL of each remote device).
- **Events:** every request carries the headers `X-AppName: MusicCast/1.0` and `X-AppPort: <udp port>`; the device then pushes JSON change events by UDP to that port of the client (the library binds a UDP socket and uses its port). Polling (status calls) is the fallback and the way to resynchronize.
- **Group / link (`Dist`)** as the library does it:
  1. server: `setServerInfo(group_id, zone, "add", client_ip_list)` (POST);
  2. clients: `setClientInfo(group_id, [zone], server_ip)` (POST);
  3. server: `startDistribution(num)`;
  4. read: `getDistributionInfo` -> `group_id`, `group_name`, `role` (server / client / none), `server_zone`, `client_list`;
  5. unlink: `stopDistribution`, then an empty `setServerInfo` / `setClientInfo`; rename: `setGroupName`.
  The group id is a 32 hex digit string chosen by the client.
- **Home Assistant platforms** of the integration: `media_player`, `number`, `select`, `switch` (so: players, numeric settings, choices, on/off settings), plus group join / unjoin on the player.

## Where it fits in yaacc
- Receivers are `Device` objects of the Cling registry, shown by `ReceiverRowBinder` / `DeviceSheetAdapter`; remote receivers are polled by `RendererStatusMonitor` (GetTransportInfo etc.) and `ReceiverVolumeLiveUpdater`. A virtual receiver exists as a precedent (`UpnpClient.LocalDummyDevice`, the phone itself).
- The play paths send `SetAVTransportURI` / `Play` to a renderer (`AVTransportPlayer`); a MusicCast group server is a normal renderer for that.

## Proposed structure (new package `de.yaacc.yxc`: the protocol abbreviation, not the brand; see "Trademarks and naming" in requirements.md)
- `YxcClient`: the YXC calls as plain methods returning small immutable value objects, over the existing HTTP client; no Android classes (JVM-testable).
- `YxcDevice` / `YxcStatus` / `YxcGroup`: the model per device and per zone (features from `getFeatures`, status, distribution info).
- `YxcEvents`: the UDP listener (started only while something shows MusicCast devices: the sheet or a player; stopped otherwise), applies events to the model, falls back to polling.
- `YxcGroupService`: link / unlink / rename with the order of calls, retries and the checks the library does (re-read `getDistributionInfo` until the expected state or fail with a message).
- `YxcDiscovery`: for each remote renderer fetch the description once, look for the YXC control URL, remember the result per UDN.
- UI binding: an extra block in the receiver row / sheet for the supported devices. Generic wording in the strings: "Link devices", "Linked with <name>", "Group <name>", "Device settings"; the brand name only where it identifies the device.

## Linked devices in the sheet: two ways to show a group
- **A. Marker only:** each device keeps its row, with a badge "linked with <server>" and the "Link / Unlink" action. Playing on a client sends to its server (or is blocked with a hint).
- **B. Additional virtual device "Group <name>":** one row for the group (server + clients). Picking it as the receiver means: send to the server (the clients follow in sync); its volume row is the group volume (one set of controls, each member keeps its own volume; the semantics need a decision); its members are shown inside. Precedent: `LocalDummyDevice`.
- The owner suggested B ("maybe as an additional virtual device"). B changes how receiver ids resolve (`UpnpClient.getDevice(id)`, `setReceiverDeviceIds`, `ShareAddressResolver`, the mini player), so it needs care; A is the smaller first step and a good base for B.

## Phases (proposal)
- **P0 research:** read Yamaha's API documents; collect JSON captures (`getDeviceInfo`, `getFeatures`, `getStatus`, `getDistributionInfo`, events) from each of the owner's devices into a device matrix (model, firmware, zones, features); write fixtures.
- **P1 client, discovery, model, UDP events (read only):** show MusicCast status in the sheet.
- **P2 link / unlink / rename in the sheet** (marker display, option A).
- **P3 group as virtual receiver** (option B) and group-aware playback and volume.
- **P4 feature parity with the Home Assistant integration:** power, volume, mute, input, sound program, playback controls / repeat / shuffle, number and switch settings, link control (audio delay / quality). UI: a "device settings" screen per MusicCast device driven by `getFeatures` (only what the device reports).

## Tests
- Fake YXC server on loopback (the pattern of `RangedResponseOverHttpTest`: real HttpCore server with the host name set to localhost) answering recorded JSON; UDP events by a test socket.
- Pure parts: request building, JSON parsing, the group call order and the expected-state checks, event application.
- Not verifiable without hardware: the real group behaviour and firmware differences; the owner's devices cover that.

## Risks
- Firmware / model differences (zones, features, group limits).
- The UDP listener only works while the app is in the foreground / the network is reachable from the device; battery: run it only while needed.
- No authentication in the API: only talk to devices found through our own discovery (same subnet), never to arbitrary user input.
- Option B touches the receiver identity code.

## Decisions (owner, 2026-11-02) and what they change here
- Generic, no model tables: the settings screen (P4) and the link possibilities come from the answers of the device (`getFeatures`: zones, inputs, sound programs, ranges of the numbers; `getDistributionInfo`: role and clients). Unknown or missing features are simply not shown.
- Research source = the open-source libraries (see requirements.md decision 5); P0 therefore is a reading task that ends in verified notes in `docs/tech.md` (project rule: verify the API from a source before coding) plus fixtures captured from the owner's devices, scrubbed.
- A first, then B. Group volume: propagate to the members; the server's own slider is the group slider only if the system propagates it (to be tested), else a second "group volume" slider on the server's row.
- P4 = settings per device. Playing on a client = send to its server with a hint; the picker shows an extended title. Link / unlink during playback may interrupt it.
- The trademark notice is in docs/index.md, README, store description and app text (commit 80d2787e).

## Still open
1. **Volume semantics details:** when the group slider propagates, is it an absolute value for every member (simple, but loses their different levels) or a relative change (keeps the offsets; needs care at 0 and 100)? Proposal: relative, with a clamp.
2. ~~Does the system propagate the server's volume to linked clients?~~ **Answered 2026-11-02 on real devices: no.** `setVolume` on the server (31 -> 34) left the client at 28; `setVolume` on the client (28 -> 31) left the server at 31. The manufacturer's app moved both together, so it sends the volume to each member itself. yaacc does the same: a group volume slider on the server's row applies a relative change to every member (clamped to each device's `range_step` of `volume`, 0 to `max_volume`).
3. **Picker title:** the exact wording and the translations for the "plays via <server>" hint; the receiver sheet row for a client in option A (badge text).
4. **Event listener lifetime:** start the UDP listener when the sheet or a player of a supported device is shown, stop after a delay when nothing needs it (battery); the polling interval as the fallback.
5. **Fixtures:** which captured answers are committed (scrubbed) and how large the set should be.

## Trust rule for events (security review, slice 2)
Events arrive by UDP and the sender address can be forged on the LAN. They are used for display only. Every later slice that **acts** (link, unlink, volume steps for a group, redirects) must read the device first (`getDistributionInfo`, `getStatus`) and decide on that answer, never on the last event. Answers of a device are untrusted too: depth, size and time of every read are bounded (`YxcJson.parseObject`, `YxcHttpTransport`), names shown in the UI are cleaned (`YxcRowText.clean`).


## Slice 3: link, extend, reduce and unlink in the sheet (owner decisions 2026-11-02)
Owner decisions: (1) the actions live on the receiver row (action "Link devices" with a picker, "Unlink"/"Leave group"); (2) the server is the device where "Link devices" is started, the picked devices become its clients; a device that is in another group is shown disabled with a hint, never dissolved silently; (3) playing on a linked client goes to its server with a hint, the receiver picker shows "plays via <server>"; interruptions of playback by linking are accepted; (4) scope is link, extend, reduce, unlink; renaming a group waits. Out of scope: initial connect (adding or removing a device in the manufacturer's app), stereo pairs / surround groups, zones other than `main`.

### What the real devices taught (docs/tech.md, section 11)
- `startDistribution` needs `num` (0 for a new group, clients before the call otherwise); no group exists before it; the server is `building` for a few seconds, then `working`.
- The devices validate almost nothing: wrong group ids, `setServerInfo` on a client, `num=99` are all answered with code 0. The only refusal seen: `setClientInfo` on a working server (code 5). So **every decision is made on reads** before the call, and the result is judged on reads after it (`YxcGroupService` does this already).
- A leaving client keeps `role: "client"` (zero group id) as long as its input is `mc_link`; the role turns `none` only when the input is set to something else. So a leave is: clear the client info (group id zeros) **and** set the input back. yaacc remembers the last input that was not `mc_link` (seen in the states, also for groups made with the manufacturer's app); no fallback (a guess such as the first input of the list landed on a streaming service on the owner's device): without a known source the device stays on the link input and the row says so. A device with role `client`, zero group id and input `mc_link` is a *stuck* client: the row says so and offers "Leave group", which sets the input.
- A client's `group_name` is "(Linked) <own name>", its `client_list` lists itself or the server: neither is used for display. The server of a client is found by the **group id** (the watched device that is `server` with the same id).

### Parts
1. `YxcDeviceState` gets the group id (not shown, used to match members). Pure `YxcGroupDirectory` (built from the states and endpoints the monitor has): for a client the server's UDN, for a server the clients' UDNs (matched by the group id, and by the addresses of its `client_list` where they are known), so the row can say "Linked with <server name>" / "Group with <client names>" with the friendly names of the receivers; unresolved members fall back to the generic words. This also replaces the client text of slice 2, which showed the client's own "(Linked) ..." name.
2. Pure `YxcLinkCandidates`: which devices the picker offers for a server row: reachable, powered or powerable, supports the group calls (`getFeatures` distribution, `client_max` not exceeded), not the server itself; devices in another group are listed disabled with the reason; members of the current group are pre-selected (extend/reduce = change the selection).
3. `YxcGroupOperations` (Android-free orchestration): makes `YxcGroupMember`s from the monitor's endpoints (never from user input), runs `YxcGroupService.link/extend/reduce/unlink` on a worker, remembers the input from before a join and restores it after a leave, marks the devices busy (the row shows "Linking ..."), and asks the monitor for a complete read of every involved device afterwards. Tests with `SimulatedYxcNetwork` (including late roles, a device that does not answer, refusal code 5).
4. UI: row action "Link devices" (picker dialog with checkboxes, disabled entries with the reason) and "Unlink" (server row: dissolves the group; client row: leaves it); progress as busy state in the row; failures as a message that names the step and the device (`YxcGroupResult`). All strings in values + de/es/fr/nl/pt/zh, no brand names in code.
5. Playback rule (pure, tested): a send to a linked client is redirected to its server (known from the directory); the user sees a hint ("plays via <server>"); the receiver picker shows the extended title. If the server cannot be resolved, the send is refused with a message instead of going to the client.

### Risks
- The behavior on the owner's devices differs from the simulation in timing; the real run (link two, play, unlink) is the acceptance test.
- Linking interrupts playback on the clients (accepted).
- Events of the manufacturer's app arrive during our operation: the busy state suppresses re-reads of the involved devices until the operation ends, then one complete read.


### Owner change after the first run on the devices (2026-11-02)
The row has **one toggle button with an icon** instead of separate buttons: "Link devices" (link icon) while the device serves nobody, "Unlink group" (broken-link icon, checked) while it serves a group, and **disabled while the device is a client of another device**. There is **no "Leave group"**: a group is changed from its server only; unlinking the server lets every client leave. A client that left but still sits on the link input is in no group, so its toggle offers "Link devices". Extending or shrinking a group in place is not offered any more (unlink and link again); `YxcGroupOperations.applySelection/leave` still support it and stay tested.

Second change (2026-11-02): the toggle of a device that **serves a group** does not unlink at once. It opens the same picker with the current clients checked; unchecking some removes them (`applySelection` reduces and puts them back on their inputs), unchecking all ends the group (the picker says so). So extending and reducing a group in place is back, through the one picker; the accessible name of the serving state is "Edit group".


## Slice 4: settings per device (Group 5; owner decisions 2026-11-03)
Entry: a second icon button ("tune") next to the link toggle in the row of a MusicCast device that answers; it opens a bottom sheet in the style of the pickers. Steps (one commit each, tests first for the pure parts):
1. **Power, input, sound program.** Power as a switch; input as a choice from the zone's `input_list` (cd, tuner, hdmi, aux, bluetooth, net_radio, streaming inputs ... whatever the device lists), without `mc_link` (the group input is set by linking and leaving); sound program as a choice from `sound_program_list`. Names of inputs and programs from `system/getNameText` (`{id, text}` lists) when the device answers, else the id made readable.
2. **Equalizer and sleep timer.** Equalizer mode (`equalizer_mode_list`) and low / mid / high from the `equalizer` range of `getFeatures` (the three captured devices report -10..10, step 1); sleep timer off / 30 / 60 / 90 / 120 minutes (`sleep` in the zone's `func_list`).
3. **Link settings.** `link_control_list`, `link_audio_delay_list`, `link_audio_quality_list`, only where the device lists them (the captured devices differ: one has no audio quality).
Not in this slice: playback controls of network sources (the row's play / pause / stop stay), tone control, surround, dialogue level, subwoofer, switches like pure direct and enhancer (the captured devices do not list them; they can follow the same model).

### Parts
- `YxcClient`: `getNameText()`, `setSoundProgram`, `setSleep`, `setEqualizer`, `setLinkControl`, `setLinkAudioDelay`, `setLinkAudioQuality` (arguments validated before anything is sent).
- `YxcNameText` (pure): the `{id, text}` lists of `system/getNameText`.
- `YxcSettings` (pure): from the zone's features, the zone status and the names it builds the list of items **only for what the device reports** (a function missing in `func_list`, an empty option list, a missing range: no item). Item kinds: switch, choice (options with value and label), number (min, max, step, current).
- `YxcSettingsWriter` (pure over a `YxcClient`): applies one change after checking it against the item (value is one of the options, number inside the range and `(value - min) % step == 0`).
- `YxcSettingsSheet` (Android): loads features, status and names on a worker, shows switch / choice rows / sliders, applies a change on a worker and then reads the status again so the sheet shows what the device really did; results after the sheet was closed are dropped.
- Everything the device says (names, option words) is cleaned and shown as plain text; writes go only to the watched device the sheet was opened for (endpoint from the monitor).

### Open until captured on the owner's devices
`system/getNameText` (the library calls it once with and once without a parameter; yaacc sends none and falls back to readable ids), the effect of `setSleep` values, the equalizer call with `mode=manual` and the three bands in one request.


## Slice 5: net sources, presets, tuner and more settings (Group 7; owner decisions 2026-11-04)
Owner decisions: build all four of "now playing + source controls", "presets", "tuner controls" and "more device settings"; now playing and the controls of the device's own source live **on the row**; the presets get **their own icon + bottom sheet**; the row's play / pause / stop **control the device's own source** while it plays one and yaacc plays nothing on it (see decisions.md).

Facts from the three captured devices (a, b, c): all list `netusb` with `preset.num` 40, `play_queue`, `mc_playlist`, `recent_info`; a and c have a `tuner` block (a: fm, dab, fm_auto_preset, preset type `separate` num 30; c: fm, rds, dab, preset num 40; fm range 87500..108000 step 50); b has none; their `netusb/getPlayInfo` has `input`, `playback`, `repeat`, `shuffle`, `play_time`, `total_time`, `artist`, `album`, `track`, `albumart_url`, `attribute`, `repeat_available`, `shuffle_available`; the zone `func_list` of the captured devices has no tone control, subwoofer, dialogue level, pure direct or enhancer; the system `func_list` has `dimmer` and `auto_power_standby` on all three. The monitor already reads `netusb/getPlayInfo` (state holds `YxcPlayInfo`) and receives `play_info_updated` / `preset_info_updated` flags.

### Steps (one commit each, tests first for the pure parts; reviewer then security reviewer after the group, as before)
0. **Captures and experiments (owner, read-only first).** `capture.sh` gets `netusb/getPresetInfo`, `tuner/getPresetInfo` (band to be found: the values the device lists in its tuner block) and `tuner/getPlayInfo` is already there; run it while a net radio station plays and while the tuner plays. A new `source_test.sh` (changes what plays, like `link_test.sh`) tries `netusb/setPlayback` (pause, play, next, previous), `setRepeat`, `setShuffle`, `netusb/recallPreset` and the tuner calls (`setFreq` seek, `recallPreset`) and prints the answers and the play info before / after. Nothing is coded against an assumed answer: the real JSON of these calls goes to `docs/tech.md` and into scrubbed fixtures first.
1. **Now playing on the row.** `YxcNowPlaying` (pure): from the zone input, `netusb` play info and tuner play info builds one cleaned, capped text line: network source "Artist – Track" (or the track alone), tuner "FM 98.50 MHz" plus the RDS text / DAB service label, nothing for inputs without play info (`hdmi`, `aux`, ...) or while the device is off / linked as a client. The monitor reads `tuner/getPlayInfo` as well when the device has a tuner block (flag `play_info_updated` of the `tuner` area) and `netusb/getPresetInfo` when the preset flag arrives. The row shows the line under the status text (`YxcRowText`), updated through the existing feed.
2. **Source controls.** `YxcClient`: `setPlayback(play|pause|stop|previous|next)`, `setRepeat(off|one|all)`, `setShuffle(on|off)` (api version >= 1.19 only; older devices get no repeat / shuffle icons), tuner `setFreq` (seek up / down) and DAB `setDabService` (previous / next). `YxcSourceControls` (pure): what is offered now: previous / next / repeat / shuffle only if the play info says they are available (`repeat_available`, `shuffle_available`, `attribute` once its bits are verified by the captures), tuner seek only on the tuner input. Small icons on the row while the device plays its own network source or the tuner (previous, next, repeat, shuffle). **Row play / pause / stop:** while the zone's input is a network source or the tuner and no yaacc player plays on this device, the three buttons send `setPlayback` (play / pause / stop) instead of the UPnP transport; as soon as a yaacc player plays on the device they are UPnP again, as today. Every action reads the play info fresh first (events are display-only), is validated against what the device reports, and a double tap does not send twice (the action is serialised like the group calls).
3. **Presets.** `YxcPresets` (pure): from `netusb/getPresetInfo` (`preset_info`: list of `{input, text}`, `unknown` input = empty slot, position = preset number) and the tuner presets the list of recallable entries with cleaned, capped names (at most the number the features report, never more than 100). `YxcClient`: `getPresetInfo`, `recallPreset` (number checked against the list: only a non-empty slot of a list that was just read). A star icon on the row (only for devices whose features report presets) opens `YxcPresetsSheet` (bottom sheet in the style of the link and settings sheets): sections "Net radio and streaming" and "Tuner", one tap recalls and closes; the row follows by events. No storing of presets in this step.
4. **Tuner.** Covered by steps 1 to 3 (display, seek, presets); direct entry of a frequency is not built.
5. **More device settings.** `YxcSettings` gets the items the devices report in addition to today's: system level `dimmer` (range from the system `range_step`, -1 = automatic), `auto_power_standby`, `network_standby`; zone level tone control (mode list, bass / treble ranges), dialogue level, dialogue lift, subwoofer volume, surround decoder type, and the switches pure direct, enhancer, bass extension, extra bass, adaptive DRC, clear voice, 3D surround (each only if named in the zone's `func_list`, with the checks `(value - min) % step == 0` and inside the reported range, like the equalizer). The captured devices list only dimmer and auto power standby of these, so the rest is covered by tests with invented features and has to be tried on a device that lists them. Not built: speaker A / B, party mode, HDMI, Bluetooth, clock / alarm, network setup, firmware.
6. **Documentation:** README / docs/index.md (MusicCast section), docs/tech.md (the real answers of step 0, as built), CHANGELOG.

### Security and trust rules for slice 5 (in addition to the rules above)
- Everything a device sends here is text for display: track, artist, album, station, RDS text, preset names pass `YxcNames.clean` and a length cap (a track line at most 200 characters, a preset name at most 100, at most 100 presets); album art is not loaded in this step (no new fetch of a device-supplied url).
- Actions decide on a fresh read, never on an event: the play info before a source action, the preset list right before a recall.
- Rates: the monitor's debounce stays; source actions are serialised and a seek / next burst is limited (one in flight, further taps within 300 ms are dropped).
- Nothing new is exposed to the network; no new permission.

### Open (answered by step 0)
Which `attribute` bits mean which control; the band values and call shapes of the tuner presets; whether `setPlayback` works while a group client; what `getPresetInfo` returns on a device without presets; whether the UPnP transport and `setPlayback` disturb each other.

### Answers of step 0 (2026-11-04, details in docs/tech.md section 12)
- `attribute` is a bit mask of the controls (bit 0 play, 1 stop, 2 pause, 3 previous, 4 next, 5-7 shuffle, 8 repeat; a reading of the captures): the net radio source has only play and stop, and pause / next / previous are accepted with code 0 but do nothing. Controls are therefore offered by the bits and, for repeat / shuffle, by `repeat_available` / `shuffle_available`.
- Row play / pause / stop to the device's source: pause is not available on net radio (hide or disable it), stop and play work.
- A recall of an empty preset slot is accepted without effect, so only non-empty slots of a fresh list are offered; tuner presets are listed per band (`band` required), position = number.
- Seek starts a scan on its own (`auto_scan`), shown as "scanning"; `setFreq` with the band `dab` is refused; DAB `setDabService?dir=next|previous` works while the band is `dab` (refused with 2 in FM).
- Now playing: net source "station (artist field) · stream title (track field)"; tuner "FM 87.55 MHz · RDS text" or "DAB service · dls". The play info of the source that is not selected is stale: the zone input and its `play_info_type` decide.
- Tuner controls (seek, DAB service) only while the zone input is the tuner: with another input the calls are accepted and do nothing. Whether a tuner preset recall switches the zone to the tuner is open (to be checked with `main/getStatus` after a recall).
- Presets of the network source are what `netusb/getPresetInfo` lists; on the owner's device it lists none (the favorites of the manufacturer's app are in the Airable service, not in the preset slots). The sheet shows the net section only when the list has entries. A "recently played" list (`netusb` function `recent_info`) is a candidate for the net radio section; to be captured first.
- The net radio section of the presets sheet can use the recently played list (`recent_info`): only the entries of the radio and streaming inputs (not `server`, not `unknown`; the server entries are tracks of UPnP servers and often yaacc's own). Recall with `netusb/recallRecentItem`, to be tried first. A tuner preset is recalled after the zone input was set to the tuner (the recall may not switch it).
- `netusb/recallRecentItem?zone=main&num=<n>` works and switches the zone input to the entry's input (measured): the recently played shortcut is built (radio and streaming entries only).
- Tuner band switch: `tuner/setBand?band=fm|dab` works (measured); a small FM / DAB button on the row, only if the device lists both bands in `getFeatures` (source capabilities, read once).
- Seek like the manufacturer's app (owner, 2026-11-04): a short tap steps the frequency, a long tap scans forward / backward (to be verified: `tuning=up|down` for the step and `tuning=cancel` for the scan before it is built).

