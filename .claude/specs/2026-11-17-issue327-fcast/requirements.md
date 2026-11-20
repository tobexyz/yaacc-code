# Issue #327: FCast receivers as targets

Issue (owner): "allow cast to Fcast receivers" (https://fcast.org/, https://github.com/futo-org/fcast). Background and the facts behind this spec: `notes.md` (same folder). Design: `design.md`. Decisions and open questions: `decisions.md`. Tasks: `tasks.md`.

Status (2026-11-20): spec on branch `feat/issue327`; Group 1 (research, test fixture, test receiver rig) is built, the app code (Groups 2 to 6 for v3, Group 7 for v4) is not. The owner decided D1 to D12 on 2026-11-20 (`decisions.md`), D13 to D15 followed; the questions Q2 to Q8 are answered too (D13 test rig, D14 trademarks); no question is open.

## Short answers to the owner's questions
- **Can the receivers be discovered and shown as cards in the receiver list?** Yes. FCast receivers announce themselves by mDNS as `_fcast._tcp` (the port is in the record), and a receiver can also be added by hand with address and port (default 46899). Each receiver becomes one card in the receiver list, built the same way as the Snapcast group cards (a synthetic device that is never registered with Cling).
- **Can YAACC stream to them?** Yes. FCast is a "push a URL" protocol like UPnP AVTransport: YAACC sends the receiver the URL of the item (from a UPnP server or from YAACC's own content server) plus its MIME type, and then controls playback (pause, resume, stop, seek, volume, speed) and follows the receiver's state. The queue, next, previous, repeat and shuffle stay in YAACC, as for the other receivers.
- **Can a card have a settings option?** Yes, but with a limit that the protocol sets: **FCast has no way to read or change a receiver's own configuration** (that is local to each receiver). The settings sheet of the card therefore offers what the protocol can control and know: device information, volume (mute is emulated), playback speed, a connection check, removing a hand-added receiver and a troubleshooting trace; the later protocol version 4 adds audio track and subtitle selection and the receiver's capabilities. See `design.md`, "The settings sheet".

## Problem Statement
YAACC plays to UPnP/DLNA renderers, OpenHome, MusicCast and Snapcast. FCast is a young, open, MIT-licensed casting protocol from FUTO with receivers for Android TV, phones and desktops (and the Grayjay app as a sender), so a growing group of self-hosters has FCast receivers on their TVs and no way to send their own library to them from YAACC.

## Decisions (2026-11-17 proposal, confirmed by the owner on 2026-11-20 as recorded in `decisions.md`)
1. **Scope:** YAACC as an FCast **sender** only (controller side). YAACC does not become an FCast receiver, and does not use FCast's screen mirroring or its "companion" resource transfer.
2. **Protocol versions (owner, 2026-11-20):** phase 1 speaks **v3** (plain JSON over TCP, no new dependency). **v4** (TLS 1.3 with a pinned key, FlatBuffers) follows as phase 2 on devices whose Android version supports it; **v4 is preferred and v3 is the fallback** (a receiver that is v3 only, or a device without TLS 1.3). **The minimum Android level (27) does not change.**
3. **Receiver model:** one receiver (one card) per FCast receiver. Not grouped, no multi-room: FCast has no group concept.
4. **Streaming model:** the receiver fetches the URL itself; YAACC sends one `Play` per item and drives the queue (no playlist push in phase 1).
5. **Naming:** "FCast" appears as a plain product name in the UI and docs with the disclaimer that the trademark policy requires; no FCast logo as an icon; package name neutral or at least not a published name (open, see `decisions.md` D5).

## Goals
- G1. Find FCast receivers on the network (mDNS), and add them by hand, by the name of the device, by a pasted connection URL or by scanning the QR code that the receivers show (D16), and show each as a card in the receivers list, with live state (name, reachable or not, idle / playing / paused, volume).
- G2. Play music, video and images from any YAACC source (UPnP servers, local files, shared content) on an FCast receiver with the same play / pause / stop / next / previous / seek / queue behaviour as for other receivers, within what FCast offers.
- G3. Control from the card and from the player screen: volume (and the emulated mute), seek, speed, with the state following changes made by another sender or on the receiver itself.
- G4. A settings option on the card, like the other receiver types have, showing and controlling what the protocol exposes (see above).
- G5. A fake receiver in the tests, a **test receiver rig for a notebook** (`tools/fcast-rig/`, D13) and a documented way to check with the real desktop receiver; the tests do not bind fixed ports.

## Non-Goals
- Being an FCast receiver, screen mirroring, the companion (`fcomp://`) resource protocol, playlist push (`application/json` containers), WHEP live streams.
- Receiver-side configuration (not in the protocol).
- Phase 1: v4 (TLS, FlatBuffers, tracks, subtitles, queue messages), QR code (`fcast://r/`) pairing.
- Grouping receivers or synchronous multi-receiver playback.
- Authentication: FCast v3 has none; YAACC stores no credentials.
- Controlling the receivers' key events (remote control of their UI).

## Success Metrics
- With a real FCast receiver (Android TV receiver and the desktop receiver), a track, a video and a picture played from a UPnP server through YAACC are shown on the receiver; pause, resume, stop, seek, volume and speed work; the end of a track starts the next one.
- A second sender changing play state or volume on the same receiver shows in YAACC's card within 2 s.
- Pure parts (codec, framing, message models, state logic, directory, naming) have JVM tests; the existing gate (unit tests, lint) stays green. The new tests need no network and no fixed port.
- Discovery finds a receiver on the owner's network, or the discovery report says why not (Wi-Fi, multicast lock, permission, what was browsed), like the Snapcast report.

## Constraints
- No native code and no new dependency in phase 1 (the platform's `org.json`, `java.net.Socket`, `NsdManager`). Phase 2 may add a FlatBuffers runtime (pinned exact version, 7-day rule, licence check).
- minSdk 27: v3 works on all levels; TLS 1.3 (v4) exists in the platform from API 29, see `design.md`.
- Battery: no wake lock, no foreground service for browsing; a connection exists only while the receiver is used or its sheet is open (`design.md`, "Connection lifecycle").
- Plain TCP on the LAN in phase 1; this is the protocol's v3 behaviour and is stated in the docs. No credentials stored.
- GPL v3 or later (YAACC) and MIT (FCast) are compatible; the protocol is written from the documents in our own code, no copied sources.
- **Trademarks (owner, 2026-11-20: text only, respect all trademarks):** brand names (FCast, Snapcast, MusicCast, Yamaha, Chromecast and others) appear as plain text names only, used descriptively and with the disclaimers the owners ask for; no logos or icons of third parties, no name that suggests an official product. FUTO's policy (notes.md, "Brand rules"): disclaimer in About and README, no logo as icon, descriptive names only.
- Spec first, tests first, then reviewer and security-reviewer gates (sequential, max 3 cycles per group), commits as tobexyz with `(#327)`, no PR unless asked, English, the owner runs the whole suite and lint (agents run only the tests they add).
