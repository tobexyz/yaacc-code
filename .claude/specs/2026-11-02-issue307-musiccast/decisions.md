
## 2026-11-02 — Slice 2 review outcome
**Context**: reviewer (3 cycles) and security-reviewer (3 cycles) on slice 2.
**Decision**: both PASS. Open suggestions kept for later: a test that really triggers the `StackOverflowError` handler (the JVM json library stops at 512 levels), re-arming the watchdog while a host name resolves (hosts are IP literals from SSDP), eviction of watched non-YXC devices, wildcard bind of the event socket, minimum interval between event-triggered reads, volume/mute re-read before the group slider (slice 4).

## 2026-11-02 — Slice 3 security review outcome
**Decision**: PASS after two cycles. Accepted for later: `clientsOf` lists devices that only claim the server's group id (row text, picker, group-volume members; the picker refuses OK until such a device is unchecked), the playback budget is "no new device after 5 s" (a started exchange can take up to 15 s), group-volume start reads share the row binder's single executor, `restoreInput` for every address a hostile server lists, a client may be refused when its real server has a stale stored role (within the 30 s poll), `group_id` / `group_name` are not length-capped, one volume thread for all devices, eviction of non-MusicCast renderers from the watched list, private-address-only discovery.

## 2026-11-04 — Slice 5: net sources, presets, tuner, more settings
**Context**: the owner asked to look at the net radio decisions and add more features since the settings sheet works so well. Slice 4 had left playback controls of network sources out; `tasks.md` kept them as a "later" item.
**Decision (owner)**: all four of now playing + source controls, presets, tuner controls and more device settings. Now playing and the source controls on the row; presets in their own bottom sheet opened by a star icon; the row's play / pause / stop control the device's own source while it plays one and yaacc plays nothing on it.
**Rationale**: the data is already read (play info, preset flags), the calls are documented, and the sheet / row patterns exist. Step 0 (real answers of the owner's devices) comes before any code against the calls, as in slice 3.

## 2026-11-04 — Source controls and the row's buttons while yaacc owns the device
**Context**: Review of slice 5 (cycle 1): while yaacc plays or has paused a track on a MusicCast device the zone input is the server input, which counts as a network source; row play / pause / stop and the previous / next / repeat / shuffle icons would then act on the device's own queue.
**Decision**: "yaacc owns the device" = the primary player on it plays or is paused. In that case the row's buttons stay with the UPnP transport and no source icons are shown (`YxcSourceControls.rowMapsToOwnSource`, `available(state, yaaccActive)`, tested). A stopped or finished player does not count. Real station names were replaced by invented ones in docs/tech.md and the fixture `b_playInfo.json`.
**Rationale**: a press must not leave yaacc's queue and the device out of step; the icons for the device's own source return as soon as yaacc has stopped.
