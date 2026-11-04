
## 2026-11-02 — Slice 2 review outcome
**Context**: reviewer (3 cycles) and security-reviewer (3 cycles) on slice 2.
**Decision**: both PASS. Open suggestions kept for later: a test that really triggers the `StackOverflowError` handler (the JVM json library stops at 512 levels), re-arming the watchdog while a host name resolves (hosts are IP literals from SSDP), eviction of watched non-YXC devices, wildcard bind of the event socket, minimum interval between event-triggered reads, volume/mute re-read before the group slider (slice 4).

## 2026-11-02 — Slice 3 security review outcome
**Decision**: PASS after two cycles. Accepted for later: `clientsOf` lists devices that only claim the server's group id (row text, picker, group-volume members; the picker refuses OK until such a device is unchecked), the playback budget is "no new device after 5 s" (a started exchange can take up to 15 s), group-volume start reads share the row binder's single executor, `restoreInput` for every address a hostile server lists, a client may be refused when its real server has a stale stored role (within the 30 s poll), `group_id` / `group_name` are not length-capped, one volume thread for all devices, eviction of non-MusicCast renderers from the watched list, private-address-only discovery.
