# An open multimedia device: protocol landscape and a device model (idea)

Status: IDEA, not a plan. Written 2026-11-10 after a discussion; the owner wants to think about whether it is worth doing. No issue, no code. Related: `.claude/specs/2026-11-09-sendspin/` (Sendspin server/client in YAACC) and `.claude/specs/2026-11-09-issue319-snapcast/notes.md` (evaluation of Snapcast and Sendspin).

## The idea (owner)
An open-source multimedia device (Android is one platform, an ESP32 another) with an API for multi-room synchronized playback, a source selector, and per-source settings (tuner, DAB, internet radio, aux, ...). UPnP and OpenHome are known but not flexible enough. Question: is there a standard?

## Answer in one paragraph
No single standard covers sync, source selection and per-source settings together. The pieces exist separately (below). The closest thing to "flexible" is a self-describing device model: the device announces its sources and the settings of each source in a machine-readable form, and a generic controller renders them. Nothing like that is standardized for consumer audio, so it would be defined by us (and optionally proposed upstream).

## Landscape (what was checked and what was not)
Checked in the session of 2026-11-10 by web search (secondary sources unless noted); items marked (memory) are from memory only.

### Synchronized transport
- Sendspin: open, Open Home Foundation, ESP32 and C++ implementations, Music Assistant native; spec read first-hand (see the Sendspin spec). Snapcast: open, mature, source-read first-hand.
- UPnP AV: synchronized playback is in AV Architecture:2 (`SyncPlay`, `SyncPause`, `SyncStop`, AVTransport v3), needs clock-synchronized devices; no implementation found, an open-source stack lacked it in 2013.
- AES67 (PTP based, professional) (memory); AirPlay 2, Google Cast (proprietary) (memory); Bluetooth Auracast (synchronized broadcast at the radio level, no control API) (memory).

### Source selection and control
- OpenHome `Product`: a list of sources, each with type, name and visibility; sources of type Playlist/Radio/Receiver bring the matching service (Linn wiki). Closest real standard; knows nothing about tuner settings.
- Vendor HTTP/JSON APIs with inputs and groups: MusicCast (YXC, already in YAACC), LinkPlay/WiiM/Arylic (community reports say the API cannot dynamically link/unlink groups), BluOS, HEOS, SoundTouch (memory for the last three).
- Matter: the Speaker device type is On/Off + Level Control only; the CSA says a streaming speaker device type is under development; no multi-room sync spec found.
- Home Assistant `media_player` (source list, select source, join/unjoin, sound mode) as a de facto vocabulary (memory).

### Tuner, DAB, internet radio
- No network control standard.
- Android Broadcast Radio HAL: `ProgramSelector` with a primary id per technology (`AMFM_FREQUENCY`, `RDS_PI`, `DAB_SID_EXT`, `HD_STATION_ID_EXT`) and secondary ids (ensemble, frequency, alternative frequencies): a good data model to borrow.
- RadioDNS (ETSI TS 103 270): maps FM/DAB identifiers to an IP stream and metadata (hybrid radio).
- Internet radio: Icecast/ICY metadata and the community Radio Browser directory (memory, not checked).

### Describing settings generically
- AES70/OCA: open control standard with a device model, but its scope excludes consumer use.
- ESPHome native API: protobuf over TCP with optional Noise encryption; the device announces its entities (select, number, sensor, media_player ...) at runtime, so a generic client works without knowing the device. `media_player` specifics not checked.

## Proposed shape (if it is done)
Three layers, each replaceable:
1. **Audio and sync:** Sendspin (fallback Snapcast).
2. **Control and source model:** a small self-describing JSON model, carried either inside the Sendspin session as an application-specific role (the spec allows `_`-prefixed roles with binary ids 192-255, in the same encrypted, paired, group-aware connection) or over a plain HTTP/WebSocket endpoint advertised by mDNS.
3. **Facades:** an OpenHome `Product` (source list) so UPnP/OpenHome controllers, YAACC included, see the device today; a Home Assistant `media_player` mapping.

### Device model sketch (illustrative, not a format proposal yet)
```json
{
  "device": {"id": "…", "name": "Kitchen", "model": "…", "api": 1},
  "sources": [
    {"id": "fm",   "type": "tuner.fm",  "name": "FM",
     "settings": [
       {"id": "station", "kind": "program", "selector": {"ids": ["amfm_frequency_khz", "rds_pi"]}},
       {"id": "seek",    "kind": "action",  "values": ["up", "down", "scan"]}]},
    {"id": "dab",  "type": "tuner.dab", "name": "DAB+",
     "settings": [{"id": "service", "kind": "program", "selector": {"ids": ["dab_sid_ext"], "secondary": ["ensemble", "frequency"]}}]},
    {"id": "net",  "type": "radio.internet", "name": "Internet radio",
     "settings": [{"id": "station", "kind": "uri", "presets": 20}]},
    {"id": "aux",  "type": "input.analog", "name": "Line in", "settings": [{"id": "gain", "kind": "range", "min": -12, "max": 12, "unit": "dB"}]}
  ],
  "state": {"source": "fm", "volume": 40, "muted": false, "group": {"id": "…", "role": "member"}},
  "commands": ["select_source", "set", "volume", "mute", "group.join", "group.leave"]
}
```
Rules of thumb: source `type` is a small open vocabulary with a `x-` escape; settings are typed (enum, range, text, action, program, uri) so a generic UI can render any source; tuner programs reuse the Android identifier scheme; unknown fields are ignored (forward compatibility); a mapping table to OpenHome `SourceXml` types and to Home Assistant source names is part of the definition.

## Hardware notes
- ESP32: a Sendspin player exists as a library; a tuner needs an external chip (for example a DAB/FM receiver on I2C/SPI) and its driver; network audio (internet radio) works without extra hardware; memory and CPU limit codecs (PCM, FLAC, Opus subsets) and TLS/Noise.
- Android: internet radio and local files only, unless a tuner dongle or a device with a radio is used; the audio sync accuracy depends on the device.

## Is it worth doing? (for the owner's decision)
For:
- Nothing open covers sources plus per-source settings plus sync; a small model could fill the gap and works with existing projects through facades.
- YAACC already has the controller side (OpenHome, MusicCast), so a new device type would have a client from day one.

Against / risks:
- Niche inside a niche (see the adoption notes in the Snapcast notes). A new "standard" without partners becomes a private format; the pull of Home Assistant/ESPHome entities is strong, and they already describe sources and settings generically.
- Scope: a hardware product, firmware, a protocol and a controller are four projects; the model itself is the small part.
- Dependencies on young protocols (Sendspin technical preview).

Cheaper alternatives to compare before deciding:
- **A. ESPHome + Sendspin + Music Assistant today:** an ESPHome device with a `select` entity for the source and a `number`/`select` for tuner settings gives source selection and settings in Home Assistant without any new protocol; YAACC could read the ESPHome native API as another controller, if desired.
- **B. OpenHome facade only:** source list via `Product`, settings via vendor extension; works with existing controllers, no new spec.
- **C. Propose the model upstream to Sendspin** as an optional role instead of building a separate protocol.

Suggested way to decide without much effort: (1) try alternative A on a single ESP32 and see what is missing; (2) only if the gap is real (settings per source that no existing entity type can express, or groups that Home Assistant cannot see), write the model as a formal proposal and ask the Open Home Foundation whether a role fits; (3) build the device only after that.

## Kill criteria
- If alternative A covers the owner's use cases, stop.
- If upstream (Sendspin/ESPHome) declines or ignores a model proposal and no partner devices appear within a reasonable time, keep it as a private format for YAACC only or drop it.

## Open questions
- Who is the user of the device: the owner's own rooms, hobbyists, or a product? This decides how much standardization is worth.
- Is a tuner (DAB/FM) a hard requirement, or is internet radio enough?
- Must third-party controllers (Home Assistant, UPnP apps) work, or only YAACC?
- Does the device play on its own when no server is present (a standalone radio), or always as a network player?
