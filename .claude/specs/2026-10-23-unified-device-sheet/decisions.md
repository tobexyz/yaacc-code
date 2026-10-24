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

## 2026-10-23 - One shared RendererStatusMonitor for the tab and the sheet
**Context**: design.md assumed an app-wide `RendererStatusMonitor` instance
existed for the sheet to reuse. It didn't: the Receiver tab owned its own.
**Decision**: `RendererStatusMonitor.getShared()` with subscribe/unsubscribe,
starting on the first subscriber and stopping on the last; the Receiver tab
was switched to it too, despite phase 1 promising "tabs unchanged".
**Rationale**: two monitors polling the same renderers when both screens are
alive would double the network traffic. Review cycles 1-2 of Group 3 found
the tab's behavior equivalent (slightly stricter about stopping), and the
bookkeeping is unit-tested in `MonitorSubscriptions`.

## 2026-10-23 - UpnpClient keeps player-list listeners across service rebinding
**Context**: the sheet can open before `PlayerService` is bound, and the
service can rebind; listeners registered directly on the service would be
lost.
**Decision**: `UpnpClient.addPlayerListChangeListener` stores the listeners
and re-attaches them when the service (re)binds, with no duplicates (checked
in Group 3 review cycle 1).

## 2026-10-23 - Follow-ups noted, not in this phase
- The sheet doesn't refresh when receivers appear or disappear on the
  network while it's open (status of known receivers does update).
- After rotation, the sheet returns to its initial half/full position.
- One slow-answering renderer stalls status polling for every receiver
  (security review Group 3): `RendererStatusMonitor` polls on a single
  thread. This predates the branch (the tab's monitor was single-threaded
  too); now the tab and the sheet share that one thread. Play/pause/stop
  are unaffected. A per-device timeout or a small pool would fix it.
- Hardening outside this spec (security review Group 2): drop remote devices
  that claim `LOCAL_UID` at registration; the unused mutable
  `PlayerService#getPlayer()`; `IconDownloadTask` accepts any URL scheme
  with no size cap.

## 2026-10-24 - Classic vs. new layout: separate activities, chosen by a setting
**Context**: asked whether the old and new layout could be switched, maybe
in settings. `TabBrowserActivity` is a thin shell (tab bar, pager); the
browsing behavior lives in four fragments that reference it
(`instanceof TabBrowserActivity`, `setCurrentTab(BrowserTabs.X)` in ten
places). Phase 1 changed shared fragments only, so an activity-level switch
would change nothing yet.
**Decision** (direction agreed, build deferred to phase 2):
- The existing four-tab activity and its fragments stay as the "classic"
  layout; the new layout gets its own activity and fragments (phase 2 needs
  them anyway, since the server list moves into the browser).
- A list preference "Layout: Classic tabs / New" in the **normal settings
  screen** (not an advanced/developer section).
- A tiny invisible launcher activity holds the launcher and Android TV
  entries and starts the chosen activity; the three internal launches
  (`Yaacc.java`, `PlayerService.java` x2) go through one helper.
- Whether the classic activity keeps the receiver bar and sheet is open
  (PRD open question 4); the owner wants to try the phase 1 behavior first.
- Not renaming `TabBrowserActivity` is proposed; "Classic" would appear only
  in the settings UI and docs. Final naming not yet decided.
**Rationale**: avoids decoupling tab order from the visible list, and avoids
copying fragments that would then need every later fix twice.

## 2026-10-24 - Phase 2 scope: mini-player now, phase 3 folded into the new layout
**Context**: with classic and new as separate activities (previous entry),
"remove the Receiver/Player tabs" no longer touches classic.
**Decision**: the new layout has no Receiver/Player tabs from the start and
ships with a mini-player bar (owner request, 2026-10-24). Phase 2 and the old
phase 3 are one piece of work (Groups 5-10 in tasks.md). Pull-to-refresh is
dropped (would need `swiperefreshlayout`, a new dependency); the refresh
button stays. The share-to-yaacc handler moves to a shared helper and the
launcher trampoline owns the launcher/TV/SEND intent filters.
**Rationale**: one screen is the point of the redesign; keeping a mini-player
for later would leave the new layout without quick access to running players.

## 2026-10-24 - Phase 2 plan amended by the Group 5 research
**Context**: `docs/tech.md` "Classic and new layout" found that `Activity#getParent()` is null for a plain activity, so four of the five fragment-to-`TabBrowserActivity` branches never run; back works only for the folder pop and the Server-tab exit; the MediaProjection result belongs to the fragment hosting `BrowseDeviceAdapter`; four other activities declare `LAUNCHER` (VIEW only); the per-instance idle timer can fire after a relaunch.
**Decision**:
- Remove the four dead branches instead of wiring them (classic stays unchanged; fixing classic back is a separate owner decision). Only the live sites use `BrowserHost`.
- New layout gets its own back handling via the `OnBackPressedDispatcher`.
- Relaunch on a Layout change by comparing the stored layout in each browser's `onResume` (covers Settings, stale notification intents, launcher re-taps).
- `TabBrowserActivity` stays exported without intent filters, so existing shortcuts keep working; the launcher trampoline holds the filters.
- Extract `ShareIntentHandler`, `ShutdownTimerController` and `StreamingPermissionHelper` in Group 6 (behavior-preserving), fixing the leaks they carry.
- One process-wide idle timer.
**Rationale**: keeps classic byte-for-byte, and moves refactors that only fix leaks ahead of the new activity.

## 2026-10-24 - Process-wide idle timer is part of switching layouts (review W4)
**Context**: the per-instance background idle timer in `TabBrowserActivity` cancels only its own callbacks on resume. When the resume check relaunches into the other layout, the finishing instance starts a 5-minute `Yaacc.exit()` that the new activity never cancels.
**Decision**: one process-wide idle timer in Group 6, used by both activities. In the single-instance case classic behaves identically; the rotation case is fixed as a side effect.
**Rationale**: required for the layout switch to be safe, so it is not optional scope; it is flagged to the owner anyway as a change to classic.

## 2026-10-24 - Security review of Group 5 amended the Group 6 plan
**Context**: security review cycle 1 (Group 5) found two plan flaws: the launcher copied the whole incoming intent (an exported component would then forward a third-party intent, with its flags, `ClipData` URIs, nested intents and grants, into a same-app, non-exported activity), and the resume-check relaunch would loop forever when a stored `"new"` met `NEW_LAYOUT_AVAILABLE == false` (e.g. after backup/restore).
**Decision**: fresh, allowlisted forward (MAIN and SEND text only, capped text); effective-layout comparison with a shared function plus a static relaunch guard; idle timer counts started browsers instead of relying on cancel-on-resume; static single-slot callbacks become multi-listener or compare-and-clear; MediaProjection result stays in the helper. Share handler scheme policy: http/https only (**owner decision pending**; it narrows today's behavior, but today a shared `file://` or path can be served to the LAN through the proxy). `allowBackup` and the stored-preferences exposure are a follow-up, not part of this spec.
**Rationale**: these are cheap to build in from the start and expensive to retrofit.

## 2026-10-24 - Group 6 as built: behavior changes accepted
- Share handler: only `http`/`https` text up to 2048 chars; a `ClipData` item's URI is no longer used (only its text). The scheme policy was the owner's pending decision; it stays at the secure default and is revisited in Backlog 1.
- A share waiting for receivers survives a rotation (graceful executor shutdown); a share waiting when the process is killed is lost, and a screen-capture permission result arriving after process death is ignored (it enabled nothing before either).
- The idle exit counts started browsers (one process-wide timer).
- The classic Server tab shows the running shutdown timer after rotation/tab re-creation (it showed blank before).
- `decisions.md` entry 2026-10-24 "Security review of Group 5 amended the Group 6 plan" ("comparing the stored layout") is superseded: the check compares the effective layout.
