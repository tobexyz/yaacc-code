# Unified device sheet, layouts, sharing: design

Branch `feat/unified-device-sheet` (issue #287). What is built is described in `docs/tech.md` ("Unified device sheet",
"Classic and new layout", "Sharing local files", "Design decisions and rationale"); this file keeps the decisions and the
reasons, the security invariants of the share feature (SI-1 ... SI-19) and the Phase 4 plan. Planning history is in git.

## Findings that shaped Phase 1 (code check, 2026-10-23)
1. **Players and receivers are not 1:1.** A remote renderer can have several `AVTransportPlayer`s (`PlayerService.createPlayer`
   only shuts down one with the same device AND content type); the local device keeps up to three singletons per type
   (`LocalImagePlayer`, `LocalMediaSessionPlayer`, `MultiContentPlayer`; "image show with background music" is a documented
   feature). The UI must not assume one player per receiver.
2. A receiver can be busy with no yaacc player (playback by another controller): `RendererStatusMonitor` polls
   `GetTransportInfo` and transport controls go straight to the AVTransport service.
3. Existing bug: `BrowseReceiverDeviceAdapter#getPlayerForDevice` returned the first match of a `HashMap`, so which of two
   local players a row showed and controlled was arbitrary.
4. The Player tab has no inline controls; it only launches a player's activity through its notification intent.
5. `Player#getDeviceId()` is a reliable grouping key (`UpnpClient.LOCAL_UID` for local players, the renderer UDN otherwise).

## Phase 1: the device sheet
**Decision.** One bottom sheet with one list **grouped by receiver**: a receiver row (status badge from
`RendererStatusMonitor`, play/pause/stop, volume, mute) with 0..N player-session rows under it (name, title, art; tap opens the
player activity). Sessions whose device is not a receiver (a renderer that dropped off) go in a trailing group so none is hidden.
Rejected: one row per device (hides sessions, i.e. finding 3); two flat sections (leaves the user to match players to devices).

**Components.** `DeviceSessionGrouper` (plain Java: turns receiver ids and `PlayerRef`s into rows; `primarySession` = the
newest playing session, else the newest overall, independent of input order), `ReceiverRowBinder` (one binder shared by the
receiver tab and the sheet, extracted first so the two cannot drift), `DeviceSheetFragment` + `DeviceSheetAdapter`,
`RendererStatusMonitor.getShared()` (one poller for tab and sheet), `PlayerListChangeNotifier` (PlayerService had no add/remove
notification; it ignores repeat calls in both directions).

```
Receiver bar (tap) --> DeviceSheetFragment
                        |- Living room TV   [play/pause/stop] volume  mute
                        |    '- Music: "Track 3"        (tap: player activity)
                        |- this device      [play/pause/stop] volume
                        |    |- Images: slideshow
                        |    '- Music: "Track 7"
                        '- Kitchen speaker  (idle)
```

**Why creation sequence, not player id.** `AVTransportPlayer` ids are random and local ids are fixed per type, so "newest" needs
a monotonic creation sequence assigned by `PlayerService` (`getPlayerCreationSequence`); the player id stays the lookup key.
A same-id replacement fires "removed" for the old instance and "added" for the new one.

**Sheet behaviour** (platform facts in `docs/tech.md`): half height on touch devices and draggable to full height; full height
when not in touch mode (D-pad focus could otherwise reach rows below the screen edge); live updates only while started
(status monitor, player-list listener, 2 s poll for the local device); a DayNight theme overlay because `AppTheme` sets no
`bottomSheetDialogTheme`.

## Phase 2: classic and new layout
**Decision.** `TabBrowserActivity` and its four fragments stay as the **classic** layout; the **new** layout is a separate
tab-less `BrowserActivity`: the server list is the top level, tapping a server shows `ContentListFragment`, and an
activity-owned **receiver bar** and **mini-player** appear on every level. A "Layout" setting (Appearance) picks the activity.
Why not hide tabs in one activity: tab positions are tied to the `BrowserTabs` order in adapter, activity and saved state, and
the server list is a different navigation model anyway. Why not copy fragments: every later fix would be needed twice.

```
Top level (servers)                         Inside a server
+--------------------------------+          +--------------------------------+
| YAACC                  timer ..|          | <- Gerbera > Music   sort view |
| Servers                 refresh|          |--------------------------------|
|  Gerbera                     > |          |  (content list or cards)       |
|  yaacc  (stream buttons, gear) |          |                                |
|  yaacc server [ON]             |          |                                |
| mini-player rows (all players) |          | mini-player rows               |
| receiver bar: who plays now  v |          | receiver bar                   |
+--------------------------------+          +--------------------------------+
```

Decisions and reasons:
- **Launcher trampoline.** An invisible `LauncherActivity` owns the launcher, Android TV and share intent filters and starts the
  effective layout with a FRESH intent (the activities then need no filters; `BrowserActivity` is not exported;
  `TabBrowserActivity` stays exported without filters so stored shortcuts keep working).
- **Layout switch** is detected in each browser's `onResume` (effective layout vs own, behind a 2 s relaunch guard), which
  covers Settings, stale notification intents and launcher re-taps; no `recreate()`, no result code, no preference listener.
- **`BrowserHost`** carries only the live navigation sites (`ServerListClickListener`, the share callback); the four fragment
  branches that cast `getParent()` to `TabBrowserActivity` never ran and were deleted, not wired, so classic stays unchanged.
- **Own back handling** in the new layout through `OnBackPressedDispatcher`; classic back is unchanged (fixing it is a
  separate owner decision).
- **One process-wide idle timer** that counts started browsers, because on a layout switch the old browser's `onStop` runs after
  the new browser's `onResume` and a per-instance timer would quit the app five minutes later.
- **Mini-player shows every running player** (owner feedback), one row each, oldest first, height capped at about three rows.
- **Classic keeps the original static footer** (provider line and receivers line, not clickable); the sheet and the tappable
  receiver bar are new-layout only (owner feedback; answers the old "does classic keep the sheet" question with no).
- **Toolbar timer** is a single symbol: stop while running, otherwise pick a duration and start. No pull-to-refresh (it would
  need `swiperefreshlayout`, a new dependency); the refresh button stays.
- **Default.** Fresh installs start in the new layout, an updated install keeps classic (`BrowserLauncher.keepClassicForUpdatedInstall`).
- `allowBackup` is off (owner): auto backup would have copied settings, the receiver selection and SAF cache entries off the device.

## Phase 3: sharing files and links, picking receivers per action
Scoped with the owner on 2026-10-25. Owner decisions:
1. **Per-share registry entry**, not the persistent "media proxy" setting; the user's settings are never changed. The HTTP
   server runs whenever the app process lives (the "local server" setting only decides which UPnP services are published), so
   nothing is started or stopped for a share; `/proxy/<key>` is served before the preference gate and the share waits until
   the server answers.
2. **A local file is kept readable by holding its descriptor open** (no copy by default), opened only AFTER the user confirmed
   the receivers, while the sender's read grant is still valid because the receiving activity record is alive. A descriptor that
   cannot seek falls back to a copy in the app cache (capped). A pending, unconfirmed share holds no descriptor, no copy and no
   registry slot.
3. **Every proxied share (files and http/https links)** gets a random unguessable key, lives in memory only (no more
   `proxy_link_*` preferences), is served only to the chosen receivers' addresses (setting "Restrict the media proxy to the
   chosen receivers", default ON; OFF keeps key and expiry only) and expires (player end + grace, absolute maximum).
4. **Receivers are picked per action in the new layout**: share, play, play-all and add-to-playlist open the picker (the device
   sheet in pick mode, "Play on..."); afterwards the selection is cleared and the receiver bar shows the receivers that play.
   The normal sheet has no selection checkboxes. Classic keeps its tab selection and static footer; only a shared file opens
   the pick-mode sheet there (the classic selection is the initial tick state and is restored after the start).
5. **One file per share.**

Components and their security job (details in `docs/tech.md`, "Sharing local files"):
- **Registry** (`ShareRegistry`): 128-bit random key, in-memory, allowed address set fixed at registration, idle grace and
  absolute maximum, max 8 entries, close exactly once outside the lock. `UnclaimedShares` removes a share no player claimed in
  2 minutes; `ShareKeyTracker` releases keys when a player ends.
- **Proxy access policy** (`ProxyAccessPolicy`, `ProxyShareResponder`): fixed order of checks, constant 404 bodies, 403 only
  after a valid key, address from the connection's endpoint details only, restriction default ON everywhere.
- **Intake** (`LauncherActivity` -> `LauncherForwardPolicy`, `LaunchNonces`, `ShareIntentHandler`, `SharedContentPolicy`,
  `PendingShare`): the exported launcher accepts SEND from every app, so it forwards a fresh intent with ONE `content:` URI as
  one ClipData item plus the read flag (the system checks ClipData and `data`, never `EXTRA_STREAM`) and a single-use launch
  token; the browsers, which are reachable directly, repeat every check and accept a file only with a valid token. Only
  `content:` URIs of OTHER apps with an audio/video/image type are accepted (`file:` and any provider of this app are refused,
  which would otherwise let any app make yaacc serve its private data to the LAN).
- **Provider calls and copy** (`BoundedCall`/`ProviderCallGate`, `WatchedCopy`, `IncomingCache`): a Binder call cannot be
  interrupted, so the design bounds what one provider can pin (one call per provider, 3 tracked providers, 4 threads per pool)
  instead of freeing threads; the copy runs under a watchdog with a budget checked before every chunk.
- **Picker** (`ReceiverPickController`, `PendingPick`, `ActionFlight`, `DeviceSheetFragment` pick mode): the pending action
  lives in the activity (lost on rotation by design); one open pick, one answer per sheet; one picked action in flight because the
  stored selection is global and read back by `PlayerService.createPlayer`.
- **Links** (`ShareLink`): intake of http/https text, no DNS, refuses loopback, link-local, own addresses and non-strict IPv4
  spellings; private LAN ranges stay allowed (NAS links); in the new layout a link also goes through the picker.

Accepted residual risks (owner): Android checks only that yaacc itself can read a forwarded URI and yaacc holds broad media
permissions, so a malicious sender could name content it cannot read itself (mitigations: type check, human-in-the-loop pick
showing the cleaned name, address and expiry limits, Android 15+ sender check, logged only); the random key is visible to any control point
that can query the receiver, so with the restriction OFF it is no protection. The full list is `known-risks.md`.

## Phase 4: consolidate the share code (open)
Owner decision 2026-10-26. A behaviour-preserving refactor of what Phase 3 left: 46 classes in `de.yaacc.share`, 9 in
`de.yaacc.upnp.server.share`, 8 share/pick helpers in `browser/`, 4 HTTP classes and `upnp/ReceiverSelection` (68 files, ~6,200
lines; ~11,700 lines of tests). Most of the split came from the rule "no `android.*` in these classes", which the owner
withdrew: yaacc is an Android app and the existing tests already mock Android types with Mockito (`Context`, `Uri`,
`ContentResolver`, `Intent`, `mockStatic`, `unitTests.returnDefaultValues = true`; no Robolectric, none is added).
BASE (the restore point of the whole phase) = commit `ecb26c59`.

**Rule (the class count follows from it, not the other way round).**
- KEEP a class that holds state or a state machine, enforces a security invariant with its own tests and mutation targets, or
  is a boundary type used by unrelated callers.
- MERGE a static helper with one caller cluster, a decision wrapper over two or three values, a data carrier, or a class that
  only existed to keep Android types out of its neighbour, into the class that owns the responsibility.
- DELETE where a JDK/Android type does the job (`java.net.URI` for the proxy URL; a plain "has address" check for the `"x"` key probe).

**Rules of the move.** All share code ends in `de.yaacc.share` (`de.yaacc.upnp.server.share` disappears, moved first as a pure
rename); `browser/` keeps four classes that talk to activities and fragments, `upnp/server/http/` the responder and one producer
file. Moves, not rewrites: a merge copies the absorbed code unchanged (same statements, same order), only qualifiers, visibility
and javadoc change; no drive-by fixes. The public API of `UpnpClient`, `ShareIntentHandler` and `ShareService` does not change
(tests verify calls on them). The two `BoundedCall` pools (intake probes, confirmed opens) stay two instances with two gates and 4
threads each: the "at most 3 of 4 threads" accounting depends on it.

**Not changed on purpose.** (a) The string-level parsers of `SharedContentPolicy`, `LinkTargetPolicy` and `ProxyAccessPolicy` stay
hand-written: under `returnDefaultValues` `Uri.parse` returns defaults on the JVM, so 120+ policy cases would test the mock, and the
policies must not use `URL`/`URI`/`InetAddress` (no DNS; what is checked is what Android uses). (b) `SenderGrantCheck` keeps its
`sdkInt` argument (`Build.VERSION.SDK_INT` is a static final). (c) Injected clocks, randoms and `FilePreparer` stay (deterministic
tests). (d) `ShareEntityProducer` and `GuardedEntityProducer` repeat the same live check and lease logic; unifying changes the SI-7
surface. (e) `ShareIntentHandler` (~820 lines) stays one orchestrator; splitting it adds classes.

**Target inventory (old -> new).** Group 17 is built; the others are open (tasks.md). File counts after each group: 62, 53, 46, 38, 33.

| Group | Target (kept class, renamed where noted) | Absorbs |
|---|---|---|
| 17 (built) | `ShareService` (was `SharedFileService`) | `ShareProxyUrl` -> `java.net.URI` in `proxyUrl`, `ShareFailureMessage` -> `failureTextRes` |
| 17 (built) | `ShareRegistry` | `ShareRegistrar` (-> `registerOrEvict`) |
| 17 (built) | `HeldFileSource` | `DescriptorWindow` (-> `clampLength`) |
| 17 (built) | `ReceiverBarController` | `ReceiverBarLabel` (-> `label`) |
| 17 (built) | `UpnpClient` | `ReceiverSelection` deleted (inline copy of the stored set) |
| 18 | `ServerSideSeeking` | `RendererIdPolicy`, `RendererStateMap` (nested `RendererStates`), `SeekPositionCalculator` |
| 18 | `ProxyAccessPolicy` | `ShareAddresses` (-> `allowedAddresses`) |
| 18 | `ShareLink` (was `LinkTargetPolicy`) | `HttpLinkTarget`, `LinkContentType` |
| 18 | `RangeStreamer` | `ChunkSink` (-> nested `Sink`) |
| 18 (droppable) | `upnp/server/http/ShareProducers` | `ShareEntityProducer`, `GuardedEntityProducer`, `LengthOnlyEntityProducer` (nested) |
| 19 | `ProviderCalls` (was `BoundedCall`) | `ProviderCallGate` (nested `Gate`), `ProviderKeys` (-> `keyOf`) |
| 19 | `SharedFileOpener` | `ProviderProbe` (-> `probe`) |
| 19 | `WatchedCopy` | `AbandonableCall` |
| 19 | `BudgetedCopier` | `CopyBudget` (nested `Budget`) |
| 19 | `IncomingCache` | `IncomingFileNames`, `DeleteOnCloseSource` (nested `HeldCopy`) |
| 20 | `SharedContentPolicy` | `OwnProviders`, `StreamIntake` (-> `validateStream`), `ShareUriPolicy` |
| 20 | `LauncherForwardPolicy` | `SenderGrantCheck` (-> `mayForward`) |
| 20 | `ShareService` | `ShareAddressResolver`, `SharedItemFactory`, `HttpServerProbe` |
| 20 | `ShareIntentHandler` | `ShareIntakeGate` (-> `admits`, `admitOrDiscard`) |
| 21 | `PendingPick` | `ReceiverPickResult` (nested `Decision`), `PickSheetResult` (nested `SheetGate`) |
| 21 | `ActionFlight` | `GuardedPickedAction` (-> `guard`) |
| 21 | `ReceiverPickController` | `AfterStartSelection` |
| 21 | `DeviceSheetFragment` | `PickTitle` (-> `nameOrFallback`, `subtitle`) |

Kept unchanged as classes (own state or state machine, own invariant, or boundary type): `UnclaimedShares`, `ShareKeyTracker`,
`ShareLimits`, `ShareConcurrencyLimiter`, `ByteRangeResolver`, `PositionalSource`, `LaunchNonces`, `PendingShare`, `DisplayName`,
`ReadinessWait`, `ShareLogRedactor`, `PickedAction`, `ProxyShareResponder`.
Result: 68 files -> 33 (27 in `de.yaacc.share`, 4 in `browser/`: `ShareIntentHandler`, `LauncherForwardPolicy`,
`ReceiverPickController`, `ReceiverBarController`; 2 in `upnp/server/http/`: `ProxyShareResponder`, `ShareProducers`).
Why 27 and not the 12-18 the owner named: each of the 27 has its own state machine, enforces one invariant with its own mutation
target, or is a boundary type; going lower makes classes of 700+ lines that mix unrelated invariants and coarsens the security
reviewer's per-class mutation checks. **Optional stage 2** (not scheduled, owner decides after Group 22; recommendation: do not):
`UnclaimedShares` + `ShareKeyTracker` -> `ShareRegistry`, `BudgetedCopier` -> `WatchedCopy`, `HeldFileSource` -> `SharedFileOpener`,
`ReadinessWait` -> `ShareService`, `LaunchNonces` -> `PendingShare`, `ActionFlight` + `PickedAction` -> `PendingPick`,
`ShareLogRedactor` -> `DisplayName` (27 -> 18 files, `ShareRegistry` ~800 lines, coarser tests that must re-express state machines).

**Behaviour preservation.** Every test case survives (moved into the class that absorbed the code, assertions unchanged unless
the API of a deleted class changed); the total is 1472 + the Group 17 characterization tests + one zone-id case; the test-name
multiset is compared against BASE (ledger in tasks.md). Test-only API kept in this phase: `RangeStreamer#serve` + the sink,
`ReceiverPickResult.Decision#clearSelectionAfterStart`, `PickSheetResult#isDelivered` (deleting them is an open owner question).

## Security invariants of the share feature (Phase 4 must not change them)
Each invariant stays pinned by the tests named in tasks.md; the security reviewer re-derives them from the code, not from this
list. "Held by" names the classes after Phase 4.

| # | Invariant | Held by (after Phase 4) |
|---|---|---|
| SI-1 | Key: 16 bytes from `SecureRandom` (`createDefault`), 22 chars unpadded base64url, never handed out twice (retired window 256, 8 draws), `isWellFormedKey` before any lookup; garbage never throws | `ShareRegistry`, `ProxyShareResponder` |
| SI-2 | Registry: monotonic clock (not wall time), idle grace 10 min after release, absolute max 6 h, `maxActive` cap, close exactly once and outside the lock, `register` returning null leaves ownership with the caller, `evictOldestReleased` touches only released entries, `removeAll()` on exit, sweeper | `ShareRegistry`, `Yaacc` |
| SI-3 | Auth chain order: path shape -> well-formed key -> lookup -> `ProxyAccessPolicy` -> renderer id -> serving. Every bad key gets the same constant 404 body, 403 only after a valid key, HEAD and range continuations take the same chain, key and path are never echoed or logged | `ProxyShareResponder`, `ServerSideSeeking` |
| SI-4 | IP step: remote address only from the connection's endpoint details; restriction defaults to ON in code, in `preference.xml` and when the read fails; zone ids stripped, IPv4-mapped IPv6 normalized, no `InetAddress.getByName`, an empty set denies everything while restricting, loopback and own addresses only when the local device was chosen, the allowed set is built from the ids captured at confirm and copied unmodifiable into the entry | `ProxyAccessPolicy`, `ShareService` (resolver), `ShareRegistry.Entry` |
| SI-5 | Ranges: invalid header or more than one range = 200 whole; unsatisfiable single range = 416 `bytes */len`; long arithmetic, saturating digits, header length cap; upstream `Range` re-serialized from parsed numbers; `Content-Range` from longs; no `Content-Disposition`; `Content-Type` from the re-validated entry MIME; `nosniff` + CSP `sandbox` | `ByteRangeResolver`, `ProxyShareResponder` |
| SI-6 | Held file: `dup` of the provider's descriptor and the original closed; regular-file and `lseek` check; start offset and length clamped to the real size; `pread` at `start + position`; close deferred to the last reader, never blocks the caller, exactly once, new reads refused after close; a failed open leaves the descriptor with the caller; a late result of a timed-out open is closed | `HeldFileSource`, `PositionalSource`, `SharedFileOpener`, `ProviderCalls` |
| SI-7 | At most `MAX_CONCURRENT_RESPONSES_PER_SHARE` (4) responses per share (503 on the 5th), idle watchdog takes a stalled reader's slot, `tryReacquire` aborts a resumed reader only when full, release idempotent, the lease is released when the producer cannot be built | `ShareConcurrencyLimiter`, `ShareProducers`, `ProxyShareResponder` |
| SI-8 | Launcher forward: fresh intent; `ACTION_SEND` + exact lower-case media type; ONE `ClipData.newRawUri` item and `FLAG_GRANT_READ_URI_PERMISSION` only; no `data`, no `EXTRA_STREAM`, no other extra; `EXTRA_STREAM` equals ClipData item 0; incoming flags carry the read grant; content policy; any provider of this app refused (fail closed); API 35+ caller check is advisory (logged only, 2026-10-29: it denied shares via the system share sheet); `SecurityException` -> plain launch; `finish()` in `finally`; the incoming intent is never modified | `LauncherForwardPolicy`, `SharedContentPolicy`, `LauncherActivity` (unchanged) |
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

## Risks and rollback (Phase 4)
- **Untested Android glue** (`ShareService#prepare`, `SharedFileOpener`, `HeldFileSource` I/O, `UpnpClient#createHttpLinkShare`
  registration) had no unit tests at BASE. Mitigation: the Group 17 characterization tests (source closed on every failure path,
  nothing registered without an address, unclaimed registration) and moves checked with `git diff -M --color-moved`; the owner re-runs
  the on-device list at the end. `HeldFileSource` and `SharedFileOpener` I/O still have no unit test: the reviewer reads those
  diffs line by line.
- **Mock-based tests are weaker than the old pure ones** where Android types now appear in arrange code. Mitigation: string-level
  parsers stay string-level; the mutation checks run on the moved tests.
- **Large mechanical diffs tire reviewers.** Mitigation: renames and package moves are separate commits (similarity 100%), merges
  follow, one class cluster per commit, reviewers get the old -> new table.
- **Parallel work on the branch would conflict**: share-related changes are frozen until Group 22 is done.
- **`InterfaceResolutionHelper.getIpAddress` returns `"0.0.0.0"`, not null, when offline**, so the "no address" guard rarely
  fires. Preserved, recorded for the owner, not fixed here.
- **Rollback.** Each group is a series of commits tagged `phase4-group-N` in the message (no tags, no history rewriting, no
  force-push). `git revert --no-commit <first>^..<last>` undoes a group; later groups first. BASE `ecb26c59` is the restore point
  of the phase. After 3 failed review cycles on a group, revert it and escalate with the unresolved findings.

## Other risks of the earlier phases (still relevant)
- D-pad focus inside a bottom sheet on TV is reasoned, not seen on a TV; classic keeps its tabs, so TV users have a path either way.
- A strict per-IP check can fail with renderers that fetch from another address than their descriptor host (multi-homed devices,
  IPv6): playback then fails with a 403 and a log line; the setting switches the check off (key and expiry stay).
- A held descriptor needs a seekable provider for range requests; the copy fallback covers the rest.
