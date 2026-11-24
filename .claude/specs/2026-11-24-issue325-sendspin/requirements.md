# Sendspin support (#325): a proof of concept first, then server and client

Status: DRAFT for the owner's review (2026-11-24). Nothing is implemented. Issue #325, "poc sendpin Support": "Take a look if it's possible to support sendspin". This spec replaces the draft of 2026-11-09 (moved here from `.claude/specs/2026-11-09-sendspin/`, which carried no issue number and was written before the Snapcast and FCast work existed). Background and the first evaluation of the alternatives: `.claude/specs/2026-11-09-issue319-snapcast/notes.md` (Discussions 4 to 6).

Normative source: the Sendspin specification, `github.com/Sendspin/spec`, now pinned at commit `2cfe197` (read first-hand on 2026-11-24; the draft of 2026-11-09 was pinned at `c281aeb`); wire `version` 1. Section names below refer to its `README.md`.

## Problem Statement
YAACC links and controls MusicCast devices because Yamaha made them flexible: devices can lead or follow a group and any controller can rearrange them. Among the open protocols Sendspin (Open Home Foundation, royalty-free, used by Music Assistant, ESPHome and DIY and commercial players) is the closest equivalent: a server orchestrates players and groups, and a new server can take a player over at any time. The protocol's server role is what YAACC already is for UPnP: it knows the media, holds the queue and drives the players. Nobody ships a Sendspin implementation for Android in plain Java.

The issue asks first whether this is **possible**. Sendspin is young (the Music Assistant documentation calls it a technical preview), mandatory end-to-end encryption (a Noise handshake) is the largest unknown for a plain-Java Android app, and tight playback sync is the second. So the work starts as a **proof of concept with kill criteria**, and the full server and client roles come only after the owner has seen the PoC.

## What is different from the draft of 2026-11-09
- **YAACC has since built most of the platform parts** this spec called "shared with the Snapcast spec": the synthetic receiver pattern (`SnapcastGroupDevice`, `FCastReceiverDevice` plus a `Player` and a row binder per protocol), the PCM tap and format conversion (`de.yaacc.multiroom.feed`), an `NsdManager` discovery stack (`SnapcastNsd`, `FCastNsd`) and a player-side audio engine for a synchronized stream (`de.yaacc.multiroom.client`: `AudioTrackSink`, `DriftController`, `FrameCorrector`, FLAC through `MediaCodec`). See `design.md`, Context. The open "inventory" and "ExoPlayer capture sink" spikes of the draft are answered by this code and are replaced by smaller checks.
- **The server role does not need the Kalman time filter.** That filter and the `client/time` bursts belong to the *client* (player). A server only answers `server/time`. The filter moves to the player phase.
- **Upstream changed a little** between `c281aeb` and `2cfe197`: a client sends its first `client/state` once its time filter's `count` is at least 1 (not "converged"); clock-sync bursts feed the filter only a burst's best measurement; pairing windows are defined for dynamic and static codes; sync accuracy distinguishes steady state (10 s after the last disturbance) and adds soft correction; metadata strings have byte limits. None of it changes the PoC; the player phase and the pairing phase take the new wording. The wire `version` is still 1.
- **The owner's Snapcast and FCast decisions are the precedent** for settings, defaults, security reviews and the test rig (`tools/snapcast-rig`, `tools/fcast-rig`).

## PoC questions (what the proof of concept must answer)
- **Q1 Crypto.** Can plain Java on Android (minSdk 27) complete the Noise `KKpsk2` handshake with a reference player, under the Sentinel PSK and under a pairing PSK, using only vetted library primitives? What does the dependency cost (size, license, 7-day rule)?
- **Q2 Transport.** Is a small in-house WebSocket subset enough and safe, or is a library better?
- **Q3 Server sync.** Does a stream of timestamped PCM chunks from YAACC's own player keep a real Sendspin player in sync for 30 minutes by the owner's ears (and, where the player reports it, by its own timing numbers)?
- **Q4 Fit.** Does a Sendspin player fit the receiver list and the player service without a new abstraction (the FCast and Snapcast pattern), and what does it touch?
- **Q5 Interop.** Which real players and servers does the owner have or can have (reference command line player, ESPHome player, Music Assistant), and do they accept YAACC as a server, unpaired with approval and with the Pairing PSK?
- **Q6 (only after the PoC gate) Player sync.** Can the phone itself be a player within the protocol's accuracy rules (+-1 ms steady state) with the existing audio engine and a new time filter?

The outcome of the PoC is a go / reduce / stop note in `decisions.md`, written with the owner.

## Goals
PoC goals (stage P, small, in this order; see `tasks.md`):
- P1. Handshake and session to a reference player in plain Java: WebSocket, Noise, `client/init`..`server/hello`/`client/hello`/`server/activate`, `server/time`, `client/state`, then audio.
- P2. Play from YAACC to one Sendspin player: the phone's current queue, PCM, play/pause/stop/next/seek, volume and mute, as for other receivers; the player appears in the receivers list (found by mDNS `_sendspin._tcp`, or typed in) with its state.
- P3. Pairing PSK (token paste) so that players which refuse unpaired access can be used; approvals and pairings can be listed and revoked.

Product goals (after the PoC gate):
- G1. Discovery and receiver rows with live state for several players (available, volume, mute, group role).
- G2. Linking like MusicCast: players of a group share one stream and one queue; link, unlink, "Play on" a member plays on its group; group volume and mute; per-player output delay.
- G3. Secure by the protocol's rules: Noise end to end, pairing, operator approval, a visible list of paired and approved players that can be revoked.
- G4. The phone as a Sendspin player (opt-in listener, advertised on the LAN, driven by any compliant server, in sync as far as Android audio allows), with one player endpoint that either YAACC's own server or a remote server drives.
- G5. (Later) controller, metadata and artwork roles; code-based pairing (CPace); FLAC and Opus; the source role.
- G6. A reproducible test rig and pure-Java conformance tests, so that interoperability is tested, not assumed.

## Non-Goals
- Snapcast (own spec, shipped), Songcast, or a UPnP/OpenHome facade for Sendspin groups.
- The visualizer and color roles; lighting; certification under the Sendspin partner program.
- Using the name Sendspin as YAACC's or a component's own product name (see Constraints).
- Hosting a Music Assistant-like library or provider system; YAACC's sources remain UPnP/DLNA servers, local files and shared content.
- Sendspin outside the LAN (relays, cloud).
- Opus, FLAC encoding and the source role in the first phases (the spec requires servers to offer FLAC and PCM; a player may list PCM only, so PCM is enough for the PoC).

## Success Metrics
- PoC gate: Q1 to Q5 answered in `decisions.md` with evidence (a run log of the handshake, the owner's 30 minute listening report, the list of touched places, the dependency cost), and a recorded go / reduce / stop decision by the owner.
- Product: interoperability with the reference command line player and an ESPHome or other real player for 30 minutes without a resync the owner can hear; link/unlink/takeover between two or more players survives reconnects; the phone as a player is driven by the reference server and by Music Assistant for 30 minutes.
- Pure parts (WebSocket framing, Noise handshake against test vectors, message codec, pairing token, pairing store, group volume, scheduler accounting) have JVM tests; the existing gate (unit tests, lint) stays green.
- A security review (general, then security, sequential) finds no open critical or warning item for the listening socket (player phase), the crypto use, the key storage and the token handling.

## Constraints
- Plain Java, no native code (owner's rule). A new dependency only where cryptography needs it and a vetted library is better than our own code (candidates in `decisions.md`); pin exact versions, 7-day rule, document in `docs/tech.md`.
- Never write our own cipher or curve arithmetic. Our code is the protocol state machines and framing; primitives (X25519, AES-GCM, ChaCha20-Poly1305, SHA-256/512, HMAC) come from the platform where it has them on API 27, else from the library.
- Trademark: "Sendspin" is a trademark of the Open Home Foundation (spec `TRADEMARKS.md`). Allowed without permission: factual statements ("implements the Sendspin protocol", "Sendspin-compatible"), the protocol name in docs and source, the protocol's identifiers (`_sendspin._tcp`). Not allowed: the name as YAACC's own product name, logos. The name is used descriptively in the UI and in docs, with a not-affiliated notice (as for FCast and Snapcast).
- The spec's license (Community Specification License 1.0) allows implementation; the reference implementations are Apache-2.0: no code is copied without checking the license, we write from the specification.
- minSdk 27 (Android 8.1): `X25519` is not in the platform JCA at this level and `ChaCha20-Poly1305` is believed to start at API 28 (verify in the spike on a device); AES-GCM, SHA-256 and HMAC are available. Do not rely on more.
- Network exposure: the player role opens a listening port; it is **off by default**, enabled by the user, advertised only while enabled, hardened (message caps, timeouts, connection limits, `Origin` rejection as the spec requires). The server role opens only outgoing connections to players the user can see in the list.
- Battery: no background work for control only; wake and Wi-Fi locks only while a stream plays.
- Process (as for Snapcast and FCast): spec first; tests first for pure parts; a general review, then a security review after each group; commits as tobexyz with the issue number, no trailers; no PR unless asked; English; all new strings in the seven locales; the owner tests audio on real devices.
