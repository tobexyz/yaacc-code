# Issue #319: Snapcast support (source, controls, later a client)

Issue (owner): support Snapcast as an audio target and as a place to control multi-room audio. Background and the research behind this spec: `notes.md` (same folder).

Status: DRAFT for the owner's review. Nothing is implemented. Branch `feat/issue319`.

## Problem Statement
YAACC plays music to UPnP/OpenHome/MusicCast receivers, but it cannot use a Snapcast multi-room system, which many self-hosters already run (Raspberry Pi and ESP32 clients, a snapserver on a home server). Snapcast has a good server and a large client ecosystem, but its own Android controller/client (Snapdroid) is not keeping up: the repository's last commit is from August 2024 and bundles Snapcast 0.29.0, while the server is at 0.35.0 (checked 2026-11-09, default branch). YAACC can offer both a controller and a speaker, with the UPnP-style control plane its users know.

## Decisions (owner, 2026-11-09)
1. **Scope:** Snapcast as a target (source into a snapserver) **and** later as a client (the phone as a speaker). No dynamic linking, because Snapcast clients have no API to be moved to another server (verified in the sources, `notes.md`). YAACC does not become a snapserver.
2. **Receiver model:** one receiver per **Snapcast group**; its clients are shown as members with their own controls.
3. **Audio feed:** YAACC **adds a TCP stream to the snapserver at runtime** (`Stream.AddStream`), no `snapserver.conf` edit needed; a documented static source is the fallback if the server refuses.
4. The Snapcast name is used as a plain product name in the UI and in the README only, with a "not affiliated" notice; package and class names are neutral (`de.yaacc.multiroom`), like `de.yaacc.yxc` for MusicCast (assumption from the MusicCast trademark rule, open for veto, see `decisions.md`).

## Goals
- G1. Find snapservers (mDNS, manual address) and show each **group** as a receiver in the receivers section, with live state (connected clients, stream, volume, mute).
- G2. Play music from any YAACC source (UPnP servers, local files, shared content) to a Snapcast group: the same play/pause/stop/next/seek/queue behavior as for other receivers, within the limits of a ~1 s Snapcast buffer.
- G3. Control the server from YAACC: per-client volume / mute / latency / name, group stream selection, group mute, adding/removing clients of a group (within one server), group volume.
- G4. (Phase B) The phone as a Snapcast client: it can be a member of a group and plays in sync, controlled through YAACC's renderer (volume, mute, queue), in plain Java without native code.
- G5. A reproducible test rig (Docker: one snapserver, two snapclients) used by integration tests and by the owner for manual checks.

## Non-Goals
- Moving a Snapcast client between servers or reconfiguring clients (no API).
- YAACC as a Snapcast server; Sendspin, Songcast or other multi-room protocols (see `notes.md` for the evaluation).
- A UPnP/OpenHome renderer facade per Snapcast group for third-party controllers (possible later, not designed here).
- Authentication to a snapserver with authorization enabled (Phase A: not supported, the server answers an error that is shown; revisit on demand).
- Control of streams that are not YAACC's (switching a Spotify source, stream plugin commands): only selecting a stream for a group.
- Unit tests that need the sandbox to run a snapserver: integration tests run on the owner's machine / CI with Docker and are opt-in.

## Success Metrics
- With the rig: a track played from a UPnP server through YAACC is heard on both snapclients, in sync, for 30 minutes (Phase A, owner's ears + Snapcast's own latency view).
- All controls of G3 change the real server and follow changes made by another controller (Snapweb) within 2 s.
- Pure parts (protocol codec, RPC models, time filter, receiver mapping) have JVM tests; the existing gate (unit tests, lint) stays green.
- Phase B: a phone in a group plays within the Snapcast latency tolerance of a Raspberry Pi client (owner listens, then measured with the server's per-client latency settings).

## Constraints
- No native code, no new dependency in Phase A; Phase B only if a decoder or crypto-free helper is really needed (pin exact versions, 7-day rule).
- GPL v3 or later (YAACC) vs GPL v3 (Snapcast): compatible; do not copy Snapcast code, write the protocol from the documentation in our own words.
- minSdk 27, battery: no wake lock or foreground service for control-only use; the feed and client run only while playing.
- Plain `ws://`/TCP on the LAN; no credentials stored.
- Spec first, tests first, reviewer then security-reviewer gates (sequential, max 3 cycles), commits as tobexyz with `(#319)`, no PR unless asked, English.
