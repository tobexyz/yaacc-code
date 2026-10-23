# Decisions: Unified device sheet

## 2026-10-23 - Order sessions by creation sequence, not player id
**Context**: Review cycle 1 of Group 2 found design.md's "newest = highest
playerId" false. `AVTransportPlayer` ids are random
(`Math.abs(UUID.randomUUID().hashCode())`, `AVTransportPlayer.java:158`),
and local players' ids are fixed per type (`AbstractPlayer#getId()` returns
the notification id). The pick was deterministic but not "newest", and
sessions under a remote device would list in random order.
**Decision**: `PlayerService` assigns each genuinely added player a
monotonically increasing creation sequence. `PlayerRef` carries it;
`DeviceSessionGrouper` orders sessions by it, and `primarySession` picks the
playing session with the highest sequence, else the highest sequence
overall. Player id stays the identity, used only for lookup.
**Rationale**: "Newest started" is what makes the Receiver row's choice and
the sheet's order meaningful to a user. Documenting "deterministic by id"
would have been accurate, but meaningless.

## 2026-10-23 - Fix same-id player replacement in the notifier
**Context**: `MultiContentPlayer`'s id is fixed, and the mixed-content branch
of `PlayerService.createPlayer` creates a second one without shutting down
the first. `put` then silently replaces the first, so "added" never fires for
the new player, and a later `remove(id)` for the old player removes the new
one, firing "removed" for a player that's still playing. The bug predates
this work, but the Group 2 notifier's guarantees depend on it.
**Decision**: `putAndNotify` fires "removed" for the replaced player and
then "added" for the new one when `put` returns a *different* instance;
removal uses `remove(id, player)`, so only that exact instance is removed.
Whether the replaced player should also be shut down is left unchanged (a
behavior change outside this spec), and is noted as an open question.

## 2026-10-23 - Local-device tap stays a no-op only for the LOCAL_UID dummy
**Context**: The Group 2 binder made row taps a no-op for any
`LocalDevice`. yaacc's own renderer is a `LocalDevice` with its own UDN
(`<uuid>-renderer`, `YaaccUpnpServerService.java`) and yaacc controls it
through an `AVTransportPlayer`, so its tap used to open that player.
**Decision**: The no-op applies only when the device id equals
`UpnpClient.LOCAL_UID`, matching the old behavior exactly.
