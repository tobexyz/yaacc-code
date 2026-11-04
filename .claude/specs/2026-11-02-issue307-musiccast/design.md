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
