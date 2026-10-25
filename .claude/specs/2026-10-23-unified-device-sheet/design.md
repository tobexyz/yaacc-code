# Unified device sheet - design

## Context
Today's flow uses four tabs (Server, Content, Receiver, Player). The goal is
to make Content the home screen and bring receivers, and later running
players, to it as a bottom sheet. Before designing that, I checked whether
"a running player" and "a receiver" are the same thing in the code.
They aren't.

## Findings (code check, 2026-10-23)

1. **Players and receivers are not 1:1.**
   - Remote renderer: `PlayerService.createPlayer(...)`
     (`PlayerService.java:379-384`) only shuts down an existing
     `AVTransportPlayer` with the same device **and** the same content type
     (music/image/video/multi). A renderer can therefore have several
     player objects at once, e.g. one music and one image player.
   - Local device: `LocalImagePlayer`, `LocalMediaSessionPlayer` and
     `MultiContentPlayer` are each kept as a singleton **per type**
     (`createImagePlayer`, `createMusicPlayer`), so up to three can run at
     the same time. This is intentional: "image show with background music"
     is a documented feature (`docs/index.md`).
2. **A receiver can be busy with no yaacc player at all.**
   `RendererStatusMonitor` polls `GetTransportInfo`, and remote transport
   controls go straight to the AVTransport service
   (`BrowseReceiverDeviceAdapter#executeAction`), not through a player
   object. Playback started by another controller shows as PLAYING, and
   its controls work.
3. **Existing bug:** `BrowseReceiverDeviceAdapter#getPlayerForDevice`
   returns the first match from `currentActivePlayer.values()`, which is a
   `HashMap`. For the local device with two players running, which one the
   row shows and controls is arbitrary.
4. **The Player tab has no inline controls.** It's a launcher:
   `PlayerListItemClickListener` opens the player's full activity
   (playlist, seek) through its notification intent.
5. `Player#getDeviceId()` is a reliable grouping key: `AbstractPlayer`
   defaults it to `UpnpClient.LOCAL_UID`, `AVTransportPlayer` returns the
   renderer's UDN, and `LocalImagePlayer` returns `LOCAL_UID`.

## Decision
Use one bottom sheet with one list, **grouped by receiver**:

- A **receiver row** holds the device-level controls the Receiver tab has
  today: selection checkbox, PLAYING badge from `RendererStatusMonitor`,
  play/pause/stop over the existing paths, volume and mute.
- Under it go **0..N player-session rows**, one per running yaacc player on
  that device. Each shows the player name, current title and album art, and
  tapping it opens the full player activity, the same as the Player tab
  does today.
- Player sessions whose device isn't in the receiver list (for example a
  renderer that dropped off the network mid-session) go in a trailing group
  under their device id, so they're never hidden.

Alternatives rejected:
- **Strict merge into one row per device:** the data model allows N
  players per device (finding 1), so any single row would hide sessions,
  which is exactly the bug in finding 3.
- **Two flat sections (Receivers, then Players):** this keeps every
  session visible, but leaves the user to match players to devices in
  their head, which is the problem the redesign is meant to solve.

## Technical Approach

### Components
| Component | Kind | Responsibility |
|---|---|---|
| `DeviceSessionGrouper` | new, pure Java, no `android.*` imports | Turns (receiver ids, player refs) into an ordered row list. Unit-tested on the plain JVM, same precedent as `CardGridSpanCount` and `SortSupport`. |
| `ReceiverRowBinder` | new, extracted | Binds one `browse_receiver_device_item` row: checkbox, status badge, volume/mute load tasks, transport buttons. Moved out of `BrowseReceiverDeviceAdapter` with no behavior change, so the tab and the sheet share it. |
| `DeviceSheetFragment` | new, `BottomSheetDialogFragment` | Hosts the RecyclerView; subscribes to `RendererStatusMonitor` and player changes while shown, and unsubscribes on dismiss. |
| `DeviceSheetAdapter` | new | Two view types: RECEIVER (via `ReceiverRowBinder`) and PLAYER_SESSION (new layout `device_sheet_player_session_item.xml`). |
| `ContentListFragment` + both `fragment_content_list.xml` layouts | changed | The two-line footer (provider line + receivers line) becomes one **receiver bar**: `contentListCurrentProvider` is removed (the path label already starts with the server name since 5.4.0), and `contentListCurrentReceivers` stays as the bar, showing the selected receivers' names with an expand indicator. It becomes clickable and D-pad focusable, and opens the sheet. Any RelativeLayout anchors that pointed at the removed provider view are re-pointed at views that are never `GONE`. |
| Play entry points | changed | `BrowseContentItemAdapter` play/play-all handlers and `ContentListClickListener` check `upnpClient.getReceiverDevices().isEmpty()` first, and if it's empty, open the sheet instead of starting `ContentItemPlayTask`. The `error_no_receiver_selected` toast in `PlayerService.createPlayer` stays as the fallback for non-UI callers (for example the renderer service). |

### Sheet behavior
- Opens half-expanded and can be dragged to full height
  (`BottomSheetBehavior`; exact flags verified in Group 1 and recorded in
  `docs/tech.md`).
- D-pad: being a dialog, it keeps focus inside the sheet, and Back
  dismisses it. Initial focus goes to the first receiver's checkbox.
- It shares the app's existing `RendererStatusMonitor` instance rather than
  starting a second poller.

### Fix for finding 3
`getPlayerForDevice` is replaced by `DeviceSessionGrouper`. The Receiver
tab's single status row then picks deterministically: the newest playing
session, otherwise the newest session overall. "Newest" is the creation
sequence `PlayerService` assigns to each genuinely added player
(`getPlayerCreationSequence`, also on `UpnpClient`), not the player id:
`AVTransportPlayer` ids are random and local ids are fixed per type (see
decisions.md). The tie between two playing sessions is the case that matters
most (image show plus background music on the local device), so the rule
must not depend on input order. The sheet lists all sessions, oldest first.

## Interfaces & Contracts

```java
// de.yaacc.browser.DeviceSessionGrouper - plain Java, no android.* imports
public final class DeviceSessionGrouper {
    public static final class PlayerRef {      // playerId (lookup key only), deviceId, playing, sequence
        public PlayerRef(int playerId, String deviceId, boolean playing, long sequence) { ... }
        public int playerId(); public String deviceId(); public boolean playing(); public long sequence();
    }
    public static final class Row {            // kind + ids, no Android types
        public enum Kind { RECEIVER, PLAYER_SESSION }
        public Kind kind(); public String deviceId(); public int playerId();  // playerId 0 on RECEIVER rows
    }
    /** Receivers in the given order, each followed by its sessions, oldest
     *  first (ascending creation sequence); sessions with an unknown
     *  deviceId go last, grouped per device, with no RECEIVER row, groups
     *  ordered by their oldest session. With no receivers every session is
     *  still listed. Never drops a session. Duplicate receiver ids are
     *  listed once, null receiver ids are skipped, null-device sessions form
     *  their own trailing group. */
    public static List<Row> group(List<String> receiverDeviceIds, List<PlayerRef> players);
    /** Among the device's playing sessions, the newest (highest creation
     *  sequence); if none is playing, the newest session overall; null if
     *  the device has no sessions. Independent of input order (players
     *  arrive in HashMap order); equal sequences fall back to the higher
     *  playerId. */
    public static PlayerRef primarySession(String deviceId, List<PlayerRef> players);
}
```

## Diagrams

```
Content tab
 └─ bottom bar "this device" (tap) ──► DeviceSheetFragment
                                        ├─ [x] Living room TV   ▶❚❚■  vol ▬▬▬○  🔇
                                        │     └─ Music: "Track 3"   (tap → player activity)
                                        ├─ [x] this device       ▶❚❚■  vol ▬▬○▬
                                        │     ├─ Images: slideshow
                                        │     └─ Music: "Track 7"
                                        └─ [ ] Kitchen speaker   (idle)

Play with no receiver selected ──► DeviceSheetFragment (instead of toast)
```

## Phases
Agreed target layout (2026-10-23; built as the *new* layout of Phase 2,
which also adds a mini-player): the Server and Content tabs merge into
one browser whose top level is the server list, with a receiver bar at the
bottom of every level.

```
Top level (server list):                   Inside a server:
┌───────────────────────────────┐         ┌───────────────────────────────┐
│ YAACC                   ⏲  ⋮  │         │ YAACC                   ⏲  ⋮  │
│ Servers                    ↻  │         │ ← Gerbera > Musik     ≡ 📅 ⊞ │
│───────────────────────────────│         │───────────────────────────────│
│ 📡 Gerbera                  > │         │  (content list or cards)      │
│ 🟢 yaacc   🎙 🖥 ⚙           > │         │                               │
│ 📡 MiniDLNA                 > │         │                               │
│───────────────────────────────│         │                               │
│ yaacc server [ON]  ⬆ ⬇ ⇄      │         │                               │
│ ▶ phone, TV                ▾  │         │ ▶ phone, TV                ▾  │
└───────────────────────────────┘         └───────────────────────────────┘
```

1. **This spec:** the device sheet, the receiver bar replacing the Content
   tab's two-line footer, the no-receiver prompt, and the finding 3 fix.
   All tabs stay.
2. **Phase 2 (agreed 2026-10-24): a classic and a new layout, chosen by a
   setting.** See "Phase 2" below. This replaces the earlier plan of merging
   the Server tab and removing the Receiver/Player tabs inside the existing
   tab activity (old items 2 and 3).

## Phase 2: classic and new layout

### Decision
`TabBrowserActivity` and its four fragments stay as the **classic** layout.
The **new** layout is a separate activity, `BrowserActivity`, with no tabs:

```
Top level (servers):                       Inside a server:
┌───────────────────────────────┐         ┌───────────────────────────────┐
│ YAACC                   ⏲  ⋮  │         │ ← Gerbera > Musik     ≡ 📅 ⊞ │
│ Servers                    ↻  │         │───────────────────────────────│
│ 📡 Gerbera                  > │         │  (content list or cards)      │
│ 🟢 yaacc   🎙 🖥 ⚙           > │         │                               │
│ 📡 MiniDLNA                 > │         │                               │
│ yaacc server [ON]  ⬆ ⬇ ⇄      │         │                               │
│ ♪ Title - Artist   ⏮ ⏯ ⏭  ⏹  │         │ ♪ Title - Artist   ⏮ ⏯ ⏭  ⏹  │  <- mini-player
│ ▶ phone, TV                ▾  │         │ ▶ phone, TV                ▾  │  <- receiver bar
└───────────────────────────────┘         └───────────────────────────────┘
```

- Top level = the server list (from the Server tab). Tapping a server shows
  the existing `ContentListFragment` for it. The yaacc row keeps its live
  audio/video stream buttons and server-settings gear. The shutdown timer
  moves to the toolbar (it is app-global). Refresh sits in the "Servers"
  header (no pull-to-refresh: `swiperefreshlayout` is not a dependency and
  this spec adds none). The yaacc server on/off switch and its
  provider/renderer/proxy icons stay a top-level-only footer (that row only
  exists while the server runs, so they cannot live on it).
- The **receiver bar** and the **mini-player** are owned by the activity, so
  they appear on every level. Both open or drive the existing device sheet /
  player screens; the Receiver and Player tabs do not exist in this layout.
- **Superseded (2026-10-24, owner feedback, decisions.md "Owner feedback after the first on-device check"):** the mini-player shows *all* running players, one row each, oldest first; the single-featured-session text that follows is the original design.
- The **mini-player** shows one *featured* session: the playing session with
  the highest creation sequence, else the highest sequence overall, picked
  by a plain-Java function (same ordering rules as
  `DeviceSessionGrouper.primarySession`, but across all devices). It shows
  title, receiver name, album art; buttons previous / play-pause / next /
  stop; tapping the bar opens that player's screen
  (`Player#getNotificationIntent()`). It hides when no player exists. It
  updates from `PlayerListChangeNotifier` events plus the 2 s poll, and
  stops both while the activity is stopped.
- A setting **Layout: Classic tabs / New** in the normal settings screen
  picks the activity. A tiny invisible `LauncherActivity` owns the
  launcher, Android TV and share (`ACTION_SEND text/plain`) intent filters,
  reads the setting, and forwards to the chosen activity. Internal launches
  (`Yaacc.java`, `PlayerService.java` x2) go through one helper.
- Navigation goes through a small `BrowserHost` interface (`showServers()`,
  `showContent()`, `showReceivers()`, `showPlayers()` plus capability
  defaults) implemented by both activities. Only the *live* call sites use
  it (`ServerListClickListener` and the share handler). The four fragment
  branches that cast `getParent()` to `TabBrowserActivity` never run, so
  they are deleted, not wired (Group 5 research, decisions.md 2026-10-24).
  The new layout has its own back handling (`OnBackPressedDispatcher`) and
  maps "receivers" to the sheet and "players" to the mini-player.
- The share-intent handler (about 60 lines in `TabBrowserActivity`) moves to
  a shared helper so both activities use it.

### Why not one activity with tabs hidden
Tab positions are tied to the order of `BrowserTabs` in the adapter, the
activity and saved state; hiding tabs would first need that decoupled, and
the new top-level server list is a different navigation model anyway.

### Defaults and open items
- Default for existing installs and Android TV: **classic** (nothing changes
  until the user switches). New installs: classic too, until the owner has
  tried the new layout on a device and decides otherwise.
- Open: does classic keep the receiver bar and sheet? (PRD question 4;
  proposed yes.)
- Not renaming `TabBrowserActivity`; "Classic" appears only in the settings
  UI and docs.

## Phase 3: sharing local files, and picking receivers per action

Scoped with the owner on 2026-10-25 (decisions.md "Share feature scoped"). Replaces Backlog 1.

### Decisions (owner)
1. **Per-share registry entry**, not the persistent "media proxy" setting. The user's settings are never changed. *(Corrected by the Group 12 research: the HTTP server is always running while the app process lives, whatever the "local server" setting says; that setting only decides which UPnP services are published. So nothing is started or stopped for a share: the proxy path serves registry entries before the settings gate, and the share waits until the server answers.)*
2. **Local files are kept readable by holding the file descriptor open (option A).** The descriptor is opened only AFTER the user confirmed the receiver pick (in both layouts: classic opens the same pick-mode sheet for a shared file), while the sender's read permission is still valid because the receiving activity is alive; a pending, unconfirmed share holds no descriptor, no copy and no registry slot. No copy by default. If the provider's descriptor cannot seek, fall back to a copy in the app cache (option B), with a size cap.
3. **Every share served through the proxy** (files and http/https links) gets a random unguessable key, lives in memory only (no more `proxy_link_*` in the preferences), is only served to the chosen receivers' IP addresses (a setting, on by default, lets the user switch this check off for receivers that fetch from another address), and expires (when its player ends plus a grace period, and after an absolute timeout).
4. **Receivers are picked per action in the new layout:** share, play and play-all open the receiver picker (the device sheet in a pick mode); after the playback has started the receiver selection is cleared. The classic layout keeps its tab-based selection and static footer; the only addition there is that a shared file (not normal browsing) opens the same pick-mode sheet; the previous classic selection is restored afterwards.
5. **One file per share** in the first version (no `SEND_MULTIPLE`).

### Threat model for local files
The share entry (the exported launcher) accepts intents from every app.
- **Confused deputy / own data:** a `file://` URI, or a `content://` URI of yaacc's own file provider, would let any app make yaacc read and serve its private data (preferences, caches) to the LAN. Only `content://` URIs of other apps are accepted; `file:` and any URI whose authority is ours are rejected; the content must report an audio, video or image MIME type.
- **Permission forwarding:** the sender's temporary read grant is honored by Android only if the sender really holds it. The launcher forwards exactly one `content:` URI as ONE ClipData item (`ClipData.newRawUri`) together with `FLAG_GRANT_READ_URI_PERMISSION` (a deliberate, narrow exception to "no URIs and no flags"), nothing else from the incoming intent, after checking that the incoming `EXTRA_STREAM` equals ClipData item 0 and that the incoming flags carry the read grant (the system checks only `data` and ClipData, never `EXTRA_STREAM`). The forward also carries one single-use launch token (`LaunchNonces`, 128 bits, valid 30 s, memory only) that the launcher mints after its checks passed; the browser activities are exported, so they accept a shared file only with such a token (decisions.md, Group 14 W1). Without it, a file sent straight to an exported activity is dropped.
- **Exposure on the LAN:** the key is 128-bit random and unguessable, the registry is in memory, entries expire, and only the chosen receivers' IPs are served (setting, default on). The HTTP server itself listens whenever the app process lives (existing behavior); without registry entries the proxy path answers 404/403 as before.
- **Residual risk (accepted, owner to confirm):** Android checks only that yaacc itself can read the URI. Yaacc holds broad media permissions and SAF grants, so a malicious sender could name MediaStore/SAF content it cannot read itself; there is no public API to tell "granted by the sender" from "readable by us anyway". Gates: audio/video/image type check, a human-in-the-loop (new layout: every share needs a receiver pick that shows the cleaned name of what will be played; classic: the same pick-mode sheet, opened only for a shared file, showing the cleaned name), and the IP/expiry limits.
- **Resource use:** one open descriptor per active share, closed on expiry/stop; a size cap for the copy fallback; at most a few active shares.

### Receiver picking (new layout)
Play, play-all and share open the device sheet in a *pick mode* (title "Play on...", checkboxes, a confirm button). Confirming sets the receivers, starts the playback and then clears the selection. The receiver bar then shows no selection by design; it opens the sheet for controlling receivers and players.

### Known risks of the design
- A strict per-IP check can fail with renderers that fetch from another address than their descriptor host (multi-homed devices, IPv6); then playback fails with a 403 and a log line. Mitigation (owner decision 2026-10-25): a setting "Restrict the media proxy to the chosen receivers", default on; switching it off keeps the random key and expiry but skips the IP check. A manual check with the user's receivers is part of the plan.
- Holding the descriptor needs a seekable provider for range requests; the copy fallback covers the rest.

## Risks
- **D-pad focus inside a bottom sheet on TV:** not yet verified. The
  mitigation is that the tabs stay in this phase, so TV users keep a
  working path either way. A TV check is part of the Group 3 Verify step.
- **Duplicated row logic drifting:** mitigated by extracting
  `ReceiverRowBinder` first (Group 2), before the sheet exists.
- **Double polling** if the sheet starts its own status monitor: mitigated
  by sharing the existing `RendererStatusMonitor` instance.
- **Player add/remove notification:** traced in Group 1 - there is none.
  The Player tab only re-reads `getCurrentPlayers()` on view creation and
  `onResume`. Group 2 adds a listener to `PlayerService`. It must ignore
  repeat calls in both directions: `addPlayer` can run twice for the same
  player, and `shutdown(Player)` removes the player and then calls
  `player.onDestroy()`, which calls `removePlayer` again
  (`AbstractPlayer.java:958-965`). As built: "added" fires when `put`
  returns null; a re-add of the same instance fires nothing; replacing a
  *different* instance with the same id fires "removed" for it and then
  "added" (decisions.md); removal uses `remove(id, player)` and fires
  "removed" only if that exact instance was still present.
- **Bottom-sheet details found in Group 1** (see `docs/tech.md`): the
  half-expanded state only works if `setFitToContents(false)` comes first;
  `AppTheme` has no `bottomSheetDialogTheme`, so without one the sheet
  would render light-only in dark mode; and half-expanded rows below the
  screen edge can take D-pad focus without scrolling into view, so the
  sheet opens fully expanded when not in touch mode.
