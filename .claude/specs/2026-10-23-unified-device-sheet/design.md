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
tab's single status row then picks deterministically: the newest (highest
player id) playing session, otherwise the newest session overall. The tie
between two playing sessions is the case that matters most (image show
plus background music on the local device), so the rule must not depend on
input order. The sheet lists all sessions.

## Interfaces & Contracts

```java
// de.yaacc.browser.DeviceSessionGrouper - plain Java, no android.* imports
public final class DeviceSessionGrouper {
    public static final class PlayerRef {      // playerId, deviceId, playing
        public PlayerRef(int playerId, String deviceId, boolean playing) { ... }
    }
    public static final class Row {            // kind + ids, no Android types
        public enum Kind { RECEIVER, PLAYER_SESSION }
        public Kind kind(); public String deviceId(); public int playerId();
    }
    /** Receivers in the given order, each followed by its sessions
     *  (ascending playerId); sessions with an unknown deviceId go last,
     *  grouped per device. Never drops a session. */
    public static List<Row> group(List<String> receiverDeviceIds, List<PlayerRef> players);
    /** Among the device's playing sessions, the highest playerId; if none
     *  is playing, the highest playerId overall; null if the device has no
     *  sessions. Independent of input order (players arrive in HashMap
     *  order). */
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
Agreed target layout (2026-10-23): the Server and Content tabs merge into
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
2. Merge the Server tab into the browser as its top level. The yaacc row
   keeps its live audio/video stream buttons and gains the server-settings
   gear. The shutdown timer moves to the toolbar, since it's app-global.
   Refresh moves to the "Servers" header, plus pull-to-refresh. The yaacc
   server on/off switch and its provider/renderer/proxy icons stay as a
   top-level-only footer: they can't live on the yaacc row, because that
   row only exists while the server runs (rows come from
   `getDevicesProvidingContentDirectoryService()`).
3. Remove the Receiver and Player tabs and add a mini-player bar, once 1
   and 2 have proven themselves, including on TV.

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
  (`AbstractPlayer.java:958-965`). So "added" fires only when `put`
  returns null, and "removed" only when `remove` returns non-null.
- **Bottom-sheet details found in Group 1** (see `docs/tech.md`): the
  half-expanded state only works if `setFitToContents(false)` comes first;
  `AppTheme` has no `bottomSheetDialogTheme`, so without one the sheet
  would render light-only in dark mode; and half-expanded rows below the
  screen edge can take D-pad focus without scrolling into view, so the
  sheet opens fully expanded when not in touch mode.
