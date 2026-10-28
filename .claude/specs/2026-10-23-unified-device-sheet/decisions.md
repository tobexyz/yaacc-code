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
- Default layout (owner): a fresh install starts in the new layout (preference default `new`), an updated install keeps classic.
  `Yaacc` calls `BrowserLauncher.keepClassicForUpdatedInstall` after `setDefaultValues` - an update has no stored value and
  would otherwise get the new default merely by opening the setting. `NEW_LAYOUT_AVAILABLE` stays as a kill switch.
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
