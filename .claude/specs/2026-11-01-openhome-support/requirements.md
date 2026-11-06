# OpenHome support: requirements (issue #301)

Status: **agreed scope, 2026-11-06.** Replaces the coarse version of 2026-11-01. Decisions: `decisions.md`. Design: `design.md`.
Tasks: `tasks.md`. Source of the service descriptions: <https://github.com/openhome/ohPipeline>
(`OpenHome/Av/ServiceXml/OpenHome/`, MIT; copies as test fixtures, see `THIRD_PARTY.md`).

## Problem
Many hi-fi network streamers (Linn, Lumin, Auralic and others) offer OpenHome next to AVTransport. OpenHome keeps a real play queue
in the renderer (tracks with ids, gapless playback, repeat and shuffle on the device). YAACC plays on a remote renderer one item
after the other through AVTransport (`SetAVTransportURI`, `Play`), so the queue lives in the phone: it stops when the phone sleeps
and the renderer's own queue and modes are not used. YAACC's own renderer offers AVTransport only, so a control point that speaks
OpenHome (and Home Assistant's AVTransport path, one item at a time) cannot queue several tracks on it.

## Goals
1. **YAACC's renderer speaks OpenHome too** (both roles are in scope since 2026-11-06). The existing MediaRenderer device offers
   the OpenHome services Product, Playlist, Info, Time and Volume next to AVTransport and RenderingControl. No setting: the
   additional services do not change what an AVTransport-only control point sees. The queue lives in the app (ids per entry) and
   is played by the music player (ExoPlayer, gapless).
2. **YAACC controls OpenHome renderers** with a new `OpenHomePlayer` (control point). The playlist is owned by the device, not by the
   app: the app's playlist dialog shows and edits the device queue. Play, play all and add to playlist put the items into the
   device queue; transport, volume, mute, position and the current track come from the OpenHome services.
3. **Per-player play mode** (repeat off / all, shuffle) maps to the device's own repeat and shuffle in both directions. OpenHome has
   no repeat-one (see decisions).
4. **Fallback.** A receiver without the OpenHome services keeps working exactly as today through AVTransport. A receiver with both
   uses OpenHome by default; the user can choose AVTransport per device.
5. **Source handling.** Before queue operations the controller makes sure the device is awake (Standby off) and its source is the
   Playlist source (Product service), switching automatically.
6. **Testable without special hardware.** The renderer is tested through the real SOAP path in plain JVM tests (service harness)
   and against the service descriptions of OpenHome; the controller is tested against the YAACC renderer, then against real
   devices when a tester is available.

## Non-goals
- Songcast, Radio, Receiver, Sender, credentials and streaming-service integration (Tidal, Qobuz), pins, the OpenHome "Transport"
  service of newer sources.
- No change to AVTransport behavior, to the media server (ContentDirectory) or to the sharing code. No new permission, no new
  dependency.
- Local picked files (#305) on OpenHome receivers: not in the first release (a queue needs the address of every track at once,
  which does not fit the "one share while the item plays" rule).
- Balance, fade, volume limit and unity gain of the Volume service (answered with fixed values by the renderer, not controlled).

## Success metrics
- A control point can put several tracks into YAACC's renderer queue with `Insert` and they play one after the other, gapless,
  with the phone asleep; `IdArray`, `Id`, `TransportState`, `Repeat`, `Shuffle`, `Volume`, `Mute`, track info and time are evented.
- YAACC's controller plays a folder on that renderer through the OpenHome queue; repeat and shuffle set in the app show on the
  renderer and the other way round.
- An AVTransport-only control point sees no difference (the existing renderer tests stay green).
- A receiver without OpenHome behaves as before.

## Constraints and open questions (to verify with devices)
- Service type URNs: expected `urn:av-openhome-org:service:Playlist:1` (domain `av.openhome.org`); versions offered by real
  devices vary (Product 1 to 4, Volume 1 to 4). To be confirmed with captured device descriptions (`capture/`).
- Which services and versions third-party control points require to list the device. Unknown until tested.
- Needs a tester with real devices before release. A first test uses YAACC against YAACC.
