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
- The classic Server tab now takes the shutdown timer's state from the running timer (`Yaacc`), so it is correct whenever the view is recreated; the old code restored only the remaining-time text and leaked the destroyed fragment as the single listener.
- `decisions.md` entry 2026-10-24 "Security review of Group 5 amended the Group 6 plan" ("comparing the stored layout") is superseded: the check compares the effective layout.

## 2026-10-24 - Share handler: waiting shares survive a rotation, without keeping the activity
**Context**: security review cycle 1 (Group 6) Warning: a plain `executor.shutdown()` still runs delayed tasks, contradicting "shutdown cancels pending work", and the queued task held `this::showPlayers`, keeping a destroyed activity reachable for up to ~6 s.
**Decision**: keep the graceful shutdown (a share must survive a rotation), accepted as a deliberate deviation from "cancel pending work"; the callback is held in a field that `shutdown()` clears, so the queue no longer pins the activity (and a share finishing after rotation does not switch tabs on a dead screen); at most 3 queued shares, extra ones ignored with a payload-free log. MediaProjection statics are `volatile`. Follow-ups, not in this phase: a timeout around `MediaMetadataRetriever#setDataSource`; stop logging the proxy target URI at DEBUG (`YaaccUpnpServerContentHttpHandler.java:775-777`); require `://` in `ShareUriPolicy` (Backlog 1 item 8).

## 2026-10-24 - Group 7 as built: decisions on the builder's open questions
- The in-screen back arrow at the content root moves to Group 8 (the system back already works).
- Tapping the shutdown-timer item with no configured duration opens the picker; the user taps again to start (starting with 0 would exit the app immediately, which only the classic switch can do). Revisit after the owner tries it.
- `UpnpClient` gained `removeUpnpClientListener` and a `CopyOnWriteArrayList`; `ServerListFragment` and `ContentListFragment` remove themselves in `onDestroyView` and `ContentListFragment` cancels its load tasks there. This touches classic but only stops destroyed fragments from receiving callbacks (a crash/leak fix); `PlayerListFragment` and `ReceiverListFragment` still leak and are left alone.
- About and log stay in the new menu (parity with classic via `BrowserActivitySupport.handleCommonMenuItem`).
- `ServerListFragment` is reused with `BrowserHost.providesShutdownTimer()` selecting `fragment_server_root.xml`; no separate fragment (about 150 lines not copied).

## 2026-10-24 - Group 8 as built
- Mini-player decisions live in plain Java (`MiniPlayerPicker`, `MiniPlayerPresenter`); LAN-supplied titles/names are cleaned (whitespace collapsed, control, bidi and zero-width characters removed, capped at 200 chars; ZWJ is kept so emoji sequences survive). `ReceiverBarLabel` does not strip bidi controls yet (carried-forward item).
- `ReceiverBarController` is shared by classic's `ContentListFragment` and `BrowserActivity`; it refreshes through the `UpnpClientListener` receiver callbacks plus a refresh on every start.
- Classic's portrait content layout now wraps the separator and bar in a never-GONE `contentListBottomBar`; the list anchors to it.
- Status icons in the new server layout reuse the existing `settings_local_server_*` strings; `browser_back` is new for the in-screen back arrow.
- `NEW_LAYOUT_AVAILABLE = true`; no UI exposes the setting until Group 9.
- Review cycle 1: raw invisible bidi characters in test string literals were replaced by `\u` escapes; `MiniPlayerBar.refresh()` survives a player changing under the poll; the delayed refresh is skipped after unbind; the cached receiver name is reset on unbind; the album art is marked decorative.

## 2026-10-24 - Mini-player album art: key without getIcon() (Group 8 security review)
**Context**: `AVTransportPlayer#getIcon()` queues a download on every call while the art is not cached, and the mini-player called it on each 2 s poll to build its dedup key, so a broken or hostile art URL would be requested continuously.
**Decision**: the key is `player id + art URI`; `getIcon()` is read once per key. Not stripped from titles (follow-ups): tag characters (used in some flag emoji), variation selectors, U+00AD and similar invisibles; `ReceiverBarLabel` still shows raw friendly names; `ImageDownloader` still accepts any scheme with no timeout or size cap.

## 2026-10-24 - Group 9 as built
- "Layout" `ListPreference` (key `settings_browser_layout_key`, values `classic`/`new`, default classic) is the first item under Appearance; entries/strings in 7 locales (`values/browser_layout.xml` + `strings.xml`); `BrowserLayoutValuesTest` pins the XML values and default to the `BrowserLayoutChoice` constants.
- A change applies when the user returns to a browser screen (resume check); players, `PlayerService` and the UPnP client are untouched by a switch; the idle timer counts 1 -> 2 -> 1 across the switch and never arms.
- Known and left for the owner's manual check: stale notification PendingIntents may stack a second browser instance; the setting is visible on Android TV although the new layout's D-pad reach is unverified (default stays classic); `allowBackup` (backup can restore a stored `new`) remains an open follow-up.

## 2026-10-24 - Owner feedback after the first on-device check (Group 11)
1. The toolbar timer was shown twice (icon and overflow entry): the overflow entry is removed; the icon stops a running timer and otherwise opens the picker, whose confirmation starts the timer.
2. The mini-player must show all players, not only the featured one: one compact row per player, oldest first like the sheet, capped height with scrolling.
3. Classic keeps the original static footer (provider line and receivers line, not clickable) in the content browsing and does not open the sheet; the sheet and receiver bar live in the new layout only. This answers PRD open question 4 with "no" and undoes the phase 1 footer change for classic.

## 2026-10-25 - allowBackup turned off (owner decision)
**Context**: Auto Backup (default on) copied the default preferences file, including the `proxy_link_*` entries (shared URLs, possibly with tokens), the receiver selection, SAF cache entries and the stored layout, to the user's Google account; raised in the Group 7-9 security reviews.
**Decision**: `android:allowBackup="false"` on `<application>`. Settings are re-entered on a new device (the owner: the setup is easy). No exclusion rules.
**Rationale**: simplest and safest; the privacy exposure of stored proxy links outweighs the convenience of restored settings. Not verified with a full Gradle build in this container (no SDK): a manifest merge conflict is only possible if a dependency sets the attribute itself, which none is known to.

## 2026-10-25 - Share feature scoped (Phase 3)
**Owner decisions**: (1) per-share temporary proxy, not the persistent setting; (2) keep a local file readable by holding its descriptor open (option A), copy to the app cache only for non-seekable providers; (3) every share served through the proxy is restricted to the chosen receivers' IPs and expires; (4) receivers are picked per action (share, play, play-all) in the new layout and the selection is cleared afterwards; (5) one file only at first.
**Readings to confirm**: (4) applies to the new layout only (classic keeps its tab-based selection and static footer); (3) applies to every share served through the proxy, including http(s) links, which also moves them out of the preferences (`proxy_link_*` retired).
**See** design.md "Phase 3" for the threat model and risks, and tasks.md Groups 12-16.

## 2026-10-25 - Share feature: readings confirmed, IP check becomes a setting
The owner confirmed both readings (receiver picking in the new layout only; every share served through the proxy, links included, moves to the in-memory registry). The strict IP check gets a checkbox setting, default on, so a user whose receiver fetches from another address can switch it off (random key and expiry stay). The picker title "Play on..." was proposed and not objected to.

## 2026-10-25 - Build environment: the Android build works in a fresh container
An earlier reviewer reported "no SDK, cannot build" and I repeated it; the owner corrected this. The container simply has no SDK pre-installed. Installing the Android command-line tools (https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip), accepting the licenses and installing `platforms;android-36` plus `platform-tools` into `/root/android-sdk` (AGP then downloads the build tools itself), with `sdk.dir=/root/android-sdk` in `local.properties` (git-ignored), makes `./gradlew` work. Maven Central answers some downloads with HTTP 429; re-running the build a few times lets Gradle cache what it got. Verified result for 3d6b6ea0: 337 tests, 0 failures, lint 190. To avoid redoing this per container, the SDK install belongs into the environment's setup script.

## 2026-10-25 - Phase 3 plan corrected by the Group 12 research
1. The HTTP server is not off when the "local server" setting is off (it always runs while the process lives): nothing is started or stopped per share; registry entries are served before the settings gate and the share waits until the server answers.
2. Residual confused deputy (broad media permissions/SAF grants): accepted and documented, mitigated by the type check, the per-share receiver pick that shows the file name, and the IP/expiry limits. To be confirmed by the owner.
3. `createPlayableItem` must handle `content:` URIs properly (provider MIME type, metadata through a `MediaDataSource`).
4. Clearing the receiver selection after every action breaks "add to playlist" in the new layout; proposal: it uses the picker too. Owner decision pending.
5. New `UpnpClient.clearReceiverDevices()` (fires the events the receiver bar needs); fix the mutation of the `SharedPreferences` set.
6. `Os.pread` instead of a shared `FileChannel`; `dup()` the descriptor so the provider process is not pinned; close locked against reads.
7. The old range code and the proxy handler's `new File(uri)` path are replaced for registry entries (new `ByteRangeResolver`, tested first).
8. The forward from the launcher carries one ClipData item plus the read flag (the system never checks `EXTRA_STREAM`), after verifying that both match; failures fall back to a plain launch.

## 2026-10-25 - Phase 3 plan amended by the Group 12 security review
1. Nothing is opened or registered at intake: a pending share holds only the validated URI and a cleaned name; the descriptor/copy and the registry slot come after the user's confirmation; one pending share, with a timeout; a second share while one is pending is dropped.
2. Classic has no receiver pick of its own: a shared file opens the pick-mode sheet there too (owner decision 2026-10-25, replacing the confirmation dialog).
3. `ShareIntentHandler` repeats all intake checks (the activities can be started directly); own-authority is checked through `PackageManager.resolveContentProvider` as well; API 35+ additionally verifies the sender's grant via `getInitialCaller()` in the LAUNCHER before the forward (in the browser the initial caller is the launcher, so the check cannot live in the handler).
4. Registry eviction of released entries, non-blocking close, clamped offsets, concurrency/idle caps, IP setting default-true everywhere, loopback only for a chosen local receiver.
5. Docs: no exploit-level detail about the existing released proxy behavior in the public docs (the passage in docs/tech.md was softened); document that the key is visible to LAN control points. Recommendation to the owner: ship the share hardening (http/https only) as a patch release from develop before this branch is merged, since 5.4.0 still has the old behavior.

## 2026-10-25 - Owner answers: classic shares and "add to playlist" both open the picker
1. A shared file in the classic layout opens the same "Play on..." pick-mode sheet (no confirmation dialog, no refusal). Normal classic browsing still never shows the sheet. In classic the selection is not cleared: it is the initial tick state and the previous selection is restored once the playback has started (assumption, say if you want "clear" there too).
2. "Add to playlist" in the new layout opens the picker, adds to the chosen receivers' playlists and clears the selection afterwards.

## 2026-10-25 - Group 13a as built: eviction is a separate method
`ShareRegistryTest#releasedEntryStillOccupiesItsSlotDuringTheGracePeriod` (reviewed in Group 12) expects `register()` on a full registry to return null even when a released entry is still in its grace period, while the Group 12 security review wants such entries evicted before refusing. Resolution without touching the reviewed test: `ShareRegistry.evictOldestReleased()` (removes the released entry with the earliest release time, never an unreleased one, closes outside the lock) is called by the glue (Group 13b) when `register()` returns null; if it returns true the glue retries once, otherwise it shows the "full" feedback. Retired-key window: the last 256 keys; 8 draw attempts per register; `register` sweeps expired entries lazily (only when full).

## 2026-10-26 - Owner feedback on the picker: scope and order
The owner expected the picker already in the pulled version; it is Group 15 and not built yet (the share was the reason for scheduling it after Groups 13/14). Clarification: "remove the selection from the receivers" also means the NORMAL sheet in the new layout has no selection checkboxes any more (they exist only in pick mode), because the selection is no longer persistent. Order decision: keep it (13 proxy -> 14 share intake -> 15 picker). Receiver bar in the new layout: names of the receivers that currently have a running player ("No receiver playing" otherwise). Classic is unchanged.

## 2026-10-26 - Server-side seeking restored on the registry (Group 13b)
The integration had removed the "server-side time range management in proxy mode" feature (the old 3-segment URL was not unused). Owner: restore it. Built on the registry for http(s) shares only: the player rewrites to `/proxy/<deviceId>/<key>` only for a remote renderer whose per-device setting is on; the device id is validated (`[A-Za-z0-9:._-]`, 1-200) after key, lookup and IP check, and used only for the preference read, the bounded per-share state map (8 renderers/share, LRU) and the stored position read. The old position-poll call site never matched a state (key mismatch) and is not restored; pause detection stays effectively unused as before. Proxy keys are redacted in the `HttpRequestSender` SOAP traces.

## 2026-10-26 - Group 13 review fixes: release hook and idle watchdog semantics
- Shares are released when a player ends: the removal hook (`Listener.onPlayerRemoving`) runs synchronously before `onDestroy()` clears the items, `ShareKeyTracker`/`ShareLifetimeListener` remember each player's keys (also for items set after the add callback) and release a key only when no other player still uses it.
- Write-idle watchdog (`WRITE_IDLE_MILLIS` 60 s, progress = at least 16 KiB per window, sweeper every 60 s): an idle response loses its download SLOT, not its stream; if the reader resumes (a long pause is normal) it re-acquires a slot and continues, and aborts only when the per-share cap is full. A never-resuming connection stays open until the 5-minute socket timeout (HttpCore offers no handler-side close). Residual: a hostile trickling client holding all 4 slots can still crowd out a renderer; with the IP restriction on (default) only chosen receivers reach the proxy, and the key is unguessable, so this is accepted.
- Registry full: toast `share_registry_full`, fallback to the direct http(s) link. The proxy URL waits up to 3 s for the loopback HTTP server (never on the main thread). Upstream URLs and proxy keys are no longer logged (failure logs print the exception class only; SOAP/AV-transport/image logs redact `/proxy/<key>`).
- Group 14 TODO: `createPlayableItem`'s "Can't retrieve duration of media url" log still passes the exception object; remove it when `createPlayableItem` is rewritten.

## 2026-10-26 - Group 13 closed; plan changes for Groups 14/15
Security review of Group 13: PASS (no Critical/Warning). Its must-haves are in tasks.md Groups 14/15 (claim window and ownership for every registration, off-main-thread provider calls, loopback/link-local/own-address refusal for link targets, picker cleanup paths). Planning change: the SEND filters for audio/video/image move from Group 14 to the end of Group 15 so the app does not advertise a share target before the whole flow works. `ShareLogRedactor` now also redacts upper-case and percent-encoded `/proxy/` forms.

## 2026-10-25 - Group 14 security review fix W1: launch nonce instead of a second sender check
The API 35 "does the real sender hold read access" check lives in `LauncherActivity`, but `TabBrowserActivity` is exported, so an app could send it a file share directly. Chosen fix: a launch nonce (`de.yaacc.share.LaunchNonces`, pure Java). After ALL launcher checks passed (including the API 35 caller check) the launcher mints a 128-bit `SecureRandom` token and puts it into the fresh forward intent as the extra `de.yaacc.extra.LAUNCH_NONCE`. `ShareIntentHandler` (used by `BrowserActivity` and `TabBrowserActivity`) accepts a FILE intake only if `consume(token)` succeeds; otherwise the intent is consumed (ClipData and extras cleared as before) and dropped with a payload-free log, before any provider call. The text path is unchanged and needs no token.
- Numbers: valid 30 s (`ShareLimits.LAUNCH_NONCE_TTL_MILLIS`), at most 8 unused tokens (oldest dropped, expired ones first), single use, constant-time compare (`MessageDigest.isEqual`, every entry compared), monotonic clock (a clock that runs backwards counts as expired), memory only in `Yaacc` (lost with the process, so a recents re-delivery after process death carries a dead token and is dropped; before, it became a new pending share and a prompt).
- Why not the alternatives: `getInitialCaller()` in the browser activities would need the same check in two activities plus a supplier into the handler, and in the browser the initial caller is the launcher for the forwarded case, so "caller is us" would still have to be trusted, which a nonce proves instead; "accept streams only when the initial caller is this app" has the same trust gap (and the system chooser case is still unverified on a device). The nonce is not forgeable by another app: it never leaves the process except inside an explicit intent to our own component, and it is 128 bits.
- The token is spent even if a later intake check rejects the share. Not unit-testable in `LauncherActivity` (an Activity); the minting is one line, the consume side and the store are covered by `LaunchNoncesTest` and `ShareIntentHandlerFileTest` (missing, forged, wrong, expired, reused: dropped; valid: accepted once). The device check of what `getInitialCaller()` returns for the system chooser stays on the Group 15 list.
- Consequence for the manifest: none. `LauncherActivity` still has only the `text/plain` SEND filter; `git diff 071fc9d2 -- yaacc/src/main/AndroidManifest.xml` stays empty.

## 2026-10-25 - Group 14 security review fix W2: one call per provider, bigger pools, thin ContentProviderClient decision
`BoundedCall` now takes an optional `ProviderCallGate` (pure Java) and a key per call. A Binder call cannot be interrupted, so the fix is to bound what one provider can pin, not to free the thread.
- Key: the package that owns the authority (`PackageManager.resolveContentProvider`, already used by the intake), so an app that declares many authorities is ONE provider; unresolvable authorities fall back to the lower-case authority (`ProviderKeys`). [Superseded 2026-10-26: all unresolvable providers share ONE fixed key, see "Group 15a" below.]
- Per-provider in-flight cap 1: a second call for a provider that has a call in flight, or is stuck, returns null at once without a thread or a provider call (the intake logs the existing payload-free line and drops the share). The entry is freed when the call returns (any outcome, including a pool refusal). [Cycle 1 text said a stuck marker expires after 10 min; superseded, see "W4" below: a permit lives exactly as long as its call.]
- Pools: 4 threads each for the intake probe pool and the confirmed-open pool (`PROVIDER_POOL_THREADS`, was 2), and at most 3 providers with a call outstanding at once (`PROVIDER_MAX_TRACKED`, the gate map is bounded by it). So one stuck provider takes one thread and one place, two stuck providers still leave one place and the pool keeps a spare thread for a call that has just returned. Residual, accepted: a provider that never returns pins its thread and its place for the life of the process (it cannot pin a second one, see W4), and three distinct such providers use up the places until a call returns. A user-visible message for this case is not added (log line only).
- `ContentProviderClient.setDetectNotResponding` was NOT used: it is a hidden API (absent from the public android-36 `android.jar`; checked with `javap`), so using it would need reflection or a non-SDK interface, and an unstable client without it does not make a hung provider fail. This needs a real device to judge and is left for the Backlog; the gate is the mitigation in this group. Tests use a latch-hung, interrupt-proof provider (`BoundedCallGateTest`, `ShareIntentHandlerFileTest`): a hung provider A does not stop B, a second share from A while A is stuck is dropped without a provider call, A stays blocked until its call returns.
- `ShareIntentHandlerFileTest#aProviderThatDoesNotAnswerDropsTheShareAndFreesTheGateForTheNext` now sends its second share from another provider: from the same (still stuck) provider it is dropped by design.

## 2026-10-25 - Group 14 security review fix W3: the copy fallback runs under a watchdog
`SharedFileOpener.copyAndHold` no longer copies on the calling (single) `fileExecutor` thread. `de.yaacc.share.WatchedCopy` (pure Java) holds the copy slot (one at a time) and runs the copy on its own daemon thread (`AbandonableCall`, a fresh thread per copy, not a shared pool); the caller waits with a timed wait of `COPY_MAX_MILLIS` (5 min).
- At the deadline the CALLER (a) closes the input from its own thread (`AssetFileDescriptor` and its stream; closing a descriptor wakes a blocked libcore read with an exception), (b) deletes the partial file, (c) releases the copy slot, and returns `TIMED_OUT` (mapped to `UNUSABLE`), so the `fileExecutor` thread and with it `intakeBusy` come free even if the worker thread stays stuck (it is abandoned). Abandoned workers still alive are counted and capped at 3 (`COPY_MAX_STUCK_WORKERS`); beyond that a new copy is turned away as `BUSY` instead of starting yet another thread.
- A late worker cannot leave anything behind: the target file is created by the caller before the worker starts and only ever opened for writing WITHOUT create (so it cannot come back after the delete); the copy loop stops at the next read return because it polls "abandoned"; a worker that already produced a held copy hands it to `disposeLate`, which closes it (and `DeleteOnCloseSource` deletes the file); nothing is registered by the worker (registration is the caller's, after `copyAndHold` returned).
- [Corrected, see S1 below] The budget is recomputed from the directory content at the start of each copy and the partial file is deleted, but that alone does not free the space a stuck worker still holds through its open output stream; the abandon path now closes that stream too.
- Not done (not asked): a "no bytes for N seconds" stall limit shorter than 5 min, and treating a stalled copy as a cancel point for the picker/`shutdown()`. While a copy runs, later probes queue behind it on `fileExecutor` and are dropped by `intakeBusy` for at most 5 min. Candidates for Group 15/Backlog.

## 2026-10-25 - Group 14 security review cycle 1: Suggestions deferred
Not done in this fix and not forgotten: (1) redirect handling for the link policy (`setInstanceFollowRedirects(false)`, at most 3 hand-followed hops through `LinkTargetPolicy`, resolve-then-connect for names; state the name/redirect residual in the Group 16 text) - Backlog/Group 16; (2) the link policy when the proxy option is off and a total deadline around the metadata reads - Backlog; (3) a re-announce of an existing pending share when an activity attaches, a cool-down after a user cancel and the short toast for a dropped share - Group 15; (4) making `intakeBusy` static - Group 15 (harmless: the pools and the pending slot are global); (5) the device checks (system chooser `getInitialCaller()`, `SecurityException` from the launcher forward, proxy URL in the MediaSession) - Group 15. Done as a cheap one-line change with a test: `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox` on every share response (`ProxyShareResponder.submit`, `ProxyShareResponderTest`).

## 2026-10-25 - Group 14 security review cycle 2: W4 stuck marker never expires, S1 output of an abandoned copy is closed
- W4. `ProviderCallGate` no longer has a clock or an expiry (`ShareLimits.PROVIDER_STUCK_MILLIS` and `markStuck` are gone). A permit lives exactly as long as its call: it is released in the `finally` of the pooled task the moment the call returns (any outcome), on a pool refusal, or at once when the caller gave up before the task began (`BoundedCall` state NEW/STARTED/CANCELLED, so a cancelled-before-start task cannot leak a place now that there is no expiry). A provider whose call is still outstanding, hung in an uninterruptible Binder call included, stays blocked however long that is, so one key can never pin more than ONE thread, however often it is asked; the 10-minute expiry would have let one package pin all four threads with four intents. Applies to both gated pools (`PROBE_CALLS` in `ShareIntentHandler`, `PROVIDER_CALLS` in `SharedFileService`).
- Invariant (`ShareLimits` comment corrected): at most 3 providers have a call outstanding (`PROVIDER_MAX_TRACKED`), so at most 3 of the 4 pool threads are ever inside provider calls and one is left for a thread that has just returned and is finishing. Residual (reworded 2026-10-26 after security review cycle 3 S5): three distinct hung providers use up all places until a call returns, OR one app whose provider cannot be resolved to a package, because all such providers share one key (since Group 15a: that is ONE place, not three); a provider that never returns holds its thread and place until the process dies. Not "three installed hostile apps" only: before 15a an app with three unresolvable authorities could use up the places alone. Tests: a stuck key stays blocked after many attempts and a simulated day, one key cannot pin more than one thread across twelve attempts while others are still served, three hung providers leave a pool thread free, the late return frees the key.
- S1. At the copy deadline the caller now also closes the worker's OUTPUT stream (held in an `AtomicReference` set by the worker; a worker that opens it after the abandon closes it itself) in addition to the input, before it deletes the file. A stuck worker therefore no longer keeps the deleted partial file's blocks (up to 512 MiB, up to 3 workers) allocated, and the directory-based budget of the next copy is no longer wrong by those bytes. Test (Linux): after a timeout with a stuck worker that had already written bytes, no descriptor of this process points into the incoming directory, while the worker is still stuck in its read. This corrects the earlier line "CopyBudget needs no extra release".
- S2 (distinct message and log counter for the stuck-copy cap, device check that closing a `ParcelFileDescriptor` stream wakes a blocked read) stays with Group 15.

## 2026-10-26 — Group 14 closed; items carried forward
**Context**: Group 14 passed both gates (general review cycle 3, security review cycle 3). The security cycle 3 Suggestions S3-S5 and the open device checks are not Warnings.
**Decision**: Carry them into Group 15 (S3: one shared provider key for providers whose package cannot be resolved, because a hung permit now lives as long as its call and the manifest has no `<queries>`; S4: make `BoundedCallTest.aFinishedCallFreesItsThread` tolerant of a worker that is not idle yet, a pre-existing test timing race, not a defect of the new state handling; S5: a distinct message when the provider gate or the copy slot is full; correct the decisions.md residual wording). The device checks stay with the owner. The SEND filters (end of Group 15) get the security re-review on the real manifest diff.
**Rationale**: Each is small, none weakens the verified fixes, and Group 15 touches the same user-visible messages.

## 2026-10-26 - Group 15a as built: pick decision, receiver bar label, selection clearing, Group 14 carry-overs
Pure-logic part of Group 15; the UI (pick-mode sheet, no checkboxes in the normal sheet, wiring in `ContentListFragment`/`ShareIntentHandler`) is 15b.
- `ReceiverPickResult.decide(confirmed, ids)` as specified by the 11 red tests, unchanged: confirmed with receivers -> start + clear afterwards; confirmed with none -> no start, no clear, `keepPickerOpen`; cancel -> nothing, and the ticked ids are never handed out. Null and empty ids are dropped; the returned set is an unmodifiable copy.
- Receiver bar (`ReceiverBarLabel`, `ReceiverBarController`): the bar shows the receivers that have a player in `PlayerService.getCurrentPlayers()` (a paused player counts, like in the mini-player; only a player that ended is gone), named like the mini-player does (`UpnpClient.getDevice(deviceId)` friendly name, else the player's name, through `MiniPlayerPresenter.receiverLabel`), cleaned with `MiniPlayerPresenter.cleanText`, duplicates merged (several players on one receiver show it once; two receivers with an identical cleaned name also show once, accepted), sorted case-insensitively, joined with "; ". The selection is NOT shown any more. There is no "+N" collapse: the old label never had one, the bar is capped at two lines and ellipsizes. Refresh comes from the player-list listener (no second poller) plus the existing receiver events. New string `receiver_bar_none_playing` ("No receiver playing") in 7 locales replaces `receiver_bar_none_selected` (the old text is removed; the tap hint "Tap to choose" is dropped, the bar keeps its "Choose receivers" click action for TalkBack). The old test "duplicate names are kept because they are distinct devices" is replaced by its opposite, because the input is now one name per player.
- `UpnpClient.clearReceiverDevices()`: stored set becomes empty (an empty stored set stays empty; `getReceiverDeviceIds()` only substitutes the local device when the key was never written) and `receiverDeviceRemoved` is fired for every receiver that was selected. It changes nothing for running players and never touches the allowed set of an existing share (fixed at registration). `getReceiverDeviceIds()` now returns a copy and `getReceiverDevices()` no longer mutates the `SharedPreferences` set (and writes back only when unknown ids were dropped). The set arithmetic is in the pure `ReceiverSelection` (tested); the event firing itself is not unit-tested (`UpnpClient` has no JVM test seam). `fireReceiverDeviceRemoved` iterates a copy of the listeners like `fireReceiverDeviceAdded`.
- S3: `ProviderKeys.UNRESOLVED` ("unresolved"): every provider whose package cannot be resolved (null, empty package name, exception, no package manager) uses that single key. Consequence: unresolved providers are served one at a time. This is the price of the bounded gate while the manifest has no `<queries>`; whether `resolveContentProvider` sees a freshly forwarded provider stays a device check.
- S4: `BoundedCallTest.aFinishedCallFreesItsThread` retries the second call for at most 5 s until the pool accepts it; same intent (a finished call frees its thread), `BoundedCall` untouched for this.
- S5: `BoundedCall.callDetailed` returns a `Result` that says whether a missing value is "refused for lack of room" (gate full, provider has an outstanding call, or pool without a free thread) as opposed to timeout/failure; `ShareFailureMessage.of(providerRefusedBusy, copySlotBusy)` maps to the new fixed string `share_busy` ("Another shared file is still being processed. Try again in a moment.", 7 locales). Used in `SharedFileService.openSource` (provider call and `Reason.BUSY` of the copy fallback; was the generic `share_open_failed`) and, new, at intake in `ShareIntentHandler.probeAndOffer` (was a log line only; a timeout or failing provider at intake stays log-only). Payload-free: the texts are fixed, the log lines hold enum names only. No counter of stuck providers was added.
- Not done here (still Group 15b or later): everything in the UI, the device checks (system chooser `getInitialCaller()`, `SecurityException` fallback, whether closing a `ParcelFileDescriptor` stream wakes a blocked read), the SEND filters.
- Test adapted because of S3: in `ShareIntentHandlerFileTest` the mocked `PackageManager` resolves nothing, so the two "another provider" tests (`aHungProviderDoesNotStopAShareFromAnotherProvider`, `aProviderThatDoesNotAnswerDropsTheShareAndFreesTheGateForTheNext`) now let `resolveContentProvider` return two different packages for their two authorities (`resolveTheTwoTestProvidersToDifferentPackages`). The assertions are unchanged; with the old setup both providers were "unresolved" and correctly shared one place.
- Verified: `./gradlew :yaacc:assembleDebug :yaacc:testDebugUnitTest :yaacc:lintDebug` green, 1314 tests, 0 failures (`ReceiverPickResultTest` 19/19), lint 189 (72 errors, 117 warnings), manifest diff against 071fc9d2 empty.
