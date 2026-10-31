# Decisions: unified device sheet, layouts, sharing

Durable decisions as "decision - reason", grouped by topic. The dated cycle logs were removed when this folder was compacted
(they are in git, and on the origin branch `backup/pre-squash-2026-10-26`). Owner decisions are marked (owner). Open questions are
in `tasks.md` ("Owner's open items"); accepted risks in `known-risks.md`; how it works in `docs/tech.md`.

## Device sheet (Phase 1)
- One sheet, one list grouped by receiver, with 0..N player-session rows under each - receivers and players are not 1:1 (a renderer
  can hold a music and an image player; the local device up to three), so a merged row would hide sessions.
- Sessions are ordered by a creation sequence assigned in `PlayerService`, not by player id - `AVTransportPlayer` ids are random and
  local ids are fixed per type, so the id says nothing about age. The receiver tab's single row shows the newest playing session,
  else the newest overall, independent of input order (fixes the old arbitrary `HashMap` pick).
- `PlayerListChangeNotifier` decides from what `put`/`remove(id, player)` return: a same-id replacement fires "removed" then "added";
  a repeat add or the second removal from `onDestroy()` fires nothing. `currentActivePlayer` is a `ConcurrentHashMap` because
  players are added off the main thread. Whether a replaced player should also be shut down was left unchanged (behaviour change
  outside this work).
- The row tap is a no-op only for the `UpnpClient.LOCAL_UID` dummy - yaacc's own renderer is a `LocalDevice` controlled through an
  `AVTransportPlayer` and its tap always opened that player.
- One shared `RendererStatusMonitor` (`getShared()`, subscribe/unsubscribe) used by tab and sheet - two monitors would double the
  polling; the tab was switched to it although phase 1 promised "tabs unchanged".
- `UpnpClient` keeps the player-list listeners and re-attaches them when `PlayerService` (re)binds - the sheet can open before the
  service is bound.
- The sheet polls and listens only while started (not while a player activity is on top).

## Classic and new layout (Phase 2)
- Separate activities chosen by a "Layout" setting in the normal settings screen; `TabBrowserActivity` is not renamed and stays
  exported without filters - hiding tabs in one activity would first need the tab order decoupled, and copying fragments doubles
  every fix; an exported shortcut that stored the old component keeps working.
- `LauncherActivity` (invisible, `Theme.NoDisplay`, `extends Activity`) owns the launcher, TV and SEND filters and forwards a FRESH
  allowlisted intent (never a copy of the incoming one) - the launcher is exported, so a copy would turn a third-party intent with
  its flags, ClipData URIs, nested intents and grants into a same-app intent.
- Layout switches are detected on resume by comparing the EFFECTIVE layout with the activity's own, behind a 2 s relaunch guard -
  a plain stored-value comparison loops forever when a stored "new" meets `NEW_LAYOUT_AVAILABLE == false` (backup restore, downgrade).
  `finish()` only, no `CLEAR_TASK` (it would kill the playing task's other activities). `getString(key, null)` is the only read.
- The four fragment branches that cast `getParent()` to `TabBrowserActivity` never ran (`getParent()` is null); they were removed, not
  wired - wiring would have changed classic back and tab jumps. Fixing classic back stays a separate owner decision.
- The new layout has its own back handling through `OnBackPressedDispatcher` (folder pop, then server list, then exit).
- One process-wide idle timer counting started browsers - on a layout switch or rotation the old browser's `onStop` runs after the new
  browser's `onResume`, so cancel-on-resume left a timer that quit the app five minutes later.
- The share handler was extracted (`ShareIntentHandler`): a waiting share survives a rotation (graceful executor shutdown, the callback
  is cleared so the queue does not pin the activity), at most 3 queued, shared text only `http`/`https` up to 2048 characters, a clip
  item's URI is never used for text, exceptions are logged by class name only.
- `ServerListFragment` serves both layouts (no copy); fragments remove their `UpnpClientListener` in `onDestroyView`; the shutdown
  timer, MediaProjection stop callbacks and `leftSettings` became listener lists / `consumeLeftSettings()` so destroyed screens are
  not kept alive.
- Mini-player shows every running player, one compact row each, oldest first, capped at about three rows (owner feedback). LAN texts are
  cleaned (`MiniPlayerPresenter.cleanText`: whitespace, control and bidi characters; ZWJ/ZWNJ kept for emoji and Persian). The art key
  is `playerId:artUri`, never built from `getIcon()` (which queues a download per call), so a hostile art URL is not requested every poll.
- The toolbar timer is one symbol (owner feedback): tap stops a running timer, otherwise opens the picker and a duration above 0 starts
  it; a stored duration of 0 never starts it (it would exit the app at once). The remaining time is shown as the toolbar subtitle in
  portrait too, because narrow screens drop the text next to the symbol.
- Classic keeps the original static two-line footer and no sheet (owner feedback); the sheet and the tappable receiver bar are
  new-layout only. Answers the old "does classic keep the sheet" question with no.
- Default layout (owner): a fresh install starts in the new layout (preference default `new`), an update without a stored value gets the new
  layout too (owner, 2026-10-29; `keepClassicForUpdatedInstall` removed, only an explicit `classic` selects classic).
  `NEW_LAYOUT_AVAILABLE` stays as a kill switch.
- Android backup is off (owner, `allowBackup="false"`) - it copied preferences incl. stored share links, the receiver selection and
  SAF cache entries to the user's account; no exclusion rules, settings are re-entered on a new device.
- Card view is the default on a fresh install (`ViewMode.DEFAULT = CARD`; an earlier explicit choice stays); the landscape browser has
  the list/card button and several card columns; the content header of the new layout has a server-list button (icon of the Server tab).

## Look and settings (recent changes)
- Theme is a three-state list setting (System, Dark, Light) replacing the "Dark mode" check box; a fresh install starts Dark, an
  existing choice migrates once at start (`ThemeChoice.migrateLegacyDarkMode`: on -> Dark, off -> System) and the old key is removed.
- The whole app is gray scale (owner): every theme color is a gray in `res/values/colors.xml`, with dark variants in
  `res/values-night/colors.xml`; the launcher icon is the only colored asset; `GrayscaleResourcesTest` pins it. The title bar is dark
  gray in both themes (black in the dark theme) with light title and icons, because the gray primary color made it light in the dark theme;
  settings section headlines are bold; the image viewer's toolbar icons are white on the dark bar.
- Log viewer "Clear" is a time marker (`logcat -T <time>` via `LogCommand`), not only `logcat -c` - the system may refuse to empty the
  buffer of an app. The log is read in the background, refreshed every 2 s while the viewer is open and only auto-scrolls while the user is
  at the end.
- Mini-player rows have an exit button besides stop: stop only stops the playback, exit removes the player.
- In the pick sheet OK is a text button like Cancel (readable on the dark sheet).

## Sharing files and links (Phase 3)
- Per-share registry entry, not the persistent proxy setting (owner): the user's settings are never changed. Keys are 128-bit random,
  in memory only; the old `proxy_link_*` preferences are deleted at start; expiry is player end + 10 min, absolute 6 h, max 8 entries.
- Nothing is started or stopped for a share - the HTTP server listens whenever the process lives (`Yaacc.onCreate` starts the service
  and the server is created unconditionally; the "local server" setting only picks the published UPnP services). `/proxy/<key>` is
  served before the preference gate and the share waits until the server answers.
- A local file is held by its descriptor, opened only after the user confirmed (owner): no copy by default, no descriptor, copy or
  registry slot for an unconfirmed share. The descriptor is `dup`ed and the provider's closed (a stable provider reference would let a
  dying sender app kill yaacc); reads are `Os.pread` (no shared offset, no interruptible channel); close is deferred to the last reader
  (a reused descriptor number under a running read is a security bug).
- Copy fallback only for non-seekable descriptors: `cache/incoming` (never `cache/shared`, which `FileProvider` exposes), random names
  from provider-independent bytes, 512 MiB per file, 1 GiB in the directory, 64 MiB free space re-checked before every chunk, one copy
  at a time, 5 min watchdog, at most 3 abandoned workers, swept at start. The caller closes the worker's input AND output at the
  deadline so a stuck worker does not keep a deleted file's blocks.
- Restriction setting "Restrict the media proxy to the chosen receivers" (owner), default ON in code, in `preference.xml` and when the
  read fails; OFF keeps key and expiry only. Loopback and the phone's own addresses are allowed only when the local device was chosen.
  The address comes only from the connection's endpoint details; a missing address is denied; zone ids are stripped (so the same
  `fe80::` address on two interfaces counts once, accepted); host names are never resolved.
- Responses: constant 404 body for every bad key (no key or path echoed or logged), 403 only after a valid key, `nosniff` and CSP
  `sandbox`, `Content-Type` from the entry, no `Content-Disposition`; at most 4 responses per share; a response with less than 16 KiB
  progress in 60 s loses its slot but not its stream (a long pause is normal) and aborts on resume only when the share is full.
- Server-side seeking was restored on the registry for http(s) shares only (owner): the 3-segment `/proxy/<deviceId>/<key>` is produced
  only for a remote renderer whose per-renderer setting is on; the device id is validated after key and IP, and the per-share state map
  is bounded (8 renderers, LRU).
- The launcher forwards ONE `content:` URI as ONE ClipData item (`ClipData.newRawUri`) plus the read flag and nothing else - the system
  checks `data` and ClipData, never `EXTRA_STREAM`, so it first requires `EXTRA_STREAM` == item 0 and the read flag in the incoming
  flags. MIME comes from the provider (`chooseMimeType`), never the manifest filter. Any provider of this app is refused (not only the
  file provider), `file:` is refused. On API 35+ the launcher asks `getInitialCaller()` for the log only (changed 2026-10-29: it denied every share from a gallery, because via the share sheet the initial caller is the sharesheet; the system already checked the sender when it launched with the read flag); a `SecurityException` from the
  forward falls back to a plain launch.
- Launch nonce instead of a second sender check in the browsers: the browsers are exported or reachable, so a file is accepted only with a
  single-use token minted by the launcher after ALL its checks (128 bits, 30 s, max 8 outstanding, constant-time compare, memory only,
  never logged). A re-delivery after process death carries a dead token and is dropped.
- `ShareIntakeGate`: an activity takes a share payload only when it renders the effective layout, else the payload is removed unread
  (an activity about to relaunch must not run the wrong branch). A share racing a layout switch is lost, accepted.
- Intake repeats every check in the browser, consumes the intent BEFORE validation, keeps ONE pending share (validated URI, cleaned
  name, kind, 128-bit token, 2 min monotonic timeout; a second offer is dropped with a fixed toast, never replaces).
- Provider calls are never on the main thread and are bounded (3 s type, 3 s name, 20 s open): one call in flight per provider with NO
  expiry (an expiry let one package pin all four threads), at most 3 tracked providers of a 4-thread pool, all providers whose package
  cannot be resolved share one key (the manifest has no `<queries>`). `ContentProviderClient.setDetectNotResponding` is a hidden API and
  was not used.
- Link policy is a best-effort filter: no DNS, authority after the LAST `@`, refuses loopback, 0/8, link-local, own addresses, IPv6
  mapped/compatible/NAT64 forms, non-strict IPv4 spellings and `localhost` names; private LAN ranges stay allowed (NAS links). It runs
  only when the proxy option is on. Redirect hops are re-checked only in the content-type fallback (max 3, HEAD only).
- Picker per action in the new layout (owner): play, play-all, row tap, add-to-playlist and every shared link or file open the "Play on..."
  sheet; confirm sets the chosen ids, starts and then clears the selection (`UpnpClient.clearReceiverDevices()` fires the removal events
  and stores an EMPTY set, which `getReceiverDeviceIds()` does not turn back into the default); cancel, back, tap outside and swipe change
  nothing. The normal sheet has no selection checkboxes, and the receiver bar shows the receivers that play now ("No receiver playing"
  otherwise; a paused player counts).
- Classic (owner): a shared file opens the same pick-mode sheet, the classic selection is the initial tick state and a copy is restored
  after the start; normal classic browsing never shows the sheet. A shared link in classic still plays at once on the stored receivers
  (pre-existing behaviour, owner-accepted).
- Links in the new layout also go through the picker (a link otherwise plays nowhere because the selection is always empty); the proxy's
  allowed addresses come from the ids captured at confirm (`createPlayableItem(Uri, Set<String>)`), never from the stored selection.
- One picked action in flight (`ActionFlight`): the stored selection is global and read back by `PlayerService.createPlayer`, so from the
  confirm until the action reports done no second pick opens (toast `receiver_pick_busy`); a flight nobody ends expires after the copy
  maximum plus a minute. Passing explicit receiver ids through six `PlayerService` signatures was not done (classic uses them too).
- The pending pick lives in the activity (lost on rotation or process death, the share is released); cancel, dismiss, timeout and a failed
  start release or remove the share they created; clearing the selection never alters an existing share's allowed set.
- A shared file's item carries the provider type, size and cleaned name and no duration (a `MediaDataSource` for metadata was not built).
- Ownership: every `register` is paired with an owner (source closed when registration fails after one evict-retry or anything throws);
  a share no player claimed within 2 min is removed; keys are released when a player ends (`onPlayerRemoving` runs before `onDestroy()`).
  `register` on a full registry returns null; the glue calls `evictOldestReleased()` once and retries (separate method so reviewed tests
  stayed valid); retired-key window 256, 8 draws.
- Logging: no key, URI, name, proxy URL or provider exception text in logs or toasts; fixed texts, enum names, simple class names;
  `ShareLogRedactor` (case- and percent-insensitive `/proxy/...`) on the SOAP, AV-transport, image and download lines. Names go through
  `DisplayName` (control, bidi and all Unicode format characters removed, 200 chars, fixed fallback), then only `setText`;
  `filterTouchesWhenObscured` on the picker's OK, Cancel and rows.
- The proxy URL is on no MediaSession or notification surface (reasoned from media3 1.11.1 bytecode, not observed on a device).
- Residual confused deputy accepted (owner): Android checks only that yaacc can read the URI and yaacc holds broad media permissions;
  mitigated by the type check, the human-in-the-loop pick showing the cleaned name, the address and expiry limits and the API 35+ check.
- Docs policy: no exploit-level detail about the existing released proxy in `docs/`, README or CHANGELOG; the technical risk list is
  `known-risks.md`, kept neutral. Released 5.4.0 is not exploitable through normal sharing (the manifest had only a `text/plain` SEND
  filter and the proxy option is off by default), so the 5.4.x patch release before the merge was dropped (owner).

## Phase 4: consolidating the share code (owner)
- Android types are allowed in the share classes (the "no `android.*`" rule is withdrawn: it split `Uri`/`PackageManager` handling off
  into one-method classes, and the existing tests already mock Android types with Mockito).
- Keep / merge / delete by a rule (state or state machine, own invariant with mutation targets, boundary type = keep), so the count
  (68 -> 33 files, 27 in the share package) follows from the rule; 12-18 would need 700+ line classes mixing unrelated invariants.
- Moves, not rewrites; no drive-by fixes; `UpnpClient`/`ShareIntentHandler`/`ShareService` public API unchanged; two provider pools stay
  two; SI-1 ... SI-19 and the mutation table are the review contract; renames are separate commits; rollback by `git revert` per group.
- Characterization tests of `ShareService#prepare` and the link registration come first (that glue had no unit test). `mockStatic` is new
  to this code base; a static mock is bound to its thread, so a real `HeldFileSource` can never be opened in a unit test and the tests
  stub it from the mocked resolver's answer on the pool thread (`docs/tech.md`, "Phase 4 notes").
- `ShareService.proxyUrl` uses `java.net.URI` and trims first; it returns null for an address with an inner blank (the old builder put the
  blank into the URL) - not reachable today, not fixed. The offline address stays `"0.0.0.0"` and still builds a URL.
- Share-related changes on the branch are frozen until Group 22.

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
- A shared file that is not accepted (type, no read grant, own app, sender without access) shows the fixed notice `share_not_accepted`
  from the launcher and from the intake; it names no reason and no payload.

## Material 3 gray scheme (owner decisions of 2026-10-29)
- **Why.** The owner chose Material 3 with the app's own gray scheme (reference look: K-9 Mail, Feeder): the dark theme has a PURE BLACK page, the
  light theme a LIGHT title bar (no dark band), tonal surfaces (page darkest in dark, containers a step lighter), big rounded cards without shadow,
  hairline dividers, soft off-white primary text and dimmer secondary text, still no color besides the launcher icon.
- **Theme.** `AppTheme` parent `Theme.Material3.DayNight` (support action bar kept, no Toolbars); all Material 3 roles are set in
  `res/values/colors.xml` and `res/values-night/colors.xml` as `gray_<role>`; the table is in `docs/tech.md` ("Look and settings"). Dark: page and
  surface `#000000`, containers `#0F0F0F` / `#161616` / `#1E1E1E` / `#262626`, text `#E3E3E3` / `#A8A8A8`, primary `#D6D6D6` on `#1B1B1B`. Light:
  page, surface and title bar `#F6F6F6`, containers `#FFFFFF` ... `#E2E2E2`, text `#1B1B1B` / `#5F5F5F`, primary `#3A3A3A` on `#FFFFFF`.
  `GrayscaleResourcesTest` checks 4.5:1 for every text/background pair of both schemes.
- **Deviations found while building.** (1) The library's `android:textColor*` theme items point at its tinted baseline colors, so the theme replaces
  them with gray selectors (`res/color/gray_text_*`). (2) The library's filled card uses the highest container, the plan wants the high one:
  `Widget.App.CardView` sets `cardBackgroundColor`. (3) `colorSurfaceTint` does not exist in material 1.14.0, nothing to set. (4) Error roles
  are left to the library: a red in `colors.xml` would break the "every color is gray" test, and the app raises no errors of its own. (5) The
  white menu icons of the image viewer and the log viewer get `app:iconTint` (they would vanish on the light bar). (6) `actionBarSize` stays
  56dp / 48dp landscape (own dimen `app_bar_height`), Material 3 would make it 64dp. (7) The title bar has no hairline and no elevation: bar and page
  have the same color; an outlineVariant line under it is a one-file change if the owner wants it. (8) The playlist delete icon gets `app:tint`
  (it was a white vector without a tint, invisible on a light card). (9) The selected row of the server folder tree uses
  `colorSecondaryContainer`/`colorOnSecondaryContainer` instead of `Color.LTGRAY` and `colorPrimaryDark`.
- **What the owner must check on a device.** See the final report of the migration (screens in both themes: server list, content list in list and
  card mode and landscape, device sheet, picker, mini-player, settings, log viewer, image viewer/players, dialogs/time picker, about), plus the
  status bar and navigation bar colors and icon shades (targetSdk 33; with 35 the bars become transparent).

## Browse resilience: retry and reload after a network drop (owner decisions of 2026-10-29)
- **Why.** A short network drop showed an empty folder (the failed load cleared the list). The app should hide that, no user action.
- **Retry.** `BrowseRetryPolicy`: 3 tries in total, 500 ms then 1500 ms, worker thread only, stops on cancel/interrupt. Retriable = no result, or an
  `UpnpFailure.isTransportFailure()` (`UpnpTransportException` from `SendingAction` for "no response"/IO error, plus device not in the registry and
  no HTTP sender in `UpnpClient.browseSync`). A server fault is never repeated (the same request fails the same way); the sorted-to-unsorted
  fallback is untouched, and a sorted request that got no answer is not blamed on the sort criteria (no `markServerSortRejected`, no second round unsorted).
  Other codes of the same value (501 from a server fault) stay non-transport on purpose: the type, not the code, decides.
- **Failure keeps the list.** On a failure after the tries the adapter is not cleared; `loadFailed` is set (item for the reload and for the error UI
  that comes later). A result without content and without failure (real empty folder) behaves as before. A null result now also ends the loading state
  (it used to leave the progress bar on).
- **Reload.** `NetworkDeviceListener` notifies `NetworkRecoveryListener`s after `enable()` for a new network (and after the Wi-Fi state re-init), not at
  startup. The client owns an app-lifetime notifier that the service chains behind the listener, so a screen can register before the service is bound.
  `ContentListFragment` registers in `onStart`, removes in `onStop`; it reloads (`populateItemList(false)`: continues from the item count, keeps scroll and
  items) only if `loadFailed` and not loading; 2 s debounce (`NetworkRecoveryReloadGate`). A restore while a load is still running is left to that
  load's own retries.
- **Guard.** `UpnpClient.browseSync` no longer dereferences a missing server service / network listener / HTTP sender (it gave an NPE on the worker
  thread); a missing device or sender is a retriable failure, a device without ContentDirectory a non-retriable one. An unexpected exception in the pool
  thread becomes a failure instead of an endless wait; a cancel (interrupt) ends the wait.
- **Not done.** Error UI (a later item). The sender is not recreated on a network change (stale pooled connections are validated before reuse).


## Browse resilience: suspend and resume (owner decisions of 2026-10-29)
- **Why.** Owner report: after a while "the connection seems lost": play refuses and the folder is empty. Code reading: the registry expires remote devices
  by wall clock after a sleep (maintainer thread, no ssdp:alive could be handled); `UpnpClient.isInitialized()` is false while the network listener is
  disabled, so `getDevice` returns null for provider and receivers; `ContentListFragment.onResume`/`init` cleared the folder when the provider was null
  and `deviceRemoved` could (it compared with `getProviderDevice()`, which is already null once the registry dropped the device); `getReceiverDevices()`
  removes a missing id from the stored selection for good and `PlayerService.createPlayer` then shows "no receiver selected". Not reproduced on a device.
- **Resume hook.** On activity `onResume`: `UpnpClient.refreshAfterResume()` on the client executor. `NetworkDeviceListener.reenableIfStale()` reuses the
  existing `disable()`/`enable()` path (and tells the recovery listeners, so a failed folder continues). Health = a network is there AND initialized AND
  same network as `currentNetwork` AND both receive loops running (new `isReceiving()` flags, cleared when the loop ends for any reason). Search again if
  the last M-SEARCH is >= 30 s old (elapsedRealtime, includes sleep) or the listener was re-created. `ServerListFragment.onResume` keeps its own search.
- **Folder stays.** No clear on `deviceRemoved`, on resume with the provider missing, or when a recreated screen restores its navigator and the provider is
  not discovered yet; `loadFailed` is set instead. Same UDN added/updated, or resume/network, continues the folder. Cleared only by choosing another
  server (`onResume` compares the selected provider with the folder) or navigating. A device event is no burst, so it uses `shouldReloadNow` (no debounce,
  a network callback that found nothing to do must not swallow the next real event).
- **Play waits.** 3 looks, 2.5 s apart (about 5 s), search (and listener check) before each wait, worker thread only (the main thread never waits). After
  giving up the old behavior is unchanged (missing ids dropped from the stored selection, "no receiver selected").
- **Left alone.** `getReceiverDevices()` still removes ids as soon as it runs with the registry missing them (also on the main thread, e.g. the classic
  footer); changing that breaks `UpnpClientReceiverSelectionTest` by design and needs a decision (purge only while `isInitialized()`). The registry's
  `UpnpProtocolHandler` is set once at service start; `NetworkDeviceListener.enable()` builds a new one on every re-enable, so renewals and local device
  advertisements of the registry still use the first handler (closed sockets after the first network change). `BackgroundIdleTimer` exits the app five
  minutes after the last browser stop when nothing runs; that is a cold start, not a resume.


## Large SAF folders: one listing, cached (owner report, 2026-10-29)
- **Why.** Browsing a DCIM/Camera tree with thousands of files froze the UI for 4.3 s, starved GENA subscribes ("Failed to acquire lock in milliseconds:
  5000") and ended with an empty Browse failure after the retries (the answer took longer than the 10 s socket timeout). Cause (read in `SafFolderBrowser`
  and in androidx `documentfile` 1.1.0): `listFiles()` is one query, but every property of every child is another provider query, and each chunk request
  listed the folder three times (count, containers, items) and sorted with property queries inside the comparator.
- **Decision.** One query per listing (`SafDirectoryLister`, `buildChildDocumentsUriUsingTree`, five columns, no FLAGS), kept in `SafListingCache`
  (8 folders, 100000 entries, 30 s, single flight, cleared on SAF cache clear / permission removal / SAF path preference change). Results are the same
  (ids from the same document uris, order, sort keys, paging, `[X]`/restricted from `checkCallingOrSelfUriPermission` + the listed mime type); the page slice
  alone gets the item work. `YaaccContentDirectory.browse` uses one browser instance. TTL 30 s: new files appear within that.
- **Client.** ContentDirectory actions get a 30 s response timeout in `HttpRequestSender` (per request `RequestConfig`), all other requests keep 10 s.
  `BrowseContentItemAdapter`: duplicate check with a hash set (was `List.contains` per object), dates parsed once for the date sort (was twice per
  comparison), no `toString` of the whole chunk for the log - all on the main thread.
- **Left alone.** Cling's service lock is held for the whole action and `RegistryImpl.maintain()` (synchronized) can wait for it while the main thread calls
  `RegistryImpl.getDevice` (see docs/tech.md, "Large SAF folders"); the client-side sort at the end of a folder load still runs on the main thread;
  `SafFolderBrowser.browseMeta` of a SAF subfolder base64-decodes a short id (pre-existing, never worked for short ids); `SAFCacheManager.lruCache`
  (`LinkedHashMap`) is touched by the preload thread without the lock `getMetadata` uses.


## Shared folders tree: worker thread, plain node data, range inserts (owner report, 2026-10-29)
- **Why.** The server configuration screen froze on a SAF folder with thousands of files: `clickedOnDocument` ran `listFiles()` and `exists()` per child on the
  UI thread, `TreeViewHolder.bind` asked the provider `getName()`, `isDirectory()` (twice) and a full `listFiles()` per folder row for the expand symbol,
  the tree manager was a `LinkedList` read by position per bind, and every expand/collapse was `notifyDataSetChanged`.
- **Decision.** One single-thread executor per activity (`shutdownNow` in `onDestroy`, no AsyncTask) reads folders with `SafDirectoryLister` (one query, shared
  cache) or `File.listFiles()`; `TreeNode` holds plain `TreeNodeData`; a per-node load generation plus a tree generation drop stale results (tap again while loading
  = collapse). Expand symbol for every folder until it was read and is empty (no emptiness query per row). `ArrayList` + range based expand/collapse/remove
  (`TreeNodeManager.Change`), the click handler asks `getBindingAdapterPosition()` (rows move without rebinding). The check box listener is detached while a
  row is bound (it used to write the preferences for every recycled row). A listing that failed stays unknown, so the next tap retries. Order unchanged
  (provider order, no sorting existed).
- **Left alone.** Layouts (the RecyclerView is `wrap_content` between two relative anchors; not verified on a device that it never measures all rows), the
  rebuild still uses `notifyDataSetChanged` (whole tree replaced), `onActivityResult` keeps its preference work and `takePersistableUriPermission` on the
  main thread (no provider query), `InterfaceResolutionHelper` in `onCreate`, the unused branch/level helpers of `TreeNodeManager`.


## Content list: sort and thumbnails off the main thread (owner request, 2026-10-29)
- **Why.** The sort of a finished folder (`sortObjects` + `notifyDataSetChanged`) ran on the main thread; every bind made a new `IconDownloadTask` (also for rows that
  never load anything, and cancelled tasks never left `asyncTasks`), re-inflated and re-tinted the type icon, and reloaded a thumbnail that was just scrolled away.
- **Decision.** `BrowseSorter` (pure, same comparators, the old code is the reference in the test) sorts a copy on a single idle-timeout daemon thread owned by the
  adapter; a generation counter drops stale results; the swap notifies only the changed range (with a payload, no change animation). Thumbnails: a task only for rows
  that have one, cancelled on re-bind/recycle, `ThumbnailSlot` skips the request a row already shows, byte-bounded LRU for decoded bitmaps (the old one counted entries),
  type icons from a night-mode keyed cache of constant states. A scroll target arriving during the sort waits for the swap.
- **Left alone.** `DiffUtil` (a range is enough, the sort is mostly a no-op for server-sorted folders), `AsyncTask` (deprecated, used everywhere), the other users
  of `IconDownloadTask` (device icons, album art) except that they now share the byte-bounded cache. `getObjects()` may return the unsorted list for the few ms of the sort.


## SAF metadata cache: own preferences file, batched commits, lazy load (owner request, 2026-10-29)
- **Why.** Default preferences + one `apply()` per file/short id = a rewrite of the whole XML per entry, a possible wait in `QueuedWork` at activity stop, and a full deserialization
  in the constructor, which can run on the main thread. `lruCache` was touched by the preload thread without the lock.
- **Decision.** File `saf_cache` (same keys, so the migration is a copy). One-time migration as a pure plan (copy what the new file lacks, counter = max, remove old keys only after the
  new file was committed; rerun until no old key is left). Writes: buffer + flush policy (250 changes / 3 s / preload end / exit), `commit()` on the dedicated `SAFCacheIO` thread. Load on that
  thread; main thread never waits (transient `t<n>` ids, nothing persisted), other threads wait up to 30 s. One lock for LRU, buffer and policy; I/O never under it.
- **Kept on purpose.** Key names and the serialization; short ids are still only persisted inside metadata entries (a folder's id does not survive a restart); the store is NOT trimmed to the
  LRU size (the old trim never removed anything because `getEvictedKeys()` drained the list in the `isEmpty()` check; trimming for real would drop the short ids of files beyond the 1000th at the
  next restart); ids are not removed on `removeCachedUnder`; the bare duration the preload stores is not made readable (found, not fixed).
- **Risk accepted.** Changes of the last 3 s are lost if the process is killed (not on app exit: `Yaacc.clearCache` waits at most 1 s for the write); the metadata entries and the id counter are
  committed together (the stored state is consistent), but numbers handed out in a lost window can go to other uris after the restart.


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
  `waitForActionComplete` itself (30 s watchdog); `RendererStatusMonitor`/`ReceiverRowBinder` use their own `new HttpRequestSender()`.


## Registry protocol handler follows the network listener (owner request, 2026-10-29)
- **Why.** The "Left alone" item of the resume entry: the registry's handler was set once; every re-enable builds a new one with new sockets and closes the old ones.
- **Decision.** `enable()` sets the new handler in the registry (in the lock, before the receivers start), `disable()` sets null; `RegistryImpl` field `volatile`; registry code is null-tolerant
  (`RemoteItems` renew/unsubscribe, `LocalItems` already was). ALIVE again after "enabled for a new network" (network callback, resume check) via `Registry.advertiseLocalDevices()` on a short-lived
  thread (the registry method is synchronized and can wait for its lock). Test seams: a package-private constructor and receiver factories in `NetworkDeviceListener`.
- **Left alone.** The HTTP server's request handler keeps the handler it was created with; the duplicate `getProtocolHandler`/`setProtocolHandler` pair of `RegistryImpl`; the wifi-state path
  (re-creates the device itself).


## HTTP server resolves the protocol handler per request (owner request, 2026-10-29)
- **Why.** `createHttpServer` passed `getNetworkDeviceListener().getUpnpProtocolHandler()` once to `YaaccUpnpServerProtocolRequestHandler`; a service started without a network kept null (every sync request threw an NPE, answered 500)
  and after a network change it kept the replaced handler. The "Left alone" item of the registry-handler entry.
- **Decision.** The request handler takes a `Supplier<UpnpProtocolHandler>`; the service supplies `registry.getUpnpProtocolHandler()` (the live volatile field, set by `NetworkDeviceListener.enable()`, null on `disable()`). It is read once per
  `process()`. No handler or a throwing supplier: 503 and one W log line; `createReceivingSync` returning null: 501 (the same status as a `ProtocolCreationException`). Tests with Mockito in `YaaccUpnpServerProtocolRequestHandlerTest`.
- **Left alone.** `syncProtocol` as a shared instance field of the handler (`responseSent`/`responseException` under concurrent requests); the registry is read from the service field without a lock (it is set once before the server is created).


## SAF preload writes readable metadata (owner request, 2026-10-29)
- **Why.** The "found, not fixed" item of the SAF cache entry: the preload stored a bare duration under `saf_cache_<uri>`, `getMetadata` could not parse it and extracted the duration again; `getDuration` returned whatever was stored.
- **Decision.** All writers of the key store the serialized `SAFMetadata` (`storeDurationLocked`: existing entry keeps mime type, size and short id; a new entry from the preload gets name/type/size of the document and the id the uri has, a new one only if it has none).
  Readers tolerate an older bare value (pure `SafCacheEntries`) and upgrade it in place without extraction (D log `CACHE_UPGRADED`). The preload extracts only for a file without entry or with an empty duration, and the queued background task
  skips a file that already has a duration. `getDuration` (deprecated, no callers) parses the entry and does not create one (it does not know the size). Test seam `SAFCacheManager.DurationExtractor` (package-private) counts extractions.
- **Left alone.** Key names and the serialization; the unreadable-file set (per process only, so such a file is tried again at the next start); short ids still persisted only inside metadata; the preload now creates short ids for audio/video files it indexes
  (the browse would create them anyway). One more provider query per extracted file (`length()`).


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
  `fit` for added items) with tests; `Player.onItemEnded()` separates "ended by itself" (repeat one repeats) from a skip (repeat one acts like off, as in ExoPlayer). ExoPlayer gets repeat mode, shuffle mode and the
  `PlayOrder` as its shuffle order. Buttons (`PlayModeControls.Binding`) in the music, AVTransport and multi content screens, a menu item pair in the image viewer; five strings in all seven locales. The renderer's
  `SetPlayMode` / `GetTransportSettings` / `CurrentPlayMode` now reach and follow the players (`AvTransport.setPlayMode`, `attachPlayers`).
- **Consequences.** `setItems` no longer shuffles the stored list (the playlist dialog shows the list order, the play order is separate). The images in the viewer now obey repeat (before they always looped) and shuffle.
  An unknown `SetPlayMode` value answers 712 (it was swallowed). The mode of a renderer instance survives its players (a new player of the transport gets the mode last set or chosen).
- **Bugs found on the way.** `AbstractPlayer.next()` at the end of the list with repeat off called `stop()` with the command flag still set, so the player stayed blocked; `LocalMediaSessionPlayer` updated its index only for
  seeks, so an item that ended by itself left the screen on the old title (`onMediaItemTransition` now handles every reason except a changed playlist).
- **Left alone.** No play mode control in the mini-player and device sheet rows; `AVTransportController` (no own playlist) hides the buttons; no `SetPlayMode` is sent to a remote renderer by `AVTransportPlayer`
  (the app drives the items); a Media3 controller (system media controls) that sets ExoPlayer's repeat or shuffle directly is not mirrored into the `PlayMode` (races with our own changes); the `DIRECT_1` and
  `INTRO` values are still advertised and play as normal; repeat ONE with shuffle is reported as REPEAT_ONE.


## Playlist dialog shows the play order; external controllers are mirrored (owner request, 2026-10-31)
- **Why.** Since `PlayOrder` shuffles instead of the stored list, the playlist dialog showed the list order while the player followed another; and a system / Bluetooth / notification / Wear controller that
  set ExoPlayer's repeat or shuffle mode left the `PlayMode` (buttons, `CurrentPlayMode`) stale (the "left alone" item of the entry above).
- **Decision (dialog).** The dialog shows `Player.getPlayOrder()` (identity without shuffle, so the list order as before). Pure logic in `PlayOrder`: `positionOf` / `itemAt`, `move(from, to)` (shift, as a drag),
  `remove(index)` (keeps the relative order of the rest; `fit` would shuffle again), `isAtOrBeforeCurrent` (lock rule). `AbstractPlayer.moveInPlayOrder` / `removeItem` / `isPlaylistPositionLocked` are the edit API
  (defaults in `Player` for the image player): with shuffle a move only changes the order, without it the list; delete removes from both and keeps `currentIndex` on the same item. Locked = playing or paused item and
  everything before it in the shown order (before: playing only, in list order); a stopped player has nothing locked and `currentIndex` is the first shown item after an edit. New `Player.PROPERTY_PLAYLIST`
  (set / add / clear / remove / move / order reset at start and stop). The adapter applies its own edits with precise notifications and skips the event it caused; other changes are a full refresh, only if the shown
  order differs. The listener is removed in `onDestroyView` (it leaked the fragment before). Click listeners ask the holder for the position (they captured a stale one after a move).
- **Decision (ExoPlayer).** The edits reach ExoPlayer as `moveMediaItem` / `removeMediaItem` / `setShuffleOrder` (hooks `onItemMoved`, `onItemRemoved`, `onPlayOrderMoved`); `syncPlaylistToExoPlayer` (rebuilt the part
  behind ExoPlayer's current item) is removed, it was wrong as soon as an item before the current one in the list could be edited.
- **Decision (mirror).** `Player.Listener` in `LocalMediaSessionPlayer` takes over the changed part (repeat or shuffle) through `RepeatPolicy.adoptExoRepeatMode` / `adoptExoShuffleMode` and `setPlayMode`; our own
  `applyPlayMode` is recognized by a main-thread flag and by "value equals play mode". Shuffle turned on from outside: `setPlayMode` creates our order, `onPlayModeChanged` installs it with `setShuffleOrder`.
  `AVTransportPlayerWrapper` already routed to the `PlayMode`; nothing changed there.
- **Left alone / found.** `addItems` on a `LocalMediaSessionPlayer` does not append to ExoPlayer (it is rebuilt at the next `startItem`; ExoPlayer's order lacks the new items until then); mini-player and device sheet
  rows list no queue, so nothing to change there; a change of the other part of the mode within the few ms between our `setPlayMode` and the posted `applyPlayMode` is the only race left (last writer wins).


## Home Assistant events again: why 066a598e could not work, what it could break, and the mode memory (owner test, 2026-10-31)
- **Symptom (owner).** With 066a598e (the renderer sends its `LastChange`) Home Assistant's shuffle and repeat buttons seemed to do nothing in the app player, which had started with shuffle enabled; before it the
  buttons changed the app player (HA showed no state). The commit was reverted (592bc528) and this is the second attempt, done with a harness that runs the real receiving path (`RendererHarness`: the real
  `AnnotationLocalServiceBinder` service, `YaaccUpnpServerProtocolRequestHandler.handle`, `UpnpProtocolHandler`, `ReceivingAction` / `ReceivingSubscribe`, Cling's message parsing and action executor, mocked
  `UpnpClient` / players / `HttpRequestSender`).
- **Finding 1 - the events never left the device, with or without 066a598e.** `ReceivingSubscribe` registers the subscription at the service and sends the initial event in `responseSent(...)`, which the server must
  call after the SUBSCRIBE answer was written. `YaaccUpnpServerProtocolRequestHandler.handle` (HEAD: `submitResponse` at line 128; `responseSent` line 283 never called by anyone) did not, so Home Assistant got `200` and a
  subscription id and then nothing: no initial event, no change event (`registry.addLocalSubscription` only stores the subscription). The handler also kept the protocol of the request in the field `syncProtocol`
  (line 73, set at 260), shared by the 8 I/O threads, so with overlapping requests the "sent" would have gone to another request's protocol. Red: every test that waits for an event at a subscriber
  (`AvTransportCommandsAndEventsTest`: 9 of the 17 tests failed with "not within 5 s: the initial event / the last mode", 2 more are the lock cases below). Consequence for Home Assistant: it believes it is subscribed (`is_subscribed`), so it does not poll
  `GetTransportSettings` again - its repeat / shuffle state is whatever the first poll returned and never changes, from either side.
- **Finding 2 - could the event thread of 066a598e stop the commands (the owner's suspicion)?** Not in the shipped state, and not by the wire: for a session as Home Assistant sends it (Get*, Stop, SetPlayMode,
  SetAVTransportURI, Play, Pause, ... 19 calls) every SOAP answer and the final play mode of the player are identical with Cling's plain manager and with the 066a598e manager
  (`everyAnswerAndThePlayerEffectAreTheSameWithAndWithoutTheEventManager`, green on the shipped code); SetPlayMode is a valid `SetPlayModeResponse` for all seven values in NoMediaPresent, Stopped, Playing and Paused
  and changes no state; 150 parallel SetPlayMode calls (plus changes at the player) with a subscriber lose none. There was exactly one instance of the service, state machine and `AvTransport` per manager. **But** the design had a real
  coupling that only shows once a subscription exists (so it was latent, because of Finding 1): `fireLastChange()` held the service manager lock while it delivered to the subscriptions. Actions run under the same
  lock (`AbstractActionExecutor` -> `manager.execute`, `LOCK_TIMEOUT` 5 s). With a subscriber that does not return, a command is answered `501 ... Failed to acquire lock in milliseconds: 600` (red:
  `aCommandDuringAnEventThatIsBeingSentIsNotHeldUp`, `everyCommandStillWorksWhileTheSubscriberIsStuck`); a busy subscription (`LocalGENASubscription.propertyChange/registerOnService/end` are `synchronized`, the last
  two then need the manager lock) and the event thread wait for each other until the lock times out, with every command behind them (red: `aSubscriptionThatIsBusyDoesNotStallTheCommandsBehindTheEventThread`); one
  listener that throws stops `PropertyChangeSupport.firePropertyChange` and the others miss the event (red: `aSubscriberThatThrowsDoesNotHurtTheCommandOrTheOtherSubscribers`). Home Assistant would have logged
  the 501 and the command would have been lost - exactly the owner's symptom, once the subscription worked. Candidates that did not hold: initial event (the full state is sent and tested; a failing `appendCurrentState` falls back to the accumulated
  event, by code only), ordering `storePlayMode` / `applyPlayMode` (no event listener on that path), re-entrancy of `LastChange` (the order manager lock -> `LastChange` monitor is the same in actions and event thread),
  main-thread deadlock (nothing the main thread calls takes the manager lock).
- **What the owner saw is therefore not explained by the events; likely by the mode memory, and by the lookup.** The app player started with shuffle because the mode last set on the transport was handed to the next
  player (Finding 3), while Home Assistant, never getting an event, showed the state of its first poll: its shuffle button then sent a mode the app already had (no visible change). And
  `UpnpClient.getCurrentPlayers(transport)` finds the players by the *kind* the track's mime type needs, not by transport, and takes the mime type differently from the code that created the player
  (DIDL protocolInfo vs file extension): for such a track `SetPlayMode` reached no player (by reading, not reproduced). Both are handled/logged now (`applied to <n> players`; the transport also gives the mode to the
  players it started itself). To be confirmed by the owner with the log lines in `docs/tech.md`.
- **Finding 3 - the mode survived the players.** `AvTransport` kept `playModeChosen` for ever, so a shuffle set from Home Assistant yesterday started the next playback shuffled. Decision: remembered while a player of
  the transport exists (also across the replacement of the player by the next item: Home Assistant plays every item on a new player), forgotten when none is left; the settings are then the start mode again
  and a subscribed controller is told. Where: `AvTransport.attachPlayers` remembers the players and listens at the `UpnpClient` player list, `checkPlayersGone()` on every removal, `playersStarting()` /
  `playersStarted()` in `AvTransportMediaRendererPlaying.onEntry` so the removal of the replaced player does not count. Rejected: forgetting on the state change to Stopped / NoMediaPresent (a stopped player is
  still a player, and the state machine never returns to NoMediaPresent); a timer (the order of the "removed" notice and the new player is not deterministic, a flag around the creation is).
- **Decision (events).** `AvTransportServiceManager` fires **without the manager lock**: the event thread takes the changes out of the `LastChange` in one step (its own monitor, microseconds), then calls each listener of
  the service on its own with the `EVENTED_STATE_VARIABLES` event and skips (logs) one that throws; `ReceivingSubscribe` registers first and reads the state again afterwards, so a change between the first read and the
  registration is in the initial event (test: `aSubscriberThatComesWhileSetPlayModeRunsMissesNoChange`). `YaaccUpnpServerProtocolRequestHandler.handle` hands the answer to a `DeliveryReportingResponseProducer` that calls
  `responseSent` / `responseException` of that request's protocol when HttpCore releases the response. Kept from 066a598e: `ObservableLastChange`, the full initial event, no `eventMaximumRateMilliseconds`, 250 ms spacing
  (now from the end of one event to the start of the next), the D log lines; new: one log line per received `SetPlayMode` with the User-Agent of the caller and the number of players it was applied to.
- **Left alone / found.** Initial event and first change event are sent from two threads of the registry pool and can arrive in either order (Cling has the same note; HA's tolerance is not verified).
  The HTTP server runs actions on its I/O threads (8): a long action (`Play` while the player is created) blocks the connections of that thread. `AvTransportMediaRendererPlaying/Stopped/Paused` capture
  `AvTransport.getUpnpClient()` when the state machine is created (now also possible at SUBSCRIBE time, via the initial event): if the client was still null then, the states keep null. The transport does not tell a
  controller when its player was closed in the app (TransportState stays PLAYING); only the play mode is reset.
