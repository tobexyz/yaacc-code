# OpenHome support: requirements (coarse)

Status: **coarse spec, requirements only.** No design, no tasks, no estimate. Not scheduled; `currentspec.md` is unchanged.
Reference: https://wiki.openhome.org/wiki/OhMedia (not read yet: the page could not be fetched when this was written, so
the OpenHome service names below are from memory and must be checked against the specification before a design).

## Problem
Many hi-fi network streamers (Linn, Lumin, Auralic and others) offer OpenHome next to AVTransport. OpenHome keeps a real
play queue in the renderer (tracks with ids, gapless playback, repeat and shuffle on the device). YAACC plays on a remote
renderer one item after the other through AVTransport (`SetAVTransportURI`, `Play`), so the queue lives in the phone: it
stops when the phone sleeps, playback is not gapless, and the renderer's own queue and modes are not used. Home Assistant
also sends one item at a time through AVTransport, so a renderer role for YAACC has the same limit.

## Goals
1. **Use OpenHome renderers as receivers (control point).** Recognize renderers that offer the OpenHome services for
   product, playlist (queue), volume, info and time. Play, play all and add to playlist put the items into the device's
   queue; transport, volume, mute, position and the current track come from those services.
2. **Per-player play mode on OpenHome.** The repeat (off, all, one) and shuffle of a player (see "Play mode" in
   `docs/tech.md`) map to the device's own repeat and shuffle for an OpenHome receiver, in both directions.
3. **Fallback.** A receiver without the OpenHome services keeps working exactly as today through AVTransport. A receiver
   with both uses OpenHome by default; the user can choose AVTransport.
4. **Testable without special hardware.** The control point can be developed and tested against an OpenHome renderer that
   runs on a Linux machine (candidates: `upmpdcli`, Linn's `ohPipeline`), with the service descriptions and event streams
   recorded as fixtures for unit tests.

## Non-goals
- Phase 1 does not make YAACC an OpenHome renderer (a later phase, only if there is demand and a tester).
- Songcast (sender/receiver), credentials and streaming-service integration (Tidal, Qobuz) are out of scope.
- No change to AVTransport behavior, to the media server (ContentDirectory) or to the sharing code.
- No new permission, no new dependency unless the design shows that it is needed.

## Success metrics
- Playing a folder on an OpenHome renderer puts all tracks into its queue and keeps playing with the phone asleep.
- Repeat and shuffle set in the app show on the device, and the other way round.
- The same screens and buttons work for AVTransport and OpenHome receivers; the user is not asked which protocol it is.
- A receiver without OpenHome behaves as before (the existing tests stay green).

## Constraints and open questions
- The receiver model assumes AVTransport and RenderingControl throughout; a receiver capability layer is probably needed
  first (design question, not a decision).
- Needs a Linux OpenHome renderer for development and a tester with real devices before release.
- Open: which services are the minimum (queue, volume, transport state, track info)? How are track ids and the queue
  kept in step with the app's own playlist dialog? Which fixtures can be recorded and shared?
- Open: ticket in the repository (issue number) once the scope is agreed.
