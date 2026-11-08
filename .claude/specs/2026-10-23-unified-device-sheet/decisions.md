# Decisions: unified device sheet, layouts, sharing

Durable decisions as "decision - reason", grouped by topic. The dated cycle logs were removed when this folder was compacted
(they are in git, and on the origin branch `backup/pre-squash-2026-10-26`). Owner decisions are marked (owner). Open questions are
in `tasks.md` ("Owner's open items"); accepted risks in `known-risks.md`; how it works in `docs/tech.md`.

> Condensed on 2026-11-04: each section keeps its heading and its first lines (the first bullets, cut at about 900 characters). The rest, with the reasoning, tables and
> test notes that followed are in the git history of this file (the commit before the cleanup). `design.md` and `docs/tech.md` carry the as-built facts.

## Device sheet (Phase 1)

- One sheet, one list grouped by receiver, with 0..N player-session rows under each - receivers and players are not 1:1 (a renderer
  can hold a music and an image player; the local device up to three), so a merged row would hide sessions.
- Sessions are ordered by a creation sequence assigned in `PlayerService`, not by player id - `AVTransportPlayer` ids are random and
  local ids are fixed per type, so the id says nothing about age. The receiver tab's single row shows the newest playing session,
  else the newest overall, independent of input order (fixes the old arbitrary `HashMap` pick).
- `PlayerListChangeNotifier` decides from what `put`/`remove(id, player)` return: a same-id replacement fires "removed" then "added";
  a repeat add or the second removal from `onDestroy()` fires nothing. `currentActivePlayer` is a `ConcurrentHashMap` because
  players are added off the main thread. …

## Classic and new layout (Phase 2)

- Separate activities chosen by a "Layout" setting in the normal settings screen; `TabBrowserActivity` is not renamed and stays
  exported without filters - hiding tabs in one activity would first need the tab order decoupled, and copying fragments doubles
  every fix; an exported shortcut that stored the old component keeps working.
- `LauncherActivity` (invisible, `Theme.NoDisplay`, `extends Activity`) owns the launcher, TV and SEND filters and forwards a FRESH
  allowlisted intent (never a copy of the incoming one) - the launcher is exported, so a copy would turn a third-party intent with
  its flags, ClipData URIs, nested intents and grants into a same-app intent.
- Layout switches are detected on resume by comparing the EFFECTIVE layout with the activity's own, behind a 2 s relaunch guard -
 …

## Look and settings (recent changes)

- Theme is a three-state list setting (System, Dark, Light) replacing the "Dark mode" check box; a fresh install starts Dark, an
  existing choice migrates once at start (`ThemeChoice.migrateLegacyDarkMode`: on -> Dark, off -> System) and the old key is removed.
- The whole app is gray scale (owner): every theme color is a gray in `res/values/colors.xml`, with dark variants in
  `res/values-night/colors.xml`; the launcher icon is the only colored asset; `GrayscaleResourcesTest` pins it. The title bar is dark
  gray in both themes (black in the dark theme) with light title and icons, because the gray primary color made it light in the dark theme;
  settings section headlines are bold; the image viewer's toolbar icons are white on the dark bar.
- Log viewer "Clear" is a time marker (`logcat -T <time>` via `LogCommand`), not only `logcat -c` - the system may refuse to empty the
 …

## Sharing files and links (Phase 3)

- Per-share registry entry, not the persistent proxy setting (owner): the user's settings are never changed. Keys are 128-bit random,
  in memory only; the old `proxy_link_*` preferences are deleted at start; expiry is player end + 10 min, absolute 6 h, max 8 entries.
- Nothing is started or stopped for a share - the HTTP server listens whenever the process lives (`Yaacc.onCreate` starts the service
  and the server is created unconditionally; the "local server" setting only picks the published UPnP services). `/proxy/<key>` is
  served before the preference gate and the share waits until the server answers.
- A local file is held by its descriptor, opened only after the user confirmed (owner): no copy by default, no descriptor, copy or
  registry slot for an unconfirmed share. The descriptor is `dup`ed and the provider's closed (a stable provider reference would let a
 …

## Phase 4: consolidating the share code (owner)

- Android types are allowed in the share classes (the "no `android.*`" rule is withdrawn: it split `Uri`/`PackageManager` handling off
  into one-method classes, and the existing tests already mock Android types with Mockito).
- Keep / merge / delete by a rule (state or state machine, own invariant with mutation targets, boundary type = keep), so the count
  (68 -> 33 files, 27 in the share package) follows from the rule; 12-18 would need 700+ line classes mixing unrelated invariants.
- Moves, not rewrites; no drive-by fixes; `UpnpClient`/`ShareIntentHandler`/`ShareService` public API unchanged; two provider pools stay
  two; SI-1 ... SI-19 and the mutation table are the review contract; renames are separate commits; rollback by `git revert` per group.
- Characterization tests of `ShareService#prepare` and the link registration come first (that glue had no unit test). …

## Process
- The Android build works in a fresh container once the SDK is installed (command-line tools, licenses, `platforms;android-36`,
  `platform-tools` into `/root/android-sdk`, `sdk.dir=/root/android-sdk` in the git-ignored `local.properties`); Maven Central sometimes
  answers 429, re-running lets Gradle cache what it got. Belongs in the environment's setup script.
- `.claude/specs/` is tracked in the public repository; whether it goes into the main branch is the owner's call (default: merge only
  `yaacc/` and `docs/`).

## Owner decisions of 2026-10-28

- Classic keeps pre-ticking the saved receivers for a shared file: convenient, and the owner accepts one-tap playing on the usual receivers.
- Local video share keeps the implicit `ACTION_VIEW`; no in-app video player: not worth the work for a bearer URL that works for one share only.
- Add to playlist creates a player for every chosen receiver that has none (`PlaylistTargets`, `PlayerService#createPlayerFor`):
  the old rule did nothing for such a receiver as soon as another chosen one was playing.
- `.claude/specs/` is merged with the branch (develop carries spec folders already); the owner only wants them consolidated, which is done (supersedes the earlier "not merged").
- Phase 4 stage 2 not done; test-only members kept; `"0.0.0.0"` address is a separate item; several player sessions per renderer stay.
 …

## Material 3 gray scheme (owner decisions of 2026-10-29)

- **Why.** The owner chose Material 3 with the app's own gray scheme (reference look: K-9 Mail, Feeder): the dark theme has a PURE BLACK page, the
  light theme a LIGHT title bar (no dark band), tonal surfaces (page darkest in dark, containers a step lighter), big rounded cards without shadow,
  hairline dividers, soft off-white primary text and dimmer secondary text, still no color besides the launcher icon.
- **Theme.** `AppTheme` parent `Theme.Material3.DayNight` (support action bar kept, no Toolbars); all Material 3 roles are set in
  `res/values/colors.xml` and `res/values-night/colors.xml` as `gray_<role>`; the table is in `docs/tech.md` ("Look and settings"). Dark: page and
  surface `#000000`, containers `#0F0F0F` / `#161616` / `#1E1E1E` / `#262626`, text `#E3E3E3` / `#A8A8A8`, primary `#D6D6D6` on `#1B1B1B`. Light:
 …

## Browse resilience: retry and reload after a network drop (owner decisions of 2026-10-29)

- **Why.** A short network drop showed an empty folder (the failed load cleared the list). The app should hide that, no user action.
- **Retry.** `BrowseRetryPolicy`: 3 tries in total, 500 ms then 1500 ms, worker thread only, stops on cancel/interrupt. Retriable = no result, or an
  `UpnpFailure.isTransportFailure()` (`UpnpTransportException` from `SendingAction` for "no response"/IO error, plus device not in the registry and
  no HTTP sender in `UpnpClient.browseSync`). A server fault is never repeated (the same request fails the same way); the sorted-to-unsorted
  fallback is untouched, and a sorted request that got no answer is not blamed on the sort criteria (no `markServerSortRejected`, no second round unsorted).
  Other codes of the same value (501 from a server fault) stay non-transport on purpose: the type, not the code, decides.
 …

## Browse resilience: suspend and resume (owner decisions of 2026-10-29)

- **Why.** Owner report: after a while "the connection seems lost": play refuses and the folder is empty. Code reading: the registry expires remote devices
  by wall clock after a sleep (maintainer thread, no ssdp:alive could be handled); `UpnpClient.isInitialized()` is false while the network listener is
  disabled, so `getDevice` returns null for provider and receivers; `ContentListFragment.onResume`/`init` cleared the folder when the provider was null
  and `deviceRemoved` could (it compared with `getProviderDevice()`, which is already null once the registry dropped the device); `getReceiverDevices()`
  removes a missing id from the stored selection for good and `PlayerService.createPlayer` then shows "no receiver selected". Not reproduced on a device.
- **Resume hook.** On activity `onResume`: `UpnpClient.refreshAfterResume()` on the client executor. …

## Large SAF folders: one listing, cached (owner report, 2026-10-29)

- **Why.** Browsing a DCIM/Camera tree with thousands of files froze the UI for 4.3 s, starved GENA subscribes ("Failed to acquire lock in milliseconds:
  5000") and ended with an empty Browse failure after the retries (the answer took longer than the 10 s socket timeout). Cause (read in `SafFolderBrowser`
  and in androidx `documentfile` 1.1.0): `listFiles()` is one query, but every property of every child is another provider query, and each chunk request
  listed the folder three times (count, containers, items) and sorted with property queries inside the comparator.
- **Decision.** One query per listing (`SafDirectoryLister`, `buildChildDocumentsUriUsingTree`, five columns, no FLAGS), kept in `SafListingCache`
  (8 folders, 100000 entries, 30 s, single flight, cleared on SAF cache clear / permission removal / SAF path preference change). Results are the same
 …

## Shared folders tree: worker thread, plain node data, range inserts (owner report, 2026-10-29)

- **Why.** The server configuration screen froze on a SAF folder with thousands of files: `clickedOnDocument` ran `listFiles()` and `exists()` per child on the
  UI thread, `TreeViewHolder.bind` asked the provider `getName()`, `isDirectory()` (twice) and a full `listFiles()` per folder row for the expand symbol,
  the tree manager was a `LinkedList` read by position per bind, and every expand/collapse was `notifyDataSetChanged`.
- **Decision.** One single-thread executor per activity (`shutdownNow` in `onDestroy`, no AsyncTask) reads folders with `SafDirectoryLister` (one query, shared
  cache) or `File.listFiles()`; `TreeNode` holds plain `TreeNodeData`; a per-node load generation plus a tree generation drop stale results (tap again while loading
  = collapse). Expand symbol for every folder until it was read and is empty (no emptiness query per row). …

## Content list: sort and thumbnails off the main thread (owner request, 2026-10-29)

- **Why.** The sort of a finished folder (`sortObjects` + `notifyDataSetChanged`) ran on the main thread; every bind made a new `IconDownloadTask` (also for rows that
  never load anything, and cancelled tasks never left `asyncTasks`), re-inflated and re-tinted the type icon, and reloaded a thumbnail that was just scrolled away.
- **Decision.** `BrowseSorter` (pure, same comparators, the old code is the reference in the test) sorts a copy on a single idle-timeout daemon thread owned by the
  adapter; a generation counter drops stale results; the swap notifies only the changed range (with a payload, no change animation). Thumbnails: a task only for rows
  that have one, cancelled on re-bind/recycle, `ThumbnailSlot` skips the request a row already shows, byte-bounded LRU for decoded bitmaps (the old one counted entries),
  type icons from a night-mode keyed cache of constant states. …

## SAF metadata cache: own preferences file, batched commits, lazy load (owner request, 2026-10-29)

- **Why.** Default preferences + one `apply()` per file/short id = a rewrite of the whole XML per entry, a possible wait in `QueuedWork` at activity stop, and a full deserialization
  in the constructor, which can run on the main thread. `lruCache` was touched by the preload thread without the lock.
- **Decision.** File `saf_cache` (same keys, so the migration is a copy). One-time migration as a pure plan (copy what the new file lacks, counter = max, remove old keys only after the
  new file was committed; rerun until no old key is left). Writes: buffer + flush policy (250 changes / 3 s / preload end / exit), `commit()` on the dedicated `SAFCacheIO` thread. Load on that
  thread; main thread never waits (transient `t<n>` ids, nothing persisted), other threads wait up to 30 s. One lock for LRU, buffer and policy; I/O never under it.
 …

## BrowseMetadata of SAF objects (owner request, 2026-10-29)

- **Why.** `browseMeta` base64-decoded the short id (never worked for short ids) and passed the object's own id as the parent of an item.
- **Decision.** Short id -> uri through the SAF cache, one document query, parent from the document id (path-like ids, no query) or `findDocumentPath` (opaque ids), fallback SAF folder with a W log.
  Sub folders are built by the same `newFolder`/`folderTitle` as the listing of their parent, so `[X]`/restricted are identical. Pure id and uri handling in `SafDocumentUris` (tests).
  A null result of any `browseMeta` is now error 701 instead of an empty DIDL.
- **Left alone.** `TotalMatches` of BrowseMetadata (child count, all browsers), `restricted = canRead` (the listing's existing semantics, copied), folder ids are still not persisted across a restart.

## Missing HTTP sender: every client call is null-safe (owner request, 2026-10-29)

- **Why.** `browseSync` was guarded; the same `getNetworkDeviceListener().getHttpRequestSender()` chain was still in `searchDevices`, `getMute`/`setMute`/`getVolume`/`setVolume` and
  `AVTransportPlayer.getHttpRequestSender()`; a NPE there reached a pool or async thread.
- **Decision.** One public helper `UpnpClient.getHttpRequestSender()`. Mute/volume/search: one log line, default result. Player callbacks: the null goes into the callback and
  `ActionCallback.run()` answers it with the callback's own `failure(...)` (the callers' existing failure paths release their waits); a `RuntimeException` of the send is a failure too.
- **Left alone.** `setMute`/`setVolume` for a local device do not `return` after the local call (they go on to look for a RenderingControl service of the local device); the busy wait
 …

## Registry protocol handler follows the network listener (owner request, 2026-10-29)

- **Why.** The "Left alone" item of the resume entry: the registry's handler was set once; every re-enable builds a new one with new sockets and closes the old ones.
- **Decision.** `enable()` sets the new handler in the registry (in the lock, before the receivers start), `disable()` sets null; `RegistryImpl` field `volatile`; registry code is null-tolerant
  (`RemoteItems` renew/unsubscribe, `LocalItems` already was). ALIVE again after "enabled for a new network" (network callback, resume check) via `Registry.advertiseLocalDevices()` on a short-lived
  thread (the registry method is synchronized and can wait for its lock). Test seams: a package-private constructor and receiver factories in `NetworkDeviceListener`.
 …

## HTTP server resolves the protocol handler per request (owner request, 2026-10-29)

- **Why.** `createHttpServer` passed `getNetworkDeviceListener().getUpnpProtocolHandler()` once to `YaaccUpnpServerProtocolRequestHandler`; a service started without a network kept null (every sync request threw an NPE, answered 500)
  and after a network change it kept the replaced handler. The "Left alone" item of the registry-handler entry.
- **Decision.** The request handler takes a `Supplier<UpnpProtocolHandler>`; the service supplies `registry.getUpnpProtocolHandler()` (the live volatile field, set by `NetworkDeviceListener.enable()`, null on `disable()`). It is read once per
  `process()`. No handler or a throwing supplier: 503 and one W log line; `createReceivingSync` returning null: 501 (the same status as a `ProtocolCreationException`). Tests with Mockito in `YaaccUpnpServerProtocolRequestHandlerTest`.
 …

## SAF preload writes readable metadata (owner request, 2026-10-29)

- **Why.** The "found, not fixed" item of the SAF cache entry: the preload stored a bare duration under `saf_cache_<uri>`, `getMetadata` could not parse it and extracted the duration again; `getDuration` returned whatever was stored.
- **Decision.** All writers of the key store the serialized `SAFMetadata` (`storeDurationLocked`: existing entry keeps mime type, size and short id; a new entry from the preload gets name/type/size of the document and the id the uri has, a new one only if it has none).
  Readers tolerate an older bare value (pure `SafCacheEntries`) and upgrade it in place without extraction (D log `CACHE_UPGRADED`). The preload extracts only for a file without entry or with an empty duration, and the queued background task
  skips a file that already has a duration. `getDuration` (deprecated, no callers) parses the entry and does not create one (it does not know the size). …

## One accessor pair for the registry's protocol handler (owner request, 2026-10-29)

- **Decision.** `RegistryImpl` keeps `getUpnpProtocolHandler`/`setUpnpProtocolHandler` (declared in `Registry`, used by `NetworkDeviceListener`, `LocalItems`, the HTTP server); `getProtocolHandler`/`setProtocolHandler` removed, `RemoteItems` and its test
  switched. The field stays `volatile` (a test checks the modifier and that the old names are gone).

## BrowseMetadata answers 1 and 1 (owner request, 2026-10-29)

- **Why.** The "Left alone" item of the BrowseMetadata entry: `browse` called `getSize` first for every browser, so `TotalMatches` of a BrowseMetadata was the child count and the folder was listed for it.
- **Decision.** `getSize` only for BrowseDirectChildren; BrowseMetadata sets NumberReturned = TotalMatches = 1 after `browseMeta` found the object (null is still 701, without a `getSize`). DirectChildren, UpdateID and the test-content branch are unchanged.
  `findBrowserFor` is package-private so a test can plug in a fake browser; no `Search` action exists, so there is no other count path.
- **Left alone.** The test-content branch (`TotalMatches` stays 1 for its folders); an unknown BrowseFlag is handled like DirectChildren.

## Repeat and shuffle per player (owner request, 2026-10-30)

- **Why.** Repeat and shuffle were global settings (`settings_replay_playlist_chkbx`, `settings_music_player_shuffle_chkbx`) read at several places (`AbstractPlayer.next()` / `isShufflePlay()` / `setItems`,
  `LocalMediaSessionPlayer`, and written by `AVTransportPlayerWrapper.setRepeatMode` / `setShuffleModeEnabled`); one player could not repeat while another did not, and a controller changing the mode rewrote the settings.
- **Decision.** The settings are only the start value of a new player (`PlayModeSettings.defaultPlayMode`, read once per player). Each `Player` has a transient `PlayMode` (repeat OFF/ALL/ONE + shuffle),
  `getPlayMode` / `setPlayMode` and `PROPERTY_PLAY_MODE`; nothing is written back. Pure `PlayMode` (UPnP mapping both ways) and `PlayOrder` (order, next/previous, shuffle created once, current item first,
 …

## Playlist dialog shows the play order; external controllers are mirrored (owner request, 2026-10-31)

- **Why.** Since `PlayOrder` shuffles instead of the stored list, the playlist dialog showed the list order while the player followed another; and a system / Bluetooth / notification / Wear controller that
  set ExoPlayer's repeat or shuffle mode left the `PlayMode` (buttons, `CurrentPlayMode`) stale (the "left alone" item of the entry above).
- **Decision (dialog).** The dialog shows `Player.getPlayOrder()` (identity without shuffle, so the list order as before). Pure logic in `PlayOrder`: `positionOf` / `itemAt`, `move(from, to)` (shift, as a drag),
  `remove(index)` (keeps the relative order of the rest; `fit` would shuffle again), `isAtOrBeforeCurrent` (lock rule). `AbstractPlayer.moveInPlayOrder` / `removeItem` / `isPlaylistPositionLocked` are the edit API
 …

## Home Assistant events again: why 066a598e could not work, what it could break, and the mode memory (owner test, 2026-10-31)

- **Symptom (owner).** With 066a598e (the renderer sends its `LastChange`) Home Assistant's shuffle and repeat buttons seemed to do nothing in the app player, which had started with shuffle enabled; before it the
  buttons changed the app player (HA showed no state). The commit was reverted (592bc528) and this is the second attempt, done with a harness that runs the real receiving path (`RendererHarness`: the real
  `AnnotationLocalServiceBinder` service, `YaaccUpnpServerProtocolRequestHandler.handle`, `UpnpProtocolHandler`, `ReceivingAction` / `ReceivingSubscribe`, Cling's message parsing and action executor, mocked
  `UpnpClient` / players / `HttpRequestSender`).
- **Finding 1 - the events never left the device, with or without 066a598e.** `ReceivingSubscribe` registers the subscription at the service and sends the initial event in `responseSent(...)`, which the server must
 …

## RenderingControl events, the other services, and the idle log flood (owner test, 2026-11-01)

- **Symptom (owner).** Home Assistant's repeat and shuffle are right now, its mute state is not; the log has `AvTransportMediaRendererPlaying: doSetTrackInfo: ...Posinfo ... 00:00:00` several times a second with no player active.
- **Finding (mute).** The RenderingControl service never sent an event: plain `DefaultServiceManager`, nothing put into its `LastChange`, `getCurrentInstanceIds()` / `getCurrentChannels()` returned `null` (the full state could not even be built), `eventMaximumRateMilliseconds = 200` on `LastChange`.
  Home Assistant is subscribed, so it does not poll `GetMute`. **Decision:** the machinery of the AVTransport events becomes `AsyncLastChangeServiceManager` (+ `ObservableLastChange`, `SubscriberAwarePropertyChangeSupport`) in `upnp/server/events`;
  `RenderingControlServiceManager` is its second user. …

## Live volume and mute in the receiver rows (owner test, 2026-11-01)

- **Symptom (owner).** With the receiver sheet open, a mute/volume change made in Home Assistant is not visible; the sheet has to be closed and reopened.
- **Finding.** `ReceiverRowBinder.bind` read volume and mute once per bind (`DeviceVolumeStateLoadTask` / `DeviceMuteStateLoadTask`). The sheet rebinds a row only when the device or primary player changes (`DiffUtil`), and a status update rebinds it
  through `notifyItemChanged` (that did re-run the load tasks for remote rows, every 10 s, mute included, on the serial `AsyncTask` thread, and set the slider while the user drags). The local row is not in the monitor; `localDeviceStatus` built a status with volume 50
  and `bind` pushed it into the slider (`status.getVolume()`), and the monitor did the same with 50 whenever `GetVolume` failed or was missing. …
