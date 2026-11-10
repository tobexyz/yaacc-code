# Sendspin support: YAACC as server and as client, linking like MusicCast

Status: DRAFT for the owner's review. Nothing is implemented. No issue number yet (branch `feat/issue319` carries the Snapcast spec; this spec lives beside it and may move to its own issue/branch). Background and the evaluation of the alternatives: `.claude/specs/2026-11-09-issue319-snapcast/notes.md` (Discussions 4 to 6).

Normative source: the Sendspin specification, `github.com/Sendspin/spec` (read first-hand at commit `c281aeb`, 2026-11-09; wire `version` 1). Section names below refer to its `README.md`.

## Problem Statement
YAACC links and controls MusicCast devices because Yamaha made them flexible: devices can lead or follow a group, and any controller can rearrange them. There is no open equivalent among the UPnP protocols. Sendspin (Open Home Foundation, royalty-free, used by Music Assistant, ESPHome and small DIY and commercial players) is the closest open candidate: a server orchestrates players and groups, and a new server can take a player over at any time. Nobody ships a Sendspin implementation for Android in plain Java, and the protocol's own server role is exactly what YAACC already is for UPnP: it knows the media, holds the queue and drives the players. The owner wants YAACC to be both a Sendspin **server** (play to and group Sendspin players, from the receivers list, like MusicCast) and a Sendspin **client** (the phone as a speaker that other servers can use).

Sendspin is young and niche (the Music Assistant docs call it a technical preview), so the work is staged with gates and kill criteria.

## Decisions (owner, 2026-11-09)
1. YAACC becomes a Sendspin server **and** a client, with linking like MusicCast (a server leads a group, members follow, link/unlink from the receivers list).
2. Everything else (UI patterns, receiver model, security defaults) follows the existing YAACC patterns; open points are listed with a recommendation in `decisions.md` and need the owner's confirmation before the matching group starts.

## Goals
- G1. Find Sendspin players on the LAN (mDNS `_sendspin._tcp`, plus manual address) and list them as receivers with live state: available, volume, mute, group role.
- G2. Play from YAACC to one or more Sendspin players in sync (YAACC as server): the same play/pause/stop/next/previous/seek and queue behavior as for other receivers.
- G3. Link and unlink like MusicCast: players of a group share one stream and one queue; the group shows a leader and members; "Play on" a member plays on its group; group volume and mute; per-player volume and output delay; unlink gives a solo player.
- G4. Secure by the protocol's rules: Noise end-to-end encryption, pairing (Pairing PSK first), operator approval for unpaired players, a visible list of paired/approved players that can be revoked.
- G5. The phone as a Sendspin player (YAACC as client): opt-in, advertised on the LAN, takes orders from any compliant server (Music Assistant, another YAACC), plays in sync with the protocol's accuracy rules as far as Android audio allows, volume/mute/output delay, pairing token display.
- G6. One endpoint for both roles: the phone's own speaker is a single player endpoint that either YAACC's own server or a remote server drives (the protocol's admission rules decide), so a YAACC phone can lead a group, follow another YAACC or Music Assistant, and switch between them.
- G7. (Later phase) The controller, metadata and artwork roles: show what another server plays and control it from the phone.
- G8. A reproducible test rig and pure-Java conformance tests using the reference players, so that interoperability is tested, not assumed.

## Non-Goals
- Snapcast (own spec), Songcast, or a UPnP/OpenHome facade for Sendspin groups.
- The visualizer and color roles; lighting; certification under the Sendspin partner program.
- Using the name Sendspin as YAACC's or a component's own name (see Constraints).
- Hosting a Music Assistant-like library or provider system; YAACC's sources remain UPnP/DLNA servers, local files and shared content.
- Sendspin for non-LAN networks, relays, cloud.
- Opus and the source role in the first phases (FLAC and PCM are mandatory for servers; players may list PCM only).
- Hardware features of other devices (HDMI inputs, display firmware).

## Success Metrics
- Interoperability: YAACC as server plays to the reference Python CLI player and to an ESPHome player, and the phone as player is driven by the reference server and by Music Assistant, each for 30 minutes without a resync the owner can hear (his ears, then the server's reported timing error where available).
- Linking: link/unlink/takeover between two or more players and two YAACC phones works from the receivers list and survives reconnects.
- Pure parts (WebSocket framing, Noise handshake with test vectors, message codec, time filter, scheduler, group volume, pairing token, pairing store) have JVM tests; the existing gate (unit tests, lint) stays green.
- Security review (sequential gate) finds no open critical or warning item for the listening socket, the crypto use and the key storage.

## Constraints
- Plain Java, no native code (owner's rule). New dependency only where cryptography is needed and a vetted library is better than our own code (candidate: Bouncy Castle lightweight API; see `decisions.md`); pin exact versions, 7-day rule, document in `docs/tech.md`.
- Never write our own cipher or curve arithmetic; use a library for X25519, ChaCha20-Poly1305, AES-GCM, SHA-256/512, HMAC. Our code is the protocol state machines and framing.
- Trademark: "Sendspin" is a trademark of the Open Home Foundation (spec `TRADEMARKS.md`). Allowed without permission: factual statements ("implements the Sendspin protocol", "Sendspin-compatible"), the protocol name in docs and source, the protocol's identifiers (`_sendspin._tcp`). Not allowed: using the name as our own project/product name. Therefore package and class names stay neutral (`de.yaacc.multiroom.*`), UI text says "Sendspin players" only descriptively, no logos.
- The spec's license (Community Specification License 1.0) allows implementation; reference implementations are Apache-2.0, so no code is copied without checking the license; we write from the specification.
- minSdk 27 (Android 8.1): `X25519` and `ChaCha20-Poly1305` are not both in the platform JCA at this level; do not rely on them.
- Network exposure: the player role opens a listening port; it is **off by default**, enabled by the user, advertised only while enabled, hardened (message caps, timeouts, connection limits, `Origin` rejection as the spec requires).
- Battery: no background work for control only; the player role holds wake/Wi-Fi locks only while playing.
- Process: spec first; tests first for pure parts; reviewer then security-reviewer after each group (sequential, max 3 cycles); no Gradle while a reviewer runs; commits as tobexyz with the issue number; no PR unless asked; English; the owner tests audio on real devices.
