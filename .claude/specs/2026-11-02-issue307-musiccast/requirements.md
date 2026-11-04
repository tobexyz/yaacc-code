# Issue #307: support the Yamaha MusicCast API

Issue (owner): "Add support for the musiccast API and allow linking of devices directly within the app."

Status: DRAFT for discussion. Nothing is implemented. Same root as feat/issue305 (feat/issue299 with feat/issue296 merged in), without the #305 spec.

## Problem Statement
Yamaha MusicCast devices are found by yaacc as ordinary UPnP media renderers, but everything specific to MusicCast (linking devices into a group for synchronized multi-room playback, power, inputs, sound programs, ...) needs the MusicCast app. The owner wants to link devices directly in yaacc.

Playing on several receivers in yaacc today sends the same URL to each one, so the devices drift apart. With a MusicCast group only the group server gets the track and its clients play in sync.

## Decisions (owner, 2026-11-02)
1. The owner has several different MusicCast devices and can test with them.
2. Scope: since an implementation for Home Assistant exists (the `yamaha_musiccast` integration on the open-source `aiomusiccast` library), supporting all the features it supports should be easy; the goal is feature parity with it, in phases.
3. UI: linking is done directly in the device sheet. Linked devices may additionally be displayed as an extra virtual device (the group).
4. (answers of 2026-11-02) The concrete devices of the owner do not matter: everything is derived generically from what a device reports (`getFeatures`, `getStatus`, `getDistributionInfo`); no model tables. The owner's devices are used for testing.
5. Sources: Yamaha's official API documents are not available; the research relies on the open-source libraries (`aiomusiccast`, MIT license; `pyamaha`, MIT license; the Home Assistant integration, Apache-2.0) as a description of the behavior. No code is copied.
6. Order: option A first (marker display, link / unlink in the sheet), then option B (the group as a virtual device).
7. Group volume: the group slider propagates the volume to the devices of the group. If a change of the server's volume is already propagated to the clients by the system, the server's slider is the group slider; otherwise a second slider "group volume" is added on the server's row. (To be found out with real devices in P1/P3.)
   **Result (2026-11-02, real devices):** a volume change sent to the server through the API does NOT reach a linked client, and a change of the client does not reach the server (server 31 -> 34 with the client staying 28, client 28 -> 31 with the server staying 31). The manufacturer\'s app, however, moved both volumes together, so the app propagates by itself. Therefore yaacc has to propagate: the second "group volume" slider on the server\'s row applies a relative change to every member of the group.
8. P4: settings per device (a settings screen built from what the device reports).
9. Playing on a linked client sends to its server automatically, with a hint; the receiver picker shows it in an extended title (for example "Kitchen (plays via Living room)").
10. Linking / unlinking while yaacc plays is allowed; the playback is interrupted.

## Goals
- Recognize MusicCast devices among the UPnP renderers and talk to them over the Yamaha Extended Control (YXC) HTTP/JSON API.
- Link and unlink devices (MusicCast groups) in the device sheet; show which devices are linked.
- Playing on a linked group plays in sync (send the track to the group server only).
- Phase by phase the features of the Home Assistant integration: power, volume, mute, input, sound program, playback controls, switches and numbers (equalizer, subwoofer volume, dialogue level, pure direct, enhancer, ...), link control (audio delay / quality).
- Status follows the device: UDP events with polling as the fallback.

## Non-Goals
- No MusicCast cloud, no streaming-service logins, no network / Wi-Fi / firmware setup of the device, no clock / alarm.
- No MusicCast support for devices that do not announce the YXC control URL.

## Success Metrics
- On the owner's real devices: link two devices in the sheet, play a track, both play in sync; unlink; the sheet follows changes made with the MusicCast app (events).
- Unit tests for the client, the model and the group logic against a fake YXC server with recorded JSON from the real devices; existing tests stay green.

## Constraints
- Yamaha's official API documents are not yet read; details (limits per model, zones, firmware differences) must come from them and from real device captures.
- Rules: license headers, tests for pure logic, 0 lint findings, commits carry `(#307)`, English, no new dependency unless needed (HTTP client exists).

## Trademarks and naming (owner, 2026-11-02: be careful with trademarks)
"Yamaha" and "MusicCast" are trademarks of Yamaha Corporation (other names in this spec, such as Home Assistant, belong to their owners). Rules for the code, the docs and the app:
- **Nominative use only:** the names appear only where they are needed to say which devices / which protocol are supported ("works with Yamaha MusicCast devices", "Yamaha Extended Control API"). Never as the name of a yaacc feature, screen, package, class prefix or the app.
- **No implied affiliation:** yaacc is an independent project. The user documentation (docs/index.md, store descriptions if any) gets this notice where the support is described: "Yamaha and MusicCast are trademarks or registered trademarks of Yamaha Corporation. YAACC is an independent project and is not affiliated with, authorized or endorsed by Yamaha."
- **No assets:** no Yamaha / MusicCast logos, icons, product photos, fonts or colour schemes; the app uses its own icons and generic wording in the UI ("Link devices", "Group", "Linked with ..."). The brand name is shown only where it identifies a device (for example the manufacturer text the device itself reports).
- **Neutral technical names:** the package and classes use the protocol abbreviation (`de.yaacc.yxc`, `YxcClient`, ...), not the brand.
- **No copying of Yamaha's documents:** the API specification documents are Yamaha's copyrighted material. Describe behavior in our own words; do not paste their text, tables or examples into the repository. Check the terms of use of the documents before relying on them (open question 2 in design.md).
- **Open-source libraries:** `aiomusiccast` and the Home Assistant integration were read to understand the behavior. No code is copied; if any code, constants lists or fixtures were ever taken from them, their license (and attribution) must be respected first.
- **Test fixtures:** JSON captured from the owner's own devices is scrubbed before it is committed (MAC addresses, serial numbers, device and network names, IP addresses).

## Open Questions
See the end of design.md (what is still open after the decisions above).
