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
| `DeviceSessionGrouper` | new, plain Java (nothing in it needs Android types) | Turns (receiver ids, player refs) into an ordered row list. Unit-tested on the plain JVM, same precedent as `CardGridSpanCount` and `SortSupport`. |
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
// de.yaacc.browser.DeviceSessionGrouper - plain Java (nothing in it needs Android types)
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

## Phase 4: consolidate the share code

Owner decision 2026-10-26 (decisions.md "Phase 4"). A behaviour-preserving refactor of what Phase 3 put on this branch: no feature, no security change, no dependency, no manifest change. BASE = commit `ecb26c59` (everything below is measured against it).

### Context
Phase 3 left `de.yaacc.share` with 46 classes (~6,200 lines), `de.yaacc.upnp.server.share` with 9, 8 share/pick helpers in `browser/`, 4 HTTP classes (`ProxyShareResponder` and three producers) and `upnp/ReceiverSelection`; the tests are ~11,700 lines. Most of that split came from one rule, "no `android.*` in these classes": every piece that touched a `Uri`, `PackageManager` or `Context` was separated from its pure part, and each pure part became a class with its own test (`ShareProxyUrl` is a 47-line string builder; `ShareFailureMessage` is an OR of two booleans). The owner withdrew the rule: yaacc is an Android app, and the existing tests already mock Android types with Mockito (`Context`, `Uri`, `ContentResolver`, `Intent`, `mockStatic`, `unitTests.returnDefaultValues = true`; there is no Robolectric and none is added).

### Decision
1. **Android types are allowed in the share classes.** Tests mock them with Mockito exactly as `ShareIntentHandlerFileTest` and `ProviderProbeTest` do. The "no `android.*`" wording is removed from tasks.md and design.md (Groups 1, 2, 12, 13 and the `DeviceSessionGrouper` rows); decisions.md keeps its history.
2. **Keep / merge / delete rule** (the number of classes follows from it, not the other way round):
   - KEEP a class that holds state or a state machine, or enforces a security invariant that has its own tests and mutation targets, or is a boundary type used by unrelated callers.
   - MERGE a class that is a static helper with one production caller (or callers in one cluster), a decision wrapper over two or three values, a data carrier, or exists only to keep Android types out of its neighbour. The target is the class that owns the responsibility; the old name survives as a nested type or a section only if tests need it.
   - DELETE / REPLACE where a platform or JDK type does the job (`java.net.URI` for the proxy URL; a plain "has address" check for the `"x"` key probe).
3. **One package.** All share code ends in `de.yaacc.share`; `de.yaacc.upnp.server.share` disappears (moved first, as a pure rename, so the later merges happen in the final place). `browser/` keeps the four classes that talk to activities and fragments; `upnp/server/http/` keeps the responder and one merged producer file.
4. **Moves, not rewrites.** A merge copies the code of the absorbed class into its target unchanged (same statements, same order); only qualifiers, visibility and javadoc change. No drive-by fixes: the open items in known-risks.md stay open (they are separate work with their own review).
5. **Public API of `UpnpClient`, `ShareIntentHandler` and `ShareService` (renamed `SharedFileService`) does not change**: tests verify `upnpClient.discardShareOf(...)` and friends, and changing those would change assertions. `UpnpClient.createHttpLinkShare` stays in `UpnpClient`; it only calls the new homes of the helpers.

### What allowing Android types simplifies, and what it does not
| Simplification | Where |
|---|---|
| A pure part that existed only to keep `Uri`/`PackageManager`/`Context` out of a class is merged back | `OwnProviders` + `ProviderKeys` + `ProviderProbe` + `HttpServerProbe` + `ShareAddressResolver` + `SharedItemFactory` into their users; `StreamIntake` takes the own-provider check as a `PackageManager` instead of a lambda only where that deletes wiring in `LauncherActivity`/`ShareIntentHandler` (optional, arrange-only test changes) |
| A class that only wrapped a JDK/Android call | `ShareProxyUrl` -> `java.net.URI`; `DescriptorWindow.clampLength` -> `HeldFileSource`; `ReceiverSelection` -> inline `new HashSet<>(...)` in `UpnpClient`; `ShareFailureMessage` -> one static `failureTextRes` |
| Test seams that only avoided Android | none removed by force; kept where they also give determinism (injected clock, injected random, `FilePreparer`) |
| **Not changed on purpose** | (a) The string-level parsers of `SharedContentPolicy`, `LinkTargetPolicy` and `ProxyAccessPolicy` stay hand-written: under `returnDefaultValues` an `android.net.Uri.parse` returns defaults on the JVM, so the 120+ policy cases would test the mock, and the policies deliberately do not use `URL`/`URI`/`InetAddress` (no DNS, what is checked is what Android uses). (b) `SenderGrantCheck` keeps its `sdkInt` argument (`Build.VERSION.SDK_INT` is a static final, not mockable). (c) Injected `LongSupplier`/`Consumer<byte[]>` stay (deterministic tests). |

### Inventory and verdicts (BASE; "G" = group that does it)
Lines are main-source lines at BASE. "Used by" lists the production callers outside the class's own file.

| Class | Lines | Used by | Responsibility | Verdict |
|---|---|---|---|---|
| **share (46)** | | | | |
| `ShareRegistry` | 502 | Yaacc, handler, UpnpClient, service, AVTransportPlayer, UnclaimedShares... | keys, entries, expiry, eviction, close-once | KEEP; absorbs `ShareRegistrar` (G17) |
| `ShareRegistrar` | 44 | UpnpClient, service | register, evict a released entry, retry | MERGE -> `ShareRegistry#registerOrEvict` (G17) |
| `UnclaimedShares` | 163 | Yaacc, UpnpClient | claim window bookkeeping with bounded overflow | KEEP (own state machine, 26 cases) |
| `ShareKeyTracker` | 130 | UpnpClient, ShareLifetimeListener | keys per player, remembered before `onDestroy` | KEEP |
| `ShareLimits` | 127 | 16 classes | the numbers that bound a share | KEEP (constants; values must not change) |
| `ShareConcurrencyLimiter` | 260 | Yaacc, producers, responder | per-share response cap, idle watchdog, leases | KEEP |
| `ProxyAccessPolicy` | 336 | LinkTargetPolicy, ShareAddresses, responder | the IP step, address normalization | KEEP; absorbs `ShareAddresses` (G18) |
| `ShareAddresses` | 69 | ShareAddressResolver | builds the allowed set | MERGE -> `ProxyAccessPolicy` (G18) |
| `ByteRangeResolver` | 368 | RangeStreamer, responder | `Range` -> 200/206/416 | KEEP |
| `RangeStreamer` | 132 | producer, responder | streams a resolved range from a source in chunks | KEEP |
| `ChunkSink` | 25 | RangeStreamer | callback interface of `serve` | MERGE -> nested `RangeStreamer.Sink` (G18) |
| `PositionalSource` | 169 | 6 classes | seekable source, close-once rules | KEEP |
| `DescriptorWindow` | 47 | HeldFileSource | one clamp method | MERGE -> `HeldFileSource#clampLength` (G17) |
| `ServerSideSeeking` | 78 | responder | server-side seek decision | KEEP; absorbs the next three (G18) |
| `RendererIdPolicy` | 50 | 5 callers | charset/length check of a renderer id | MERGE -> `ServerSideSeeking#isValidRendererId` (G18) |
| `RendererStateMap` | 138 | ServerSideSeeking, HttpLinkTarget | per-renderer seek state, LRU | MERGE -> nested in `ServerSideSeeking` (G18) |
| `SeekPositionCalculator` | 76 | ServerSideSeeking | time -> byte estimate | MERGE -> `ServerSideSeeking` (G18) |
| `LinkTargetPolicy` | 307 | UpnpClient | which link targets intake accepts | KEEP; renamed `ShareLink` (G18) |
| `HttpLinkTarget` | 125 | responder, handler, UpnpClient, policy | http(s) normalization, the registry target of a link | MERGE -> `ShareLink` (G18) |
| `LinkContentType` | 106 | UpnpClient | HEAD probe, redirects by hand | MERGE -> `ShareLink` (G18) |
| `SharedContentPolicy` | 200 | 11 classes | which `content:` URIs and MIME types are accepted | KEEP; absorbs `OwnProviders`, `StreamIntake`, `ShareUriPolicy` (G20) |
| `StreamIntake` | 89 | handler | the browser's repeat of the intake checks | MERGE -> `SharedContentPolicy#validateStream` (G20) |
| `LaunchNonces` | 161 | Yaacc, handler, LauncherActivity | single-use launch token store | KEEP (own security invariant) |
| `SenderGrantCheck` | 61 | LauncherActivity | API 35 sender check decision | MERGE -> `LauncherForwardPolicy` (G20) |
| `PendingShare` | 243 | Yaacc, handler, service | the one pending shared file, token, timeout | KEEP |
| `DisplayName` | 111 | MiniPlayerPresenter, handler, PendingShare, ProviderProbe | cleans app/LAN texts | KEEP |
| `BoundedCall` | 236 | handler, service | pool-bounded provider call with timeout | KEEP; renamed `ProviderCalls`, absorbs gate and keys (G19) |
| `ProviderCallGate` | 94 | BoundedCall, handler, service | one call in flight per provider | MERGE -> `ProviderCalls` (G19) |
| `AbandonableCall` | 214 | WatchedCopy | own-thread call, abandon cap | MERGE -> `WatchedCopy` (G19) |
| `WatchedCopy` | 228 | SharedFileOpener | copy with watchdog, one at a time | KEEP; absorbs `AbandonableCall` (G19) |
| `BudgetedCopier` | 115 | WatchedCopy | copy loop with a budget check per chunk | KEEP; absorbs `CopyBudget` (G19) |
| `CopyBudget` | 67 | BudgetedCopier | stop rule | MERGE -> `BudgetedCopier` (G19) |
| `IncomingCache` | 142 | Yaacc, BudgetedCopier, service, opener | the `cache/incoming` directory: sweep, totals, create | KEEP; absorbs the next two (G19) |
| `IncomingFileNames` | 76 | IncomingCache | random names, shape test | MERGE -> `IncomingCache` (G19) |
| `DeleteOnCloseSource` | 69 | WatchedCopy | wrapper that deletes the copy at close | MERGE -> `IncomingCache` (G19) |
| `ReadinessWait` | 78 | handler, service, UpnpClient | poll until ready, injected clock | KEEP |
| `ShareLogRedactor` | 44 | 9 classes outside | removes proxy paths from log text | KEEP |
| `ShareProxyUrl` | 47 | UpnpClient, service | string builder `http://ip:port/proxy/key` | DELETE -> `java.net.URI` in `ShareService#proxyUrl` (G17) |
| `ShareFailureMessage` | 51 | handler, service | OR of two booleans | DELETE -> `ShareService#failureTextRes` (G17) |
| `PendingPick` | 157 | ReceiverPickController | the one open pick, id, outcome | KEEP; absorbs the next two (G21) |
| `ReceiverPickResult` | 108 | PendingPick, PickSheetResult | confirm/cancel/empty decision | MERGE -> `PendingPick` (nested `Decision`) (G21) |
| `PickSheetResult` | 90 | DeviceSheetFragment | the sheet's one-answer gate | MERGE -> `PendingPick.SheetGate` (G21) |
| `ActionFlight` | 106 | ReceiverPickController, guard | one picked action in flight, watchdog | KEEP; absorbs `GuardedPickedAction` (G21) |
| `GuardedPickedAction` | 60 | ReceiverPickController | flight guard around an action | MERGE -> `ActionFlight#guard` (G21) |
| `PickedAction` | 39 | BrowserHost, activities, ContentListFragment | the callback contract of a pick | KEEP (boundary type, 5 callers) |
| `AfterStartSelection` | 93 | ReceiverPickController | clear vs restore the selection | MERGE -> `ReceiverPickController` (G21) |
| **upnp.server.share (9)** | | | | |
| `SharedFileService` | 219 | handler | open, register, build the item of a confirmed file | KEEP; renamed `ShareService`, absorbs the next four (G17 rename, G20 merges) |
| `ShareAddressResolver` | 128 | service, UpnpClient | chosen receivers -> allowed addresses | MERGE -> `ShareService` (G20) |
| `SharedItemFactory` | 88 | service, UpnpClient | MIME -> DIDL item | MERGE -> `ShareService` (G20) |
| `HttpServerProbe` | 52 | UpnpClient | loopback connect | MERGE -> `ShareService#isHttpServerRunning` (G20) |
| `SharedFileOpener` | 252 | service | open descriptor or copy | KEEP; absorbs `ProviderProbe` (G19) |
| `ProviderProbe` | 94 | handler | `getType` + display name | MERGE -> `SharedFileOpener#probe` (G19) |
| `HeldFileSource` | 142 | opener | held descriptor, `pread` | KEEP; absorbs `DescriptorWindow` (G17) |
| `OwnProviders` | 57 | handler, LauncherActivity | is this authority one of ours | MERGE -> `SharedContentPolicy` (G20) |
| `ProviderKeys` | 63 | handler, service | gate key of a provider | MERGE -> `ProviderCalls` (G19) |
| **browser (8) / upnp (1)** | | | | |
| `ShareIntentHandler` | 820 | activities, UpnpClient, ReceiverPickController | link and file intake, pending share, confirm, play | KEEP (one orchestrator; splitting it adds classes); absorbs `ShareIntakeGate` (G20) |
| `ShareIntakeGate` | 68 | both activities, handler | "admit the payload only in the effective layout" | MERGE -> `ShareIntentHandler#admitOrDiscard` (G20) |
| `ShareUriPolicy` | 69 | LauncherForwardPolicy, handler, SharedContentPolicy | scheme + length of shared text | MERGE -> `SharedContentPolicy` (G20) |
| `LauncherForwardPolicy` | 187 | handler, LauncherActivity | what the launcher forwards | KEEP; absorbs `SenderGrantCheck` (G20) |
| `ReceiverPickController` | 224 | both activities | owns the pick, shows the sheet, receives the result | KEEP; absorbs `AfterStartSelection` (G21) |
| `PickTitle` | 51 | DeviceSheetFragment | cleaned share name or fixed text | MERGE -> `DeviceSheetFragment` (G21) |
| `ReceiverBarController` | 223 | BrowserActivity | binds the receiver bar | KEEP; absorbs `ReceiverBarLabel` (G17) |
| `ReceiverBarLabel` | 68 | ReceiverBarController | joins cleaned names | MERGE -> `ReceiverBarController#label` (G17) |
| `ReceiverSelection` (upnp) | 15 | UpnpClient | `new HashSet<>(stored)`, `removeAll` | DELETE -> inline in `UpnpClient` (G17) |
| **upnp.server.http (4)** | | | | |
| `ProxyShareResponder` | 419 | HTTP handler | the `/proxy/` auth chain and answers | KEEP |
| `ShareEntityProducer`, `GuardedEntityProducer`, `LengthOnlyEntityProducer` | 119, 148, 64 | responder | HttpCore body producers | MERGE -> one file `ShareProducers` with three nested classes (G18, droppable) |

**Result:** 68 files -> 33: 27 in `de.yaacc.share` (`ShareRegistry`, `UnclaimedShares`, `ShareKeyTracker`, `ShareLimits`, `ShareConcurrencyLimiter`, `ProxyAccessPolicy`, `ByteRangeResolver`, `RangeStreamer`, `PositionalSource`, `HeldFileSource`, `ServerSideSeeking`, `ShareLink`, `SharedContentPolicy`, `LaunchNonces`, `PendingShare`, `DisplayName`, `ProviderCalls`, `SharedFileOpener`, `WatchedCopy`, `BudgetedCopier`, `IncomingCache`, `ReadinessWait`, `ShareLogRedactor`, `ShareService`, `PendingPick`, `ActionFlight`, `PickedAction`), 4 in `browser/` (`ShareIntentHandler`, `LauncherForwardPolicy`, `ReceiverPickController`, `ReceiverBarController`), 2 in `upnp/server/http/` (`ProxyShareResponder`, `ShareProducers`). Old name -> new name is the table above; review.md and security-review.md keep the old names.

**Why 27 and not 12-18 in the share package:** every one of the 27 either has its own state or state machine (registry, claims, tracker, limiter, nonces, pending share, pending pick, flight, positional source, watched copy), enforces one security invariant that has its own mutation target (access policy, range maths, content policy, link policy, provider calls, incoming cache, display name, redactor), or is a boundary type (`PickedAction`, `ShareLimits`, `ReadinessWait`). Going lower means merging those: the largest merged class would be 700+ lines mixing unrelated invariants, and the security reviewer's per-class mutation checks would get coarser. The optional stage 2 below shows exactly what that costs; the owner decides after Group 22.

**Optional stage 2 (not scheduled; owner decision after Group 22):** `UnclaimedShares` + `ShareKeyTracker` -> `ShareRegistry`; `BudgetedCopier` -> `WatchedCopy`; `HeldFileSource` -> `SharedFileOpener`; `ReadinessWait` -> `ShareService`; `LaunchNonces` -> `PendingShare`; `ActionFlight` + `PickedAction` -> `PendingPick`; `ShareLogRedactor` -> `DisplayName`. That gives 27 -> 18 files, with `ShareRegistry` ~800 lines and `PendingPick` ~330; the cost is coarser classes and re-expressed state-machine tests (UnclaimedShares' early-claim and overflow cases cannot simply be moved into the registry's lifetime logic without a rewrite of the structure they pin). Recommendation: do not.

### Behaviour preservation
**Tests.** Every test case survives. Cases move into the test class of the class that absorbed the code; assertions are unchanged unless the API of a deleted class changed (listed below). The count stays 1472 except for the N characterization tests added in Group 17 and one extra case (IPv6 with a zone id, ledger L1); nothing else. Tests may be named by topic (`ShareLinkPolicyTest`, `ShareLinkContentTypeTest`) when one file would pass ~800 lines; the rule is that every case targets the new class. A throw-away script (not committed) compares the multiset of test method names from `build/test-results` at BASE and at HEAD; the allowed differences are exactly the ledger in tasks.md.

**Tests of deleted trivial classes (the only cases that change form):**
| Deleted | Cases at BASE | Behaviour pinned | Pinned now by |
|---|---|---|---|
| `ShareProxyUrlTest` (4) | `buildsTheUrlAReceiverFetches`, `noAddressMeansNoUrl`, `noKeyOrPathMeansNoUrl`, `anIpv6AddressIsBracketed` | URL shape `http://ip:port/proxy/key`; no address or no key means no URL; IPv6 is bracketed | `ShareServiceTest` on `proxyUrl(ip, key)` and `hasAddress(ip)`: the same four cases; the "no path" half of the third case disappears because the path is the constant `YaaccUpnpServerService.PROXY_PATH`, no longer an argument (key half kept as `noKeyMeansNoUrl`); one case added for an IPv6 address with a zone id (parity with BASE output) |
| `ShareFailureMessageTest` (10) | 4 cases on `Kind` (`aProviderCallRefusedForRoomIsBusy`, `aBusyCopySlotIsBusy`, `bothBusyIsBusy`, `anyOtherFailureStaysTheGenericMessage`) | busy provider or busy copy slot -> "busy" text, anything else -> generic text | `ShareServiceTest` on `failureTextRes(boolean, boolean)` returning `R.string.share_busy` / `R.string.share_open_failed`: same 4 cases |
| | 6 cases on `BoundedCall.Result#isRefusedBusy` (`aGateThatIsFullRefusesTheCallAsBusy`, `aPoolWithoutAFreeThreadRefusesTheCallAsBusy`, `aProviderThatDoesNotAnswerInTimeIsNotReportedAsBusy`, `aFailingCallIsNotReportedAsBusy`, `aCallThatAnswersNullIsNotReportedAsBusy`, `aCallThatAnswersHasTheValueAndIsNotBusy`) | what counts as "refused busy" | they were never about `ShareFailureMessage`: moved verbatim to `BoundedCallTest` (G17), then `ProviderCallsTest` (G19) |
| `ReceiverSelectionTest` (8) | `copyIsEqualButNotTheSameObject`, `changingTheCopyDoesNotChangeTheStoredSet`, `copyOfAnUnmodifiableSetIsModifiable`, `copyOfNullIsEmpty`, `withoutRemovesOnlyTheUnknownIdsAndLeavesTheStoredSetAlone`, `withoutNothingKeepsEverything`, `anEmptyStoredSetStaysEmpty`, `removingEverythingGivesAnEmptyModifiableSet` | the set from `SharedPreferences.getStringSet` is never modified, null -> empty, unknown ids are removed from a copy | `UpnpClientReceiverSelectionTest` through `getReceiverDeviceIds()` and `getReceiverDevices()` (spy with a mocked `SharedPreferences` returning an unmodifiable set), same 8 names |
The other absorbed classes keep all their cases (moved, retargeted at the new class): see the ledger in tasks.md.

**Test-only API kept in this phase** (to avoid deleting cases): `RangeStreamer#serve` + the sink (blocking pump, 18 of 24 `RangeStreamerTest` cases), `ReceiverPickResult.Decision#clearSelectionAfterStart`, `PickSheetResult#isDelivered`. Whether to delete them with their cases is an open question for the owner.

**Public API.** The sorted list of `public`/`protected` method signatures of `UpnpClient` and `ShareIntentHandler` is identical at BASE and at HEAD (checked by command in each group). `ShareIntentHandler.FilePreparer` keeps its shape (`ShareService.Prepared` replaces `SharedFileService.Prepared`).

**Move discipline.** Per group: first the pure renames/package moves (`git mv`, similarity 100%), then the merges. `git diff -M --color-moved=dimmed-zebra BASE..HEAD` must show merged bodies as moved blocks; the reviewer lists every non-moved changed line of main code and each must be a qualifier, a visibility change, a javadoc change or a documented call-site change. The two static `BoundedCall` instances (`ShareIntentHandler.PROBE_CALLS`, `ShareService.PROVIDER_CALLS`) stay two instances with two gates and 4 threads each; merging the classes must not merge the pools (the "at most 3 of 4 threads per pool" accounting, security-review Group 14 cycle 2/3, depends on it).

### Security invariants the refactor must not change
References: security-review.md Group 12 cycles 1-4, Group 13 cycle 1, Group 14 cycles 1-3, Group 15 cycles 1-2, Group 16. Each invariant stays pinned by the tests named in the ledger of its group; the security reviewer re-derives them from the code, not from this list.

| # | Invariant | Held by (after Phase 4) |
|---|---|---|
| SI-1 | Key: 16 bytes from `SecureRandom` (`createDefault`), 22 chars unpadded base64url, never handed out twice (retired window 256, 8 draws), `isWellFormedKey` before any lookup; garbage never throws | `ShareRegistry`, `ProxyShareResponder` |
| SI-2 | Registry: monotonic clock (not wall time), idle grace 10 min after release, absolute max 6 h, `maxActive` cap, close exactly once and outside the lock, `register` returning null leaves ownership with the caller, `evictOldestReleased` touches only released entries, `removeAll()` on exit, sweeper | `ShareRegistry`, `Yaacc` |
| SI-3 | Auth chain order: path shape -> well-formed key -> lookup -> `ProxyAccessPolicy` -> renderer id -> serving. Every bad key gets the same constant 404 body, 403 only after a valid key, HEAD and range continuations take the same chain, key and path are never echoed or logged | `ProxyShareResponder`, `ServerSideSeeking` |
| SI-4 | IP step: remote address only from the connection's endpoint details; restriction defaults to ON in code, in `preference.xml` and when the read fails; zone ids stripped, IPv4-mapped IPv6 normalized, no `InetAddress.getByName`, an empty set denies everything while restricting, loopback and own addresses only when the local device was chosen, the allowed set is built from the ids captured at confirm and copied unmodifiable into the entry | `ProxyAccessPolicy`, `ShareService` (resolver), `ShareRegistry.Entry` |
| SI-5 | Ranges: invalid header or more than one range = 200 whole; unsatisfiable single range = 416 `bytes */len`; long arithmetic, saturating digits, header length cap; upstream `Range` re-serialized from parsed numbers; `Content-Range` from longs; no `Content-Disposition`; `Content-Type` from the re-validated entry MIME; `nosniff` + CSP `sandbox` | `ByteRangeResolver`, `ProxyShareResponder` |
| SI-6 | Held file: `dup` of the provider's descriptor and the original closed; regular-file and `lseek` check; start offset and length clamped to the real size; `pread` at `start + position`; close deferred to the last reader, never blocks the caller, exactly once, new reads refused after close; a failed open leaves the descriptor with the caller; a late result of a timed-out open is closed | `HeldFileSource`, `PositionalSource`, `SharedFileOpener`, `ProviderCalls` |
| SI-7 | At most `MAX_CONCURRENT_RESPONSES_PER_SHARE` (4) responses per share (503 on the 5th), idle watchdog takes a stalled reader's slot, `tryReacquire` aborts a resumed reader only when full, release idempotent, the lease is released when the producer cannot be built | `ShareConcurrencyLimiter`, `ShareProducers`, `ProxyShareResponder` |
| SI-8 | Launcher forward: fresh intent; `ACTION_SEND` + exact lower-case media type; ONE `ClipData.newRawUri` item and `FLAG_GRANT_READ_URI_PERMISSION` only; no `data`, no `EXTRA_STREAM`, no other extra; `EXTRA_STREAM` equals ClipData item 0; incoming flags carry the read grant; content policy; any provider of this app refused (fail closed); API 35+ caller check fails closed; `SecurityException` -> plain launch; `finish()` in `finally`; the incoming intent is never modified | `LauncherForwardPolicy`, `SharedContentPolicy`, `LauncherActivity` (unchanged) |
| SI-9 | Launch nonce: 128 bits `SecureRandom`, TTL 30 s, single use, at most 8 outstanding, constant-time compare, never logged; a file at a browser without a valid nonce is dropped | `LaunchNonces`, `ShareIntentHandler` |
| SI-10 | Browser repeats every check: action `SEND`, exactly one ClipData item, no `data`, read flag in its OWN flags, item 0 only and never `EXTRA_STREAM`, content policy, own-provider check; the intent is consumed BEFORE validation; a payload is handed to the handler only by the activity of the effective layout | `SharedContentPolicy#validateStream`, `ShareIntentHandler` (incl. the gate) |
| SI-11 | One pending share (a second offer is dropped, never replaces), 2 min monotonic timeout, 128-bit token with constant-time compare, `take` once; nothing is opened, copied or registered before the confirm; one open pick (stale/forged/repeated ids ignored, state cleared before callbacks run); the sheet answers once; one picked action in flight with a watchdog; after-start selection: new layout clears, classic restores a copy; a clear never alters an existing share's allowed set | `PendingShare`, `PendingPick`, `ActionFlight`, `ReceiverPickController`, `ShareIntentHandler` |
| SI-12 | Provider calls: never on the main thread; type/name/open each bounded (3 s / 3 s / 20 s); at most one call in flight per provider (no expiry), at most 3 tracked providers of a 4-thread pool, unresolved providers share one key; MIME only through `chooseMimeType`; name cut at 1024 chars, only a string column is read, then `DisplayName` | `ProviderCalls`, `SharedFileOpener`, `DisplayName` |
| SI-13 | Copy fallback: names `share-<32 hex>.bin` from `SecureRandom`, `CREATE_NEW`, private `cache/incoming`, never from provider data; the sweep deletes only exact-shape regular files, never follows links; budget = min(512 MiB, 1 GiB minus what is there) and a 64 MiB free-space reserve re-checked before EVERY chunk; a declared size over the budget is refused before a byte is written; one copy at a time; watchdog closes the input from the caller thread and the worker's output, deletes the partial file, returns the slot, abandons at most 3 workers, and a late worker cannot recreate the file; the held copy is closed first and then deleted | `WatchedCopy`, `BudgetedCopier`, `IncomingCache` |
| SI-14 | Ownership: every `register` is paired with an owner (source closed if registration returns null after one evict-retry, or on any throw); a share no player claimed within 2 min is removed; claim/release idempotent, an early claim wins, overflow keys are still reported once; keys are remembered before a player's `onDestroy` | `ShareService`, `UnclaimedShares`, `ShareKeyTracker`, `ShareRegistry` |
| SI-15 | Link policy: no DNS; authority after the LAST `@`; loopback, 0/8, link-local, own addresses, IPv6 mapped/compatible/NAT64 forms, every non-strict IPv4 spelling, `localhost` names, NFKC look-alikes refused; private LAN ranges allowed; redirect hops of the content-type probe re-checked (max 3, HEAD only, body never read); only http/https; no address means no URL and nothing is registered | `ShareLink`, `ShareService` |
| SI-16 | Logging and toasts: no key, URI, name, proxy URL or provider exception text; fixed texts, enum names, simple class names; `ShareLogRedactor` pattern `(?i)(?:/|%2f)proxy(?:/|%2f)[^\s"'<>]*`; toasts are fixed resources | all; `ShareLogRedactor` |
| SI-17 | `ShareLimits` values unchanged | `ShareLimits` (`git diff BASE` empty apart from package/javadoc) |
| SI-18 | Surfaces: manifest diff vs `071fc9d2` is exactly the one SEND filter; no new component, permission, dependency or preference key for shares; `BrowserActivity` not exported; the proxy URL is on no MediaSession/notification surface | manifest, build files |
| SI-19 | Picker UI: `filterTouchesWhenObscured` on OK, Cancel and rows; names and LAN texts only via `setText` after cleaning; the share name only through the cleaner with a fixed fallback | `DeviceSheetFragment`, `ReceiverBarController` |

### Mutation checks (reviewer, throw-away copy)
For each group the reviewer makes a git worktree of the group's parent commit and of the group's HEAD in the scratchpad (never in the repo), applies the mutants below that belong to the classes the group touched (at least 6 per group, always including every security-critical class the group moved), runs only the affected test classes (`--tests`), and records per mutant: killed or survived, and the killing test names. Expected: the same mutants are killed before and after, by at least as many test cases. A mutant that survives after but was killed before is a FAIL of the group. Mutants are candidates; the reviewer adapts them to the code in front of him.

| Class (after) | Mutants |
|---|---|
| `ShareRegistry` | key from 8 bytes; `createDefault` random replaced by a constant; `lookup` returns an expired entry; `register` ignores `maxActive`; `evictOldestReleased` evicts a running entry; entry closed twice / never closed on `remove`; `isWellFormedKey` accepts 21 chars; `registerOrEvict` retries without evicting |
| `ProxyAccessPolicy` | empty allowed set permits while restricting; missing remote address permits while restricting; no mapped-IPv4 normalization; zone id not stripped; `restrict=false` branch inverted; `allowedSet` adds loopback although the local device was not chosen |
| `ByteRangeResolver` | two ranges answered 206; invalid header answered 416; suffix longer than the file not clipped; digits overflow wraps |
| `PositionalSource` / `HeldFileSource` | `close()` blocks until reads end; real close runs twice; reads allowed after close; `clampLength` returns the declared length unclamped |
| `ShareConcurrencyLimiter` / `ShareProducers` | cap 4 -> 5; release not idempotent; `tryReacquire` always true; producer ignores `shareLive` |
| `ProxyShareResponder` | IP check skipped for HEAD; IP check skipped when a `Range` header is present; 404 body contains the key |
| `ServerSideSeeking` | invalid renderer id accepted; state map unbounded; estimate beyond 85% of the file |
| `SharedContentPolicy` | `file:` accepted; own authority accepted; wildcard MIME accepted; `validateStream` ignores `hasData`, the read flag, or accepts 2 clip items; an own-provider check that throws returns "not ours" |
| `LauncherForwardPolicy` | SDK >= 35 with a denied caller still forwards; a throwing caller check forwards; text over the cap truncated instead of dropped |
| `LaunchNonces` | token reusable; TTL ignored; more than 8 outstanding |
| `PendingShare` | a second offer replaces; `take` twice returns data; wrong token accepted; timeout `>` instead of `>=` |
| `ProviderCalls` | gate permit never released; two calls per provider allowed; gate gets an expiry; `isRefusedBusy` true for a timeout |
| `SharedFileOpener` | an abandoned open result not closed; probe runs without the type-string check on the name column |
| `WatchedCopy` / `BudgetedCopier` | budget checked only before the first chunk; free space not re-read; input not closed at the deadline; partial file kept on failure; more than 3 abandoned workers allowed |
| `IncomingCache` | sweep deletes a non-matching name or follows a symbolic link; the copy deleted before the delegate is closed; random name from a fixed seed |
| `DisplayName` | Cf characters kept; no length cap |
| `ShareLink` | `127.1` accepted; authority taken after the FIRST `@`; redirect hop not re-checked; `http:///x` accepted |
| `UnclaimedShares` | exactly-at-window counts as due; an early `claimed` forgotten; overflow key lost |
| `PendingPick` | a second `open` replaces the open pick; a stale id resolves; `abandon` does not cancel; confirm without receiver closes the pick |
| `ActionFlight` | a second `tryBegin` succeeds; watchdog disabled; a stale ticket ends the next flight |
| `ReceiverPickController` | after-start hook clears in classic; restores in the new layout |
| `ShareIntentHandler` (incl. gate) | payload admitted in a non-effective layout; intent consumed after validation instead of before; second pending share accepted |
| `ShareLogRedactor` | `%2f` alternative removed; case-insensitivity removed |
| `ShareService` | source not closed when the registry is full; share registered although no address; failure text always "open failed" |

### Groups
| Group | Content | Classes after |
|---|---|---|
| 17 | spec rule removal done; characterization tests of `ShareService.prepare`; package move `upnp.server.share` -> `share` (pure); rename `SharedFileService` -> `ShareService`; delete/replace `ShareProxyUrl`, `ShareFailureMessage`, `ReceiverSelection`; merge `ShareRegistrar`, `DescriptorWindow`, `ReceiverBarLabel` | 62 |
| 18 | serving side: `ServerSideSeeking` (+3), `ProxyAccessPolicy` (+`ShareAddresses`), `ShareLink` (+2), `RangeStreamer.Sink`, `ShareProducers` | 53 |
| 19 | provider calls, file open and copy: `ProviderCalls` (+2), `SharedFileOpener` (+`ProviderProbe`), `WatchedCopy` (+`AbandonableCall`), `BudgetedCopier` (+`CopyBudget`), `IncomingCache` (+2) | 46 |
| 20 | intake: `SharedContentPolicy` (+3), `LauncherForwardPolicy` (+`SenderGrantCheck`), `ShareService` (+3), `ShareIntentHandler` (+`ShareIntakeGate`) | 38 |
| 21 | picker: `PendingPick` (+2), `ActionFlight` (+guard), `ReceiverPickController` (+`AfterStartSelection`), `DeviceSheetFragment` (+`PickTitle`) | 33 |
| 22 | docs/tech.md, spec files, final numbers | 33 |

Every group: build -> `reviewer` PASS -> `security-reviewer` PASS (sequential, max 3 cycles). Groups 18-21 move security-critical code, so both reviews apply to each even though no behaviour changes; the security reviewer's job there is the invariant table and the mutation table above.

### Risks and rollback
- **Untested Android glue** (`ShareService#prepare`, `SharedFileOpener`, `HeldFileSource` I/O, `UpnpClient#createHttpLinkShare` registration) has no unit tests at BASE, so a slip would not be caught. Mitigation: Group 17 adds characterization tests for `prepare` and the link registration first (source closed on every failure path, nothing registered without an address, unclaimed registration); the merges are pure moves checked with `git diff -M --color-moved`; the on-device list in decisions.md (2026-10-26, Group 15 b) is re-run by the owner at the end.
- **Mock-based tests are weaker than the old pure tests** where Android types now appear in arrange code. Mitigation: the string-level policy parsers stay string-level (see "not changed on purpose"); mutation checks run on the moved tests.
- **Large mechanical diffs tire reviewers.** Mitigation: renames and package moves are separate commits (similarity 100%), merges follow, one class cluster per commit, reviewers get the old->new table.
- **Parallel work on the branch** would conflict. The owner freezes share-related changes until Group 22 is done.
- **`java.net.URI` vs the old string builder** (IPv6 brackets, zone ids, whitespace): Group 17 compares both outputs on the four old inputs plus a zone-id input before the old class is deleted.
- **`InterfaceResolutionHelper.getIpAddress` returns `"0.0.0.0"`, not null, when offline** (the Group 13 review assumed null). The "no address" guard therefore rarely fires; behaviour is preserved and the observation is recorded for the owner; not fixed here.
- **Rollback.** Each group is a series of commits tagged in the message with `phase4-group-N` (no tags, no history rewriting, no force-push). `git revert --no-commit <first>^..<last>` in reverse order undoes a group; later groups must be reverted first. BASE `ecb26c59` is the restore point of the whole phase. After 3 failed review cycles on a group, revert that group and escalate with the unresolved findings.
