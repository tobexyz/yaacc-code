# Security Review: Unified device sheet

## Cycle 1 — 2026-10-23
Reviewing: Group 1

Scope: `git diff origin/feat/issue287..HEAD` (`docs/tech.md` "Unified device
sheet" section, `DeviceSessionGrouper.java` stub, `DeviceSessionGrouperTest.java`,
spec files). Design guidance in `design.md` and `docs/tech.md` was checked for
anything that would steer Groups 2-3 toward an insecure build.

Committed-content scan: no secrets, credentials, tokens, private IPs, debug
endpoints or user-specific absolute paths in the diff. The only filesystem
path in `docs/tech.md` is the `~/.gradle/caches/...` location of the public
Material 1.14.0 AAR (the hash segment is the public artifact SHA-1). No new
dependencies, no manifest changes, no new exported components. The stub and
test have no I/O, no `android.*` imports and no reflection.

Existing code paths the design reuses were checked and are sound:
- Tap-to-open reuses `Player#getNotificationIntent()`
  (`PlayerListItemClickListener.java:51-61`). Every implementation builds an
  explicit-component intent and uses `FLAG_IMMUTABLE`
  (`AVTransportPlayer.java:966-973`, `LocalImagePlayer.java:340-355`,
  `LocalMediaSessionPlayer.java:477-481`), so the fill-in `Intent` passed to
  `send()` cannot redirect or alter it.
- Device/player names are rendered with `TextView#setText` (no
  `Html.fromHtml`, no `WebView`) in the existing adapters
  (`PlayerListItemAdapter.java:99`).

### Critical
- None.

### Warning
- None.

### Suggestion
- [design.md "Components" / tasks.md Group 3, session row] Constraint for
  Group 3: render every LAN-sourced string (device friendly name, player
  name, track title, artist) with plain `TextView#setText`. No
  `Html.fromHtml`, no `Linkify`/`autoLink`, no `WebView`, and no string
  formatting where the untrusted value becomes the format string
  (`String.format(untrusted, ...)`). Pass it as an argument to a resource
  format string instead. This applies to the new
  `device_sheet_player_session_item.xml` and the receiver bar text built
  from selected receivers' names.
- [design.md "Components", session row album art] Constraint for Group 3:
  load album art through the existing `IconDownloadTask` path, as
  `PlayerListItemAdapter.java:120` does. Don't add a new loader. Hardening
  note on that existing path (pre-existing, not introduced here):
  `ImageDownloader#getUriAsStream` (`ImageDownloader.java:144-153`) opens
  any `java.net.URL` scheme from DIDL `upnp:albumArtURI`, and
  `BitmapSampling.readAllBytes` reads with no size cap. A hostile media
  server on the LAN can make the app fetch arbitrary URLs (including
  `file:`) or send an unbounded body (OOM). Worth a follow-up issue:
  allowlist `http`/`https`, cap the byte count, and set connect/read
  timeouts. The sheet adds another caller, so it widens exposure slightly.
- [docs/tech.md §6, design.md "Risks", tasks.md Group 2 listener] Constraints
  for the `PlayerService` listener API:
  - Hold listeners in a structure that is safe to mutate while it is being
    iterated (for example `CopyOnWriteArrayList`), since add/remove can
    happen from within a callback.
  - The sheet must unregister in `onDestroyView`/dismiss, as tech.md says.
    It must also guard the posted main-thread callback
    (`isAdded()`/`getView() != null`, or remove pending callbacks on
    unregister). A `Handler.post` queued before unregistering can still run
    after the view is gone and touch a dead fragment.
  - Pass no `Context`, `Activity` or `View` into `PlayerService` with the
    listener. A listener that captures the fragment and is never removed
    leaks the activity for the lifetime of the service.
- [docs/tech.md §6, "copy it on the main thread"] `currentActivePlayer` is a
  plain `HashMap` (`PlayerService.java:59`), and tech.md notes that
  `createPlayer...` runs on the caller's thread. Copying on the main thread
  does not by itself prevent a `ConcurrentModificationException` if a
  background thread writes at the same time. This is a robustness or
  local-crash concern rather than an exploitable one. Group 2 should either
  confirm that every `addPlayer`/`removePlayer` caller runs on the main
  thread, or synchronize the map, and record the outcome in tech.md.
- [DeviceSessionGrouper.java `group`, Group 2] Device ids come from
  untrusted SSDP/UDN data. A LAN device can advertise `uuid:LOCAL_UID`, or
  two devices can share a UDN, so `receiverDeviceIds` may contain
  duplicates. `UpnpClient.getDevice` short-circuits `LOCAL_UID` to the local
  dummy device (`UpnpClient.java:499-501`), which limits the impact. Even
  so, `group` should handle duplicate receiver ids deterministically. The
  simplest rule is to emit each session once, under the first matching
  receiver, so a spoofed device can't make sessions appear twice or under an
  attacker-named row. Null `deviceId` in `PlayerRef` should go to the
  trailing group without throwing. One test for each case would pin this
  down.
- [tasks.md Group 3] Constraint: `DeviceSheetFragment` stays an in-app
  `DialogFragment`. Don't add an activity, receiver or deep link to open
  the sheet, and don't change `android:exported` on anything. Taps on
  session rows must keep using the player's existing immutable
  `PendingIntent`, not build a new implicit `Intent`. (Out of scope here:
  `AVTransportPlayerActivity` and the other player activities are already
  `exported="true"` and take a `PLAYER_ID` extra, which is pre-existing and
  unchanged by this spec.)

### Verdict: PASS

## Cycle 1 — 2026-10-23
Reviewing: Group 2

Scope: `git diff 8423351b..HEAD -- yaacc docs`, which covers
`DeviceSessionGrouper` (implemented), `ReceiverRowBinder` (extracted),
`BrowseReceiverDeviceAdapter`, `PlayerListChangeNotifier`, `PlayerService`,
`UpnpClient#getPlayerCreationSequence`, the two test classes and
`docs/tech.md`. Context came from decisions.md and the Group 2 cycles in
review.md. Test results on disk: `DeviceSessionGrouperTest` 22/22 and
`PlayerServiceListenerTest` 14/14, with 0 failures and 0 errors.

Group 1 suggestions that Group 2 owned:
- **Duplicate, null and spoofed device ids: honored.** `group()` treats ids
  as opaque. A duplicate receiver id is listed only at its first occurrence,
  a null receiver id is skipped, null-`deviceId` sessions form a trailing
  group, and null lists count as empty. `primarySession` tolerates null ids.
  Tests cover each case, including `remoteDeviceSpoofingLocalUidIsGroupedLikeAnyOtherId`.
- **A spoofed `LOCAL_UID` can't take control of local players.** A
  `RemoteDevice` advertising `uuid:LOCAL_UID` gets the local
  `primarySession` for display only. In `ReceiverRowBinder`:
  - play/pause/stop route by type (`isLocalDevice`: `LocalDevice` or
    `LocalDummyDevice`, `ReceiverRowBinder.java:358-360, 391`), so a rogue
    remote row sends AVTransport actions to itself, never to a local
    `Player`;
  - tap is a no-op for any `LOCAL_UID` string (`:325-327`), so it can't
    open a local player's intent;
  - checking the rogue row stores the id string, and
    `UpnpClient.getDevice("LOCAL_UID")` resolves to the dummy device
    (`UpnpClient.java:499-501`).

  What's left is cosmetic: an attacker-named row can mirror local track
  title and album art. That was already true of the old
  `getPlayerForDevice` string match, so it isn't new.
- **Listener API: honored.** `Listener` takes only `Player`. The notifier
  holds a main-looper `Handler::post` executor and no `Context`.
  Listeners sit in a `CopyOnWriteArrayList`, so a listener can unregister
  itself mid-callback. Delivery re-checks `listeners.contains(...)`, so a
  listener removed before a queued delivery runs is not called. Tests
  cover self-unregistration, removal before delivery and double
  registration. The strong reference held until unregister is documented
  on `PlayerService#addPlayerListChangeListener`.
- **Every map write goes through the notifier: honored.** All
  `currentActivePlayer` mutations (`PlayerService.java:119, 132, 564`) go
  through `putAndNotify`/`removeAndNotify`. `remove(id, player)` stops a
  stale instance from evicting its replacement. The `ConcurrentHashMap`
  switch resolves the Group 1 concurrent-write note. The one remaining
  bypass is `getPlayer()` (see Suggestions).

Tap path: `openPlayerActivity` now matches any `Player` by `getDeviceId()`
instead of only `AVTransportPlayer`. Non-AVTransport players all report
`LOCAL_UID`, which returns early, so a remote device can't reach them. The
send is still `getNotificationIntent().send(context, 0, new Intent())` on
explicit, `FLAG_IMMUTABLE` PendingIntents, so the fill-in can't alter the
target. No change of security relevance.

Untrusted strings: the extraction adds no new place where they are
rendered. Friendly name, track title and status still go through
`TextView#setText`. Album art and device icons still go through
`IconDownloadTask`. There is no `Html.fromHtml`, no `Linkify`/`autoLink`,
and no untrusted format string.

No secrets, credentials, local paths, new dependencies, manifest changes or
exported components in the diff.

### Critical
- None.

### Warning
- None.

### Suggestion
- [PlayerService.java:252-254] `getPlayer()` returns the live mutable
  `currentActivePlayer.values()`. A `values().remove(...)` through it would
  bypass the notifier and leave a stale creation sequence. It has no
  callers. Remove it, or return
  `Collections.unmodifiableCollection(...)` like `getCurrentPlayers()`
  (`:486`), so "every write goes through the notifier" can't regress.
- [Group 3, DeviceSheetAdapter] Constraint: when turning a RECEIVER `Row`
  back into a `Device`, resolve its id with `UpnpClient.getDevice(id)`, which
  short-circuits `LOCAL_UID` to the dummy device. Don't use an id→device map
  built from the registry list. `group()` keeps only the first occurrence of
  a duplicate id. If a rogue `uuid:LOCAL_UID` device sorts first, a map
  lookup would bind the real local sessions under the attacker's row and
  hide the dummy-device row.
- [ReceiverRowBinder.java:122] Each binder owns a
  `newSingleThreadExecutor()` that is never shut down. That's harmless for
  the single adapter of the Receiver tab. If Group 3 creates a binder per
  sheet open, each open leaks an idle thread. Share one binder, or add a
  `release()` that calls `executorService.shutdown()` from the sheet's
  `onDestroyView`.
- [BrowseReceiverDeviceAdapter.java:198-211, ReceiverRowBinder.java:234]
  The local-status poll and the synthesized local status pick devices by the
  `LOCAL_UID` string, so a spoofed device can receive (and, if sorted first,
  take over) the local row's status display. This is cosmetic only, as noted
  above, and pre-existing. The root fix is a follow-up: ignore remote
  devices whose UDN identifier equals `LOCAL_UID` in
  `UpnpClient#remoteDeviceAdded`/`deviceAdded`, rather than special-casing
  it at every consumer.
- Carried forward from Group 1, still open for Group 3: plain-text
  rendering of LAN strings in the new session row and receiver bar, and the
  pre-existing `ImageDownloader` scheme allowlist and size cap follow-up.

### Verdict: PASS

## Cycle 1 — 2026-10-23
Reviewing: Group 3

Scope: `git diff 02751ed0..HEAD -- yaacc`, which covers `DeviceSheetFragment`,
`DeviceSheetAdapter`, `ReceiverBarLabel`, `MonitorSubscriptions`,
`RendererStatusMonitor` (now shared), `ReceiverRowBinder`, `UpnpClient`, the
Content and Receiver fragments, the play entry points, layouts, styles,
strings and tests. Context came from decisions.md (shared monitor;
`UpnpClient` keeping player-list listeners across rebinding). Test results
on disk: `MonitorSubscriptionsTest` 10/10 and `ReceiverBarLabelTest` 7/7,
with 0 failures and 0 errors.

Earlier suggestions aimed at Group 3:
- **Untrusted strings rendered as plain text: honored.** Session name,
  title and orphan device id use `setText` only
  (`DeviceSheetAdapter.java:321-331`). The receiver bar uses
  `ReceiverBarLabel.build(...)`, which builds a plain `String.join`, then
  `setText` (`ContentListFragment.java:384-390`). Receiver rows still go
  through `ReceiverRowBinder`. The new layouts have no `autoLink`, no
  `Html.fromHtml` and no `Linkify`, and every LAN string view is limited by
  `maxLines` and `ellipsize`. No untrusted value is used as a format string.
- **Album art only through `IconDownloadTask`: honored**
  (`DeviceSheetAdapter.java:347-350`, with a recycle-guard tag).
- **Listener lifecycle: honored.** The sheet subscribes in `onStart` and
  drops the monitor listener, the player-list listener and the local poller
  in `onStop`, with `onDestroyView` as a backstop
  (`DeviceSheetFragment.java:164-215`). Every callback null-checks
  `adapter`, which is cleared in `onDestroyView`. Nothing is delivered after
  unregistering:
  - `MonitorSubscriptions.deliver` checks the generation and membership;
  - `PlayerListChangeNotifier` re-checks membership;
  - the poller checks `liveUpdatesRunning` and is removed with
    `removeCallbacks`.

  `UpnpClient`'s retained list (`CopyOnWriteArrayList`) is emptied by the
  same `removePlayerListChangeListener` call that detaches the listener from
  the service. So the app-scoped `UpnpClient` holds the fragment (an inner
  class) only from `onStart` to `onStop`. Every `onStart` is paired with an
  `onStop` before destruction, so no dead fragment is retained. Re-attaching
  on rebind is de-duplicated (`addIfAbsent`/`contains`). The shared monitor
  holds no `Context`.
- **Binder executor shut down: honored.** `DeviceSheetAdapter.release()`
  calls `ReceiverRowBinder.shutdown()` from `onDestroyView`, and later
  transport taps check `isShutdown()`. That leaves one thread per open view,
  which is released when the view goes away.
- **Receivers resolved via `UpnpClient.getDevice(id)`: honored**
  (`DeviceSheetAdapter.java:116-125`), de-duplicated by id. A rogue
  `uuid:LOCAL_UID` device therefore resolves to the local dummy device and
  can't own the local row in the sheet. This is stricter than the Receiver
  tab.
- **No exported components: honored.** The manifest diff is empty and
  there are no new intent filters. The sheet is shown only by
  `ContentListFragment.showDeviceSheet()` through `getChildFragmentManager()`.
  Its callers are the receiver bar's click listener and the UI click
  handlers in `BrowseContentItemAdapter` (play, play-all) and
  `ContentListClickListener`.
- **Session tap: honored.** `ReceiverRowBinder.openPlayer` sends the
  player's own `getNotificationIntent()` (explicit, `FLAG_IMMUTABLE`) with
  an empty fill-in. No new intent is built.

No-receiver prompt: `openDeviceSheetIfNoReceiverSelected()` is reachable only
from those three UI click handlers. The renderer-service path
(`AvTransportMediaRendererPlaying` → `PlayerService`) and remote control
points never reach `ContentListFragment`, so a LAN peer cannot pop the
sheet or any other UI. The non-UI fallback is still `PlayerService`'s toast.
`show()` also refuses when the state is saved or the sheet is already
showing.

Shared monitor, cross-subscriber effects: subscribers can't affect each
other's delivery. Unsubscribing a listener stops polling only when it was
the last one, and in-flight results from an older generation are dropped.
The availability question is covered in the first Suggestion below.

No secrets, credentials, local paths or new dependencies in the diff.

### Critical
- None.

### Warning
- None.

### Suggestion
- [RendererStatusMonitor.java:65 (single-thread executor), HttpRequestSender.java:63-64]
  A hostile LAN renderer can stall status polling for every receiver. All
  polls run serially on one executor thread. The HTTP client's 10 s socket
  timeout is an *inactivity* timeout, so a device that accepts the
  connection and trickles a byte every few seconds can hold that thread
  indefinitely. Every other receiver's status then freezes in both the
  Receiver tab and the sheet. Impact is limited to status display:
  transport and volume actions run on other executors, and no data is
  exposed. The single executor isn't new; the shared monitor extends the
  stall to the sheet. Two follow-up options:
  - add an overall per-request deadline (for example, cancel the future
    after N seconds);
  - poll on a small bounded pool and skip a device whose previous poll is
    still in flight.

  The same applies within one `ReceiverRowBinder`: its single transport
  executor serializes play/pause/stop across devices (pre-existing).
- [ReceiverBarLabel.java:43-56] Optional hardening: strip Unicode bidi
  controls (U+202A-U+202E, U+2066-U+2069) and other format characters from
  names before joining. As it stands, one device's name can visually reorder
  or disguise the other selected receivers' names in the bar. This is
  cosmetic only, since the label drives no action.
- Carried forward, still open:
  - `PlayerService.getPlayer()` (`:252-254`) still returns the mutable
    live `values()` view, which bypasses the notifier (no callers).
  - `ImageDownloader` scheme allowlist and size cap.
  - Ignore remote devices whose UDN identifier equals `LOCAL_UID` at
    `UpnpClient` ingress. The sheet is safe either way, but the Receiver tab
    still special-cases the string.

### Verdict: PASS

## Cycle 1 — 2026-10-24
Reviewing: Group 4 (docs)

Scope: uncommitted `git diff -- docs/index.md docs/tech.md CHANGELOG.md`.
This change touches docs only. `git status` shows no code changes since
the Group 3 security review (PASS). `docs/` is published to GitHub Pages,
so the review checked for content that shouldn't be public and for any
statement about security-relevant behavior that doesn't match the code.

Content that shouldn't be public: none found. The diff was scanned for
URLs, IPv4 addresses, absolute home or user paths, email addresses, and the
words token, secret, password and api key. Nothing matched. The diff adds
no external links. The only references are repo-relative class, layout and
resource names and the in-page anchor `#receiver-controls`. The existing
`~/.gradle/caches/...` reference sits outside this diff and was already
cleared in Group 1.

Security-relevant claims, checked against the source:
- **Polling stops when the sheet isn't visible: accurate.** The sheet's
  three update sources (status monitor subscription, player-list listener,
  2 s local poller) start in `onStart()` and stop in `onStop()`
  (`DeviceSheetFragment.java:153-172, 191-210`, `removeCallbacks` on the
  poller). The shared `RendererStatusMonitor` stops polling once its last
  listener leaves. Its other subscriber, the Receiver tab, subscribes only
  between `onResume()` and `onPause()` (`ReceiverListFragment.java:64-96`).
  `ViewPager2`/`FragmentStateAdapter` resumes only the current page, so
  the Receiver tab doesn't keep polling while the Content tab is showing.
  The quoted intervals match the code: 10 s per remote renderer
  (`RendererStatusMonitor.java:57`) and 2 s for the local poll
  (`DeviceSheetFragment.java:59`).
- **Session tap: accurate.** tech.md §8 says the tap sends the player's own
  notification `PendingIntent`. That matches the Group 3 finding: explicit
  intent, `FLAG_IMMUTABLE`, empty fill-in.
- **Untrusted renderer data: accurate.** "all LAN strings are set with
  plain `setText`" and album art "through `IconDownloadTask`" match the
  Group 3 review.
- **No new entry points: accurate.** The docs describe the sheet as opened
  only from the receiver bar and from the play paths in the Content tab.
  They don't describe a deep link, an exported component or remote
  triggering.
- **"Add to playlist" isn't gated: accurate.** This is a UX detail with
  no security impact.
- **Known limitations: accurate.** tech.md §8 discloses that a slow
  renderer delays status for every receiver. That is the availability issue
  from the Group 3 Suggestion, described at a level that teaches nothing
  beyond the public source. It also says, correctly, that transport
  controls are unaffected.
- **Nothing documents an insecure practice.** The docs give no
  instructions to disable checks, sideload, enable debugging or expose
  services.

### Critical
- None.

### Warning
- None.

### Suggestion
- None new. The Group 2 and Group 3 follow-ups remain open and are outside
  this docs group:
  - `PlayerService.getPlayer()` returns a mutable view;
  - `ImageDownloader` needs a scheme allowlist and a size cap;
  - remote devices claiming `LOCAL_UID` should be dropped at ingress;
  - the per-request deadline for `RendererStatusMonitor` polls;
  - bidi-control stripping in `ReceiverBarLabel`.

### Verdict: PASS

## Cycle 1 — 2026-10-24
Reviewing: Group 5 (phase 2)

Scope: `git diff e80480e7~1..HEAD` (`BrowserLayoutChoice` and `MiniPlayerPicker`
stubs, their two tests, `docs/tech.md` "Classic and new layout", plan edits in
tasks.md, design.md and decisions.md). Because this group sets up the
security-sensitive parts of Groups 6-9, the plan for them was also reviewed
against the current code: `TabBrowserActivity.java` (share handler, idle timer,
`onResume`), `UpnpClient#createPlayableItem`, the proxy path in
`YaaccUpnpServerContentHttpHandler`, `MediaProjectionHelper`, `Yaacc.java`,
`AndroidManifest.xml` and `file_paths.xml`.

What this group changed: no production behavior. Both stubs are plain Java
with no `android.*` imports, no I/O, no reflection and no callers under
`yaacc/src/main`. The manifest is untouched, nothing is exported, and there are
no new dependencies. `docs/tech.md` has no secrets, tokens, IP addresses,
user-specific paths or e-mail addresses. Its only filesystem path is the public
`~/.gradle/caches/...` AAR location, which was already cleared in Group 1. It
advises nothing insecure, except the trampoline body in section (f), which is
the first Warning below. Fail-closed contract: the tests require exact,
case-sensitive `"new"` for NEW and everything else (null, empty, unknown,
differently cased) for CLASSIC, which is the safe direction for a value that
backup/restore or adb can set. The stub `resolve` returns null, but nothing
calls it yet.

### Critical
- None.

### Warning
- [tasks.md Group 6 "LauncherActivity" Accept, docs/tech.md (f) trampoline
  body] The plan forwards the incoming intent verbatim. The doc says "Prefer
  the copy ... carries the grant exactly like the original", "keep the
  launcher's own FLAG_ACTIVITY_* bits", and the task says "`new
  Intent(getIntent())`, sets only the component". The launcher is exported
  and takes any intent, because an explicit intent ignores the filters. The
  copy-everything design therefore launders an arbitrary third-party intent
  into a same-app intent for `BrowserActivity`, which is meant to be
  non-exported. The copy keeps the sender's flags, `ClipData`, every extra
  (including nested `Intent`/`PendingIntent` extras such as `EXTRA_INTENT`),
  `getSelector()`, `FLAG_GRANT_*` and `FLAG_GRANT_PERSISTABLE/PREFIX` bits, and
  `data`. `new Intent(Intent)` copies the selector as well. Consequences:
  - Anything `BrowserActivity` or `TabBrowserActivity` reads from
    `getIntent()` now looks internal. `getReferrer()` and
    `getCallingPackage()` are laundered to the app itself, so the browsers
    can't distinguish the sender.
  - Forwarded sender flags (`CLEAR_TASK`, `CLEAR_TOP`, `FORWARD_RESULT` and
    others) apply to the trampoline's `startActivity`.
  - The grant check on the forward runs as the app's own UID, which owns the
    non-exported `FileProvider` and holds the sender's grants. An
    attacker-supplied `content://<applicationId>.fileprovider/...` or a `file:`
    URI in `ClipData` is opened with the app's identity if any code opens it
    (see the share-handler Suggestion).
  - Today only `ACTION_SEND` is consumed, so the exploitable effect is small.
    The design makes every future read of `getIntent()` in the new,
    supposedly private, activity attacker-reachable. It also contradicts the
    check "no `FLAG_GRANT_*` added", which misses the real problem: the
    sender's grant bits are already on the copy.

  Amend Group 6 before building: replace "copy with only the component
  changed" with a fresh, allowlisted forward (rules under Suggestion, "Group 6
  must"). The change is small and removes the confused-deputy and laundering
  questions altogether.
- [tasks.md Group 6 "resume check", docs/tech.md (c) snippet, decisions.md
  2026-10-24] The relaunch has no loop guard and can loop forever as written.
  Each browser compares `BrowserLayoutChoice.resolve(pref)` with its own
  layout and, on mismatch, starts "the other" class via the launch helper. In
  the same task, until Group 8 flips `NEW_LAYOUT_AVAILABLE`, the helper maps
  every choice to `TabBrowserActivity`. A stored `"new"` is therefore a
  mismatch in `TabBrowserActivity`, which starts `TabBrowserActivity`,
  which finishes and mismatches again. This repeats forever, with no way to
  reach Settings to fix it. `"new"` can reach a device without the hidden UI
  through backup/restore (the manifest sets no `allowBackup`, so the default
  is true), adb, or a downgrade from a later build. The same loop appears
  after Group 8 if `BrowserActivity` ever fails to stay up, or if the preference
  flips between the two `onResume` calls. The relaunch also runs in
  `onResume`, after `onCreate` has already run the share handler, so a
  mismatched instance processes the share and then redirects.

  Stale or forged PendingIntents are not a problem. The three internal
  ones are explicit, `FLAG_IMMUTABLE` (`Yaacc.java:172`,
  `PlayerService.java:111, 201`), so another app can't forge or edit them.
  The only risk is the loop above. Amend Group 6 with the guard rules under
  Suggestion.

### Suggestion
Forward-looking requirements, to be folded into tasks.md. "Group N must:" items
are binding on that group's review.

**Group 6, trampoline and manifest**
- Group 6 must: build a fresh `Intent` for the forward instead of
  `new Intent(getIntent())`. Rules:
  - set the component to the target class from the launch helper;
  - copy the action only if it is `ACTION_MAIN` or `ACTION_SEND`, and nothing
    else for any other action;
  - for `ACTION_SEND`, copy type `text/plain`, `EXTRA_TEXT` read with
    `getStringExtra` (a `String`, length-capped, for example 8 KiB), and a
    rebuilt plain-text `ClipData` only if the share handler needs it;
  - never copy flags (no `FLAG_GRANT_*`, no `FLAG_ACTIVITY_*`, no persistable
    or prefix bits), `data`, `ClipData` URI items, `EXTRA_STREAM`, `EXTRA_INTENT`
    or any other extra, the selector, the package, the identifier or the
    categories;
  - drop all `Uri` items, and so grants, unless the owner decides otherwise
    (the filter is `text/plain`, so a `content:` item is not playable media
    anyway). The `content://` on-device check in tech.md should then be
    recorded as "not supported by design".
- Group 6 must: wrap all reads of the incoming intent in
  `try { ... } catch (RuntimeException)` (covers `BadParcelableException`,
  `ClassNotFoundException`-driven failures and similar from a crafted extras
  bundle), forward nothing on failure, and call `finish()` in `finally`. A
  malformed extras bundle currently crashes the whole process, including
  `PlayerService`. `checkIfReceivedShareIntent` calls `getExtras()` unguarded.
  `TabBrowserActivity` stays exported, so `ShareIntentHandler.handle` needs the
  same guard (see below).
- Group 6 must: add no `launchMode` or `taskAffinity` to `LauncherActivity`,
  `TabBrowserActivity` or `BrowserActivity`, as tech.md already proposes. Set
  `excludeFromRecents` only if the owner wants it.
- Group 6 must: keep `TabBrowserActivity` as `exported="true"` with no filters
  as decided. Check that the manifest diff shows exactly one new exported
  component (`LauncherActivity`) and that `TabBrowserActivity` keeps its
  current exposure rather than gaining any. Neither `TabBrowserActivity` nor
  `BrowserActivity` may read an internal-only extra, action or token from
  `getIntent()` as a trust signal, and no `getReferrer()` or
  `getCallingPackage()` based decisions (the trampoline erases the origin,
  and `TabBrowserActivity` is directly reachable by explicit intent anyway).
- Moved VIEW filter (`MAIN+VIEW+DEFAULT+LAUNCHER`, no `<data>`): flagged as
  asked. It matches only data-less implicit `VIEW` intents with `DEFAULT`
  from any app. Moving it unchanged adds no surface, because today's exported
  `TabBrowserActivity` has the same filter, and no sensitive action hangs off
  it. Dropping it is better hygiene, but it's an owner decision, since an
  older shortcut or automation may depend on it. If kept, Group 6 must
  copy it verbatim and the trampoline must apply the same action allowlist
  above, which means a `VIEW` intent forwards as a plain launch (action
  dropped, nothing carried over).
- Group 6 must: not export `BrowserActivity` (Group 7 re-checks). Also no
  `<intent-filter>` on `TabBrowserActivity` or `BrowserActivity`.

**Group 6, resume check (guard for finding 2)**
- Group 6 must: decide mismatch on the effective layout, not the stored one.
  Define `BrowserLauncher.effectiveLayout()` as `CLASSIC` while
  `NEW_LAYOUT_AVAILABLE` is false, else `BrowserLayoutChoice.resolve(...)`.
  Relaunch only if `effectiveLayout() != this activity's layout`. The launch
  helper and the check must use the same function, so the target can never be
  the same class as the caller. Add a unit-testable assertion for this
  (stored `"new"` with the flag false means no relaunch).
- Group 6 must: add a process-wide, in-memory loop guard. For example, a
  static timestamp or counter in `BrowserLauncher` that allows at most one
  relaunch per 2 s (or per process start, with a reset when a browser stays
  resumed past a short threshold). If the guard trips, render in the current
  layout and log. In-process state can't be forged, unlike an intent extra,
  and a forged "already relaunched" extra could only suppress a redirect.
- Group 6 must: relaunch with a fresh `new Intent(this, target)`, with no
  extras, data or flags copied from `getIntent()`. Process the share payload
  in the instance that received it, or do the layout check before the share
  handler runs, so the payload is consumed exactly once and never re-forwarded.
- Group 6 must: read the stored value with default `null` only
  (`getString(key, null)`), as tech.md says.

**Group 6, share handler (finding 2)**
- Existing risks, pre-existing and reachable today by any app on the device
  through the exported `TabBrowserActivity`:
  - `createPlayableItem(Uri)` takes any scheme or even a bare path. With the
    proxy option on, it writes `sha256(uri)` -> uri to the default prefs and
    the local server serves it at `/proxy/<hash>`. The handler opens
    `new File(uri)` or `ContentResolver.openInputStream(Uri.parse(uri))`
    (`YaaccUpnpServerContentHttpHandler.java:1396-1470`), so a shared
    `file:///data/data/<pkg>/shared_prefs/...`, a plain path, or the app's own
    `content://...fileprovider/...` becomes readable over HTTP by anything on
    the LAN, and by the sharing app through localhost. The hash is
    predictable. The app also holds `MANAGE_EXTERNAL_STORAGE`, so shared
    storage is in reach too. This needs the proxy option enabled.
  - Untrusted text starts playback on the selected receivers with no
    confirmation, and when none is ready it rewrites the saved receiver
    selection to `LOCAL_UID`.
  - A `RuntimeException` on the worker thread
    (`throw new RuntimeException(ioException)`) kills the process when run
    through `executor.execute`.
- Group 6 must: not widen any of this. Specifically, `ShareIntentHandler`
  must remain the only code that parses `EXTRA_TEXT` or clip data, must only
  `Uri.parse` and pass the result to `createPlayableItem`, and must never
  build an `Intent`, `PendingIntent` or file path from it.
- Group 6 must (recommended, needs an owner nod because the task says "parsed
  exactly as today"): apply a scheme allowlist of `http` and `https` before
  `createPlayableItem`, reject everything else (`file`, `content`, no scheme or
  bare paths, `intent`, `javascript`), and toast a short, fixed message.
  Without the allowlist, file a follow-up issue that cites the proxy path
  above. This is the largest existing hole the share path has, and Group 6
  builds the one shared helper both layouts will use.
- Group 6 must: wrap parsing in `try/catch (RuntimeException)` and the worker
  body in `catch (Exception)` with a log and a toast, with no rethrow
  (the `IOException -> RuntimeException` crash becomes a handled failure),
  cap the URI length (for example 2048), and keep no `Activity` or `View`
  reference (application context plus a main-looper `Handler`, as planned).
  `shutdown()` must also cancel pending delayed work so a destroyed screen
  doesn't start playback 6 s later (use `shutdownNow()` or
  `setExecuteExistingDelayedTasksAfterShutdownPolicy(false)`).
- Group 6 must: keep clearing consumed extras on its own copy, not on a shared
  `Intent`, and not log the shared text at INFO or above (it can contain
  signed URLs or tokens in the query string). `createPlayableItem` logs only
  the exception today; keep it that way.

**Group 6, idle timer and static callbacks (finding 4)**
- Process-wide idle timer. The plan says "started when a browser goes to the
  background, cancelled when any browser resumes". The activity lifecycle
  during a switch is `A.onPause` -> `B.onCreate/onStart/onResume` ->
  `A.onStop`. A timer started in `A.onStop` therefore starts after
  `B.onResume` has cancelled it, nothing cancels it again, and
  `Yaacc.exit()` runs 5 minutes later while the user is in `B`. Making the
  timer process-wide doesn't fix this on its own. This is a functional
  defect more than a security one (the runnable only exits when no players,
  server, renderer or proxy are active), so it is logged here rather than
  failing the cycle, but it must be fixed in Group 6.
  - Group 6 must: count started browsers (increment in `onStart`, decrement in
    `onStop`) and start the timer only when the count reaches 0. Cancel it in
    every `onStart`/`onResume`.
  - Group 6 must: hold the timer in a process-scoped object using only the
    application context. It must not capture an `Activity` (today the
    runnable and handler capture the activity for up to 5 minutes).
  - Group 6 must: re-read the preferences and the player list when the timer
    fires, as today. It must not cache the decision.
  - Group 6 must: add a plain-JVM test for the counter (the switch order
    above ends with the timer cancelled, background ends with it armed).
- `Yaacc.setShutdownTimerListener` is a single slot. A new fragment attaching
  in `onStart` before the old one detaches in `onStop` (the same ordering
  as above) would have its listener removed by the old fragment's detach.
  Group 6 must: make `ShutdownTimerController` support several listeners
  (`CopyOnWriteArrayList`) or a compare-and-clear detach (clear only if the
  slot still holds the caller). It must hold no `Context`/`View`, deliver on the
  main thread, and tolerate a detach during delivery. `Yaacc.exit()` from the
  timer finishing must still end the process whichever layout is on top.
- `MediaProjectionHelper.stopCallback` has the same single-slot hazard
  (`ServerListFragment.java:89`, set there and unset in `onStop` after the
  Group 6 change). Group 6 must: unregister with the same instance and clear
  the slot only if it still holds that instance. The callback must not capture
  a fragment or `Context`, but a weak or lifecycle-checked reference, and it
  must hop to the main thread before touching views (`onStop` of the
  projection arrives on the framework's callback thread).
- MediaProjection token handling in `StreamingPermissionHelper`. Group 6
  must keep the result `Intent` in `MediaProjectionHelper`'s static fields
  only (in-process). It must not be written to `savedInstanceState`, a
  `Bundle`, `SharedPreferences`, a `ViewModel`, an `Intent`/`PendingIntent`
  extra, a file or a log. Log only the result code and "granted/denied", never
  `data`, `data.getExtras()` or `toString()` of the extras. It must also:
  - accept the result only for the request the helper itself issued (check the
    request code, or use an `ActivityResultLauncher` owned by the helper) and
    ignore a result when no request is pending;
  - not expose a getter for `storedResultData` beyond what
    `createMediaProjectionFromStored` needs;
  - keep `clearPermission()` on the disable path.

**Group 7**
- Group 7 must: `BrowserActivity` `exported="false"`, no filters. Its
  `getIntent()` handling is limited to passing the forwarded, sanitized intent
  to `ShareIntentHandler` (null callback). It must not read any other extra,
  `data`, or `ClipData` URI, must not call `startActivity` with anything derived
  from the intent, and must use the same `BrowserLauncher.effectiveLayout()`
  resume check with the Group 6 loop guard.
- Group 7 must: add no deep link, no `onNewIntent` path other than the share
  handler, and no `PendingIntent` built from incoming data.
- Group 7 must: `ServerRootFragment` and the reused `BrowseDeviceAdapter` keep
  LAN-supplied names and icons as plain `setText` / `IconDownloadTask`.

**Group 8**
- Group 8 must: build the mini-player bar from `Player` objects only. Tap uses
  `Player#getNotificationIntent()` with an empty fill-in `Intent`, as the
  sheet does (explicit, `FLAG_IMMUTABLE`). No new implicit `Intent`.
- Group 8 must: render title, receiver name and album art with plain `setText`
  and `IconDownloadTask`. Strip bidi controls from receiver names if
  `ReceiverBarLabel` gets the hardening suggested in the Group 3 cycle,
  since the mini-player will repeat the name.
- Group 8 must: unregister the `PlayerListChangeNotifier` listener and stop
  the 2 s poll in `onStop` and `onDestroy`, pass no `Context`/`View` into
  `PlayerService`, and guard posted callbacks with `isAdded()` / `getView() !=
  null`. Do not add another status poller, as the plan already says.
- Group 8 must: pass `MiniPlayerPicker` only copies of players, and treat
  null device ids, spoofed `LOCAL_UID` ids and equal sequences as the tests
  require (no dependence on input order, no exceptions). Transport commands
  run off the main thread through the existing `ReceiverRowBinder` path.

**Group 9**
- Group 9 must: store the layout as a `ListPreference` with fixed
  `entryValues` `classic`/`new` and read it only through
  `BrowserLayoutChoice.resolve`. Nothing may treat the stored string as a
  class name, an action, a path or an intent target.
- Group 9 must: keep `NEW_LAYOUT_AVAILABLE` and the effective-layout check, so
  a restored backup value of `new` can't strand the user.
- Group 9 must: make no `android:exported`, `allowBackup`, or permission
  changes.
- Group 9 / owner: consider `android:allowBackup="false"` or an exclude rule
  for the default preferences file. It holds the `proxy_link_*` entries (the
  mapping from shared URI to proxy key), the receiver selection, and now the
  layout choice. This is outside the spec, so it is a follow-up note only.

**Group 10**
- Group 10 must: record in `docs/tech.md` what the trampoline forwards and
  what it drops, the scheme allowlist (or the open follow-up issue), and the
  loop guard. Correct the section (f) trampoline body in `tech.md`
  (copy-with-component), which stays advisory to later readers until it is
  amended.

**Carried forward, still open** (outside this phase):
- `PlayerService.getPlayer()` returns a mutable view;
- `ImageDownloader` needs a scheme allowlist and a size cap;
- ignore remote devices whose UDN equals `LOCAL_UID` at `UpnpClient` ingress;
- per-request deadline in `RendererStatusMonitor`;
- bidi-control stripping in `ReceiverBarLabel`.

### Verdict: FAIL
Group 5's own changes are clean. FAIL is for the two plan flaws above
(unsanitized full-intent forward, and the relaunch without a loop guard that
loops under the planned `NEW_LAYOUT_AVAILABLE = false` interim). Both are
fixed by editing the Group 6 task text and tech.md (f) before Group 6 starts;
no code changes are needed. Once the amended Group 6 Accept/Constraints carry
the "Group 6 must" items marked as Warning fixes (fresh allowlisted forward;
effective-layout comparison, loop guard, fresh relaunch intent), the next cycle
should PASS.

## Cycle 2 — 2026-10-24
Reviewing: Group 5 (phase 2)

Scope: re-check of the two cycle 1 Warnings against commit 6165278f (`docs/tech.md` (f), `tasks.md` Groups 6-10, `decisions.md`). Plan text only; no code changed.

### Cycle 1 Warning status
- **W1 launcher copies the whole incoming intent: RESOLVED in the normative text.** `tasks.md:117` (Group 6 Accept) and `tech.md` (f) body (~1494-1535) now require a fresh `Intent` for the chosen class; allowlist MAIN and SEND `text/plain` only (anything else is a plain launch); only a length-capped `getStringExtra(EXTRA_TEXT)` (ClipData text, never URI, as fallback); no flags, `data`, ClipData URIs, `EXTRA_STREAM`/`EXTRA_INTENT`/other extras, selector or categories; reads inside `try/catch(RuntimeException)` forwarding nothing; `finish()` in `finally`. Verify greps for `new Intent(getIntent())` / `new Intent(in)` (`tasks.md:118`). The (f) code sample matches.
- **W2 relaunch with no loop guard: RESOLVED in tasks.md.** `tasks.md:117` requires comparison of the *effective* layout (CLASSIC while `NEW_LAYOUT_AVAILABLE` is false), the same function in the launch helper and the check, stored value read with default `null`, fresh relaunch intent, static in-process guard (one relaunch per ~2 s, stays put and logs when tripped), share payload consumed once; `tasks.md:118` adds a JVM test of the guard's decision function.

### Other cycle 1 constraints
- Idle timer counter (started-browser count, arms at 0, application context only, re-reads prefs on fire, JVM tests for overlap/switch/rotation): present, `tasks.md:113`, consistent with `decisions.md`.
- Share handler (text cap, worker exceptions caught and logged without payload, `shutdown()` cancels delayed work, text never used for intent/PendingIntent/path, http/https-only scheme policy): present, `tasks.md:113`. Scheme policy is marked "owner decision pending" in `tasks.md` and `decisions.md`: accepted as a pending item, not a finding.
- Single-slot callbacks (multi-listener or compare-and-clear, no Context/View, main-thread delivery): present, `tasks.md:113`.
- MediaProjection result (helper static fields only, never Bundle/prefs/Intent, extras not logged, accepted only for the issued request): present, `tasks.md:113`.
- Group 7 (not exported, no filters, no `startActivity`/PendingIntent/deep link from `getIntent()`, uses Group 6 check and guard), Group 8 (tap uses `getNotificationIntent()` with an empty fill-in), Group 9 (no exported/allowBackup/permission changes, layout string never a class name or target), Group 10 (record allowlist, scheme policy, loop guard in tech.md (f)): present and consistent with Group 6.
- `grep` for `new Intent(getIntent())`: only the Verify command text in `tasks.md:118` remains; no instruction to copy an incoming intent. `keeps action, extras`: no hits (removed from the (f) sample).

### Critical
- None.

### Warning
- [docs/tech.md:925] The Summary of recommendations table still says "forward a copy with `setComponent`". This is the exact instruction Cycle 1 W1 rejected, and `tasks.md` tells implementers to follow `docs/tech.md`. Change to "forward a fresh, allowlisted intent (MAIN / SEND text/plain only), `finish()` in `finally`".
- [docs/tech.md:1149-1158 and :922] The "Relaunch when the Layout preference changes" recipe still shows `BrowserLayoutChoice.resolve(prefs.getString(layoutKey, null)) != THIS_LAYOUT` (stored layout, no guard, `BrowserLauncher.browserIntent(this)`), and the summary row says only "compares the stored layout". A reader who follows it reproduces the Cycle 1 W2 loop under `NEW_LAYOUT_AVAILABLE = false`. Update the snippet and the row to the effective-layout comparison with the static relaunch guard and a fresh intent, as in `tasks.md:117`. Also :1140-1142 still says "any browser `onResume()` cancels" the idle timer; align with the started-browser counter.
- Note: the three items are stale advisory text, not missing requirements. The Accept lines in `tasks.md` are correct and take precedence, but the user's rule is that no text may contradict the amended constraints, and Group 10 only schedules the (f) body correction (already done in 6165278f), not these. Fix is documentation only (two table rows, one snippet, one sentence); `decisions.md` and `tasks.md` need no change.

### Suggestion
- [tasks.md Group 10 / security-review.md Cycle 1 "Group 10 must"] The Group 10 note about correcting the (f) body is already satisfied by 6165278f; reword the Group 10 constraint to cover only the (c) snippet and the scheme-policy record so it is not misread as open.
- [docs/tech.md:1490-1492] Step 5 ("Prefer the copy so the trampoline's own getIntent() is not mutated") is grant-mechanics background and is now followed by the fresh-forward requirement; add "(analysis only; see the fresh-forward rule below)" to avoid misreading.

### Verdict: FAIL
Both cycle 1 Warnings are resolved in the normative Group 6 text, and all other constraints are present and consistent. FAIL is only for the stale contradicting text in `docs/tech.md` (:925, :922/:1149-1158, :1140-1142). Once those lines are aligned, no further cycle needs code or design changes; a re-check of those lines should PASS.

## Cycle 3 — 2026-10-24
Reviewing: Group 5 (phase 2)

Scope: re-check of the three stale passages that failed Cycle 2, against commit 055004e1 (`docs/tech.md`, `tasks.md` Group 10). Plan text only; no code changed.

### Cycle 2 Warning status
- **Summary table "forward a copy with `setComponent`": RESOLVED.** `docs/tech.md` row "Trampoline" now reads "forward a **fresh allowlisted intent** (never a copy of the incoming one)".
- **Resume-check recipe: RESOLVED.** The summary row and the section (c) snippet now use `BrowserLauncher.effectiveLayout(prefs.getString(layoutKey, null))`, `RelaunchGuard.tryRelaunch()` (at most once per ~2 s, else stay and log) and `new Intent(this, BrowserLauncher.targetClass(this))`. A paragraph below the snippet explains the stored-"new"-while-unavailable loop. This matches `tasks.md` Group 6 Accept. The `getString(key, null)` default is retained.
- **Idle timer "any browser `onResume()` cancels": RESOLVED.** tech.md now says the process-wide timer counts started browsers, arms only when the count reaches 0, and that plain cancel-on-resume is not enough (old browser's `onStop` can re-arm it). Matches `tasks.md` Group 6 and `decisions.md`.
- Suggestions from Cycle 2 are both applied: step 5 of the grant analysis is labelled "analysis only"; the Group 10 constraint no longer lists the (f) body as open work.

### Verification greps (docs/tech.md and the spec directory)
- `forward a copy`, `setComponent`, `cancels it`, `browserIntent`, `LaunchHelper`, `stored layout`, `copy-with`: zero hits in `docs/tech.md`. In the spec, `tasks.md`, `design.md` and `requirements.md` have no hits either, apart from `tasks.md:143` ("nothing treats the stored layout string as a class name", a prohibition, so correct). Remaining hits are in review/security-review history and `decisions.md:113` (see Suggestion).
- `new Intent(getIntent())` / `new Intent(in)`: only the Verify grep text in `tasks.md:118` and review history. The trampoline sample in tech.md (f) builds `new Intent(this, BrowserLauncher.targetClass(this))` and copies only `text/plain` and a capped `EXTRA_TEXT`; flags, data, ClipData URIs, `EXTRA_STREAM`/`EXTRA_INTENT`, selector, categories are excluded.
- Remaining `getIntent()` and `cancel` mentions in tech.md (:1109, :1136, :1472) describe today's classic code or grant-mechanics analysis, not instructions.
- `tasks.md` Groups 6-10 agree with tech.md: fresh allowlisted forward, effective-layout check with shared function, static loop guard, started-browser idle counter, scheme policy flagged as owner decision, Group 7 `exported="false"` with no `getIntent()`-derived launches, Group 8 empty fill-in, Group 9 no manifest/backup/permission changes.

### Critical
- None.

### Warning
- None.

### Suggestion
- [decisions.md:113] The 2026-10-24 "Phase 2 plan amended by the Group 5 research" entry still says "comparing the stored layout in each browser's `onResume`". The later entry "Security review of Group 5 amended the Group 6 plan" supersedes it (effective layout plus guard), but a skim reader could miss that. Add "(superseded: effective layout plus loop guard, see below)".
- [Group 10] The scheme policy (http/https only) is still an owner decision pending. When it is decided, record the outcome in `docs/tech.md` as-built.
- Carried forward, still open (outside this phase): `allowBackup`/preferences exposure; `PlayerService.getPlayer()` mutable view; `ImageDownloader` scheme allowlist and size cap; ignore remote devices whose UDN equals `LOCAL_UID`; per-request deadline in `RendererStatusMonitor`; bidi-control stripping in `ReceiverBarLabel`.

### Verdict: PASS
Zero Critical, zero Warning. The plan text (tasks.md Groups 6-10, tech.md, decisions.md) no longer instructs copying the incoming intent, comparing the raw stored layout without the guard, or relying on cancel-on-resume alone. Group 6 may start; the next security review is on the Group 6 code.

## Cycle 1 — 2026-10-24
Reviewing: Group 6 (phase 2)

Scope: `git diff d23da4b2~1..HEAD -- yaacc` (parts A and B plus fcfe04d6), the
merged debug manifest (`./gradlew :yaacc:processDebugManifest --offline`, output newer
than the source manifest), and the old `TabBrowserActivity` at `d23da4b2~1`. Unit tests
on disk for the security-relevant classes are green: `LauncherForwardPolicyTest` 16,
`RelaunchGuardTest` 13, `ShareUriPolicyTest` 10, `StartedBrowserCounterTest` 11,
`ShutdownTimerControllerTest` 7, `BrowserLayoutSwitchTest` 3, `BrowserLayoutChoiceTest` 12;
0 failures, 0 errors. Each Group 5 (cycles 1-3) constraint was checked against the code.

**1. LauncherActivity and manifest: honored.**
- Fresh `new Intent(this, BrowserLauncher.targetClass(this))` (`LauncherActivity.java:52`).
  Only `LauncherForwardPolicy.decide` can add anything: exact `android.intent.action.SEND` +
  exact `text/plain` + non-empty text of at most 2048 chars becomes `ACTION_SEND`, type and
  `EXTRA_TEXT`. Everything else (MAIN, VIEW, null, unknown, wrong case, over-cap text) is a plain
  launch. A text over the cap is dropped, not truncated.
- No flags, data, categories, selector, package, `EXTRA_STREAM`, `EXTRA_INTENT` or any other
  extra is copied. Text comes from `getStringExtra(EXTRA_TEXT)` or `ClipData.Item#getText()`.
  The item URI is never read, so no grant can be carried. The incoming intent is only read, never
  modified. `grep "new Intent(getIntent())\|new Intent(in)"` finds nothing in `yaacc/src/main`.
- Reads are in `try/catch (RuntimeException)` that falls back to a plain launch. `startActivity`
  has its own catch, and `finish()` is in `finally`. Logs carry only the exception class name.
- Manifest source diff is one hunk. `LauncherActivity` is the only MAIN+LAUNCHER, LEANBACK and
  SEND component; the MAIN+VIEW+DEFAULT+LAUNCHER filter (no `<data>`) is moved verbatim. No
  `launchMode`, `taskAffinity`, `allowBackup` or permission change. The merged manifest shows the
  other exported activities, receivers and providers with the same filters as before, and
  `TabBrowserActivity` exported with no filter. Exported surface is unchanged apart from
  `LauncherActivity`.
- `TabBrowserActivity` reachability: it was already exported with the same filters, and an
  explicit intent ignores filters, so every direct path existed before. The filters moved
  away, so it can no longer be chosen by an implicit intent. Nothing new is reachable. No code
  reads `getReferrer()` or `getCallingPackage()`; the launcher erases the origin, but nothing
  depends on it.
- Crafted explicit SEND straight to `TabBrowserActivity`: it skips `LauncherForwardPolicy`
  (no type check) but cannot skip `ShareUriPolicy`. `ShareIntentHandler.readAndClearSharedText`
  reads only `getStringExtra`/clip text, in `try/catch`, and `handle()` then applies
  `ShareUriPolicy.isAllowed` (http/https only, 2048 cap) before `Uri.parse`. The launcher policy is
  therefore a pre-filter, not a gate. Confirmed.

**2. Resume-check relaunch: honored.**
- One function decides: `BrowserLauncher.effectiveLayout` returns `CLASSIC` while
  `NEW_LAYOUT_AVAILABLE` is false; `TabBrowserActivity.relaunchIntoOtherLayout` compares it with
  `Layout.CLASSIC` through `BrowserLayoutSwitch.shouldRelaunch`. Stored `"new"` with the flag false
  is no relaunch (tested), and `targetClass` is currently the constant `TabBrowserActivity.class`.
- `RelaunchGuard`: `synchronized`, monotonic clock (`System.nanoTime()/1e6`), first call allowed,
  denied calls don't extend the window, a backwards clock counts as elapsed. A relaunch is one
  `startActivity(new Intent(this, target))` with no extras, flags or data from `getIntent()`, then
  `finish()`. It cannot loop; if the guard trips it logs and stays.
- The stored value is read with `getString(key, null)` inside `try/catch (RuntimeException)` and
  is mapped only by `BrowserLayoutChoice.resolve` (`"new"` exact, else CLASSIC). No class name,
  action or path is taken from preferences.
- The share payload is handed to the handler in `onCreate`, before the `onResume` check, and is not
  forwarded again.

**3. ShareIntentHandler: honored except for the shutdown/Activity-reference point (Warning 1).**
- Scheme allowlist: `ShareUriPolicy` accepts http/https only (case-insensitive ASCII; scheme is the
  text before the first `:`), cap 2048. `file`, `content`, `intent`, `javascript`, bare paths and
  leading blanks are rejected, with a fixed log line.
- The text is only passed to `Uri.parse` and `createPlayableItem`. No `Intent`, `PendingIntent` or
  `File` is built from it. It is consumed on the handler's own intent copy (`removeExtra`,
  `setClipData(null)`), not a shared one.
- Logging: every `YaaccLogger` call in the changed files (grepped) uses fixed text or
  `getClass().getSimpleName()`; none uses the text, the `Uri`, `getMessage()` or the exception.
  Default log level is ERROR in any case. Toasts are the fixed "start in N seconds" and
  "no receiver found using local device" strings.
- Worker: `prepareAndPlay` catches `Exception` (the old `IOException -> RuntimeException` process
  crash is gone); `play` catches `RuntimeException`; `RejectedExecutionException` is caught.
- Proxy hole closed for the share path. `UpnpClient#createPlayableItem` (`UpnpClient.java:1599`)
  has exactly one caller, `ShareIntentHandler.java:138`, and the only writer of
  `proxy_link_*` is `UpnpClient.java:1636`, inside that method. With the policy, `file://`, bare
  paths, the app's own `content://...fileprovider` URI and ClipData URIs can no longer become a
  `/proxy/<hash>` entry. Checked edge cases: `http:/data/...` (single slash) and mixed-case
  `HTTP://` do not match the handler's case-sensitive `startsWith("http://")`
  (`YaaccUpnpServerContentHttpHandler.java:1278`); they fall through to `new File(...)` (a
  nonexistent relative path) or to `ContentResolver.openInputStream` (which does not resolve
  providers for a non-`content` scheme). The result is a failed read, not a disclosure.
- Other entry points that reach `createPlayableItem` with untrusted strings: none. Untrusted
  http(s) URLs still reach it through the same call: an on-device app can make yaacc fetch and
  proxy any http(s) URL, including `127.0.0.1`/LAN addresses (pre-existing SSRF-style reach, no
  new capability). Other untrusted-URL sinks that do not touch the proxy are outside this group:
  `ImageDownloader` (any `URL` scheme from DIDL `albumArtURI`, carried forward) and renderer
  `SetAVTransportURI` played by the local player.

**4. Idle timer: honored.** `BackgroundIdleTimer` keeps a static `StartedBrowserCounter` created
under `synchronized` with the application context only (the lambda captures the `Application`,
the scheduler is a main-looper `Handler`). `onExpiry` re-checks the count and `exitIfIdle`
re-reads the preferences and `getCurrentPlayers()` when it fires. The counter is `synchronized`,
arms only when `started` reaches 0, is cancelled in every `onBrowserStarted`, and ignores a stop
without a start. No leak, and no exit-while-active regression (old order issue is fixed).

**5. MediaProjection: honored.**
- The result `Intent` goes `onActivityResult` -> `StreamingPermissionHelper.onPermissionResult` ->
  `MediaProjectionHelper.handlePermissionResult` -> static `storedResultData`. It is not put in a
  Bundle, preference, `Intent` extra, file or log. Logs carry only "granted"/"denied". There is no
  getter for `storedResultData`; denial and `clearPermission()` null it, and the disable path in
  `BrowseDeviceAdapter` still calls `clearPermission()`. `onStop` of the projection clears it too.
- Gating: result accepted only for `REQUEST_CODE_MEDIA_PROJECTION` and only while the static
  `requestPending` flag (set by `createRequestIntent`) is true; it is cleared on the first answer.
  Fragment `onActivityResult` is only called for activities that fragment started, so another
  component or app cannot deliver a result. A stale grant cannot be accepted without a new
  request.
- Callbacks: `CopyOnWriteArrayList`, `addIfAbsent`, remove by instance, delivered through
  `new Handler(Looper.getMainLooper())` (was `null`), each call in `try/catch`. The static
  `stateReset` captures only the application context. The per-screen callback is
  `helper::refresh`, whose lambda references the fragment; it is registered in `onStart` and
  removed in `onStop`, so it is not retained after stop (same lifecycle bound that cycle 3 of
  Group 3 accepted).
- `MediaProjectionManager` is now obtained from `getApplicationContext()` (previously the first
  caller's context, which could be an Activity or the service, held statically). The
  `YaaccUpnpServerService` call sites pass `this` and are unaffected; `getMediaProjection` does
  not depend on the context.

**6. ShutdownTimerController, timer listeners, LocalServerLifecycle, consumeLeftSettings: no
security-relevant change.** Compared with the old `onResume`: same preference key, same
`stopService` before `startForegroundService` only after the settings were left, same stop when
the server is off, same application-context `Intent`. `consumeLeftSettings` reads and resets
the flag exactly once, as `leftSettings = false` did. Autostart is untouched (boot receiver and
manifest unchanged). The relaunch path returns before `LocalServerLifecycle.apply`, so the
instance that stays applies it once. `Yaacc` keeps a `CopyOnWriteArrayList` of listeners
(`addIfAbsent`), a listener exception is caught per listener, the controller holds the `Yaacc`
application and preferences only, and `onFinish` still calls `exit()`. `ServerListFragment`
attaches in `onStart`, detaches in `onStop`, and drops its display lambda in `onDestroyView`.

**7. Logging, components, permissions, dependencies.** `git diff d23da4b2~1..HEAD --
yaacc/build.gradle yaacc/src/main/AndroidManifest.xml` shows no `build.gradle` change and the one
manifest hunk above: no new permission, no new dependency, one new exported component
(`LauncherActivity`, which replaces the filters on `TabBrowserActivity`). No new logging of
shared text, URIs, extras, the MediaProjection result or preference values. No secrets, tokens
or local paths in the diff.

### Critical
- None.

### Warning
- [ShareIntentHandler.java:61, 66-73, 125-128; TabBrowserActivity.java:217, 244-249;
  tasks.md Group 6 Accept "shutdown() cancels pending delayed work"] Two Group 5 "Group 6 must"
  items are not met.
  - `shutdown()` is a plain `executor.shutdown()`. A `ScheduledThreadPoolExecutor` runs existing
    delayed tasks after `shutdown()` by default, so a destroyed screen still starts playback up to
    6 s later. Group 5 required `shutdownNow()` or
    `setExecuteExistingDelayedTasksAfterShutdownPolicy(false)`. decisions.md ("survives a
    rotation") records the choice, but it is not an owner decision on the security constraint, and
    tasks.md still says the opposite of the code.
  - "Keep no Activity or View reference": the callback `this::showPlayers` binds
    `TabBrowserActivity` and is captured by the queued `execution` and by the `mainHandler.post`.
    After `onDestroy` the destroyed Activity (view hierarchy, fragments) stays reachable for the
    time the task is queued plus the whole of `createPlayableItem`.
    `MediaMetadataRetriever#setDataSource(url)` has no timeout, so a hostile or slow host holds the
    Activity for as long as it likes.
  - Fix, which keeps the rotation behavior the owner wanted: make `onPlayersStarted` a `volatile`
    field set to null in `shutdown()` (or hold it in a `WeakReference`), so a pending share still
    plays but nothing references the Activity; and either cancel pending work unless
    `isChangingConfigurations()` (for example `shutdown(boolean keepPending)`), or record the
    "pending share completes after destroy, at most about 6 s plus preparation" rule in tasks.md,
    decisions.md and `docs/tech.md` as an accepted deviation. Add a JVM-testable check of the
    chosen policy (an executor-policy test is enough).

### Suggestion
- [ShareUriPolicy.java:41-52] Optional hardening: require `://` after the scheme and use
  `Uri.normalizeScheme()` (or lower-case the scheme) before `createPlayableItem`, so mixed-case
  `HTTP://` links work through the proxy and `http:/...` forms are rejected rather than reaching
  the handler's `File` branch.
- [ShareIntentHandler.handle] The queue is unbounded and each call also shows a Toast. An on-device
  app can send many SEND intents to the exported `TabBrowserActivity`, each starting an unconfirmed
  playback. This is pre-existing (the old code created a thread per share, so it is better now).
  Cap the pending shares per handler (for example drop while one is pending) and consider a
  connect/read timeout around `setDataSource`. The unconfirmed playback and the rewrite of the
  saved receiver selection to `LOCAL_UID` from cycle 1 are still open.
- [MediaProjectionHelper.java static fields] `storedResultData`, `storedResultCode` and
  `mediaProjection` are plain statics written on the main looper and read on the server service
  threads; make them `volatile` (pre-existing).
- [YaaccUpnpServerContentHttpHandler.java:775-777] Pre-existing: the proxy lookup logs the target
  URI (the shared link) at DEBUG. Default level is ERROR, but a user who raises the level and shares
  the log exposes link tokens. Log the key only.
- [docs/tech.md, tasks.md Group 6 Accept] Correct "shutdown() cancels pending delayed work" to
  the final policy once Warning 1 is resolved.

Forward-looking requirements:
- Group 7 must: compute `BrowserLauncher.effectiveLayout(...)` once per decision and derive the
  target from that value (today `targetClass(Context)` is a constant; when it starts reading the
  layout, do not read the preference a second time between the check and the launch).
- Group 7 must: do the layout check in `BrowserActivity.onCreate` before it hands the intent to
  `ShareIntentHandler`, or hand over only the sanitized text, so the payload is consumed in exactly
  one instance. Use the same `RelaunchGuard.shared()` and `new Intent(this, target)` with nothing
  copied from `getIntent()`.
- Group 7 must: `BrowserActivity` `exported="false"`, no `intent-filter`, no `launchMode` or
  `taskAffinity`; `getIntent()` goes only to `ShareIntentHandler` (null callback). Call
  `BackgroundIdleTimer.browserStarted/browserStopped` from `onStart/onStop`, and call
  `shareIntentHandler.shutdown()` in `onDestroy` under whatever policy resolves Warning 1.
- Group 7 must: make no decision from `getReferrer()` or `getCallingPackage()`; the launcher
  erases the origin and `TabBrowserActivity` is directly reachable.
- Group 8 must: flip `NEW_LAYOUT_AVAILABLE` only together with the working `BrowserActivity`
  branch of `targetClass`; keep the `StreamingPermissionHelper`/`ShutdownTimerController` users
  attach/detach in `onStart`/`onStop`, with no `Context` or `View` in static state.
- Group 9 must: keep the layout `ListPreference` values fixed (`classic`/`new`), read only via
  `BrowserLayoutChoice.resolve`, and make no `exported`, `allowBackup` or permission change.
- Group 10 must: record in `docs/tech.md` as built: what the launcher forwards and drops, the
  http/https policy and its cap, the share-handler shutdown policy, the loop guard and the
  idle counter.
- Carried forward, still open (outside this group): `allowBackup`/preferences exposure
  (`proxy_link_*`, receiver selection, layout choice); `PlayerService.getPlayer()` mutable view;
  `ImageDownloader` scheme allowlist and size cap; ignore remote devices whose UDN equals
  `LOCAL_UID` at `UpnpClient` ingress; per-request deadline in `RendererStatusMonitor`;
  bidi-control stripping in `ReceiverBarLabel`.

### Verdict: FAIL
No Critical findings, and items 1, 2, 4, 5, 6 and 7 plus the scheme/logging/proxy parts of 3 pass.
FAIL is for one Warning: the share handler's `shutdown()` does not cancel pending delayed work
and a queued task keeps the destroyed `TabBrowserActivity` reachable, both against binding
Group 5 constraints. The fix is small and keeps rotation survival (null the callback in
`shutdown()`, then pick and document the cancel policy). A re-check of those lines should PASS.

## Cycle 2 — 2026-10-24
Reviewing: Group 6 (phase 2)

Scope: commit a0959e6877 (`ShareIntentHandler.java`, `MediaProjectionHelper.java`, `tasks.md`, `decisions.md`), re-check of the cycle 1 Warning. `TabBrowserActivity.java:216-248` (the only caller) and `Yaacc.exit()` were read for context. No code or docs edited.

### Cycle 1 Warning status: RESOLVED
- **(1) Nothing queued keeps the Activity/Fragment/View reachable after `shutdown()`.** The `onPlayersStarted` callback (`this::showPlayers`) is no longer captured by any task. It lives in `volatile playersStartedCallback`, which `shutdown()` sets to null. The queued `execution` lambda captures `this` (handler: `appContext`, `UpnpClient`, `Handler`, executor, counter, the volatile field) and the parsed `Uri`. The `prepareAndPlay` posts capture `appContext`, `item` and `this`. The `play(item)` post reads the field when it runs (not when posted), so a post queued before `shutdown()` sees null. The Toasts use `appContext`. `UpnpClient` is the application-scoped instance from `Yaacc` (`new UpnpClient(this)` with the Application). The Activity holds the handler, not the other way round. Remaining pin: the handler object and the worker thread until the last task ends (see Suggestion on `setDataSource`).
- **(2) Pending counter.** No early return sits between `incrementAndGet()` (line 120) and the try block: `shutDown`/null intent, no text, policy reject and cap reject all return before it, and the `Runnable` construction cannot throw. After the increment there is exactly one decrement per path: the task's `finally` once the task runs (covers any exception or Error from `prepareAndPlay`), or the `RejectedExecutionException` catch when `schedule/execute` itself throws (then nothing was queued, so no double decrement). A `Toast` failure after a successful `schedule` propagates out of `handle` but the task still decrements itself. Nothing cancels tasks (no `shutdownNow`/`cancel`), so a queued task cannot leave the count stuck. The only way the count stays up is a stalled `setDataSource` on the single worker; it is per-handler and clears with the next activity instance (see Suggestion). **Callback order is correct:** the cap check (102-105) comes before the callback assignment (107), so a rejected over-cap share does not overwrite the callback of an earlier queued share. Accepted shares do overwrite it (last writer wins), which is harmless here: within one handler the callback is always the same activity's `showPlayers` (classic), or null (Group 7). Mixed null/non-null on one handler would lose the tab switch only; not a security issue. The shared text of a dropped share is already removed from the intent, so a dropped share is not replayed.
- **(3) Share finishing after shutdown.** `play()` runs on the main looper, calls `UpnpClient.initializePlayersWithPlayableItems` and `player.play()` (application-scoped state, `PlayerService`), and calls the callback only if it is non-null. After `shutdown()` it is null, so no tab switch (`setCurrentTab` -> `runOnUiThread` -> `tabLayout`) runs on the dead activity. `handle` and `shutdown` are both main-thread, so the `shutDown` check and the callback assignment cannot interleave; the `RejectedExecutionException` branch is therefore unreachable in practice (and would leave the callback set only in that unreachable race). Nothing else in the handler touches Activity state. `RuntimeException`s in `play` are caught with class name only.
- **(4) Accepted deviation (graceful shutdown): acceptable, no stricter option needed.** A destroyed screen can start playback at most about 6 s plus preparation after the share, only for a link the user explicitly sent to yaacc, after `ShareUriPolicy` (http/https, length cap), with a Toast already announcing "start in N seconds". `Yaacc.exit()` ends in `Runtime.getRuntime().exit(0)`, so an explicit Exit kills any waiting share. Back-press/rotation within 6 s completing the share is the expected outcome of "share to yaacc". The cancel-unless-`isChangingConfigurations()` variant would add a branch for little gain; take it only if the owner wants "back = cancel". The deviation is recorded in `tasks.md` (Group 6 Accept) and `decisions.md` (two entries, 2026-10-24) and both match the code (graceful `executor.shutdown()`, callback cleared, cap 3, volatile statics, follow-ups listed).
- **(5) Consistency and logging.** `tasks.md:113` and `decisions.md:129-138` match the code. All log lines are fixed strings or exception class names; no payload, URI or `getMessage()` is logged. `MediaProjectionHelper` statics (`mediaProjection`, `mediaProjectionManager`, `storedResultCode`, `storedResultData`) are now `volatile`; the diff is only the modifiers, no behavior change.

### Critical
- None.

### Warning
- None.

### Suggestion
- [ShareIntentHandler.java:102-107, test] Cycle 1 asked for a JVM check of the chosen policy; the commit adds none (the class builds a `Handler`/`Looper` in a field initializer, so it is not JVM-testable as is). Extract the counter and shutdown flag into a small plain-Java gate (`tryAcquire()/release()/close()`, max 3) and test it (cap, release on every path, rejected acquire after close). Not blocking.
- [ShareIntentHandler.java:155-160] `createPlayableItem` -> `MediaMetadataRetriever#setDataSource` has no timeout. A hostile host can hold the single worker, the handler and its three counter slots (shares are then ignored for this handler's lifetime) and keep the thread alive after shutdown. Availability only, no Activity pinned. Already listed as a follow-up in `decisions.md`; keep it on the backlog with a connect/read timeout.
- [ShareIntentHandler.java:64-66, TabBrowserActivity manifest] `TabBrowserActivity` is exported with the standard launch mode, so each SEND can start a new instance with its own handler and its own cap of 3. The cap bounds each handler, not the process. Consider making the pending count static/process-wide (the counter holds no Context).
- [ShareIntentHandler.java:107] Move the callback assignment next to the increment so an early exception from `upnpClient.startService()` cannot replace a queued share's callback without queuing anything (harmless today, same activity).
- [docs/tech.md:1383-1392] Group 10 should record the final policy (graceful shutdown, callback cleared, cap 3), as already required by the Group 10 note.

### Verdict: PASS
Zero Critical, zero Warning. The cycle 1 Warning is resolved: the destroyed activity is no longer reachable from queued tasks, the counter is balanced on every path, a late share plays without touching the dead screen, and the graceful-shutdown deviation is documented and acceptable. Group 6 may proceed.


## Cycle 1 — 2026-10-24
Reviewing: Group 7 (phase 2)

Scope: `git show e95c9a2b` (`BrowserActivity`, `BrowserActivitySupport`, `BrowserLauncher`, `BrowserBackNavigation`, `ShutdownTimerToggle`, `BrowserHost`, `ServerListFragment`, `ContentListFragment`, `TabBrowserActivity`, `UpnpClient`, `activity_browser.xml`, `fragment_server_root.xml`, `menu/activity_browser.xml`, strings, manifest, decisions.md/tasks.md). The merged debug manifest (`./gradlew :yaacc:processDebugManifest --offline`) was regenerated. `BrowserBackNavigationTest` (4) and `ShutdownTimerToggleTest` (3) are green on disk. Each "Group 7 must" item from Group 5 and Group 6 was checked against the code.

**1. Manifest and build: honored.**
- Source diff is one hunk: `BrowserActivity` with `exported="false"` and a label. No `intent-filter`, `launchMode`, `taskAffinity`, permission, `allowBackup` or provider change.
- Merged manifest: `BrowserActivity` is `exported="false"`. The exported set is unchanged from Group 6 (`LauncherActivity`, `TabBrowserActivity` without filter, and the pre-existing viewer, player, about, log and server-control activities, `ServerAutostart` and `ProfileInstallReceiver`). No new `uses-permission` (21, as before).
- `git diff HEAD~1 -- yaacc/build.gradle` is empty. No other gradle, toml or properties file changed. No new dependency.

**2. BrowserActivity attack surface: honored.**
- Reach: only in-process explicit intents. `BrowserLauncher.targetClass` is used by `LauncherActivity` and the three PendingIntents (`Yaacc.java:177`, `PlayerService.java:106, 199`, unchanged and `FLAG_IMMUTABLE`), plus `relaunchIfOtherLayout`. `targetClass` still returns `TabBrowserActivity` while `NEW_LAYOUT_AVAILABLE` is false, so nothing starts `BrowserActivity` today.
- `getIntent()` is read in one place, `shareIntentHandler.handle(getIntent(), null)` (`BrowserActivity.java:105`), and `onNewIntent` passes the new intent to the same handler only. No `setIntent`, `getExtras`, `getData`, `getReferrer`, `getCallingPackage`, `PendingIntent` or `AlarmManager` in any new file (grepped). The handler keeps the Group 6 guarantees: `ShareUriPolicy` (http/https, 2048), try/catch, no payload in logs, callback null so no Activity pin.
- The only `startActivity` calls are `new Intent(this, SettingsActivity.class)` (own class, from the menu), the battery prompt (below) and the relaunch. Nothing is built from incoming data.
- Order: `relaunchIfOtherLayout(this, Layout.NEW)` is the first statement after `super.onCreate`, before `setContentView`, the permission prompts, the handler and the fragment. A relaunching instance returns before it creates a share handler, so a stale payload is dropped, never forwarded or replayed. `onNewIntent` is a no-op then (handler is null).
- Effective layout is computed once per decision: `relaunchIfOtherLayout` calls `effectiveLayout(activity)` once, passes the value to `BrowserLayoutSwitch.shouldRelaunch` and to `targetClass(Layout)`. The preference is not read twice.
- Relaunch intent is `new Intent(activity, targetClass(effective))`: no action, extras, data or flags from `getIntent()`. The `RelaunchGuard.shared()` guard (one per ~2 s) applies to both browsers through the shared helper. When it trips, the activity logs and stays.
- Explicit intent from another app to `BrowserActivity`: `exported="false"` makes the framework reject it with a `SecurityException` at `startActivity` in the sender. Not reachable. Via `TabBrowserActivity` (exported): a crafted intent is read by its share handler in `onCreate` (same policy as before) and, if the layout differs, `onResume` starts a fresh intent for the other class. The attacker's action, extras, data, clip and flags are dropped. The tab instance finishes. A stale internal explicit launch of `BrowserActivity` while the layout is classic goes `onCreate` -> relaunch to `TabBrowserActivity` with nothing carried over. If the guard trips, `BrowserActivity` renders; the share would then still pass `ShareUriPolicy`.
- `onStart`/`onStop` call `BackgroundIdleTimer.browserStarted/Stopped` in pairs (a relaunch that finishes in `onCreate` never reaches `onStart`, and the counter ignores a stop without a start). `onDestroy` calls `shareIntentHandler.shutdown()` and drops the menu item.

**3. BrowserActivitySupport: honored (verbatim, no new surface).** Compared with the removed `TabBrowserActivity` code:
- Same three permission arrays, same request code 101, result still not handled. `requestPermissions` only asks for what the manifest already declares (no new permission).
- Battery prompt: `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with `package:` + `activity.getPackageName()` (our own package), the same empty-intent/`ActivityNotFoundException` swallow as before. Nothing comes from an incoming intent.
- `handleCommonMenuItem` maps exactly four IDs (`menu_exit`, `menu_settings`, `yaacc_about`, `yaacc_log`) to `Yaacc.exit()`, `new Intent(activity, SettingsActivity.class)`, `AboutActivity.showAbout` and `YaaccLogActivity.showLog`. Menu item IDs come from our inflated resource; there is no external way to deliver one. `BrowserActivity` adds only the two timer items. The class is package-private and `final`; no component, filter or export was added.

**4. Server list reuse: honored.**
- `ServerListFragment` picks `fragment_server_root.xml` only when the host reports `providesShutdownTimer()`. The other init code is shared with classic. The `serverList`, `serverListRefreshButton`, `serverListLocalServerEnabled`, `serverListLocalServerSettings` and status-icon IDs match the classic layout.
- MediaProjection flow: unchanged. `BrowseDeviceAdapter` -> `permissionCallback` -> `streamingPermissionHelper.createRequestIntent()` -> `startActivityForResult(REQUEST_CODE_MEDIA_PROJECTION)` -> fragment `onActivityResult` -> `StreamingPermissionHelper.onPermissionResult` (request code check and static `requestPending`). `BrowserActivity` does not override `onActivityResult`, so it cannot feed a result in. `clearPermission()` stays on the disable path. Callback registered in `onStart`, removed in `onStop`.
- Server switch: writes only the `settings_local_server_chkbx` preference, as in classic (no confirmation there either), and refreshes the icons. The server is started or stopped by `LocalServerLifecycle.apply` on `onResume` (both browsers; unchanged Group 6 behavior). Gear: explicit `Intent(getContext(), YaaccUpnpServerControlActivity.class)`, same as classic. The three status icons have no click listener and are `focusable=false`. Nothing is reachable by a different path or with less confirmation than in classic.
- LAN-supplied text: `holder.name.setText(friendlyName)` and `IconDownloadTask` (`BrowseDeviceAdapter.java:171, 207`). `grep autoLink|Linkify|fromHtml|Html.` over `java` and `res/layout` finds only `about.xml:32` (static About text, pre-existing) and a comment in `device_sheet_player_session_item.xml`. `fragment_server_root.xml` has fixed string-resource text only.

**5. UpnpClient listeners and fragment lifecycle: honored.**
- `listeners` is a `CopyOnWriteArrayList`; every iteration (`fire*`) is safe against concurrent add/remove. This removes the earlier `ArrayList` `ConcurrentModificationException` risk. `removeUpnpClientListener` removes one registration and is a no-op for an unknown listener.
- `ServerListFragment` and `ContentListFragment` add in `init` (called once per view in `onViewCreated`) and remove in `onDestroyView`; a back-stack pop recreates the view and `init` registers again. Add and remove are balanced, so the app-scoped client holds no destroyed fragment. `ContentListFragment.onDestroyView` also calls `bItemAdapter.cancelRunningTasks()` before `super`.
- `PlayerListFragment` and `ReceiverListFragment` (classic only) still never remove themselves (recorded in decisions.md, outside this group). `DeviceSheetFragment` registers no `UpnpClientListener`.

**6. Shutdown timer toolbar: honored.**
- `ShutdownTimerToggle.decide`: running -> STOP; not running and `configuredMillis > 0` -> START; otherwise ASK_DURATION. A tap never starts a 0-duration timer (`Yaacc.startShutdownTimer(0)` would call `exit()` right away). Unit-tested. No `PendingIntent` and no `AlarmManager`; the timer is the existing in-process `CountDownTimer`.
- `attach` in `onStart`, `detach` in `onStop`; `Yaacc` keeps a `CopyOnWriteArrayList` with `addIfAbsent`. The controller holds the application, the preferences and the display lambda (the Activity) only between those calls. `onDestroy` drops the `MenuItem`.
- The "set" item opens `MaterialTimePicker`; the stored value is a `long` under the existing key.

**7. State and back stack: honored.** `BrowserActivity` has no `onSaveInstanceState` and puts nothing in a `Bundle` or `Intent`. Fragments are restored by the `FragmentManager`. `ContentListFragment` keeps its `Navigator` in the saved state exactly as classic did (object IDs and titles of the folder path). The provider and receiver selection stay in `UpnpClient` and the default preferences, as in classic (`showContent` reads the stored provider). The MediaProjection result stays in `MediaProjectionHelper` statics.

**8. Logging and strings: honored.** The new log calls (`BrowserActivity`, `BrowserActivitySupport`, `BrowserLauncher`) use fixed text or an exception class name. No intent, extras, share text, URI, preference value, device name or MediaProjection data is logged. The two new strings (`shutdown_timer_set`, `browser_servers_header`) exist in all 7 locales, are plain text and carry no format args or markup.

### Critical
- None.

### Warning
- None.

### Suggestion
- [BrowserActivity.java:104-105, TabBrowserActivity.java:134-135] The share handler runs on every `onCreate`, including recreation. A rotation reuses the same in-process `Intent` that the handler already cleaned, but after process death the system re-delivers the original intent, so the shared link plays again (pre-existing, classic does the same). Guard with `savedInstanceState == null` in both browsers.
- [Yaacc.java:203, ShutdownTimerController.java setConfiguredMillis/start] `setConfiguredMillis(0)` while a timer runs, or the classic switch with no configured duration, calls `startShutdownTimer(0)`, which exits at once. The new toggle avoids it, but the picker can still confirm 00:00 on a running timer. Pre-existing and a user action; consider `if (millis <= 0) stop()` in the controller.
- [ShutdownTimerToggle] A single tap on the toolbar item starts a stored timer without confirmation. The item switches to the checked state and shows the remaining time, and another tap stops it. Acceptable; revisit if the owner finds it too easy to trigger.
- [BrowserActivity.java:92-96] With a null `UpnpClient` the activity returns after `setContentView` with no fragment and no share handler (same as classic). Harmless; consider finishing instead of showing an empty screen.
- [tasks.md] Group 7 text says `ServerRootFragment`; the code reuses `ServerListFragment` with `fragment_server_root.xml` (recorded in decisions.md). Align tasks.md.

Forward-looking requirements:
- Group 8 must: flip `NEW_LAYOUT_AVAILABLE` only together with `targetClass(Layout.NEW)` returning `BrowserActivity` (already in place), and re-run the manifest check (`BrowserActivity` still `exported="false"`, no filter, `launchMode` or `taskAffinity`; exported set unchanged).
- Group 8 must: keep `BrowserActivity` reading `getIntent()` only through `ShareIntentHandler`; any tap target for the mini-player and receiver bar uses `Player#getNotificationIntent()` with an empty fill-in, no implicit intent, and no `PendingIntent` built from incoming data.
- Group 8 must: build the mini-player from `Player` objects only; render title, receiver name and art with `setText` / `IconDownloadTask` (no `autoLink`, `Html`, `Linkify`); unregister the `PlayerListChangeNotifier` listener and stop the poll in `onStop` and `onDestroy`; hold no `Context` or `View` in statics; guard posted callbacks with `isAdded()` / `getView() != null`.
- Group 8 must: add its new fragments' `UpnpClientListener` registration (if any) with a matching `removeUpnpClientListener` in `onDestroyView`.
- Group 8 must: keep the `ShutdownTimerController` attach in `onStart` and detach in `onStop`, and not add a second timer path that can start a 0-duration timer.
- Group 8 must (carried): pass `MiniPlayerPicker` only copies of players and handle null and spoofed `LOCAL_UID` ids; transport commands run off the main thread.
- Group 9 must: store the layout as a `ListPreference` with fixed values `classic`/`new`, read only through `BrowserLayoutChoice.resolve`; make no `exported`, `allowBackup` or permission change; keep `NEW_LAYOUT_AVAILABLE` and `effectiveLayout`.
- Group 9 must: after the flip, check that choosing a layout in Settings leads to the other browser through `relaunchIfOtherLayout` only (fresh intent, guard), with no stored string used as a class name, action or target.
- Group 10 must: record the as-built launcher, guard, share-handler and idle-timer policies in `docs/tech.md`, as required by Group 6 cycles 1-2.
- Carried forward, still open (outside this group): `allowBackup`/preferences exposure; `PlayerService.getPlayer()` mutable view; `ImageDownloader` scheme allowlist and size cap; ignore remote devices whose UDN equals `LOCAL_UID`; per-request deadline in `RendererStatusMonitor`; bidi-control stripping in `ReceiverBarLabel`; `PlayerListFragment` / `ReceiverListFragment` never remove their `UpnpClientListener`.

### Verdict: PASS
Zero Critical, zero Warning. `BrowserActivity` is non-exported with no filters and adds no component or permission; its intent handling is limited to the capped, scheme-checked `ShareIntentHandler`; the layout check runs first, computes the effective layout once, and relaunches with a fresh intent under the shared loop guard; the extracted support class is verbatim; the server list reuse keeps the MediaProjection gating and plain-text rendering; listener and timer registrations are balanced. Group 8 may proceed.


## Cycle 1 — 2026-10-24
Reviewing: Group 8 (phase 2)

Scope: `git show fdb8dee9 c78fb5d6` (`MiniPlayerBar`, `MiniPlayerPresenter`, `MiniPlayerPicker`, `ReceiverBarController`, `BrowserActivity`, `BrowserHost`, `BrowserLauncher`, `ContentListFragment`, `ServerListFragment`, `mini_player_bar.xml`, `activity_browser.xml`, `fragment_content_list.xml` (+land), `fragment_server_root.xml`, strings in 7 locales). Read in addition: `IconDownloadTask`, `IconDownloadCacheHandler`, `ImageDownloader`, `BitmapSampling`, `AVTransportPlayer#getIcon`, the players' `getNotificationIntent` implementations, `UpnpClient` player-listener and `getDevice` code, `ShutdownTimerController`, `RelaunchGuard`, `BrowserLayoutSwitch`. Each "Group 8 must" item from the Group 5-7 cycles was checked. `processDebugManifest --offline` was re-run: the task was UP-TO-DATE (inputs unchanged).

**1. Manifest and build: honored.**
- `git diff fdb8dee9~1..HEAD -- yaacc/src/main/AndroidManifest.xml yaacc/build.gradle` is empty. No gradle, toml or properties file changed; no dependency change.
- Merged debug manifest: `BrowserActivity` `exported="false"`, no filter, no `launchMode`/`taskAffinity`. Exported set and 21 `uses-permission` entries as in Group 7. No new component or permission. `allowBackup` is not set in source (default true); unchanged.

**2. Layout preference with `NEW_LAYOUT_AVAILABLE = true`: honored.**
- Writers of `settings_browser_layout_key`: only our own UID (default `SharedPreferences` file, not world-readable; no provider, no exported component, no intent extra, no deep link writes it; no Settings entry exists yet - Group 9). Other routes: Auto Backup / `adb backup` restore (`allowBackup` default true) and `run-as` / root on `debuggable` builds. These can only choose between the two in-app layouts.
- `BrowserLayoutChoice.resolve` is `STORED_NEW.equals(value)`: exact, case-sensitive, anything else (null, "New", " new", unknown, wrong type via the `RuntimeException` catch) is classic. The value is only compared; it is never used as a class name, action, extra, path or reflection target (`targetClass(Layout)` is a two-way `if`).
- Loop safety: both activities call the one helper `relaunchIfOtherLayout(this, own)` (Tab on resume, Browser in `onCreate` before anything else). It computes `effectiveLayout` once, passes that single value to `shouldRelaunch` and `targetClass`, and starts a fresh `new Intent(activity, target)` (nothing from `getIntent()`). Two activities can only relaunch each other if the stored value differs between two reads; the shared `RelaunchGuard` (one per ~2 s, process-wide, synchronized, monotonic clock) then stops it and the activity stays and renders. A relaunching `BrowserActivity` returns before creating the receiver bar, mini-player or share handler, so no listener or thread is started.
- The three PendingIntents built with `targetClass(context)` (`Yaacc`, `PlayerService` x2) remain `FLAG_IMMUTABLE`, explicit, with no incoming data; with "new" they now point at the non-exported `BrowserActivity`, which our own PendingIntent may start.

**3. MiniPlayerBar.**
- `getIntent()`: only the pre-existing `shareIntentHandler.handle(getIntent(), null)` (and `onNewIntent` to the same handler). None of the new files reads an intent. Honored.
- Tap: `ReceiverRowBinder.openPlayer` sends the player's own `getNotificationIntent()` with `new Intent()` (no extras, no data, no action); `CanceledException` is caught and logged. Flags in the players: `AVTransportPlayer` `IMMUTABLE|CANCEL_CURRENT`, `LocalMediaSessionPlayer`/`MultiContentPlayer` `IMMUTABLE`, `LocalImagePlayer` `UPDATE_CURRENT|IMMUTABLE`. All explicit; the fill-in is ignored by `FLAG_IMMUTABLE` anyway. Honored.
- Text: title and receiver pass `MiniPlayerPresenter` and go through `setText`. Grep for `fromHtml|HtmlCompat|Linkify|autoLink|setMovementMethod|linksClickable|textIsSelectable` over the new Java and the layouts: only a comment in `mini_player_bar.xml`. Both TextViews are `maxLines=1`, `ellipsize=end`; the receiver bar `maxLines=2`. Honored.
- Commands: `Executors.newSingleThreadExecutor()` created in `bind()`, `shutdown()` in `unbind()` (and again from `onDestroy` as backstop, idempotent); `sendCommand` ignores a null/shut-down executor and catches `RejectedExecutionException`; no thread outlives a drained queue. Commands already queued before `unbind()` still run (documented; the player methods hand off to the player's own executors). The post-command refresh is skipped when unbound and `refresh()` re-checks `bound`. Honored.
- Picker: gets `PlayerRef` value copies built from an `ArrayList` copy of the live collection; null players skipped; `refresh()` catches `RuntimeException` (player mutated under the poll). Honored.
- Listener/poll: `addPlayerListChangeListener` in `bind()` (from `onStart`), `removePlayerListChangeListener`, `removeCallbacks` of poll and refresh in `unbind()` (from `onStop` and `onDestroy`). No static holds a `Context`/`View`. Honored.
- Logging: only `YaaccLogger` calls in `MiniPlayerBar` (4), all with fixed text plus an exception class name; no title, name, URI or id. `ReceiverBarController` and `MiniPlayerPresenter` do not log. Honored.
- Art: the `IconDownloadTask(art, artKey)` path is deduplicated by `artKey` and drops late results by tag (`unbind()` clears the tag). But see the Warning: the same method also calls `player.getIcon()` on every poll.
- `IconDownloadTask` / `ImageDownloader` accept `new java.net.URL(uri).getContent()`: any scheme the JVM handles (http, https, ftp, file, jar), no connect or read timeout, no byte cap (`BitmapSampling.readAllBytes` is unbounded). The mini-player does not add a scheme or size problem by itself (the URI is the `albumArtUri` of the DIDL item the user chose to play, as in the sheet, player list and notification), but it makes the exposure continuous (art is fetched for the featured player on every browser screen without opening anything) and, through `getIcon()`, repeated (Warning).
- `cleanText`: removes control chars (`CONTROL`), all whitespace/space/line/paragraph separators (U+2028/2029 included, folded to one space), U+0085, LRM/RLM/ALM, LRE-RLO (202A-202E), isolates (2066-2069), ZWSP, WJ, BOM; cuts at 200 UTF-16 chars without splitting a surrogate pair; unit tests use `\u` escapes. Sound against line-break and override spoofing: title and receiver are separate single-line views, so a hostile title cannot occupy the receiver line, and no unbalanced bidi override or isolate survives. Not stripped (Suggestion): see below.

**4. ReceiverBarController: honored.**
- `addUpnpClientListener` in `bind()` (activity `onStart`, or classic fragment view creation), `removeUpnpClientListener` in `unbind()` (activity `onStop`/`onDestroy`, fragment `onDestroyView`); both idempotent via `bound`. Callbacks from the UPnP threads hop to the main handler (`updateRunnable`, removed in `unbind`), `update()` returns when unbound, so late events are dropped. The app-scoped client holds the controller (and through it the bar view) only while bound.
- Shared use: classic `ContentListFragment` hides its own bar only when `providesReceiverBar()`; otherwise creates and unbinds the controller in `onDestroyView` (no leak across view re-creation). `BrowserActivity` nulls its fields in `onDestroy`.
- "No receiver selected" prompt: `openDeviceSheetIfNoReceiverSelected` is called only from `ContentListClickListener:75` and `BrowseContentItemAdapter:581, 587` (item/play taps). The receiver bar tap opens the sheet. No intent, share or player event opens it. `DeviceSheetFragment.show` returns when the state is saved or the tag exists, so no double sheet and no `IllegalStateException`; `BrowserActivity.showReceivers` runs it on the UI thread.
- Receiver names go through `ReceiverBarLabel` + `setText` (not hardened, Suggestion).

**5. Shutdown timer: honored.** `grep startShutdownTimer`: `ShutdownTimerController.start` and `setConfiguredMillis` (unchanged in this group), `Yaacc` itself. `BrowserActivity` starts only via `ShutdownTimerToggle.decide` (START needs `configuredMillis > 0`). The classic switch in `ServerListFragment` (no guard, unchanged) and `setConfiguredMillis(0)` on a running timer remain the open Group 7 Suggestion. Group 8 adds no path. Attach in `onStart`, detach in `onStop` unchanged.

**6. Strings and logs: honored.** Eight new keys (`mini_player_previous/play/pause/next/stop/art/open`, `browser_back`) exist in `values` and `values-de/es/fr/nl/pt/zh`; plain text, no markup, no format args; `\'` escaped in fr. No new log carries a payload.

### Critical
- None.

### Warning
- [MiniPlayerBar.java:292, 300-301; AVTransportPlayer.java:1048-1070; Yaacc.java:94] Request flood to the album-art host. `showArt()` builds the dedup key with `player.getIcon() != null` before it compares keys, and `show()` runs on every 2 s poll. `AVTransportPlayer#getIcon()` has a side effect: when the 512x512 entry is not in `IconDownloadCacheHandler` it queues a new `ImageDownloader` download on `Yaacc.getContentLoadExecutor()` (fixed pool, unbounded queue) on every call. A failed download is never cached, so any art URL that returns 404, a non-image, an error, or is slow/hanging causes a fresh download per poll (plus up to two more on a key change), for as long as a browser screen is started. Against a hostile or broken LAN media server the art URL comes from its DIDL (`upnp:albumArtURI`, any scheme, no timeout, no size cap): one request every 2 s, each reading an unbounded body into memory, and hung connections occupy the pool threads that `BrowseContentItemAdapter` also needs (thumbnails and folder loads), so browsing stalls. The sheet and player list call `getIcon()` only when they bind a row, gated by a signature; the mini-player is the first caller that does it on a timer. Fix: do not call `getIcon()` in the key or on the poll path. Key on `player.getId() + albumArtUri` only; call `getIcon()` at most once per key change (or never: the `IconDownloadTask` already loads the art, and the placeholder shows until then). Add a unit-testable rule for it (key has no dependence on `getIcon()`), and keep the existing per-key `IconDownloadTask` call.

### Suggestion
- [MiniPlayerPresenter.java:isBidiControl] Not stripped, judged low risk since the single-line views and the 200-char cap contain them: tag characters U+E0000-E007F and variation selectors (FE00-FE0F, E0100-E01EF) that make two names look equal or carry hidden data; soft hyphen U+00AD, U+034F, U+180E, invisible operators U+2061-2064, deprecated format characters U+206A-206F, U+FFFC; blank fillers U+115F/1160/3164/FFA0 and U+17B4/17B5 (a name that renders empty). Consider dropping all `Character.FORMAT` characters except U+200C/200D (needed for Persian and emoji) plus the tag range and variation selectors, and falling back to the player name when the result is empty (already done). Strong RTL letters inside a name can still reorder neighbouring text; inherent.
- [ReceiverBarLabel.java:build, ReceiverBarController.java:update] The receiver bar is now permanent on every browser screen of the new layout but shows raw `getFriendlyName()` (trim only): bidi overrides, tag characters and a name like `Kitchen; Bedroom` (looks like two selected receivers) are not neutralised, while the mini-player's receiver line is cleaned. Reuse `MiniPlayerPresenter.cleanText` in `ReceiverBarLabel.build` and in `DeviceSheetAdapter`/`BrowseDeviceAdapter` name binding (carried from Group 3/7; the Group 8 condition "if `ReceiverBarLabel` gets the hardening" was not triggered, so this is not a miss).
- [MiniPlayerBar.java:resolveReceiverName] An unresolved device id (empty cache entry) is looked up every poll, and `UpnpClient.getDevice` logs the identifier at debug level each time; add a negative cache for the bind, or lower the log. A resolved name is cached until `unbind()`, so a renamed device shows the old name while the bar stays started.
- [MiniPlayerBar.java:unbind] `executor.shutdown()` lets queued commands run after the screen is gone. Fine for single user taps; `shutdownNow()` is an option if the queue could ever grow.
- [AVTransportPlayer.java:972] `FLAG_CANCEL_CURRENT` makes every `getNotificationIntent()` call cancel the previous PendingIntent with the same identity, including the notification's. Pre-existing, now reachable from one more tap target. Prefer `FLAG_UPDATE_CURRENT|FLAG_IMMUTABLE` (functional, no security impact).
- [ImageDownloader.java, IconDownloadTask.java] Still carried: scheme allowlist (http/https), connect/read timeouts, byte cap (e.g. 2 MB), and executing `IconDownloadTask` on the content pool. The mini-player increases exposure only as described above: the art of the featured player is fetched on every browser screen, once per art change after the Warning is fixed.
- [MiniPlayerBar.java] The poll runs `getCurrentItemTitle`, `isPlaying` and the registry lookup on the main thread; all are in-memory today. Keep it so (no network on the poll).

Forward-looking requirements:
- Group 9 must: store the layout as a `ListPreference` with `entryValues` `classic`/`new`, read it only via `BrowserLayoutChoice.resolve`/`BrowserLauncher.effectiveLayout`; no `exported`, `allowBackup` or permission change; after a Settings change the switch happens through `relaunchIfOtherLayout` only (fresh intent, `RelaunchGuard`); do not add a second reader of the key.
- Group 9 must: keep `NEW_LAYOUT_AVAILABLE` and the `RuntimeException` catch around the preference read.
- Group 9 / owner (carried): decide on `android:allowBackup="false"` or a backup exclude for the default preferences file (it now also carries the layout, receiver selection and `proxy_link_*` entries).
- Group 10 must: record in `docs/tech.md` the as-built mini-player policy (poll 2 s, executor lifecycle, `cleanText` rules and cap, art only via `IconDownloadTask`, no `getIcon()` on the poll path) and the launcher/guard/share-handler/idle-timer policies required by earlier cycles.
- Group 10 must: re-run the manifest check after the last code change (`BrowserActivity` still `exported="false"`, exported set unchanged).
- Carried forward, still open: `allowBackup`/preferences exposure; `PlayerService.getPlayer()` mutable view; `ImageDownloader` scheme allowlist, timeouts and size cap; ignore remote devices whose UDN equals `LOCAL_UID`; per-request deadline in `RendererStatusMonitor`; bidi stripping in `ReceiverBarLabel`; `PlayerListFragment`/`ReceiverListFragment` never remove their `UpnpClientListener`; classic timer switch and `setConfiguredMillis(0)` can start a 0-duration timer.

### Verdict: FAIL
One Warning: the mini-player's per-poll `player.getIcon()` call re-queues an unthrottled, unbounded album-art download whenever the art is not in the 512x512 cache (404, hostile, slow), which lets a LAN media server drive a request stream and starve the shared content-load pool. Everything else holds: manifest, build and exported set unchanged; the layout preference is exact-match only and cannot be set by another app or intent; the relaunch path is single-computation with a shared loop guard; tap target, text rendering, executor, listener lifecycle, logging, timer and strings meet the Group 8 requirements. Fix the key/poll path and the next cycle should PASS.


## Cycle 2 — 2026-10-24
Reviewing: Group 8 (phase 2)

Scope: `git show 3f3d9658` (`MiniPlayerBar.showArt`, `decisions.md`, cycle 1 text). Read in addition: `MiniPlayerBar` (bind/unbind/refresh/show/showArt), `MiniPlayerPicker`, `ReceiverBarController`, `ReceiverRowBinder` (art part), `IconDownloadTask`, `AVTransportPlayer#getIcon/#getAlbumArt/#isPlaying paths`, `AbstractPlayer#getIcon/#getAlbumArt`, `LocalMediaSessionPlayer`, `LocalImagePlayer`, `MultiContentPlayer`. Only `MiniPlayerBar.java` changed in code (no manifest, gradle, string or layout change).

**1. Other getIcon()/art-download paths on the poll or refresh: none.**
- `grep getIcon|IconDownloadTask|getAlbumArt` over `de.yaacc`: in the mini-player the only calls are in `MiniPlayerBar.showArt` (`getAlbumArt()` x1, `getIcon()` x1, `IconDownloadTask` x1). `MiniPlayerPresenter` and `MiniPlayerPicker` have no art code; `ReceiverBarController` is text only (`update()` runs on UPnP client events through the main handler, never touches players' art); `MiniPlayerBar.show()` calls `getCurrentItemTitle`, `getName`, `isPlaying`, `getDeviceId`, `getIconResourceId` (constant `R.drawable.ic_baseline_devices_32` in `AVTransportPlayer`, no side effect). `getAlbumArt()` in all five implementations returns a field or null (no I/O, no download).
- `getIcon()` is reached only through `showArt` after the key compare, so it runs once per key change (poll, add/remove event or the 500 ms post-command refresh all land in the same `refresh() -> show() -> showArt()` and return early on an equal key). Honored.
- Residual: one `getIcon()` (content pool, 512x512) plus one `IconDownloadTask` (AsyncTask, 48x48, different cache entry) per key = two downloads of the same URL per key change while the art is not cached. Bounded per key; see Suggestion.

**2. Placeholder and late art: correct.**
- `getIcon()` null first: the tinted placeholder (`getIconResourceId()`, or the app icon for `yaacc48_24_png`) is set, the tag is `artKey`, `IconDownloadTask(art, artKey)` runs; `onPostExecute` applies the bitmap only if `art.getTag()` equals `artKey`. A non-null `getIcon()` (cached 512 art or the device-icon fallback of `super.getIcon()`) is shown at once and the task still replaces it with the art.
- Different featured player: key has another id, tag overwritten, the old result is dropped by the tag compare. Same player, new art URI (new track): key changes, same handling. Art URI becomes null on the same player: key `M:<id>:null`, placeholder shown, no task, the old task's result is dropped. `unbind()` sets the tag to null and clears `shownArtKey`; a hidden bar (`shownArtKey = null`) re-shows and re-requests only once on the next appearance.
- Because the key no longer includes `getIcon() != null`, art that finishes downloading through `getIcon()`'s own download does not update the view by itself; the view relies on the `IconDownloadTask` of the same key, which is the intended path. No stale or wrong image is possible (every write is tag-guarded or happens in `showArt` itself).

**3. Null art URI: nothing triggered.** `AVTransportPlayer#getIcon()` only queues a download inside `if (albumArtUri != null)` and `getAlbumArt()` returns that same field, so `artUri == null` implies no download there; `showArt` starts `IconDownloadTask` only for `artUri != null`; the other players return null or the plain field and never download in `getIcon()`. Honored. (A track change between the `getAlbumArt()` and `getIcon()` reads can make `getIcon()` fetch the new URI once; still once per key.)

**4. Flipping A,B,A,B: bounded, not a Warning.**
- Each flip changes `shownArtKey`, so each flip re-runs `showArt` for the newly featured player: 1 `getIcon()` download + 1 `IconDownloadTask` if its art is not cached; a cached (successfully loaded) art is a cache hit with no network. Failed or hanging art is never cached, so a flip re-requests it.
- Rate: `refresh()` evaluates one featured player per call; the poll is every 2 s, other calls are player add/remove events and the 500 ms refresh after a user tap. One key change per refresh, so at most 2 requests per refresh. Sustained, driven by the poll alone: at most one flip per 2 s, i.e. 2 requests per 2 s when both players have uncached art, 1 per 2 s (2 per 4 s) when only one side has uncached art. This is the same order as the cycle 1 baseline for a single player, not worse.
- Reachability: the picker is deterministic (playing first, then newest creation sequence, then higher id), so players that are both playing or both paused never flip. A flip needs `isPlaying` to alternate between two players in lockstep with the poll. A renderer can push its own player to not playing (PAUSED/STOPPED transport info), but a player becomes playing again only through Yaacc's own `Play`/`next()` actions (user taps, queue advance), not by the device's own reports, and the art URI is the one of the DIDL item the user chose, not renderer-controlled. A sustained lockstep alternation by a hostile device alone is therefore not reachable through a normal state; user-speed play/pause between two receivers flips at human speed. The `bind()`/`unbind()` cycle (activity start/stop) re-requests each uncached key once per start.
- Hang case (no timeout in `ImageDownloader`): `IconDownloadTask.execute` runs on the serial AsyncTask executor, so hung tasks queue behind each other (no concurrent flood, at most one queued task per key change, each pinning the art `ImageView`); the `getIcon()` download runs on the shared 10-thread content pool, so about 10 hung key changes would occupy it. This needs the flip precondition above plus a hanging art URL; the missing connect/read timeout is the carried open item, and the mitigation below is not needed to close this cycle.
- Cache of requested keys: not warranted as a Warning. A simple "requested keys" set that skips the task would break restoring the art when a player comes back (the view was overwritten by the other player); the right form is a small failed-key backoff (see Suggestion).

**5. decisions.md: consistent.** The 2026-10-24 entry matches the code (key = player id + art URI, `getIcon()` read once per key) and lists the follow-ups that were not done (invisible/format characters, `ReceiverBarLabel` raw names, `ImageDownloader` scheme/timeout/size). No contradiction with earlier entries. Nit for Group 10: cycle 1 asked docs to say "no `getIcon()` on the poll path"; the as-built rule is "`getIcon()` once per key change".

### Critical
- None.

### Warning
- None.

### Suggestion
- [MiniPlayerBar.java:307] Drop `getIcon()` here entirely (cycle 1 allowed "or never"). The `IconDownloadTask` already loads the art, so `getIcon()` costs a second download of the same URL per key and keeps a side-effecting call in a UI path; show the placeholder/device icon until the task finishes.
- [MiniPlayerBar.java:showArt] Optional hardening against flips and rebinds: remember failed keys with a time (for example a 10-entry `LinkedHashMap<String, Long>` per bar, no retry within 60 s) and skip the network call but still set the placeholder. Do not skip on "already requested" alone (a successful earlier load must be restored from `IconDownloadCacheHandler`, which the task already does).
- [MiniPlayerBar.java:showArt] No unit test pins the "key has no dependence on `getIcon()`" rule (cycle 1 asked for a testable rule). Extract the key to a pure helper (for example in `MiniPlayerPresenter`) and assert it does not change with the icon state.
- [Group 10 docs] Record the as-built rule "`getIcon()` at most once per art key; art otherwise only through `IconDownloadTask`" in `docs/tech.md`.
- Carried, unchanged: `ImageDownloader` scheme allowlist (http/https), connect/read timeouts and byte cap; `IconDownloadTask` on the serial AsyncTask executor; the cycle 1 Suggestions (bidi/format characters, `ReceiverBarLabel`, receiver name negative cache, `shutdownNow`, `FLAG_CANCEL_CURRENT`), and all Group 9/10 forward-looking requirements of cycle 1.

### Verdict: PASS
Zero Critical, zero Warning. The cycle 1 Warning is closed: `getIcon()` is no longer on the poll path, it runs once per `player id + art URI` key, a null art URI triggers nothing, late art is tag-guarded and correct across player and art changes, and the worst-case flip rate (one key change per refresh, 2 requests per 2 s, only if players alternate their playing state in lockstep with the poll, which a device cannot drive alone) is not a sustained flood through a normal state. Group 8 may proceed.


## Cycle 1 — 2026-10-24
Reviewing: Group 9 (phase 2), preference task

Scope: `git show 72f337cf 87b79d5f` (`preference.xml`, `values/browser_layout.xml`, `settings_browser_layout_*` strings in 7 locales, `BrowserLauncher` Javadoc, `BrowserLayoutValuesTest`). Read in addition: `BrowserLayoutChoice`, `BrowserLauncher` (all of it), `SettingsActivity`, `SettingsFragment`, the debug merged manifest, every `PendingIntent.get*` call and every `SharedPreferences` writer in `de.yaacc`. The Group 5-8 "Group 9 must" lists were checked item by item. No code or docs edited.

**1. Manifest and build: honored.**
- `git diff 85ae9d2a..HEAD -- yaacc/src/main/AndroidManifest.xml yaacc/build.gradle` is empty. No exported, `allowBackup`, permission or component change; no dependency change.
- `SettingsActivity` has no `android:exported` and no intent filter, so it is not exported on any API level (the merged manifest has the same two lines). It extends `AppCompatActivity` and hosts a `SettingsFragment` itself (not `PreferenceActivity`), so there is no fragment-injection extra. It reads no intent, extra or data. Another app cannot start it, drive it or pre-select a value.

**2. Fixed values, one reader: honored.**
- `entryValues` come from `browser_layout_values` (`classic`/`new`, `translatable="false"`, so no locale file can change them; the 6 locale files carry only the three display strings). Default `classic`. `BrowserLayoutValuesTest` pins both the array and the default to `BrowserLayoutChoice.STORED_*` and `DEFAULT_STORED_VALUE`.
- The key `settings_browser_layout_key` appears in exactly one Java file, `BrowserLauncher.effectiveLayout(Context)` (read, default `null`, `RuntimeException` caught, then `NEW_LAYOUT_AVAILABLE` and `BrowserLayoutChoice.resolve`). No second reader, no writer in code (the `ListPreference` is the only writer).
- The stored string is only compared (`STORED_NEW.equals(value)`). `targetClass(Layout)` is a two-way `if` on the enum. It is never a class name, action, URI, path, extra or reflection target. Unknown, differently cased, wrong-type values resolve to classic.

**3. Can another app set it? No.**
- The default `SharedPreferences` file is private to the app UID. No `ContentProvider` exposes it (the only provider is `FileProvider`, `exported="false"`). Every `.edit()` writer in `de.yaacc` was listed: all write fixed keys from in-app UI/lifecycle, or keys built from internal state (`SAFCacheManager`, `NetworkInterfaceManager`, `ImageViewerActivity`). The one writer that touches an `Intent` (`YaaccUpnpServerControlActivity.onActivityResult`) takes the system document-picker result for `takePersistableUriPermission` and writes no layout key; no preference is keyed from an incoming intent or URI. There is no settings import/export path.
- Remaining routes: Auto Backup / `adb backup` restore (see 4) and `run-as`/root on debuggable builds. Both are outside the app's trust boundary and can only choose between the two in-app layouts.

**4. allowBackup residual risk (unchanged, not new).** `allowBackup` is not set in source or in the merged manifest, so it defaults to true. A backup holds the layout value with `proxy_link_*`, the receiver selection and SAF cache entries. An attacker who can tamper with a restored backup can set `new`; that routes only to `BrowserActivity` (`exported="false"`, no filter), through `relaunchIfOtherLayout`, with `NEW_LAYOUT_AVAILABLE` and the loop guard in force. The effect is a layout flip and nothing else; the wider exposure of the other keys in the file is the open owner item from Group 5-8 and is not worsened by this change.

**5. Switch path and PendingIntents: no abuse path, UX only.**
- A change in Settings takes effect on `onResume` through `relaunchIfOtherLayout` (one `effectiveLayout` computation, fresh `new Intent(activity, target)`, shared `RelaunchGuard`, `NEW_LAYOUT_AVAILABLE` kept). Nothing from `getIntent()` is forwarded. There is no relaunch from `SettingsFragment`; the setting does not itself start an activity.
- Stale PendingIntents (`Yaacc.createYaaccGroupNotification`, `PlayerService.createSessionActivity`, `updateServiceNotification`): all three are `new Intent(this, <class>)` (explicit component, no data, no extras) with `FLAG_IMMUTABLE`. Only our own process can have created them, and another app can neither alter nor read the target. After a switch, an old notification still points at the previous class; a tap starts that activity, whose resume (or `onCreate`) check relaunches to the effective browser. The result is at worst a second instance on the stack (the already noted `tasks.md` observation). Not security relevant. The other notification PendingIntents (`AVTransportPlayer`, `MultiContentPlayer`, `LocalImagePlayer`, `LocalMediaSessionPlayer`, `YaaccUpnpServerService`) do not use `targetClass` and are `FLAG_IMMUTABLE` and explicit as in earlier cycles.

**6. Android TV.** The setting is visible on TV while D-pad reach of the new layout is unverified. A user could choose `new` and find the browser hard to operate. This is an availability and usability concern, not a security one; it is covered by the owner's manual checks. Recovery is by the same Settings screen (reachable in `BrowserActivity`; this must stay true).

**7. Other.** The `entries` strings are static resources; `android:summary="%s"` renders the selected entry only. No logging of the value. No new dependency, no network, no reflection. Test reads only module resource files.

### Critical
None.

### Warning
None.

### Suggestion
- [BrowserLauncher.java] `NEW_LAYOUT_AVAILABLE` and the `RuntimeException` catch stay as they are; a future change must not add a second reader of the key.
- Group 10 must: record in `docs/tech.md` as built that the key is read only in `BrowserLauncher.effectiveLayout(Context)`, values are `classic`/`new` (default `classic`), and the switch runs only through `relaunchIfOtherLayout` (fresh intent, `RelaunchGuard`).
- Group 10 must: re-run the manifest check after the last code change (`BrowserActivity` and `SettingsActivity` not exported, exported set unchanged) and keep `git diff` of manifest and `build.gradle` empty.
- Group 10 must: write the `allowBackup` residual (item 4) into the docs or the decision log, with the owner's decision on `android:allowBackup="false"` or a backup exclusion for the default preferences file (carried; outside the spec).
- Group 10 must: keep the settings entry reachable in the new layout (item 6) and record the Android TV status after the owner's manual check; note the stale-notification stack duplicate as a known behavior or fix it with `FLAG_ACTIVITY_REORDER_TO_FRONT` on the relaunch intent only (fresh intent, no forwarded data).
- Carried forward, still open (outside this group): `allowBackup`/preferences exposure; `PlayerService.getPlayer()` mutable view; `ImageDownloader` scheme allowlist, timeouts and size cap; ignore remote devices whose UDN equals `LOCAL_UID`; per-request deadline in `RendererStatusMonitor`; bidi-control stripping in `ReceiverBarLabel`; `PlayerListFragment`/`ReceiverListFragment` never remove their `UpnpClientListener`.

### Verdict: PASS
Zero Critical, zero Warning. The preference stores one of two fixed values, is read in one place, is only compared, is private to the app, and cannot be written or driven by another app (the settings activity is not exported). The manifest and gradle diffs are empty. Group 9's preference task may proceed to Group 10.


## Cycle 1 — 2026-10-24
Reviewing: Group 10 (phase 2)

Scope: `git diff 826471c6..HEAD -- docs README.md CHANGELOG.md` (commits 40294bcf, cb869510; `docs/index.md`, `docs/settings.md`, `docs/tech.md`, `CHANGELOG.md`, `README.md`). Docs only: no source, manifest or gradle change in these two commits. Each claim about a security property was checked against the code (`LauncherForwardPolicy`, `ShareUriPolicy`, `BrowserLauncher`, `MiniPlayerPresenter`, `MiniPlayerBar`, `ShareIntentHandler`) and against a regenerated merged debug manifest (`./gradlew :yaacc:processDebugManifest --offline`, parsed). No docs or code edited.

**1. Leak scan: clean.**
- The prescribed grep (`token|secret|password|api key|/home/|/tmp/|e-mail|claude.ai`) has zero hits over the diff. A second scan of the added lines for IPv4 addresses, `session_`, `/root`, `.internal`, `localhost`, the owner's name/domain, key material and cloud key prefixes has zero hits. The only URLs are `developer.android.com` reference links and the placeholder `http://a\nfile:///x`. The only path-like strings are `~/.gradle/caches/...` (home-relative, no user name) and public AOSP/GitHub raw URLs in the "Sources" list. No session URLs, no private hostnames.

**2. Disclosure level: appropriate for a public open-source note.**
- Share-hardening entry (CHANGELOG, index.md, tech.md "Share handler"): says what is now accepted and why ("a shared link cannot expose local files through the proxy option"). That issue is fixed in this branch; there are no steps, no endpoint format beyond the already public `/proxy/<hash>`, no intent recipe. Fine.
- tech.md `ShareUriPolicy` note (`http:` and `http://a\nfile:///x` pass the scheme check): both strings keep the `http` scheme and cannot reach a `file:` source. It documents a looseness, not a bypass of the file protection. Fine.
- tech.md "Known limitations": `allowBackup`, stale PendingIntents, `ImageDownloader` (any scheme the JVM handles, no timeout, no byte cap, serial AsyncTask), `setDataSource` timeout, name rendering. Each is one general sentence of impact and the follow-up. None gives a request, URL, payload or sequence that would exploit it, and the `ImageDownloader` entry has no recipe. The `ImageDownloader` weakness exists in released code, but the wording is the engineering level that the carried-forward items already had in the repo. Not a Warning.
- The "Research notes (Group 5)" grant-forwarding analysis (`FLAG_GRANT_*`, `ActivityStarter`) is platform behavior analysis; the doc states the mitigation (fresh intent, flags never copied). No recipe against shipped code (the trampoline did not ship before this branch).

**3. No insecure advice: honored.** The new text never tells users to enable the proxy, the media server or the renderer on any network, to disable a protection, or to grant a permission. The Layout text recommends nothing but a choice between two layouts and says classic stays the default (also on TV). The share paragraph tells users to share only the link. The Android TV and manual-check gaps are stated as unverified, not as safe.

**4. Security statements are true as built.**
- Launcher forward allowlist: `LauncherForwardPolicy.decide` forwards only `android.intent.action.SEND` + `text/plain` + non-empty text up to 2048 chars, exact case-sensitive compare; over-cap text is dropped, not truncated. Matches tech.md (forwarded and never-copied lists, "fresh Intent", `finally` + `finish()`).
- http/https only: `ShareUriPolicy.isAllowed` compares the text before the first `':'` to `http`/`https` (ASCII, case-insensitive), length cap 2048, nothing trimmed. Matches index.md ("ignored"), CHANGELOG and tech.md. The pinning test `ShareUriPolicyTest#onlyTheSchemeAndLengthAreChecked` exists.
- Merged manifest: `BrowserActivity` `exported="false"` and no filter; `SettingsActivity` no `exported` and no filter; `YaaccUpnpServerService` and `ImageViewerBroadcastReceiver` not exported; `TabBrowserActivity` exported with no filter; `LauncherActivity` exported. Exported set counted from the merged manifest = 12, same names as tech.md (`LauncherActivity`, `TabBrowserActivity`, `ImageViewerActivity`, `MusicPlayerActivity`, `AVTransportPlayerActivity`, `MultiContentPlayerActivity`, `AboutActivity`, `YaaccUpnpServerControlActivity`, `YaaccLogActivity`, `ServerAutostart`, `BluetoothValidationActivity`, `ProfileInstallReceiver`). 21 `uses-permission`. `allowBackup` unset in the merged manifest (default true), as documented. Source manifest last changed in e95c9a2b (Group 7); `build.gradle` unchanged since the phase start.
- Mini-player text limits are stated honestly and match `MiniPlayerPresenter`: whitespace runs to one space, control characters dropped, the listed bidi/format characters (U+200E/F, 061C, 202A-E, 2066-9, 200B, 2060, FEFF) dropped, 200-UTF-16-unit cap without splitting a surrogate, ZWJ/ZWNJ kept; the "not stripped" list (tag characters, variation selectors, fillers, etc.) and the unneutralised names in `ReceiverBarLabel` are admitted.
- CHANGELOG "Idle exit ... with nothing running" is a user-level shortening; tech.md states the full condition (no player, and local server, renderer and proxy all off).

**5. Carried "Group 10 must" items: all satisfied.**
- Key read only in `BrowserLauncher.effectiveLayout(Context)`: recorded ("One reader ... Do not add a second reader"); grep confirms `settings_browser_layout_key` appears only in `BrowserLauncher.java:77`.
- Values `classic`/`new`, default `classic`, `BrowserLayoutValuesTest` pin, exact-match resolve, never a class name: recorded.
- Switch only via `relaunchIfOtherLayout` (single `effectiveLayout` computation, fresh intent, `RelaunchGuard` one per 2000 ms): recorded; no relaunch in Settings.
- Launcher forward/drop, scheme policy and cap, share-handler shutdown policy (graceful, callback cleared, 3 queued), loop guard, idle counter: recorded.
- Mini-player policy (poll 2 s, executor lifecycle, `cleanText` rules and cap, art only via `IconDownloadTask`, `getIcon()` at most once per key change, none on the poll path): recorded, including the double download per key and the flip case.
- Manifest re-check after the last code change: recorded in tech.md "As built: manifest" and independently re-run in this review with the same result.
- `allowBackup` residual with the owner decision pending: recorded under "Known limitations / owner decisions".
- Settings reachable in the new layout (overflow menu): recorded.
- Android TV unverified (setting visible, default classic, recovery via Settings): recorded in tech.md and index.md.
- Stale-notification duplicate and the `FLAG_ACTIVITY_REORDER_TO_FRONT` option: recorded.
- Carried open items (`ImageDownloader` scheme/timeout/cap, `getPlayer()` mutable view, `LOCAL_UID` ingress, `RendererStatusMonitor` deadline, bidi in `ReceiverBarLabel`, listener leaks in `PlayerListFragment`/`ReceiverListFragment`, 0 ms timer): all listed.

### Critical
- None.

### Warning
- None.

### Suggestion
- [docs/index.md "Media proxy"] Unchanged text, but now that the section also describes share limits, add one sentence that the proxy serves the content unencrypted to every device on the local network, so it should only be enabled on a network you trust.
- [docs/tech.md "Known limitations", `ImageDownloader`] Optionally reword "any URL scheme the JVM handles" to "schemes other than http and https" so the entry reads as a requirement ("restrict to http/https") rather than as an enumeration of what works today.
- [CHANGELOG.md "Idle exit"] Optionally add "and the server, renderer and proxy options are off" to match the code and tech.md.
- [docs/tech.md header] The section says it was verified against `e312ee8b`; the later commits are docs only, but Group 10's own closing note could cite the final code commit (`87b79d5f`) for the manifest re-check.
- Carried forward, still open (outside this group): `allowBackup` decision; `ImageDownloader` scheme allowlist, timeouts, size cap; `MediaMetadataRetriever#setDataSource` timeout in the share worker; `LOCAL_UID` ingress filter; `RendererStatusMonitor` per-request deadline; bidi stripping in `ReceiverBarLabel`; `ShareUriPolicy` tightening (require `://`, reject whitespace/control characters).

### Verdict: PASS
Zero Critical, zero Warning. The public docs contain no secrets, internal hosts, user paths or session links. The limitations are stated at engineering level with no exploit recipe for an unfixed issue, nothing advises unsafe use, every security statement matches the code and the regenerated merged manifest (12 exported components, `BrowserActivity` and `SettingsActivity` not exported), and all Group 8, 9 and earlier "Group 10 must" items are recorded. Group 10 may be closed.


## Cycle 1 — 2026-10-25
Reviewing: Group 11

Scope: `git diff 5cb69905..HEAD` (commits 15b38840, d884898a, 385bf1b9, 91ae4563, fa6224d2 plus spec-only commits). Read in full: `MiniPlayerBar`, `MiniPlayerAdapter`, `MiniPlayerRows`, `MiniPlayerArtKeys`, `MaxHeightRecyclerView`, `MiniPlayerPresenter`, `mini_player_row.xml`, `mini_player_bar.xml`, `activity_browser.xml`, `dimens.xml`, `ContentListFragment` (footer), both `fragment_content_list.xml`, `ShutdownTimerController`, `ShutdownTimerToggle`, the `BrowserActivity` diff and the Group 11 docs. Read in addition: `IconDownloadTask`, `IconDownloadCacheHandler`, `ReceiverRowBinder.openPlayer`, `AVTransportPlayer`/`LocalMediaSessionPlayer` art code, `PlayerService`, `UpnpClient.getDevice`, `Yaacc.startShutdownTimer`, and the pre-branch footer at merge-base 6b6c31f8. The Gradle build and the Android SDK are not available in this container: nothing was compiled or run on Android. I compiled `MiniPlayerArtKeys`, `MiniPlayerRows` and `DeviceSessionGrouper` with `javac` (plain Java) and ran a small scratch harness for the art-key bookkeeping (results under item 2). All edited XML files parse. No code or docs edited.

**1. Manifest and build: honored.**
- `git diff 5cb69905..HEAD -- yaacc/src/main/AndroidManifest.xml yaacc/build.gradle` is empty; no gradle, toml, properties or proguard file is in the range's file list. No new component, filter, exported flag, permission or dependency. `BrowserActivity` stays non-exported (manifest untouched since Group 7). The "As built: manifest" text in tech.md ("Groups 8, 9 and 11 did not touch it") is true.

**2. Mini-player list.**
- *Art requests per path.* Only `MiniPlayerAdapter.bindArt` touches art. It is gated twice: per holder (`holder.shownArtKey`) and per adapter (`MiniPlayerArtKeys.isNewKey(playerId, key)`, key = `playerId:artUri` from `MiniPlayerRows.artKey`; no `getIcon()` in the key). Only a new key calls `player.getIcon()` and `new IconDownloadTask(art, key).execute(uri)`. Poll, refresh, scroll, rebind after a recycle and a DiffUtil title/state change all hit the early return or the cache-only branch. `IconDownloadCacheHandler.getBitmap` is `LruCache.get(uri + w + "x" + h)`: in-memory, no network, no disk, no write, no side effect. The cache-only branch does not start a task. Honored.
- *Worst-case rate (scratch run).* Steady state, 10 polls, one player: 1 request. Three players whose art URI changes on every poll: 30 `IconDownloadTask` starts in 10 polls (3 per poll), plus one `getIcon()` per key for `AVTransportPlayer`. That is N x (one request per key change), as the task statement expects. It is not reachable by a renderer or media server alone: `albumArtUri` is copied from the DIDL item when Yaacc starts a track (`AVTransportPlayer` ~l.746, `LocalMediaSessionPlayer` ~l.254) and is never updated from renderer-reported state, so the key changes at track-change speed (the same cadence at which the player itself already fetches the 512x512 art and the notification art). The art URI can only change faster than the poll if a track is shorter than 2 s.
- *retainOnly pruning timing.* `MiniPlayerAdapter.submit` prunes `artKeys` and `icons` to the ids of the list it was just given, on every refresh. A player that leaves `getCurrentPlayers()` and comes back is a new sight and re-requests once per re-entry (scratch run: 10 alternating polls = 4 requests with 5 present polls, i.e. one per re-add after a prune; never one per poll while it stays present). A flap needs `PlayerService.removeAndNotify/putAndNotify`, i.e. a real player shutdown plus re-creation (user action, queue end, or a LAN control point driving the local renderer's SetAVTransportURI/Play/Stop when that option is on). Each cycle already costs the other side one action and already makes the player itself fetch the art (`AVTransportPlayer`) and refresh the notification. The amplification is bounded and no worse than before Group 11. Not a Warning; Suggestion below.
- *Stale results.* `IconDownloadTask(ImageView, String deviceId)` drops its result unless `imageView.getTag()` equals the key. `bindArt` sets the tag before starting, `onViewRecycled` sets it to null, a different key overwrites it. A recycled holder re-bound to the same key gets the old in-flight result (same player, same URI), which is correct. A holder re-bound to another player drops it. Honored.
- *Memory.* `icons` (`SparseArray<Bitmap>`) holds one `getIcon()` result per player (usually the shared `LruCache` instance, at most 512x512 per player), pruned in `submit` and cleared in `clear()` (from `unbind()`); `artKeys` the same. Bounded by the number of running players.
- *Text.* `title` and `receiver` are produced only in `MiniPlayerBar.rowFor` via `MiniPlayerPresenter.title/receiverLabel` (`cleanText`, unchanged since Group 8) and bound with `setText`. No `fromHtml`, `autoLink`, `Linkify` or movement method; both TextViews are `maxLines=1`, `ellipsize=end`. `cleanText` stops at 200 chars. Honored.
- *Content descriptions.* Play/pause uses `R.string.mini_player_pause/play`; the other buttons use fixed string resources; the art `ImageView` has a fixed description and `importantForAccessibility="no"`; the row's click action label is `R.string.mini_player_open`. No untrusted text is used for a description or accessibility label. Honored.
- *Row tap.* `onOpen` returns when unbound, else `ReceiverRowBinder.openPlayer(list.getContext(), player)`: the player's own `getNotificationIntent()` PendingIntent sent with `new Intent()` (no extras, data, action); `CanceledException` is caught (logged with the exception). The players' PendingIntents are explicit and `FLAG_IMMUTABLE` (Group 8). Honored.
- *Commands.* `sendCommand` returns when `!bound`, executor null or shut down; runs `previous/play/pause/next/stop` on the single executor created in `bind()` and shut down (`shutdown()`, not `shutdownNow()`) in `unbind()`; `RejectedExecutionException` and `RuntimeException` are caught and logged by class name only; the post-command refresh is posted only while `bound` and `refresh()` re-checks it. After `unbind()` no command is accepted and the list is emptied (`adapter.clear()`, `GONE`), so no row can be tapped; commands queued before `unbind()` still run (known, unchanged Suggestion). The play/pause decision uses `player.isPlaying()` on the main thread (in-memory). Honored.
- *Lifecycle.* `bind()` adds the player-list listener and posts the 2 s poll; `unbind()` removes the listener and both callbacks and clears `receiverNames`. `BrowserActivity` calls `bind()` in `onStart`, `unbind()` in `onStop` and `onDestroy` (then nulls the field). `pollRunnable` re-checks `bound` and does not re-post when unbound. Honored.
- *Copies.* `new ArrayList<>(upnpClient.getCurrentPlayers())`, `PlayerRef` value objects, `MiniPlayerRows.order` returns a new list and skips nulls and duplicate ids, `Row` is an immutable snapshot, `submit` copies into an unmodifiable list. `refresh()` catches `RuntimeException`. Honored.
- *Logging.* `MiniPlayerBar` has four `YaaccLogger` calls, all fixed text plus `getClass().getSimpleName()` of the exception. `MiniPlayerAdapter`, `MiniPlayerArtKeys`, `MiniPlayerRows`, `MaxHeightRecyclerView` do not log. See item 6 for the one pre-existing payload log reached more often.
- *Height cap.* `MaxHeightRecyclerView` only shrinks a `wrap_content` list to min(180dp, 40 % of the offered height) and the list scrolls inside; an `EXACTLY` spec is untouched. In the vertical `LinearLayout` the content container is `0dp` with weight 1, so the bar is measured first against the full height and the container keeps at least about 45 % (less the receiver bar). A hostile list cannot hide the content or the receiver bar, and every row stays reachable by scrolling or the D-pad (rows are focusable before their buttons, the list itself is not focusable). Honored.

**3. Classic footer restore: honored and equal to the pre-branch behavior.**
- The two TextViews (`contentListCurrentProvider`, `contentListCurrentReceivers`) are not clickable and not focusable, have no background ripple, no listener and no intent. Text is set only with `setText` of `getFriendlyName()` (receivers joined with `"; "`), from `showCurrentProvider/showCurrentReceivers`, and refreshed in `receiverDeviceAdded/Removed` through `runOnUiThread`. This matches merge-base 6b6c31f8 (same views, `minLines=2`, `singleLine=false`, no `maxLines`; same unbounded-lines exposure as the original, no new one).
- `host.providesReceiverBar()` decides in `initFooter()`: `showsStaticFooter = host == null || !host.providesReceiverBar()`; in the new layout all three views are `GONE`. `ContentListFragment` no longer references `DeviceSheetFragment` or `ReceiverBarController` and builds no `Intent`. `openDeviceSheetIfNoReceiverSelected()` returns false unless `host.providesReceiverBar()`; `DeviceSheetFragment.show` is called only from `BrowserActivity.showReceivers` (line 228). `TabBrowserActivity.showReceivers()` still switches to the Receiver tab (unchanged). Classic cannot open the sheet or send an Intent through this change.
- Hardening gap carried, same as the original: friendly names are unsanitized (see Suggestion).

**4. Shutdown timer: honored.**
- `ShutdownTimerToggle.decide(running)` returns `STOP` or `ASK_DURATION` only; `START` no longer exists, so a tap cannot start a timer from a stored duration unseen. The toolbar calls `showPicker(fm, title, true)` only when not running and not state-saved. In `showPicker`, `setConfiguredMillis(millis)` runs on confirm and `start()` only if `startOnConfirm && millis > 0L`. The 2-argument overload delegates with `false`; the classic `ServerListFragment` calls the 2-argument form, and its switch (`start()` without a guard) is unchanged. `Yaacc.startShutdownTimer(0)` would end the app at once, so the guard matters: it is present on the only new path.
- Residual (not new): `setConfiguredMillis(0)` restarts a running timer with 0 ms (`app.startShutdownTimer(millis)`); from the new toolbar this needs the timer to be started elsewhere (the classic switch) while the picker is open and then confirm 00:00. Carried; see Suggestion.
- `menu_shutdown_timer_set` is gone from `menu/activity_browser.xml`; `shutdown_timer_set` is removed from `values` and `values-de/es/fr/nl/pt/zh` (7 files); a repo-wide grep finds the name only in docs and spec text, none in Java, XML or tests. No dangling reference.

**5. Docs (CHANGELOG.md, docs/index.md, docs/tech.md): clean and accurate.**
- The prescribed grep (`token|secret|password|api[_ -]?key|/home/|/tmp/|claude\.ai`) over `git diff 5cb69905..HEAD -- docs README.md CHANGELOG.md` has zero hits. A second scan for `session_`, e-mail-like strings, IPv4 addresses, `/root`, key headers has zero hits. The only branch name is the public `origin/feat/issue287`.
- No insecure advice. Security statements match the code: one `getIcon()` per key, no `getIcon()` on the poll path, cache-only reads for recycled holders, `retainOnly`, executor lifecycle, empty fill-in intent, `cleanText` rules and the "not stripped" list, 180dp/40 % cap, zero-duration timer never started, manifest untouched.
- Docs gap (Suggestion): "A player that disappears takes its key and icon with it" does not say that a player that reappears is fetched again (one request per re-add); and the doc still reads "raw friendly names" for the classic footer (stated, accurate).

**6. New logging of LAN-supplied text: none added by Group 11.** `git diff 5cb69905..HEAD -- yaacc/src/main/java` contains no added or removed `YaaccLogger`/`Log` line. One pre-existing log is now reached more often: `UpnpClient.getDevice` writes `identifier='<deviceId>'` at debug on every call that is not `LOCAL_UID`; `MiniPlayerBar.resolveReceiverName` calls it every poll for each player whose receiver name did not resolve (a resolved name is cached, an unresolved one is not), so it is N lines per 2 s instead of one. The identifier is a UDN, the same string the sheet already looks up. Not a Warning.

### Critical
- None.

### Warning
- None.

### Suggestion
- Follow-up [MiniPlayerAdapter.java:submit, MiniPlayerArtKeys.java] A player that leaves and re-enters the list (real shutdown plus re-creation, possibly driven by LAN SOAP actions when the local renderer is on) re-requests its art once per re-entry because `retainOnly` prunes at once. If you want a hard bound, keep pruned keys for a short time (for example 60 s or 8 entries, LRU) or remember failed keys with a time stamp; do not skip on "already requested" alone, since a successful earlier load must still be restored from the cache.
- Follow-up [MiniPlayerAdapter.java:bindArt] Drop `getIcon()` here (carried from Group 8): the `IconDownloadTask` already loads the art, and `AVTransportPlayer#getIcon()` queues a second download of the same URL (512x512) per key and per player. With N rows this is 2N downloads per round of key changes.
- Follow-up [ImageDownloader.java, IconDownloadTask.java] Still open and now scaled by N players: scheme allowlist (http/https), connect and read timeouts, byte cap (for example 2 MB), and running the task on the shared serial AsyncTask executor (a hung art URL blocks every other `AsyncTask` in the app). The mini-player fetches art of every running player on every browser screen without opening anything.
- Follow-up [MiniPlayerBar.java:resolveReceiverName, UpnpClient.java:525] Add a negative cache for unresolved device ids (per bind) or lower the `getDevice` log to verbose; N players now produce N log lines per poll.
- Follow-up [MiniPlayerBar.java:unbind] `executor.shutdown()` lets queued commands run after the screen is gone; `shutdownNow()` is an option (carried).
- Follow-up [ContentListFragment.java:showCurrentProvider/showCurrentReceivers] The restored classic footer shows raw `getFriendlyName()` with no `maxLines`: a very long or multi-line name can grow the footer and push the list away, and `Kitchen; Bedroom` looks like two receivers. This is the original behavior. Reuse `MiniPlayerPresenter.cleanText` and add `maxLines`/`ellipsize` if you want to harden it (carried: names in `ReceiverBarLabel`, sheet and server list).
- Follow-up [ShutdownTimerController.java:setConfiguredMillis] Do not restart a running timer when `millis == 0` (or stop it); also guard `ServerListFragment`'s switch against a 0 duration. Carried from Group 7.
- Follow-up [MaxHeightRecyclerView.java:onMeasure] `offered * 40 / 100` is int arithmetic; use `(int) ((long) offered * 40 / 100)` so a huge offered size (for example from an unbounded parent) cannot overflow. Not reachable in the current layout.
- Follow-up [docs/tech.md "As built: mini-player"] Add one sentence that a player that disappears and returns is requested again, and that the art cost is up to N x the per-key cost.
- Carried, unchanged and still open: `allowBackup` owner decision, `PlayerService.getPlayer()` mutable view, `LOCAL_UID` ingress, `RendererStatusMonitor` per-request deadline, bidi/format character stripping (tag characters, variation selectors, fillers), `FLAG_CANCEL_CURRENT` in `AVTransportPlayer#getNotificationIntent`, listener leaks in `PlayerListFragment`/`ReceiverListFragment`.
- Build status: the full Gradle build, lint, unit tests, `processDebugManifest` and any on-device run could not be done here (no Android SDK). The manifest conclusion rests on the empty `git diff` of the source manifest and `build.gradle`, not on a regenerated merged manifest. Please run `./gradlew :yaacc:testDebugUnitTest :yaacc:processDebugManifest :yaacc:lintDebug` on a machine with the SDK before merging.

### Verdict: PASS
Zero Critical, zero Warning. The manifest and build files are unchanged; the mini-player list requests art only on a new player-and-URI key (never on poll, refresh, scroll or rebind; recycled holders read the in-memory cache only), drops stale results by tag, keeps memory bounded by the number of players, renders only cleaned text through `setText`, opens players with an empty fill-in on the player's own immutable PendingIntent, runs commands off the main thread on one executor tied to `bind()`/`unbind()`, and its height cap cannot hide controls. The classic footer is the original non-interactive `setText` footer with no path to the sheet or an Intent. The timer starts only from a confirmed duration above 0 and the removed string has no remaining reference. The docs are free of secrets and paths and match the code. Group 11 may proceed (static review only; the Gradle build and device run are still outstanding).


## Cycle 1 — 2026-10-25
Reviewing: Group 12 (phase 3: research + red test skeletons)

Scope: design.md "Phase 3" (decisions, threat model, risks), tasks.md Groups 12-16, docs/tech.md "Sharing local files" (a)-(g), the five `de.yaacc.share` stubs and their tests, `LauncherForwardPolicy` + `LauncherForwardPolicyStreamTest`, the merged manifest (`yaacc/build/intermediates/merged_manifests/debug`), `YaaccUpnpServerService.createHttpServer`, `UpnpClient.createPlayableItem`, `LauncherActivity`, `MiniPlayerPresenter.cleanText`. No code runs yet (stubs are fail-closed); this review judges the plan and the contracts the tests pin. Not built, not run on a device.

What holds (checked against the tests, not only the Javadoc):
- `SharedContentPolicy` tests pin: `file:`, `http(s)`, `android.resource`, `intent`, opaque `content:x`, empty authority, `@` (user-id/userinfo, so `content://10@auth/` fails), `%` in the authority, any non-ASCII (Cyrillic look-alike), whitespace and control characters, 2048 cap (exact boundary), own authority case-insensitive with empty path/query/fragment, null/empty own authority fails closed.
- `LauncherForwardPolicy.decideStream` pins: SEND only, audio/video/image only (lower case, no parameters), `EXTRA_STREAM` == ClipData item 0 exactly, read flag required. The decision object has one field and three accessors (a reflection test), so no flag other than READ and no type/extras can be forwarded; incoming WRITE/PERSISTABLE/PREFIX bits are tolerated but dropped (`-1` flags test). The grant recorded by the system is per target uid and URI (no prefix), so forwarding READ for one ClipData item gives the browser no more than that one URI.
- `ShareRegistry` tests pin: 16 random bytes -> 22-char base64url, no key reuse (retired window), absolute max not extended by lookup/markActive/release, grace never beyond the absolute max, clock going backwards does not expire, close exactly once even under concurrency, cap 8 and a refused `register` leaves the caller owning the target, `isWellFormedKey` rejects traversal/percent/unicode/padding.
- `ProxyAccessPolicy` tests pin: both sides normalized, IPv4-mapped (dotted and hex, any case) folded, zone stripped, compat form not mapped, leading-zero/octal, hex, short and decimal-integer IPv4 -> null, brackets/ports/host names/networks never match, null/empty/garbage remote or set denies when restricting, loopback is NOT special (allowed only if the set has it), unrestricted passes even a missing address but the key and expiry stay separate.
- `ByteRangeResolver` tests pin: header capped at 1024, never throws, long overflow saturates, multi-range -> 200 whole (no amplification), invalid -> ignored, unsatisfiable -> 416 `bytes */len`, empty/unknown length handled.
- The picker contract (`ReceiverPickResult`) is right: cancel carries no ids, confirm without a receiver keeps the picker open.
- Descriptor design (dup + close original, `Os.pread`, positional only, `S_ISREG` + `lseek` seekable test, `/dev/zero`-style char devices and pipes excluded, HEAD opens nothing) is sound. One shared descriptor per share, so concurrent connections do not multiply descriptors.

### Critical
- None.

### Warning
- [tasks.md Group 14 Accept, tech.md (f) "Share flow", design.md decision 2] **The descriptor (and the copy fallback) is opened and the share registered at intake, before any human confirmation.** The launcher is exported and needs no permission, and the picker only comes after. A hostile app (or a loop in one) can fire SEND intents and, per intent, make yaacc open a provider descriptor, `getType`/open on the main thread (a provider that never answers hangs the UI), and, with a non-seekable provider, copy up to 512 MiB to `cache/incoming/` each; `maxActive` 8 means 8 pending shares exhaust the registry and the next legitimate share or http(s) proxy link is refused (`register` returns null), 8 x 512 MiB fills the cache. Nothing in the plan bounds unconfirmed shares. design.md says the pick is the human gate, but the gate sits after the resource use. Fix in the plan now (see "Group 14 must" 1-3): a pending (unconfirmed) share is NOT in the registry and holds no descriptor, copy or slot; open, copy and register only after the user confirmed; at most one pending share, with a timeout.
- [design.md Threat model "every share needs a receiver pick", tasks.md Group 15 "classic is unchanged"] **The human gate does not exist in the classic layout**, which is the default for every existing install and for Android TV. The new SEND filters are on the one exported launcher for both layouts; in classic `ShareIntentHandler` plays on the stored receivers (default `{LOCAL_UID}`) or falls back to `LOCAL_UID` after a blind wait, with no prompt (tech.md (f), Backlog 1 item 3). With Group 14 as planned any app can make yaacc open and serve a file yaacc can read but the sender cannot (MediaStore, SAF tree grants, the documented residual) and play it on the user's receivers with no confirmation, replayed after process death. The residual-risk acceptance in decisions.md 2026-10-25 item 2 names the pick as a mitigation, so it is not valid for classic. Fix in the plan now ("Group 14 must" 4): in classic, a stream share needs an explicit confirmation (dialog with the cleaned name and the receivers it will play on) before anything is opened, or stream shares are refused in classic. Pre-existing text-link auto-play is unchanged and out of scope.
- [tasks.md Group 14 Accept "ShareIntentHandler validates it (policy)"] **The forwarded-intent checks live only in the launcher, but `TabBrowserActivity` (classic target) is `exported="true"` without filters** (manifest line 69-72) and can be started directly with any intent, skipping `decideStream`. Direct start with a ClipData URI and no read flag means no grant check by the system (the system checks the sender's access only when the flag is set), so the sender could name content yaacc may read but it may not. The handler must repeat the launcher's checks on its own intent, not trust "it came from the launcher": ClipData with exactly one item, no `data`, `getUri()` of item 0 only (never `EXTRA_STREAM`), `FLAG_GRANT_READ_URI_PERMISSION` present in the activity's intent flags, `SharedContentPolicy` again, the real MIME via `getType`. Add to Group 14 Accept ("Group 14 must" 5).
- [tasks.md Group 14, tech.md (a); `SharedContentPolicy.isAcceptableUri(String, String ownAuthority)`] **"Own authority" is a single string in the contract; the app has more providers.** Merged manifest providers: `androidx.core.content.FileProvider` (`de.yaacc.fileprovider`, `grantUriPermissions=true`, `exported=false`) and `androidx.startup.InitializationProvider` (`de.yaacc.androidx-startup`). The second passes the policy today (harmless now: it serves no files), but a future library provider, another build variant authority, or `${applicationId}` differences would pass silently, and for a same-uid provider the system forward check succeeds without any grant. tech.md (a) already recommends comparing `PackageManager.resolveContentProvider(authority, 0).packageName` with `getPackageName()`, but tasks.md Group 14 does not carry it. The unit contract can stay (first gate); the glue needs a second gate ("Group 14 must" 6). Not a signature change, so the Group 12 tests stay valid.

### Suggestion
Requirements to fold into the tasks (each is testable or greppable):

**Group 13 must:**
1. `createDefault()` uses one `java.security.SecureRandom` (not `Random`, `ThreadLocalRandom`, `Math.random`) and a monotonic clock (`System.nanoTime()/1_000_000`, not wall clock, not `android.*`); a test or grep pins it. Keys: never logged, never in a 404 body, never in `toString`, never in an exception message; the handler checks `isWellFormedKey` before `lookup` and answers 404 with the same constant body for malformed, unknown, expired and released keys.
2. Closing a shared source must never block: use an in-flight counter with deferred close (the last reader closes), not a write lock that waits for a reader stuck in a FUSE `pread`; the registry calls `close()` outside its own lock so one stuck reader cannot freeze `lookup`/`register`/the sweeper. Reads after close return -1, never touch a recycled descriptor number.
3. Descriptor bounds from the provider are untrusted: reject `startOffset < 0`, an unknown or negative length, `startOffset + length` overflow (`Math.addExact`), a length beyond `fstat` size minus the offset (clamp), and a regular-file check on the dup (`S_ISREG`), so a lying `AssetFileDescriptor` cannot read outside its window or serve an endless source. Read in chunks of at most 64 KiB; `int` casts only after the clamp.
4. Per-entry limits on the proxy side: at most 4 concurrent responses per entry (503 beyond), HTTP/2 streams count too (`H2ServerBootstrap`, up to 100 streams per connection by default), and a write-idle timeout shorter than the server-wide `setSoTimeout(5 min)` for registry responses. Per-connection sockets are the scarce resource, not descriptors (one shared descriptor per share). A cap of requests per remote address that failed key lookup is optional (keys are 128-bit).
5. IP step: remote address from `HttpCoreContext.cast(context).getEndpointDetails()` only; never from `Forwarded`/`X-Forwarded-For`/`Host`; `restrictToReceivers` read per request with default `true` in code (`getBoolean(key, true)`), in the preference XML (`defaultValue="true"`) and when the read fails; implement normalization by hand as the tests pin it, never `InetAddress.getByName` (resolves names, accepts `010.0.0.1`/`0x7f.1`/decimal forms). Denial: 403 + payload-free log (remote address only), checked on EVERY request including `Range` continuations and `HEAD`.
6. Local device as receiver: add `127.0.0.1`, `::1`, the own address and the bind addresses ONLY when `LOCAL_UID`/`LocalDummyDevice` (or the local `LocalDevice`) is among the chosen receivers. A local app can then reach the proxy from loopback or the own address, so the key is the only barrier there; the key must not be published to other apps (see Group 16 below).
7. `HTTP_LINK` entries: forward the upstream `Range` only as the re-serialized parsed `bytes=a-b` (never the raw header); keep the existing upstream timeouts; entries never relay to `file:`/`content:` (scheme re-checked with `ShareUriPolicy`, case-insensitive `http`/`https` including `HTTP://`).
8. `register` full: the caller shows feedback (never a silent no-op); when full and an entry is released-but-in-grace, evict the oldest released entry before refusing (a user's own playback must not be starved by leftover grace slots). The sweeper runs without requests (about every 30 s) and `removeAll()` on `Yaacc.exit()`.
9. Remove every log of keys/URIs on this path (`YaaccUpnpServerContentHttpHandler.java:280, 314-316, 773-777`) AND the players' logging of the proxied item URL (`AVTransportPlayer`/`PlayableItem`), since the key is in that URL. Grep `proxy/` and `PlayableItem` for `YaaccLogger`.

**Group 14 must:**
1. Two-phase intake. Phase A (main thread, cheap, no descriptor): policy checks only; remember the URI string, MIME hint and a cleaned display name. No `openAssetFileDescriptor`, no copy, no registry entry. Phase B (after the user confirmed the pick, background thread): `getType`, display name (if needed), open the descriptor, dup, seekability, copy fallback, `register`. The grant lives as long as the browser's activity record, so opening after the pick is inside the grant (tech.md (a) 3). A failure in B shows a localized error and releases everything.
2. At most ONE pending share. A new share while one is pending or its picker is open is dropped with a payload-free log and a short toast ("finish the current share first"); do NOT replace it under an open picker (a replace at the moment of the tap would make the user confirm a different file than the one named). Pending shares expire after 2 minutes (picker dismissed, state cleared). The pending state holds only the URI string, MIME hint and cleaned name.
3. Blocking provider calls (`getType`, `openAssetFileDescriptor`, display-name query) run on a worker with a timeout (about 10 s); copy fallback: global budget of 1 concurrent copy and 1 GiB total in `cache/incoming/`, free-space check, cancel on picker cancel, delete on close, orphan sweep at start (as planned), a copy never starts before the confirm.
4. Classic layout: before Phase B for a STREAM share, an explicit confirmation dialog ("Play '<cleaned name>' on <receiver names>?") that gates it, or refuse stream shares in classic. The confirmation is created per share and bound to it (see Group 15 token). Consume the intent once: after reading it, `setIntent(new Intent())`/clear ClipData and flags, and a `savedInstanceState` guard so rotation and "recents" re-delivery do not replay it.
5. Browser-side revalidation as in Warning 3 (flag in the activity's own intent, ClipData count 1, no `data`, policy, `getType`), using the same `SharedContentPolicy`/`decideStream` logic, not a copy. Never use `EXTRA_STREAM` on the browser side.
6. Own-authority gate: after the unit policy, `PackageManager.resolveContentProvider(authority, 0)`; reject when the provider's package is `getPackageName()` (query errors count as reject only for our own package; unknown authority is not our package). Derive `ownAuthority` from `getPackageName() + ".fileprovider"`/the same source as the manifest placeholder, never a literal. Optionally also reject `:` and `\` in the authority (not needed for lookup, but nothing legitimate uses them); add a test with `content://de.yaacc.fileprovider:80/x` and a trailing-dot authority.
7. API 35+ hardening (documented: `ComponentCaller.checkContentUriPermission`, `Activity.getInitialCaller()`, "had access at launch time", throws `IllegalArgumentException` for URIs not passed at launch and `SecurityException` without access to the URI): in the launcher, on `SDK_INT >= 35`, forward only if the initial caller itself had READ access to the URI (this closes the MediaStore/SAF confused deputy on Android 15+); catch both exceptions and treat them as "no forward". Older versions keep the documented residual. Verify on a device; the docs say the result is computed at launch.
8. Forward intent: explicit component, `ACTION_SEND`, `ClipData.newRawUri("", sameUriParsedFromTheCheckedString)`, `FLAG_GRANT_READ_URI_PERMISSION` only; a test or review grep that no `setFlags/addFlags` with WRITE/PERSISTABLE/PREFIX exists in `LauncherActivity`, that `setData`/`putExtra(EXTRA_STREAM)` are not used, and that `SecurityException` falls back to a plain launch with a payload-free log.
9. The manifest change is exactly three `<data android:mimeType>` in the one new SEND/DEFAULT filter (or three filters); no `BROWSABLE`, no `SEND_MULTIPLE`, no `*/*`. Re-run `processDebugManifest` and diff the exported components.
10. `createPlayableItem` for `content:`: no `MediaMetadataRetriever.setDataSource(String)`, no registry write of the raw provider URI, no fallback to `image/*` on error (reject instead), title never the provider's raw display name.

**Group 15 must:**
1. Pending action bound to a token: the activity keeps `pendingShare{id, cleanedName}`; the sheet's result bundle carries that id; a result without a matching pending id (stale, process death, second sheet) is ignored. Confirm starts exactly that share. Cancel/dismiss/back/outside tap releases the pending share (clears state; nothing was opened, per Group 14 must 1); `onStop` with `isFinishing()` too.
2. The picker title for a share shows the name through `MiniPlayerPresenter.cleanText` (control, bidi, zero-width removed, collapsed whitespace, 200 cap; it is package-private in `de.yaacc.browser`, so keep the picker/handler in that package or widen it) and a fixed fallback ("a shared file") when the cleaned name is empty; the name is rendered via `setText`, never `Html.fromHtml` and never interpolated into a resource string that could carry markup. Also show the type word (audio/video/image) from the validated MIME, not from the sender.
3. No auto-play for a share in the new layout: playback starts only from the sheet's confirm. A confirm with an empty receiver set keeps the picker open (`keepPickerOpen`), never falls back to `LOCAL_UID` for shares (the old "no receiver ready -> LOCAL_UID" fallback stays classic-only).
4. Receivers are resolved at confirm time from the chosen ids; an id that no longer resolves to a device yields no address, and the entry is registered with only the resolved addresses; if none resolve, the share fails with feedback (the empty set denies all when restricting, as tested). The allowed set is built with `ProxyAccessPolicy.normalizeAll`, host names never resolved on the main thread.
5. Selection is cleared only after `player.play()` was called for the players; on failure or cancel the stored selection is unchanged (pick mode uses its own sink, as planned). `clearReceiverDevices()` fires the removed events; stop mutating the set from `getStringSet`.
6. A new share while a pick for another action is open: dropped as in Group 14 must 2; a second sheet instance is never opened for the same pending id.
7. Process death: pending state is dropped (accepted); the original SEND intent is re-delivered by the system, so Group 14 must 4's consume-once and the confirm gate must make the replay a prompt, never a playback.
8. Accessibility strings in 7 locales for title, name label, confirm, cancel; the confirm button has a non-color-only disabled state.

**Group 16 (docs) must:**
1. Setting text and docs for "Restrict the media proxy to the chosen receivers": say that with it OFF the file or link is available to any device on the network that learns the URL for as long as the share lives, and that the URL (with its key) is visible to every UPnP control point on the LAN that can query the receiver (`GetMediaInfo`/`GetPositionInfo`/event `AVTransportURI`). So "unguessable key" protects against guessing, not against someone who can read the receiver's state; the IP check is the control for that case. Change design.md's "key is unguessable ... only the chosen receivers' IPs are served" sentence to say this, and note that with the local device chosen (loopback + own address allowed) a local app that learns the key (for example from a published local renderer's state) is not stopped by the IP step.
2. Record the residual confused-deputy (MediaStore with `READ_MEDIA_*`/`MANAGE_EXTERNAL_STORAGE`, SAF tree grants that yaacc holds, `com.android.externalstorage.documents`) as accepted for API < 35, mitigated on 35+ by Group 14 must 7, and the classic-layout confirmation.
3. Optional: zone ids are stripped for comparison (`fe80::1%wlan0` equals `fe80::1%eth0`); link-local addresses can collide across interfaces of one phone. Accept and say so.

**Item 6 of the request (docs/tech.md recipe for an unfixed issue).** Judged a Suggestion with a process component. Released 5.4.0 still has the issue (shared `file:`/clip URIs written to `proxy_link_<sha256(uri)>` and served by `new File(...)` when the non-default "media proxy" option is on); the fix (`ShareUriPolicy`, launcher allowlist) is only on this unmerged branch and CHANGELOG [Unreleased] says so in one sentence. tech.md "(c)" spells out the chain in one place: the `proxy_link_` writer, that the key is `sha256(uriString)` (predictable, so a LAN reader who knows or guesses a path can compute the URL), that a preference value that is a local path "is served today", and the handler line numbers. That is a recipe for released users in a public repo once merged. Recommendation: (a) ship the Group 5/6 hardening (policy + launcher allowlist + retire `proxy_link_*`/`new File(uri)` serving) as a small 5.4.x patch release before or together with the merge of this branch, not with the large layout work; (b) until then keep the branch docs out of master; (c) in tech.md reduce the "(c) Writers/readers" and "this is the hole" wording to the engineering minimum (what is replaced and why) without the key derivation and without saying a path value "is served today"; (d) the CHANGELOG sentence is fine as is. Not a Warning because the branch is unreleased and the exposure needs a non-default option.

### Verdict: FAIL
Four Warnings, all fixable by amending tasks.md/design.md text now (no test changes needed, the Group 12 contracts are sound): (1) intake must not open descriptors, copy or register before the user's confirmation and must bound pending shares, (2) the classic layout needs its own confirmation or no stream shares (the "receiver pick is the gate" argument does not hold there), (3) the browser-side handler must repeat the launcher checks because `TabBrowserActivity` is exported, (4) own-authority must be the package-based check, not one string. Once the "Group 13/14/15 must" items above (at least Group 14 must 1, 2, 4, 5, 6) are in tasks.md, Groups 13-15 can proceed; re-review at the end of Group 14 with the real manifest diff and glue.


## Cycle 2 — 2026-10-25
Reviewing: Group 12 (phase 3)

Scope: commit 05fde336 (tasks.md Groups 13/14/15/16, design.md decision 2, decisions.md, docs/tech.md proxy passage) against Cycle 1 W1-W4, grepped for stale text in tasks.md, design.md, docs/tech.md, CHANGELOG.md, docs/index.md, docs/settings.md. Checked against the stubs and tests in `yaacc/src/{main,test}/java/de/yaacc/share` and `LauncherForwardPolicyStreamTest`. Plan text only; nothing built or run.

### Cycle 1 Warning status
- **W1 (no descriptor/copy/slot before confirm): resolved in tasks.md and design.md decision 2, but a contradicting passage remains in tech.md (see Warning 1).** Group 14 Accept: the pending share holds only the validated URI, the cleaned name and the read grant; open, copy and register only after the confirmation; at most one pending share; timeout (about 2 min); a new share while one is pending is dropped and never replaces it; token; intent consumed once; no confirmation means nothing is opened. Group 15 repeats token, drop-new, release on cancel.
- **W2 (classic): resolved.** Group 14 Accept requires an explicit confirmation dialog in classic (cleaned name + selected receivers) before anything is opened; constraint 6 and decisions.md item 2 mark "refuse file shares in classic" as the owner's pending alternative, with the dialog as the default.
- **W3 (handler repeats checks): resolved.** Group 14 constraint 1 (one ClipData item, no `data`, item 0 only, never `EXTRA_STREAM`, read flag in its own intent, policy and `getType` again).
- **W4 (own authority): resolved.** Constraint 2 (`PackageManager.resolveContentProvider(authority, 0).packageName` vs our package); the unit contract is unchanged, so `SharedContentPolicyTest` stays valid.
- Group 13/15/16 requirements present: eviction of released entries and visible feedback when full; non-blocking close (in-flight counter, close outside the registry lock); provider offset/length clamped; per-share concurrency and idle caps; IP setting default true in code, XML and on read failure; loopback/own address only when the local device is a chosen receiver; no `InetAddress.getByName`; `cleanText` + fixed fallback; never auto-play, never `LOCAL_UID` fallback; key visible to LAN control points; no exploit-level detail in the public docs.
- No contradiction with the red tests: no test pins an intake-time open or a registry slot; `ShareRegistryTest` (cap, refused `register`, grace, retired keys), `ReceiverPickResultTest` (`keepPickerOpen`), `LauncherForwardPolicyStreamTest` (one field, READ only) and the policy tests fit the amended Groups 13-15.

### Critical
- None.

### Warning
- [docs/tech.md:2746-2751 "Share flow in the new layout"] Still reads: "`handle` validates, **opens the descriptor immediately** (the grant is valid only while the browser record lives), registers nothing yet or registers with an empty address set". This is the pre-amendment W1 design and directly contradicts design.md decision 2 and Group 14 Accept. `docs/tech.md` is the document implementers are told to follow (and the one the Group 16 constraint calls "as built"), so Group 14 can be built the unsafe way from it. Rewrite to: `handle` validates the policy only and keeps one pending share (URI string, cleaned name, token, timeout); descriptor/copy/`register` after the confirmation; cancel or timeout just drops the pending state (nothing to close). Also the "abandoned-share timeout in the registry is the backstop" sentence applies only to entries registered after confirmation.
- [design.md "Threat model" Residual risk and Exposure bullets; decision 4; tasks.md Group 15 Accept/Constraints] The W2 fix is not carried through the design text: "Gates: ... a human-in-the-loop (**every share needs a receiver pick**, ...)" is still wrong for classic (there the gate is the new confirmation dialog), decision 4 and Group 15 Accept say "classic is unchanged" while Group 14 adds a classic dialog, and Group 15 says a share "never falls back to `LOCAL_UID`" with no "new layout" qualifier although tech.md (f) and the Backlog say classic keeps that fallback for links. Since Group 16 documents the threat model from this text, state it exactly: pick in the new layout, confirmation dialog in classic (or refusal, owner pending); "classic unchanged" for everything except the share-confirmation dialog; the `LOCAL_UID` ban applies to file shares in both layouts, while classic http/https link auto-play stays as is.
- [tasks.md Group 14 Constraints (4)] "on API 35+ also require `Activity.getInitialCaller().checkContentUriPermission(...)`" is listed under `ShareIntentHandler` (the browser). In the browser the initial caller is the yaacc launcher (it started the activity), not the real sender, so the check proves nothing (yaacc holds the forwarded grant) or throws `IllegalArgumentException`. Cycle 1 asked for it in `LauncherActivity` (its initial caller is the sender), before the forward, catching `IllegalArgumentException` and `SecurityException` as "no forward". Say so in tasks.md and decisions.md item 3; otherwise the API 35 hardening gives false assurance.

### Suggestion
- [docs/tech.md:2476-2481, :1181, :1429] The proxy-hole sentences are softened (the `new File(uri)` and "is served today: this is the hole" wording is gone; grep for `new File\|served today\|hole` finds only unrelated hits plus `:1429` "opening the proxy hole"). Remaining engineering detail is acceptable but still names the key derivation (`sha256(uriString)`) and the URL form with port next to the preference gate (:2476-2477), and :1181 says a `file://` URI "would be served to the whole LAN through `/proxy/<hash>`". Reduce to "keys derived from the URI, replaced by random keys" and drop the URL/port; reword :1429 ("without exposing local files through the proxy"). CHANGELOG.md and docs/index.md are clean. Keep the recommendation of a 5.4.x patch release before merge (decisions.md item 5).
- [tasks.md Group 14 Accept] It does not say where `ContentResolver.getType` and the display name come from. If they are read at intake, a hostile provider can hang the main thread before any confirmation (one pending share only, but a loop of intents still blocks the UI). Run provider calls on a worker with a timeout (about 10 s), or take the cleaned name from the URI and read `getType` after the confirmation; keep the MIME the picker shows ("audio/video/image") either from the intent type or after that worker call. Also fold the copy budget (one concurrent copy, 1 GiB in `cache/incoming/`) from Cycle 1 into Group 14.
- Cycle 1 Group 13 items not yet in tasks.md (not blocking, carry): `SecureRandom` + monotonic clock pinned by a grep, constant 404 body for malformed/unknown/expired keys, remote address only from the connection endpoint (never `Forwarded`/`X-Forwarded-For`), upstream `Range` re-serialized as `bytes=a-b` for `HTTP_LINK`, removal of key/URL logging in `AVTransportPlayer`/`PlayableItem`, sweeper every ~30 s and `removeAll()` on `Yaacc.exit()`.

### Verdict: FAIL
Cycle 1 W1-W4 are resolved in tasks.md, design.md decision 2 and decisions.md and are consistent with the red tests, but three Warnings of stale or misplaced plan text remain: docs/tech.md (f) still tells implementers to open the descriptor at intake, the design threat model still names the receiver pick as the gate for every layout, and the API 35 caller check is placed where it cannot work. All three are text edits; no test changes are needed.


## Cycle 3 — 2026-10-25
Reviewing: Group 12 (phase 3)

Scope: HEAD d884be14 against Cycle 2 Warnings 1-3, grepped across tasks.md, design.md, decisions.md and docs/tech.md. `git show HEAD --stat` shows the commit touches only design.md, security-review.md and docs/tech.md; tasks.md and decisions.md were last changed in 05fde336 (the review before). Plan text only; nothing built or run.

### Cycle 2 Warning status
- **W1 (tech.md "Share flow in the new layout"): resolved.** tech.md:2745-2753 now says: pending share holds only URI, cleaned name, token and the read grant; no descriptor, copy or registry slot; one pending share, timeout, a new one is dropped; descriptor/addresses/registry/play only on confirm; cancel or timeout just releases; classic confirms through a dialog; `getType`/display name on a worker with a timeout. Grep `immediately|at intake|when the share arrives|sha256|served today|hole` in tech.md and design.md: no hit is a share-flow statement (remaining hits are "whole", an unrelated "immediately redirects" at :1695 and "writes through immediately" at :2667). `sha256` and "served today" no longer occur in tech.md, CHANGELOG.md, docs/index.md, docs/settings.md.
- **W2 (classic wording): partly resolved.** design.md is fixed: decision 4 ("keeps its tab-based selection and static footer; the only addition there is the confirmation dialog for shared files") and the Residual-risk gates ("new layout: receiver pick ...; classic: explicit confirmation dialog with the cleaned name and the selected receivers"). decisions.md items 2/3 are consistent. NOT fixed in tasks.md (unchanged by HEAD), see Warning 2.
- **W3 (API 35 getInitialCaller in the launcher): NOT resolved**, see Warning 3.

### Critical
- None.

### Warning
- [docs/tech.md:2306-2308, section (a) "browser side must treat the forwarded intent as untrusted"] Still ends "run `SharedContentPolicy` again, and **open the descriptor at once**." This is the same pre-amendment instruction as Cycle 2 Warning 1, in a second place, and it contradicts tech.md (f) :2745-2753, design.md decision 2 and Group 14 Accept (no descriptor before the confirmation). Replace with "...run `SharedContentPolicy` again and keep only the validated URI string as the pending share; open the descriptor after the user's confirmation".
- [tasks.md Group 15 Accept (line 213) and Constraints (line 215)] Not touched by HEAD. Accept still ends "classic is unchanged" although Group 14 adds the classic confirmation dialog; Constraints still say a share "is never auto-played and never falls back to `LOCAL_UID`" with no layout qualifier, while tech.md (f) :2742-2743 says classic keeps the "no receiver ready -> `LOCAL_UID`" fallback. Group 16 documents from this text. Say: "classic is unchanged except the confirmation dialog for shared files", and "a file share never falls back to `LOCAL_UID` in either layout (the classic http/https link auto-play keeps its fallback)". Also check the classic file-share confirm cannot start with an empty receiver set via the old fallback.
- [tasks.md Group 14 Constraints item (4); decisions.md 2026-10-25 item 3] Not touched by HEAD. Item (4) is listed among the `ShareIntentHandler` constraints ("on API 35+ also require `Activity.getInitialCaller().checkContentUriPermission(...)`") and decisions.md item 3 says "`ShareIntentHandler` ... API 35+ additionally verifies the sender's grant via `getInitialCaller()`". In the browser the initial caller is the yaacc launcher, so the check proves nothing (or throws `IllegalArgumentException`). Neither tasks.md nor tech.md/design.md names `LauncherActivity` for this check. Reword in both: the check runs in `LauncherActivity` on `SDK_INT >= 35`, before the forward, against the sender as initial caller; forward only if the caller had READ access; catch `IllegalArgumentException` and `SecurityException` as "no forward"; the browser side does not repeat it. Group 14 Accept should list it (it currently does not).

### Suggestion
- [tasks.md Groups 13/14] The commit message says extra Group 13/14 constraints were added, but tasks.md is unchanged, so none of the Cycle 2 suggestions are in the plan yet: `getType`/display name on a worker with a timeout (about 10 s) and the copy budget (1 concurrent copy, 1 GiB in `cache/incoming/`) exist only in tech.md :2751-2753; `SecureRandom` + monotonic clock (only in the tech.md API sketch :2776), constant 404 body, remote address only from the endpoint (never `Forwarded`/`X-Forwarded-For`), upstream `Range` re-serialized as `bytes=a-b`, no key/URL logging in `AVTransportPlayer`/`PlayableItem`, sweeper about every 30 s and `removeAll()` on `Yaacc.exit()` appear nowhere. Add them to Group 13/14 Constraints so they are built and reviewed.
- [tasks.md:229 Backlog 1 item 1] Still reads "without opening the proxy hole (a shared `file://` path served to the LAN through `/proxy/<hash>`)". The file is in the public repo; soften as was done in tech.md. Keep the 5.4.x patch-release recommendation (decisions.md item 5).
- [docs/tech.md:2476-2478] Remaining detail on the old preference proxy ("content key derived from the shared string", the `settings_local_server_proxy_chkbx` gate) is acceptable engineering minimum; no key derivation or "served" claim remains.

### Verdict: FAIL
The tech.md share-flow section and design.md are fixed, and the public docs no longer carry the key derivation. Three Warnings of text remain, all in plan text and none needing code or test changes: a second "open the descriptor at once" in tech.md (a), the unqualified "classic is unchanged"/"never falls back to `LOCAL_UID`" in tasks.md Group 15, and the API 35 `getInitialCaller` check still assigned to `ShareIntentHandler` in tasks.md Group 14 and decisions.md instead of `LauncherActivity`.


## Cycle 4 — 2026-10-25
Reviewing: Group 12 (phase 3)

Scope: the diff of HEAD 7171267f (decisions.md, tasks.md, docs/tech.md; security-review.md only gained the Cycle 3 text) against Cycle 3 Warnings 1-3 and the Suggestions, plus the requested grep across docs/tech.md, design.md, tasks.md, decisions.md and an end-to-end read of the pending-share/classic-dialog story. Plan text only; nothing built or run.

### Cycle 3 Warning status
- **W1 (tech.md (a) "open the descriptor at once"): resolved.** docs/tech.md:2305-2308 now says to run `SharedContentPolicy` again and keep only a PENDING share (URI, cleaned name, token), descriptor after the confirmation, see (f).
- **W2 (tasks.md Group 15 wording): resolved.** Accept: "the classic layout keeps its tab-based selection and static footer (its only addition is the Group 14 confirmation dialog for shared files)". Constraints: "never auto-played and, in the new layout, never falls back to `LOCAL_UID` (classic keeps that fallback for links)". Matches design.md decision 4 and tech.md (f).
- **W3 (API 35 caller check): resolved.** tasks.md Group 14 Constraints (4) names the LAUNCHER (not the handler, because in the browser the initial caller is the launcher), runs it before the forward, catches `IllegalArgumentException` and `SecurityException` as "do not forward", and marks the sub-35 residual and "to be verified on a device". decisions.md item 3 says the same.

### Cycle 3 Suggestions now in the plan
- Group 14 constraint (5): `getType` and the display-name query on a worker thread with a timeout and a total copy budget, never on the main thread before the confirmation (tech.md (f) says the same).
- Group 13 Constraints: `SecureRandom` and a monotonic non-`android.*` clock in `createDefault()`, constant 404 body, remote address only from the endpoint details, upstream `Range` re-serialized from the parsed ranges, sweeper plus `removeAll()` on exit, no key/full-URL logging in `AVTransportPlayer` and `PlayableItem`.
- Backlog 1 item 1 no longer spells out the old proxy behavior.

### Grep result (the requested pattern)
Legitimate hits only for `immediately|at intake|when the share arrives|at once|sha256|proxy hole|served to the LAN|classic is unchanged`: unrelated "at once"/"immediately" in tech.md (:1084, :1404, :1438, :1695 redirect, :2169, :2336 close the original, :2392, :2425 copy limit, :2667 checkbox write-through), tasks.md:215 (checkbox write-through), design.md:17, decisions.md:142, decisions.md:196 ("Nothing is opened or registered at intake", the correct statement) and tasks.md:201 ("sha256-of-URI keys are retired", a removal). No "classic is unchanged", "proxy hole" or "when the share arrives" remains. The two hits that still describe the old released behavior are listed under Suggestion.

### End-to-end consistency (pending share / classic dialog)
design.md decision 2 and 4 and the threat-model gates, tasks.md Groups 13-16, tech.md (a) and (f), and decisions.md items 1-3 all say: validate only at intake; one pending share holding URI, cleaned name, token and read grant; timeout; a new share while one is pending is dropped; descriptor/copy/registry slot only after confirmation (receiver pick in the new layout, explicit dialog with cleaned name and selected receivers in classic); cancel or timeout releases; launcher does the API 35 check; handler repeats the intake checks. No contradiction with the red tests: `ShareRegistryTest`, `ByteRangeResolverTest`, `ProxyAccessPolicyTest`, `SharedContentPolicyTest`, `ReceiverPickResultTest` (`keepPickerOpen`) and `LauncherForwardPolicyStreamTest` (one field, READ only) pin pure policies only; none pins an intake-time open, a registry slot at intake, or an unqualified classic behavior.

### Critical
- None.

### Warning
- None.

### Suggestion
- [tasks.md:113 (Group 6 scheme policy) and decisions.md:126] Both still say a shared `file://` URI or path "can be served to the LAN through `/proxy/<hash>`". Same class as the already-softened Backlog 1 text and decisions item 5 (no exploit-level detail about the released behavior in the public repo); reword to "the old proxy option could expose local paths" without the URL shape. docs/tech.md :2439-2447 and :2482 keep proxy-handler engineering detail (accepted in Cycle 3).
- [tasks.md Group 14 Accept] The API 35 launcher check lives only in Constraints (4); add it to Accept so the test/verify step covers it.
- [tasks.md Group 14 (6) / Group 15] Classic confirm after the old "no receiver ready -> `LOCAL_UID`" fallback: state that the classic dialog lists the effective receiver (this device when the fallback applies), so the user never confirms an empty list that then plays locally.
- Keep the 5.4.x patch-release recommendation (decisions.md item 5) and verify the API 35 behavior on a device during Group 14.

### Verdict: PASS
Zero Critical, zero Warning. All Cycle 2/3 Warnings are resolved in the plan text, the plan is consistent end to end and with the red tests. Groups 13-15 may proceed; re-review at the end of Group 14 with the real manifest diff and glue code.


## Cycle 1 — 2026-10-26
Reviewing: Group 13 (phase 3)

Scope: commits e7172cb3..HEAD (`git log --stat 2743c16d..HEAD`, HEAD ab4eec4a) read in the code: `YaaccUpnpServerContentHttpHandler` (routing, `ContentHolder`), `ProxyShareResponder`, `ShareRegistry`, `ShareConcurrencyLimiter`, `ShareLimits`, `PositionalSource`, `HeldFileSource`, `DescriptorWindow`, `RangeStreamer`, `ShareEntityProducer`, `GuardedEntityProducer`, `HttpLinkTarget`, `ProxyAccessPolicy`, `ShareAddresses`, `ShareAddressResolver`, `RendererIdPolicy`, `RendererStateMap`, `ServerSideSeeking`, `SeekPositionCalculator`, `ShareKeyTracker`, `ShareLifetimeListener`, `PlayerListChangeNotifier`, `AVTransportPlayer`, `UpnpClient`, `Yaacc`, `ShareLogRedactor` and every log call under `yaacc/src/main/java` that can carry a media URI. Checked against Group 12 cycles 1-4 ("Group 13 must" 1-9). Tests: `./gradlew :yaacc:testDebugUnitTest --tests 'de.yaacc.share.*' --tests 'de.yaacc.upnp.server.http.*' --tests 'de.yaacc.player.ShareLifetimeListenerTest'` = 558 tests, 11 failures, all in `ReceiverPickResultTest` (the intentionally red Group 15 skeleton). `git diff 2743c16d~1 -- yaacc/src/main/AndroidManifest.xml` is empty and no build file changed: no new component, permission or dependency. `LOCAL_FILE` has no glue yet (`HeldFileSource.open` has no caller), so the descriptor side was judged as a library.

What holds (file:line):
- Auth chain. `YaaccUpnpServerContentHttpHandler.java:163-170` routes every path whose first segment is exactly `proxy` to `ProxyShareResponder.respond` before the preference gate (`:176`), sizes 1 and 4+ end in 404. Order in `ProxyShareResponder.java:104-139`: `isWellFormedKey` (22 chars of `A-Za-z0-9-_`, `ShareRegistry.java`) -> `lookup` -> `ProxyAccessPolicy.isAllowed` -> `RendererIdPolicy.isValid` -> serving. Malformed, unknown, expired, released-over and wrong-kind keys all take the same `submitConstant(404, NOT_FOUND_BODY)`: constant body, no key/path echo, no early-out difference (an expired entry is removed and closed on the way, which costs the caller microseconds and needs a key already). 403 appears only after a valid key. HEAD takes the same chain (`head` is only a flag for the producer). `Uri.getPathSegments()` percent-decodes once and drops empty segments: `%2F` or `%00` inside a segment fails the key/deviceId charset, `//`, a trailing `/`, `?query` are harmless, `..` is a legal device id (charset has `.`) and only ever becomes a preference suffix, 4+ segments give 404. Case: `/PROXY/` is not routed (falls to the legacy branches, which have no proxy branch left).
- Remote address: only `HttpCoreContext.cast(ctx).getEndpointDetails().getRemoteAddress()` (`ProxyShareResponder.java:356-376`); no header is read (grep `Forwarded|X-Forwarded|Host` in the responder: none). On H2 (`H2ServerBootstrap`, `YaaccUpnpServerService.java:773`) the details still describe the TCP peer. Restriction setting: `getBoolean(key, true)` and `true` on any exception (`:344-350`), `defaultValue="true"` in `preference.xml:139`, no other reader. Both sides are normalized by `ProxyAccessPolicy` (zone ids, mapped IPv4, no `InetAddress.getByName`; `ShareAddressResolver` uses `URL.getHost()` and `getHostAddress()`, nothing is resolved).
- Headers. `Range` is parsed by `ByteRangeResolver` (length-capped, long arithmetic) and sent upstream as `bytes=<n>-<m>` rebuilt from numbers (`HttpRange.toHeaderString`); `Content-Range` comes from longs; no `Content-Disposition`; `Content-Type` comes from the entry MIME. For `HTTP_LINK` that is `MediaMetadataRetriever` container type or `image/*` (`UpnpClient.java:1626-1648`, `MimeType.valueOf` throws on garbage), so no hostile free text reaches a header today.
- `LOCAL_FILE` source. `PositionalSource` (in-flight counter, close deferred to the last reader, new reads refused once closing, close never blocks the caller, close exactly once) + `HeldFileSource` (`dup` then close of the provider's descriptor, `S_ISREG` + `lseek` check, `DescriptorWindow.clampLength` against `fstat`, `pread` at `startOffset+position`, 64 KiB chunks) meet Group 12 must 2 and 3. A failed `open` leaves the `AssetFileDescriptor` with the caller. Response paths never own the descriptor, so no response error path can leak it; the registry closes it outside its lock (`ShareRegistry.closeAll`) on remove, expiry, eviction, `removeAll()` (`Yaacc.exit()`, first statement) and the sweeper (`Yaacc.java`, 60 s, daemon thread, exceptions caught); process death closes it in the kernel.
- Concurrency. `ShareConcurrencyLimiter` is keyed by share key: 4 per share, so a key holder can fill only its own share (503 on the 5th), never the slots of another share. Global bound: 8 shares x 4 responses x 64 KiB (about 2 MiB) plus at most a handful of watchdog-expired but still open sockets per minute, ended by the 5-minute socket timeout. Lease: idempotent release, expiry returns the slot once, re-acquire under the same cap, no lock inversion (`noteProgress` takes only the lease monitor). `submit()` releases the lease if `submitResponse` throws; the upstream producer open failure releases it (`ProxyShareResponder.java:259-270`).
- Registry. 128-bit `SecureRandom` key (`createDefault`), monotonic `System.nanoTime()/1_000_000` clock, 256-key retired window, 8 draws, close-once under the single lock, `evictOldestReleased` only touches released entries, `register` null leaves ownership with the caller, `lookup` never throws for garbage. Players: `ShareLifetimeListener` + `ShareKeyTracker` remember keys before `onDestroy()` clears the list and release a key only when no other tracked player uses it.
- Address policy. Loopback and the phone's own addresses are added only when `LOCAL_UID`/`LocalDummyDevice`/`LocalDevice` is among the chosen receivers (`ShareAddressResolver.java:51-87`, `ShareAddresses`); an empty set denies everything while restricting.
- Device id / seeking. Charset `[A-Za-z0-9:._-]`, 1-200, checked after key and IP. Preference reads are `manage_external_seeking_<id>` (boolean, default false) and `server_position_<id>` (long): the only keys with these prefixes in the app are the per-renderer ones written by `SettingsFragment.java:178` and `AVTransportPlayer`, so a crafted id cannot reach another preference; a type clash is a caught `ClassCastException`. State is created only for an id whose setting is on, 8 per share, LRU, dropped with the share. The ids also pass `RendererIdPolicy` in `AVTransportPlayer.isServerSideSeekingEnabled` before a URL or a preference write is built.
- Settings. `Yaacc.removeLegacyProxyLinks` removes every `proxy_link_*` (also `proxy_link_mime_type*`, same prefix) at start; nothing else reads the prefix; the old `new File(preferenceValue)` proxy branch is gone; `ContentHolder`'s `new File(uri)` branch is unreachable from the responder because `HttpLinkTarget.isFetchable` (`ProxyShareResponder.java:195`) accepts only http/https. All six translations of the three new strings say the right thing (on = restricted).

### Critical
- None.

### Warning
- None.

### Suggestion
Residuals and answers to the questions asked:
- [SSRF exposure, `ProxyShareResponder.respondLink`, `ContentHolder.getEntityProducer` `YaaccUpnpServerContentHttpHandler.java:1128-1237`] An `HTTP_LINK` entry makes the phone fetch whatever http(s) URL was shared, including `127.0.0.1:<port>`, link-local addresses and LAN admin pages, and relay the answer to the chosen receivers (the redirect handling of `HttpURLConnection` stays within http/https, so `file:`/`jar:` are not reachable). The share text comes from any app through the exported launcher (`ShareIntentHandler`, auto-play until Group 15). It is not wider than before: the old feature served the same links (and local paths) to the whole LAN under a guessable `sha256(uri)` key, now it needs a random key, the receiver's address and a live share. Recommendation (Group 14, at link intake, one choke point in `HttpLinkTarget.of`): refuse literal loopback, unspecified, link-local (169.254/16, fe80::/10) and the phone's own addresses, accepting that host names cannot be checked without resolving; private LAN ranges must stay allowed (NAS links). Group 15 shows the cleaned link host in the picker title.
- [Blocking upstream I/O on the HTTP I/O threads, `ProxyShareResponder.java:209-213` and `YaaccUpnpServerContentHttpHandler.java:1039-1040,1137-1138,1237`] HEAD on an `HTTP_LINK` share, and every Range/server-seeking request, runs a blocking upstream HEAD (5 s + 5 s) before and outside the lease; HEAD takes no lease at all; GET runs `init()` (3 attempts x 15 s connect, 60 s read, 1 s sleeps) and each `produceData` does a blocking `read` on an I/O thread. The server has 8 I/O threads (`YaaccUpnpServerService.java:771`) shared with the UPnP endpoints, so a party that passes the key and the IP check can park them (4 slots x 8 shares exceeds 8 threads; HEAD is unbounded). Pre-existing behaviour and gated by key + address (default: only the chosen receivers), so not a Warning; a slow upstream does the same to a legitimate renderer. Fix when the handler is next touched: take the lease before the probe and for HEAD (a separate small cap for HEAD), or move the upstream fetch off the I/O thread.
- [Logs that still carry a media URI] `YaaccUpnpServerProtocolRequestHandler.java:96,103,242` and `upnp/protocol/sync/ReceivingAction.java:121` log full SOAP/GENA bodies and messages at VERBOSE (default level is ERROR). yaacc polls renderers (`RendererStatusMonitor`) and does not subscribe to their events, so a key of this device does not reach these lines today, but a second control point or another yaacc driving this phone's renderer service would. Wrap them in `ShareLogRedactor.redact`. `util/image/ImageDownloader.java:79,130` (DEBUG, album-art URIs only, `e.getMessage()` of a failed fetch is the URL) and `UpnpClient.java:1647` (DEBUG, the ORIGINAL link before the key exists; already listed as the Group 14 TODO) are the other URL logs left. No proxy key or full proxy URL is logged at WARN/ERROR by default anywhere I found (`ImageFragment`, `AvTransportMediaRenderer*`, `UpnpClient`, `AVTransportPlayer`, `PlayableItem`, `HttpRequestSender`, `FileDownloader`, the handler and `ContentHolder` are clean; failure logs print the exception class only).
- [`ShareLogRedactor.java:29`] `"/proxy/[^\\s\"'<>]*"` is case- and encoding-sensitive and over-redacts inside XML by design (an entity-escaped `&lt;/res&gt;` is swallowed up to the next blank or quote, which is safe). It does not cover `/PROXY/` or `%2Fproxy%2F` (a URL inside a query string). Use `(?i)(?:/|%2f)proxy(?:/|%2f)[^\s"'<>]*` and add the two cases to `ShareLogRedactorTest`. JSON/XML/port forms are covered.
- [Unclaimed share, `UpnpClient.java:1670-1710`, `ShareIntentHandler.java:161-173`] A share is registered in `createPlayableItem`, but released only through a player. If no player appears (`waitForPlayerServiceComeUp()` false, no receivers, an exception in `play`) the entry is never released, keeps its slot until `ABSOLUTE_MAX_MILLIS` (6 h) or exit and `evictOldestReleased` cannot reclaim it; 8 such entries make every later share fall back with the "full" toast. For links this is only a slot; for `LOCAL_FILE` it is an open descriptor. Group 14 must: after `register()`, if `initializePlayersWithPlayableItems` returns no player holding the key (or throws), call `registry.remove(key)` at once; and give an unclaimed entry a short claim window (about 2 minutes, `release` if no `markActive` came).
- [Residual, local apps] With the local device among the receivers, loopback and the phone's own addresses pass the IP step, so a hostile app on the phone is stopped only by the 128-bit key. It cannot read logcat, and Media3 does not send `MediaItem.localConfiguration` to other processes, but the proxy URL is in the local player's `MediaItem` and in the item list: do not publish it (no `METADATA_KEY_MEDIA_URI`, no notification extra, no `MediaItem.RequestMetadata` mediaUri) and verify on a device. The same applies to a LAN peer that can query a published renderer's `AVTransportURI`, as already documented in Group 16 must 1.
- [`ShareAddressResolver.java:77`] `RemoteDevice.getDetails().getBaseURL()` (`<URLBase>`) is chosen by the receiver's own description and its host is added to the allowed set. A hostile receiver the user picked could name another address (including this phone's own LAN address, which would admit local apps). It already holds the key. Consider dropping URLBase unless it equals the descriptor host.
- [`UpnpClient.java:1706-1708`] `InterfaceResolutionHelper.getIpAddress` may return null (offline); the proxy URL then reads `http://null:49157/proxy/<key>` and the registered entry is useless. Return null (direct link or error) instead.
- [`ProxyShareResponder.java:172-186`] `new RangeStreamer`/`new ShareEntityProducer` run after `tryAcquire` without a `try`; they cannot throw for a result resolved against the same source, and the watchdog would return the slot after 60 s anyway, but wrap them like `respondLink` does (release the lease on `RuntimeException`).
- [Defence in depth, `ProxyShareResponder.contentTypeOf/mimeTypeOf`] Re-validate the entry MIME at serve time (`SharedContentPolicy.isAcceptableMimeType` or the fixed HLS types, else `application/octet-stream`) so a future caller cannot put free text into `Content-Type`.
- [Out of scope, pre-existing, `YaaccUpnpServerContentHttpHandler.java:245-249`] The legacy 404 page puts the decoded path segment into `text/html` unescaped (reflected HTML on a LAN origin). Escape it when the handler is next touched. Server-wide: `Http1Config` is the default (no `maxLineLength`/`maxHeaderCount`) and the 5-minute socket timeout also applies before a request line arrives; set both when the server is next configured.
- [Tests] `ProxyShareResponderTest` covers only `LOCAL_FILE`; the `HTTP_LINK` branch (range re-serialization, `isFetchable` refusal, HEAD, server-side seeking planning through the responder) has no responder-level test.

**Group 14 must:**
1. Register only after the confirmed pick and always pair `register()` with ownership: if it returns null after one `evictOldestReleased()` retry, or anything throws between `HeldFileSource.open` and `register`, close the source (the registry does not). Remove/release the key at once when no player took it (Suggestion above); never leave a registered `LOCAL_FILE` without a path to `release`.
2. `HeldFileSource.open` and everything that calls the provider (`getType`, `openAssetFileDescriptor`, `fstat`) off the main thread with the timeout already planned; the entry MIME is `SharedContentPolicy.chooseMimeType(...)` only, never a provider string.
3. Link intake: literal loopback / link-local / own-address refusal in `HttpLinkTarget.of`, and the null-IP fix above.
4. The allowed set comes from `ShareAddresses.build(...)` for the receivers chosen at confirm (Group 15 must 4); keep `InetAddress.getByName` out.
5. `createPlayableItem` rewrite: drop the exception object from the duration log (`:1647`), do not log the original link.

**Group 15 must:**
1. Cancel, dismiss, timeout and failed start call `release`/`remove` for the share they created (the player-based release does not cover a pick that never reaches a player).
2. Clearing the selection must not clear the share's allowed set (it was copied at registration); a re-pick of other receivers needs a new share (or an explicit, tested allowed-set update), not a silent widening.
3. Do not put the proxy URL into any `MediaSession`/notification surface of the local player (see residual above).

### Verdict: PASS
Zero Critical, zero Warning. The request chain is in the Group 12 order with constant bodies, endpoint-only remote address, restriction default true on every path, no header injection or path-traversal route, a bounded per-share limiter that cannot starve other shares, close-once non-blocking descriptor handling and a registry/lifetime design without a path that keeps a closed player's share beyond the grace except the unclaimed case (Suggestion, Group 14 must 1). The SSRF exposure is stated and is narrower than the feature it replaces. Group 14 may proceed with the musts above; re-review at the end of Group 14 with the real `LOCAL_FILE` glue and the manifest diff.


## Group 14 — Cycle 1 — 2026-10-25
Reviewing: Group 14 (share intake), commits 8e11433a (14a), 02f47c60 (14b), 2589e3eb and 2c746cc7 (tests); HEAD 5b1c6a01. Read in the code: `LauncherActivity`, `LauncherForwardPolicy`, `ShareIntentHandler`, `BrowserHost`/`BrowserActivity`/`TabBrowserActivity` hooks, `StreamIntake`, `SenderGrantCheck`, `SharedContentPolicy`, `OwnProviders`, `PendingShare`, `DisplayName`, `UnclaimedShares`, `ShareRegistrar`, `ShareProxyUrl`, `IncomingCache`, `IncomingFileNames`, `CopyBudget`, `BudgetedCopier`, `DeleteOnCloseSource`, `BoundedCall`, `LinkTargetPolicy`, `LinkContentType`, `HttpLinkTarget`, `ProviderProbe`, `SharedFileOpener`, `SharedFileService`, `SharedItemFactory`, `ShareAddressResolver`, the `UpnpClient` and `ProxyShareResponder` diffs, the `Yaacc` sweeps and the new strings. Checked against Group 12 cycles 1-4 ("Group 14 must" 1-10) and Group 13 ("Group 14 must" 1-5).

Manifest: `git diff 071fc9d2 -- yaacc/src/main/AndroidManifest.xml` is empty, no build file changed, the working tree is clean. `LauncherActivity` still has only the `text/plain` SEND filter, so a file reaches the launcher only through an explicit intent, and `TabBrowserActivity` (`exported="true"`, no filter) is reachable the same way; `BrowserActivity` is `exported="false"`. Tests: `./gradlew --offline :yaacc:testDebugUnitTest` on `de.yaacc.share.*`, `ShareIntentHandlerFileTest`, `LauncherForwardPolicy*`, `de.yaacc.upnp.server.share.*` = 818 tests, 11 failures, all in `ReceiverPickResultTest` (the intentionally red Group 15 skeleton).

What holds (file:line):
- Launcher forward (`LauncherActivity.java:113-153`). A fresh explicit intent: `ACTION_SEND`, the validated lower-case media type, ONE `ClipData.newRawUri("", uri)` and `FLAG_GRANT_READ_URI_PERMISSION` via `addFlags`; no `setData`, no `EXTRA_STREAM`, no extras, no WRITE/PERSISTABLE/PREFIX flag anywhere in the class (grep). Preconditions in order: SEND + media type, EXTRA_STREAM text equals ClipData item 0 text, read flag in the incoming flags, `SharedContentPolicy` (content:, printable ASCII, no `@`/`%` in the authority, not the file provider), then every provider of this app via `resolveContentProvider` (fail closed on a throw; `OwnProviders.java`), then the API 35 caller check (`SenderGrantCheck`: below 35 allowed, 35+ any non-GRANTED or any `RuntimeException` = no forward). Reads of crafted extras are inside a `RuntimeException` catch with a payload-free log; the incoming intent is never modified; `SecurityException` on a stream forward falls back to a plain launch, any other exception ends in the outer catch; `finish()` in `finally`. Nothing from ClipData items 1..n, `ClipData.Item` intents or `data` reaches the forward; the text path still reads text only. A stream-typed intent that fails any check takes the text path, which needs `text/plain`, so it becomes a plain launch.
- Browser side repeats everything (`ShareIntentHandler.java:270-330`, `StreamIntake.java`): action SEND, exactly one ClipData item, no `data`, read flag in its OWN flags, item 0 only, `SharedContentPolicy`, any-provider-of-this-app check, own-authority rule; `EXTRA_STREAM` is never read. The intent is consumed (`setClipData(null)`, `removeExtra`) before validation, so rotation and recents re-delivery stop at `clipItemCount != 1`. After process death the system re-delivers the original intent and a NEW pending share is made: the replay is a prompt (the pending state is in memory), never a playback.
- Pending share (`PendingShare`): URI text, cleaned name and chosen MIME only; one slot; `offer` while pending is dropped and does not extend the timeout; 128-bit `SecureRandom` token, constant-time compare, token never logged; 2 minute monotonic timeout (a clock that runs backwards counts as expired); `take` once; `confirm` with an empty selection releases it; wrong or stale token does nothing. Nothing is opened, copied or registered before `confirm` (`prepareAndPlayFile` runs only after `take`).
- Provider values. `getType` and the DISPLAY_NAME query run on a `BoundedCall` worker (3 s), never on the main thread; the type goes through `chooseMimeType` (<= 255 chars, audio/video/image, no wildcard or parameters), the name is cut at 1024 chars, only a `FIELD_TYPE_STRING` is read, then `DisplayName.forShare` (control, bidi, zero-width removed, 200 chars, localized fallback). Names, types and URIs are never logged (all log lines carry fixed text or `getClass().getSimpleName()`/an enum name; grep of every `YaaccLogger`/`Toast` in the Group 14 files); toasts are fixed resource strings; no key or proxy URL is logged. DIDL titles are the cleaned name (escaped by the DIDL writer). `ProxyShareResponder.localFileContentType` re-validates the entry MIME at serve time.
- Copy fallback (14b). Names are `share-<32 hex>.bin` from `SecureRandom`, created with `CREATE_NEW` in the private `cacheDir/incoming`, never from provider data (no path traversal); `sweep` deletes only regular files of exactly that shape, `NOFOLLOW_LINKS`, never links or directories, on a daemon thread at `Application.onCreate` (the registry is in memory, so no copy of this process exists yet). Budget = min(512 MiB, 1 GiB minus what is in the directory), declared size above the budget refused before a byte is written, `BudgetedCopier` counts real bytes against the cap and re-reads free space against the 64 MiB reserve before EVERY chunk (a lying `getDeclaredLength` cannot beat it), one copy at a time (`COPY_SLOT`, BUSY otherwise), partial copy deleted on every failure, `DeleteOnCloseSource` closes the delegate then deletes the copy (POSIX unlink-while-open). `afd` is closed in every outcome of `copyAndHold`, including BUSY.
- Descriptor ownership. `openDescriptor` leaves a failed open with nothing open; a late result of a timed-out `BoundedCall` is closed through `disposeLate`; `SharedFileService.prepare` closes the source in `finally` for every path before `register` took it (server not ready, no address, `resolve` throws, registry full after the evict-retry), `registry.remove(key)` closes it after; `playFile` and the player-service wait call `discardShareOf` (remove + `UnclaimedShares.released`) when no player took the share; `UnclaimedShares` is the 2 minute backstop in the sweeper (and the 6 h absolute cap behind it); `Yaacc.exit()` removes all. No path found that keeps a descriptor without a registered owner.
- Link policy (`LinkTargetPolicy`). Host is parsed by hand from the text (no `URL`/`URI`/`InetAddress`, no DNS); authority after the LAST `@`, backslash/`%`/bad port/empty label refused, `localhost` and `*.localhost`, 127/8, 0/8, 169.254/16, `::1`, `::`, fe80::/10, mapped/compatible/NAT64 forms, the phone's own addresses, every non-strict IPv4 spelling (`2130706433`, `0177.0.0.1`, `0x7f.1`, `127.1`), NFKC look-alikes and invisible characters. `http://null:...` is gone (`ShareProxyUrl.build` returns null without an address; checked before anything is registered). The log in `createPlayableItem` no longer carries the exception or the link. No `InetAddress.getByName` in the app code that handles shares (grep).
- Manifest, flags, dependencies: unchanged; no new permission, component or dependency.

### Critical
- None.

### Warning
- [W1: `LauncherActivity.java:138-146` (the only API 35 sender check) vs `TabBrowserActivity` `exported="true"` and `ShareIntentHandler.java:270-330`] The Android 15 "does the real sender hold read access" hardening, which design.md and the Group 12 must-haves rely on to close the MediaStore/SAF confused deputy on 35+, can be skipped by starting `TabBrowserActivity` directly. A hostile app sends an explicit intent to it with `ACTION_SEND`, a stream type, ONE ClipData item naming content that yaacc can read but the sender cannot (MediaStore with `READ_MEDIA_*`, an SAF tree grant held by yaacc) and the read flag. The system skips the sender's own-access check when the target already holds the permission (the residual that makes the launcher check necessary at all); the handler's checks (`StreamIntake`) are syntactic and the launcher is never involved, so on 35+ the share becomes a pending share exactly as on 34. The human gate (picker with the cleaned name) and the IP/expiry limits remain, so this is the accepted residual, but the control that was meant to make it "mitigated on 35+" does not exist on the direct path. Fix (either): on `SDK_INT >= 35`, in the browser activity that receives the intent (not in the handler: it has no `Activity`), if `getInitialCaller()` is not this app (its package differs from `getPackageName()`; when the launcher forwarded it, the initial caller is the launcher and its own check already ran), run `getInitialCaller().checkContentUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` before `handle(...)`, treating `IllegalArgumentException`/`SecurityException` as "ignore", e.g. through a small `SenderGrantCheck`-style supplier passed to `handle`; or accept stream shares in `TabBrowserActivity` only when the initial caller is this app. If neither is wanted, say in design.md, decisions.md and the Group 16 text that the 35+ mitigation covers the launcher path only. Add a test for the direct path with a non-granted caller. Verify on a device (also what `getInitialCaller()` returns when the sender is the system chooser).
- [W2: `ShareIntentHandler.java:96,350` (`PROBE_CALLS`, 2 threads, static), `SharedFileService.java:64,173` (`PROVIDER_CALLS`, 2 threads, static)] A hanging provider locks sharing out for the rest of the process. A Binder call into another process is not interruptible, so `future.cancel(true)` after the 3 s (probe) or 20 s (open) timeout frees the caller but not the pool thread; `BoundedCall` then refuses every later call (`SynchronousQueue`, `AbortPolicy`, returns null) and the intake logs "the provider did not answer in time" for every genuine share. One hostile app needs no user interaction: its own provider blocks in `getType`/`query`, it sends two explicit intents more than 3 s apart (`intakeBusy` spaces them), the launcher check passes on 35 because the sender holds its own URI, both threads are pinned, and all file shares from any app are ignored until the user kills the app. A legitimate cloud provider that hangs does the same without malice, and there is no user feedback. The "at most two threads" bound protects the process but not the feature. Fix: make the call self-recovering, preferably through `ContentResolver.acquireUnstableContentProviderClient(uri)` + `ContentProviderClient.setDetectNotResponding(~10 s)` (the framework kills an unresponsive provider process, the blocked call fails with `DeadObjectException`, an unstable client does not take yaacc down with it) and release the client in `finally`; additionally keep a per-provider-package "has a call outstanding" marker (package from `resolveContentProvider(authority, 0)`, which the intake already calls) and refuse a second call to a package whose earlier call is still stuck, so one package can never take both threads. Test: two blocked calls from one package leave the pool usable for another package.
- [W3: `SharedFileOpener.java:167-210` (`copyAndHold`/`copyInto`), `ShareIntentHandler.java:419,437`] The copy fallback has no watchdog. `COPY_MAX_MILLIS` (5 min) is checked only BETWEEN reads (`BudgetedCopier` asks `cancelled` before each `in.read`), and `copyAndHold` is deliberately not run through `BoundedCall`. A provider whose pipe never produces a byte (or stops after some) blocks `in.read` for ever on the single `fileExecutor` thread while holding `COPY_SLOT`. Consequences: every later probe and every later confirmed share is queued behind it; `intakeBusy` becomes true when the next intent queues its probe and never clears, so all further file shares are dropped with "another one is being checked"; the picker/toast gives the user nothing. It needs one user confirmation of the attacker's pending share (or a legitimate provider that stalls), after that the feature is dead until the process dies. Fix: run the copy on its own worker (not the shared single-thread executor) and arm a watchdog at `COPY_MAX_MILLIS` (and on shutdown) that closes the `AssetFileDescriptor`/input stream from another thread; closing the descriptor wakes a blocked libcore read with an exception, `copyAndHold` then deletes the partial copy and releases `COPY_SLOT` in its `finally`. Also treat a stalled copy as a cancel point for the picker/`shutdown()`. Test with a stream that blocks until closed.

### Suggestion
- [Link policy is first-hop only, `UpnpClient.java:1646-1700`, `YaaccUpnpServerContentHttpHandler.java:1136-1160`] With the proxy on, `LinkTargetPolicy` judges the shared text, but (a) the proxy's upstream fetch uses `HttpURLConnection` with default redirects, (b) `MediaMetadataRetriever.setDataSource(link)` follows redirects inside the platform, and (c) a host name that resolves to loopback or link-local (`localtest.me`, `127.0.0.1.nip.io`, an attacker DNS record, rebinding) is accepted by design ("host names cannot be checked without resolving", Group 13). So `http://public.example/x` answering `302 Location: http://127.0.0.1:<port>/...` passes. The body goes to the receivers the user picked, not back to the sender, so it is a blind GET from yaacc's identity; not wider than before Group 14 and loopback is reachable by any app anyway. `LinkContentType.find` already re-checks every hop, so the fix is cheap and consistent: `setInstanceFollowRedirects(false)` in the two `HttpURLConnection` sites of the handler, follow at most 3 hops by hand and run each through `LinkTargetPolicy.isAllowedTarget`; for names, resolve once on the worker, check the answers and connect to the checked address. State the name/redirect residual in the Group 16 text.
- [Proxy option off: phone-side reads unchecked, `UpnpClient.java:1661,1667,1679-1680`] `LinkTargetPolicy` is applied only when `proxy` is true (`hop -> !proxy || ...`). With the option off the phone still does `readLinkMetadata` (MediaMetadataRetriever, pre-existing) and, new in Group 14, up to four `HEAD` requests (3 hand-followed redirects) to any http(s) target including loopback, link-local and its own address; an app can force the HEAD path because it only runs when the retriever failed. Blind (the result is a media type that goes into the DIDL), and playback itself is then the receiver's fetch, so the exposure is small; still, apply the policy to the metadata reads regardless of the proxy setting (skip the reads and play the link directly without metadata when the target is refused). Neither read has an overall deadline: `setReadTimeout` is per read, so a server that drips bytes can pin the single link executor thread (`pendingShares` caps the queue at 3, after which links are dropped). Add a total deadline (for example 15 s) around both.
- [Pending slot as a nuisance vector, `PendingShare`, `ShareIntentHandler.java:306-314`] Any app that can start the browser can re-occupy the one pending slot after every cancel or timeout, so legitimate shares are dropped (log line only; Group 14 must 2 asked for a short toast) and the user is prompted repeatedly. Accepted trade-off of "never replace"; for Group 15: show the toast, and consider a short cool-down after a user cancel. Also, a pending share survives a rotation/recreation of the activity (the application-level `PendingShare` stays) but the consumed intent is not re-announced, so the share silently blocks new ones until the 2 minute timeout: Group 15 should re-announce an existing pending token (`onPendingShare`) when an activity attaches, or cancel it on `onDestroy` of the host that was told.
- [Served MIME, `ProxyShareResponder.addStreamingHeaders` :328-333] Provider-declared subtypes such as `image/svg+xml` pass `isAcceptableMimeType` and are served with that Content-Type without `X-Content-Type-Options: nosniff` or `Content-Security-Policy: sandbox`. A web browser on the LAN that is given the share URL would execute an SVG's script in the phone server's origin (same origin as its UPnP endpoints, which are LAN-open already). Add both headers on share responses, or map image types to a fixed allowlist.
- [`BoundedCall`/`intakeBusy` scope] `intakeBusy` is an instance field (one per browser activity instance) while the pools are static; two activity instances can each have one intake in flight. Harmless (the pools and the pending slot are global) but make `intakeBusy` static for clarity.
- [To verify on a device with the Group 15 flow] (1) `getInitialCaller()` on API 35 when the sender goes through the system chooser (`startActivityAsCaller`: it must report the app, not the system uid, which would pass every URI check); (2) that `SecurityException` really surfaces from the launcher's `startActivity` for a provider that forbids re-granting; (3) that the proxy URL does not reach the local player's MediaSession/notification surface (Group 13 residual, Group 15 must 3).

### Verdict: FAIL
Zero Critical, three Warnings. The launcher forward, the browser-side revalidation, the pending/token/replay handling, the copy caps and file naming, descriptor ownership on every cancel/timeout/failure path, the log hygiene and the link-literal policy hold. The Warnings are availability and a missing control on a reachable path: W1 the API 35 sender check does not cover the exported `TabBrowserActivity`; W2 a hanging provider permanently exhausts the two-thread provider pools with no user interaction; W3 a stalled copy pins the only file executor and the copy slot with no watchdog. All three are fixable inside Group 14's files (`TabBrowserActivity`/`BrowserActivity` hook or a documented scope change, `BoundedCall`/`ShareIntentHandler`/`SharedFileService`, `SharedFileOpener`) without a manifest change; the SEND filters at the end of Group 15 must not be added before this re-review passes, and Group 15 inherits the pending-share suggestions above.

## Group 14 — Cycle 2 — 2026-10-25
Reviewing: the fix commit e384ef2f (`git diff 9d700c1c..HEAD`, 23 files): `LaunchNonces`, `LauncherActivity`, `Yaacc`, `ShareIntentHandler`, `ProviderCallGate`, `ProviderKeys`, `BoundedCall`, `ShareLimits`, `SharedFileService`, `AbandonableCall`, `WatchedCopy`, `SharedFileOpener`, `ProxyShareResponder`, plus the new tests, decisions.md and design.md. Read in full: `LaunchNonces`, `ProviderCallGate`, `ProviderKeys`, `BoundedCall`, `AbandonableCall`, `WatchedCopy`, the launcher forward and the handler intake. Not re-read, unchanged since cycle 1: the other "What holds" classes (`StreamIntake`, `PendingShare`, `SharedContentPolicy`, `LinkTargetPolicy`, `IncomingCache`, `BudgetedCopier`, ...).

Manifest: `git diff 071fc9d2 -- yaacc/src/main/AndroidManifest.xml` is empty; no build file, permission, component or dependency changed; working tree clean (nothing committed or edited by this review). Tests: `./gradlew --offline :yaacc:testDebugUnitTest --rerun-tasks` = 1288 tests, 11 failures, all `ReceiverPickResultTest` (the intentionally red Group 15 skeleton); `LaunchNoncesTest`, `ShareIntentHandlerFileTest`, `ProviderCallGateTest`, `BoundedCallGateTest`, `AbandonableCallTest`, `WatchedCopyTest`, `ProviderKeysTest`, `ProxyShareResponderTest` all green.

### W1 (API 35 sender check bypass through the exported `TabBrowserActivity`): closed
- Mint and consume. The launcher mints only at the very end of `buildStreamForward` (`LauncherActivity.java:158`), after the policy, own-provider and API 35 checks; a failed check returns null before any mint. `ShareIntentHandler.java:323` spends the token before `StreamIntake` and before any provider call; a missing, forged, wrong, expired or reused token drops the share with a payload-free log line. The text path (`isStreamType` false) needs none and never reads a URI, so the only way to a file share is the stream branch, which is now gated. `BrowserActivity` is not exported; `TabBrowserActivity` is, and is covered by the same handler.
- Unforgeability: 128 bits from `SecureRandom`, constant-time compare over every entry (`MessageDigest.isEqual`), a wrong guess spends nothing, the store never leaves the process (field of `Yaacc`, no persistence, not in a bundle, preference or log). The forward is an explicit intent to our own class (`new Intent(this, target)`), so no other app can receive it. Another app cannot read the extra: recents and `AppTask` expose the task's base intent and component only, never the extras of the top activity, and an intent log does not print extras. Even a leak is worth little: the token is spent by the first `onCreate`/`onNewIntent`, which follows the forward at once, and lives 30 s.
- Replay and lifetime: rotation or recents re-delivery inside the process re-presents a spent token and is dropped (stricter than cycle 1, where it became a prompt). Process death is fail-closed: the store is a field of the new `Application`, so the re-delivered original intent carries a token nobody knows. `consume(intent)` also removes the extra. The same token reaching two handlers: the first wins, the second is dropped; `relaunchIfOtherLayout` forwards a fresh intent without the file, so the share is lost, never duplicated. A clock that runs backwards expires tokens. Mint is bounded at 8 (oldest dropped); a flood of valid launcher intents could evict a token only if 8 forwards were in flight at once, which a 30 s window and an immediate consume do not allow (worst case: a dropped share, no exposure).
- A SecurityException fallback leaves one unused token for at most 30 s; unreachable for other apps. Not unit-testable in the launcher (an Activity); the one-line mint is reviewed.

### W2 (hanging provider pins the static pools): closed for the cycle 1 scenario, one residual below (W4)
- Gate. `BoundedCall.call(key, ...)` takes a permit first; a provider that has a call in flight or a stuck marker is refused without a thread or a provider call. Release is in a `finally` inside the pooled task (any outcome), on pool refusal, and `markStuck` is a no-op after release. A task cancelled before it ran (interrupt right after `submit`) never reaches its `finally`, but `giveUp` marks the permit stuck, so it cannot leak. `tracked` is bounded by `maxTracked` (3) whatever keys arrive; `LaunchNonces` and the permit map are the only new maps and both are bounded. Only the keyed overload is used on the two gated pools (grep).
- Keys cannot be spoofed or collided: the authority comes from `SharedContentPolicy.authorityOf`, which rejects `@` and `%` (no user-id prefix variants), the key is `pkg:<package>` from `resolveContentProvider` or `auth:<lower-case authority>`, the prefixes cannot collide, and an app with several authorities is one key when the package resolves. Calling a legitimate provider's authority only holds that provider's permit for the length of an instant call.
- Several packages: three distinct stuck packages fill the three places and refuse everybody else until one returns or expires. That is the documented residual and needs three installed hostile apps; accepted.
- `setDetectNotResponding` not used (hidden API, verified by the implementer); acceptable, left to a device check.

### W3 (copy fallback without a watchdog): closed
- `WatchedCopy` takes the slot with `tryAcquire`, creates the file BEFORE the worker starts (random `CREATE_NEW` name, so no later copy can collide with a late worker's path), runs the copy on a fresh daemon thread, and at the deadline the CALLER closes the input (`onAbandon`, `DescriptorInput.close` closes the stream and the afd; neither blocks), deletes the partial file and releases the slot in its `finally`, returning `TIMED_OUT`; the single `fileExecutor` thread, `intakeBusy` and the slot all come free even when the worker never wakes. Every other path (budget <= 0, create failure, `Failure` from the worker, cap refusal = `RejectedExecutionException` -> `BUSY`, interrupt) goes through the same `finally`: input closed, file deleted (target is null only after success), slot released. Because `target` is set to null only after `call` returned, a worker result that loses the race to `abandon()` is closed by `disposeLate` (closing deletes the file again, harmless); a worker that wins the race returns a normal result.
- Late worker: it opens the file with `WRITE` only (no create), so a deleted file cannot come back; the copy loop polls `abandoned` and the deadline between reads; `holder.hold` on a deleted file fails; the worker registers nothing. `abandon()`/`end()` keep `abandonedAlive` consistent under the run's lock (also for a worker killed by an `Error`). Threads: at most one running + 3 abandoned (`COPY_MAX_STUCK_WORKERS`), enforced under the slot, so there is no check-then-start race.
- The design does not depend on "closing wakes the read" (it is not device-verified); the abandon path is the guarantee.
- CopyBudget needs no release (recomputed from the directory), but see S1: bytes already written to a deleted-but-open file are not seen by it.

### "What holds" from cycle 1: re-checked, no regression
Launcher forward shape (one ClipData item, read flag only, no extras apart from the token), browser-side revalidation, pending/token/timeout handling, provider-value cleaning, log hygiene (the new log lines carry fixed text; no nonce, key, package or URI is logged), copy caps and naming, descriptor ownership, link literal policy: unchanged code, tests green. New: `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox` on every share response (`ProxyShareResponder.submit`), with a test.

### Critical
- None.

### Warning
- [W4: `ProviderCallGate.java:128-135` (`purgeExpired`), `ShareLimits.java:101-113`, decisions.md "W2"] The stuck marker expires after 10 minutes, but the thread it stands for stays pinned (a Binder call cannot be interrupted). After expiry the gate forgets the pinned thread, admits the same provider again, and its next hanging call pins another thread of the pool. One installed app with one hanging provider and no user interaction can therefore pin all four threads of the intake probe pool with four launcher intents at least 10 minutes apart (launcher check passes on its own URI, `intakeBusy` is free after 3 s); after that `pool.submit` is rejected for every provider, `BoundedCall` returns null, and every file share from every app is dropped ("did not answer in time or is still busy") until the process dies. That is cycle 1 W2 at a lower rate, and it contradicts the stated invariant in `ShareLimits` ("a thread is always left for a call that has just returned", "pool keeps a spare thread"), which holds only until the first marker expires. decisions.md records the 4-thread outcome as an accepted residual, but it is the same unattended feature lock-out, reachable by a single package, and the fix is a few lines. Fix: never let a marker expire while its call is outstanding, so a provider whose call has not returned is blocked until it returns (the `finally` release already frees it the moment it does): drop the time-based purge for stuck permits (keep `PROVIDER_STUCK_MILLIS` out of the decision), or count physically pinned permits per key and refuse a key that has one. Then one package pins at most one thread, `maxTracked` (3) bounds the total, and the fourth thread stays free for everybody else. Update `ProviderCallGateTest#`/`BoundedCallGateTest` (the "marker expires" tests become "marker stays until the call returns") and add: after N stuck cycles from one key the pool still serves another key, and the late return of a stuck call frees its key. Apply to both gated pools (`ShareIntentHandler.PROBE_CALLS`, `SharedFileService.PROVIDER_CALLS`).

### Suggestion
- [S1: `WatchedCopy.java:141-160,177`] At the deadline the caller closes the INPUT and deletes the file, but the worker still holds its OUTPUT stream to the (now unlinked) file, and a worker that stays stuck in `read` never closes it. The bytes written so far (up to the 512 MiB cap) stay allocated until the process ends, are invisible to `IncomingCache.remainingCopyBudget` (it sums the directory) and to the sweep, and up to three such workers can hold 1.5 GiB. The free-space reserve in `BudgetedCopier` still protects the volume for later copies, and each case needs a user to confirm a hostile share, so this is not a Warning. Cheap fix: in the abandon path, truncate the file to length 0 before `delete` (a `FileChannel.truncate(0)` / `RandomAccessFile.setLength(0)` releases the blocks even while another descriptor is open), or hand the output stream to `onAbandon` so the caller closes it too. Correct the decisions.md line "CopyBudget needs no extra release".
- [S2: `AbandonableCall`/`WatchedCopy` cap] Three stuck copy workers (three user-confirmed hostile shares) turn every later copy fallback into `BUSY` until the process dies, and the user sees only the generic open-failed toast. Fine for now; for Group 15 consider a distinct message and a log counter, and a device check of whether closing a `ParcelFileDescriptor`-backed stream really wakes a blocked read (if it does not, S1 and this cap are the only protection).
- [Device checks, unchanged from cycle 1, still for Group 15] `getInitialCaller()` when the sender is the system chooser; `SecurityException` from the launcher forward for a provider that forbids re-granting; `resolveContentProvider` visibility for a freshly granted URI under package visibility (when it returns null the key falls back to the authority, and an app with several authorities then holds several places).
- [Cycle 1 Suggestions deferred by decisions.md] Redirect handling and the proxy-off link policy (Backlog/Group 16), the pending-slot nuisance and `intakeBusy` scope (Group 15): agreed; the Group 15 SEND filters should still wait for the W4 fix.

### Verdict: FAIL
Zero Critical, one Warning. W1 and W3 are closed and I found no regression in the cycle 1 "What holds" items. W2 is closed for the cycle 1 attack (two hanging intents), but the 10-minute stuck-marker expiry lets one package pin the whole probe pool within about 40 minutes without user interaction (W4); removing the expiry (a marker lives until its call returns) fixes it inside `ProviderCallGate` and its tests, no manifest change.

## Group 14 — Cycle 3 — 2026-10-25
Reviewing: the fix commit 7713389c (`git diff 94961ca7..HEAD`, 9 files): `ProviderCallGate`, `BoundedCall`, `ShareLimits`, `WatchedCopy`, the tests and decisions.md. Read in full: `ProviderCallGate`, `BoundedCall`, `AbandonableCall`, `WatchedCopy`, `ProviderKeys`, the two call sites (`ShareIntentHandler.java:382`, `SharedFileService.java:183`; only the keyed overload is used on the gated pools).

Manifest: `git diff 071fc9d2 -- yaacc/src/main/AndroidManifest.xml` is empty; working tree clean (nothing edited or committed by this review). Tests: `./gradlew --offline :yaacc:testDebugUnitTest --rerun-tasks` over `de.yaacc.share.*`, `ShareIntentHandlerFileTest`, `upnp.server.share.*` = 851 tests, 11 failures, all `ReceiverPickResultTest` (the intentionally red Group 15 skeleton); `BoundedCallTest`, `BoundedCallGateTest`, `ProviderCallGateTest`, `AbandonableCallTest`, `WatchedCopyTest` green; six further reruns of those five classes were all green (no flake seen).

### W4 (stuck marker expiry): closed
- One key, one place, one thread. `tryAcquire` refuses a key already in `tracked`; there is no clock and no purge, so a permit leaves the map only through `release`. A provider whose call has not returned (hung Binder call) therefore cannot be admitted again and cannot pin a second thread. `release` is identity-checked (`tracked.get(key) == permit`), so a late or double release cannot free a newer permit of the same key (test `theLateReturnOfAnOldPermitDoesNotFreeANewerOne...`).
- Bound. `tracked` <= `maxTracked` (3) whatever keys arrive; threads inside provider calls <= tracked <= 3 of the 4 pool threads. The fourth thread is free for a provider that is not stuck, apart from the microseconds between a permit's release (in the task's `finally`) and the worker becoming idle (see flake below).
- Every exit of `BoundedCall.run` was walked:
  - gate refuses: returns null, no permit, no thread.
  - `RejectedExecutionException` from `pool.submit` (AbortPolicy, nothing queued): permit released, null.
  - task exception / `RuntimeException` / `Error` from `task.call()`: the `finally` around `task.call()` releases; the exception becomes `ExecutionException` and a null result. `disposeLate` throwing cannot leak (release already happened).
  - timeout after start: `giveUp` CAS NEW->CANCELLED fails (state is STARTED), the permit stays, and the task's own `finally` releases it when the call returns. Intended: the thread is still occupied.
  - timeout or caller interrupt before the task began: `giveUp` CAS NEW->CANCELLED succeeds, `future.cancel(true)`, permit released by the caller. A pool thread that still picks the wrapper up sees the CAS fail and returns without running the task or touching the permit. The interrupt flag is restored.
  - races: the transitions NEW->STARTED (worker) and NEW->CANCELLED (caller) are one `compareAndSet` on the same `AtomicInteger`, so exactly one wins. Worker wins: it owns the release in its `finally` (no code between the CAS and the `try` can throw), and the caller's `giveUp` does not release. Caller wins: the caller releases once and the worker never runs the task. No interleaving gives zero or two owners. `future.cancel(true)` after STARTED only interrupts the worker; the `finally` still runs.
  - interrupt that arrives before `submit` returns or while a result is already present: `future.get` throws, `giveUp` follows the same rules, `takeAndAbandon` returns the result if it was delivered (no leak: the result is the caller's) or marks the handoff abandoned so a late result goes to `disposeLate`.
- No new permanent lock-out beyond the accepted residual. A provider that hangs once and then returns is freed at that moment by the `finally`; a provider that never returns holds exactly one place for the life of the process. The lock-out of the pool needs three distinct hung packages (or hung keys, see S3), as accepted. Dropping the expiry removes the cycle 2 attack (one package, four intents 10 minutes apart). `PROVIDER_STUCK_MILLIS` is gone; no leftover reference (grep).

### S1 (output stream of an abandoned copy worker): closed
- Order. `AbandonableCall.abandon()` sets `abandoned` under the run lock BEFORE `onAbandon` runs on the caller. The worker does `output.set(out)` (volatile) and only then reads `abandoned` (lock). Either the worker's read sees true (the caller had already abandoned): it throws `Failure(TIMED_OUT)` and the try-with-resources closes `out`; or it saw false, which orders `set(out)` before the caller's `abandon()` and so before the caller's `output.get()`: the caller sees the stream and closes it. A worker that opens its stream after the caller's `get()` returned null always lands in the first case. No window leaves an unseen open stream.
- Double close: the caller's close and the worker's try-with-resources close are the same idempotent `Channels`-style stream close; `closeQuietly` swallows `IOException` and `RuntimeException`. A worker that writes after the caller closed gets an exception and ends (counted out by `end()`).
- No file left behind: the caller closes input and output first, then the `finally` deletes the file (`target` is non-null on every non-success path), so the blocks are released even when the worker stays stuck in a read. A worker that opens later has `WRITE` without `CREATE`, so it fails on the deleted file and cannot bring it back; one that opened in the window before the delete is closed by itself via the `abandoned` check. A result that wins the race is the caller's (`done` is true, `abandon()` returns false); one that loses it goes through `disposeLate` (closes the source, which deletes the file again).
- The caller's close of the output cannot block on the stuck read (the stuck call is on the provider's pipe, not on the local write). The test `aStuckWorkerDoesNotKeepTheDeletedPartialFileOpen` covers it.

### Flake `BoundedCallTest.aFinishedCallFreesItsThread`: test timing, not a defect in the new state handling
The test uses the UNKEYED `call(task, ...)` on a 1-thread pool (`gate` null, `permit` null, so the NEW/STARTED/CANCELLED path only toggles a state that nothing reads), and the test file is unchanged since 02f47c60. `future.get` returns as soon as the `FutureTask` has its result, which happens before the worker thread has gone back to `SynchronousQueue.poll`. A second `submit` right after that finds the one thread not yet idle and the pool at max, so `AbortPolicy` refuses it. This race exists in the pre-fix `BoundedCall` too. Fix the test (poll/retry the second call for a short time, or give the pool 2 threads and assert a thread is reusable); do not change `BoundedCall`.
Production relevance: the keyed path releases the permit inside the task, slightly before the worker is idle, so a call is refused only if more calls than pool threads are in flight or finishing at the same instant. With 3 places and 4 threads that needs at least two calls finishing together while two providers are hung; the result is a transient null (one share shown as "busy"), fail-closed, no exposure, no permanent state. Not a finding.

### "What holds" from cycles 1 and 2: re-checked, no regression
Launch nonces (mint at the end of `buildStreamForward`, spent before any provider call, 128 bit `SecureRandom`, constant-time compare), launcher forward shape and API 35 sender check, browser-side revalidation, pending/token/timeout handling, provider-value cleaning, key derivation (`ProviderKeys`), log hygiene, copy caps and naming, descriptor ownership, close-once handling, response headers: all unchanged in this commit (diff limited to the 9 files above), tests green. WatchedCopy: slot, create-before-start, deadline abandon, `COPY_MAX_STUCK_WORKERS`, `disposeLate` and the no-create late worker are unchanged apart from the extra output close.

### Critical
- None.

### Warning
- None.

### Suggestion
- [S3: `ProviderKeys.java:44-50`, manifest has no `<queries>`] When `resolveContentProvider` returns null (package visibility on API 30+ if a forwarded grant does not make the owner visible; not verified) the key is `auth:<authority>`. Before this commit that cost a temporary lock-out; now a hung call holds its place for good, so ONE hostile app that declares three authorities could use up the three places by itself in that case. Needs the visibility failure, a real hanging provider and three confirmed-free launcher intents; not a Warning because the platform behavior is unverified and the effect is a share feature outage until the process is restarted. Cheap hardening: when the package cannot be resolved use ONE shared key (for example `unresolved`) instead of per authority, or the sender package from the launcher, and verify on a device that `resolveContentProvider` sees the provider of a freshly forwarded URI. The same applies if the resolution flips between `pkg:` and `auth:` for one app (two places for one app).
- [S4: `BoundedCallTest.aFinishedCallFreesItsThread`] Make the test tolerant of the worker not being idle yet (see above).
- [S5: decisions.md] Update the W2/W4 text: the residual is "three distinct hung providers, or one app whose provider cannot be resolved to a package (S3)"; no expiry. Group 15: a distinct toast/log counter when `BoundedCall` returns null because the gate is full, so a permanently stuck provider is visible.
- [Device checks, still for Group 15] `getInitialCaller()` for the system chooser; `SecurityException` from the launcher forward; whether closing a `ParcelFileDescriptor`-backed stream wakes a blocked read (the abandon path does not depend on it).

### Verdict: PASS
Zero Critical, zero Warning. W4 is closed: a permit lives exactly as long as its call, one key never holds more than one place or thread, at most 3 of the 4 pool threads are inside provider calls, and every exit of `BoundedCall.run` releases the permit exactly once (including pool refusal, caller interrupt, timeout before and after start and task exceptions). S1 is closed race-free. The flake is a pre-existing test timing issue. Group 15 may add the SEND filters.

## Group 15 — Cycle 1 — 2026-10-26
Reviewing: Group 15 (15a `93f5dff0`, 15b `0553dfe0`, SEND filters `868f7e5a`, fixes `a54ee1d5`), `git diff 464b7518..HEAD -- yaacc/src` (58 files), HEAD `8abcb618`. Read in the code: `AndroidManifest.xml`, `LauncherActivity`, `LauncherForwardPolicy`, `LaunchNonces`, `StreamIntake`, `SenderGrantCheck`, `SharedContentPolicy`, `ProviderProbe`, `ShareIntentHandler`, `PendingShare`, `PendingPick`, `ActionFlight`, `GuardedPickedAction`, `PickSheetResult`, `ReceiverPickController`, `DeviceSheetFragment`, `DeviceSheetAdapter`, `ContentListFragment`, `BrowserActivity`/`TabBrowserActivity`/`BrowserHost`, `BrowserLauncher`, `SharedFileService`, `ShareAddressResolver`, `ShareAddresses`, `ProxyAccessPolicy`, `ProviderKeys`, `BoundedCall`, `UpnpClient` diff, `MultiContentPlayer`, `PlayerService`, `DisplayName`, the picker layouts and the new strings in 7 locales.

Manifest: `git diff 071fc9d2 -- yaacc/src/main/AndroidManifest.xml` is exactly one added `<intent-filter>` (lines 68-74): `ACTION_SEND`, `DEFAULT`, `audio/*`, `video/*`, `image/*`, on `LauncherActivity`. No `SEND_MULTIPLE`, no `BROWSABLE`, no scheme, no new component, no `exported` change, no permission; `TabBrowserActivity` still exported without filter, `BrowserActivity` still `exported="false"`. Build files unchanged. Working tree clean (nothing edited or committed by this review). Tests: `./gradlew --offline :yaacc:cleanTestDebugUnitTest :yaacc:testDebugUnitTest` = 1453 tests, 0 failures, 0 errors, 0 skipped (119 classes), which includes all `de.yaacc.share.*`, `ShareIntentHandler*`, `LauncherForwardPolicy*`, `de.yaacc.browser.*` and `de.yaacc.upnp.server.share.*` tests.

### What the SEND filters change (re-evaluation of the Group 14 intake chain, now reachable by the chooser)
The launcher was already exported, so any app could already send it any intent with an explicit component. The filter adds nothing an attacker could not do before; it makes the app a visible target for ordinary shares. Every gate was re-walked on the real code and nothing regressed (the files `LauncherActivity`, `LauncherForwardPolicy`, `LaunchNonces`, `StreamIntake`, `SenderGrantCheck`, `SharedContentPolicy`, `ProviderProbe`, `ProxyShareResponder` are unchanged since the Group 14 PASS):
- Launcher forward: only `ACTION_SEND` + a media type in exact lower case (`audio/*`, `video/*`, `image/*` or a concrete `audio|video|image/<token>`); EXTRA_STREAM string must equal ClipData item 0; the incoming flags must carry the read grant; `content:` of another app only (`SharedContentPolicy.isAcceptableUri`, ASCII, no `@`/`%` in the authority, cap 2048); any provider of this app refused (`OwnProviders.isOwnAuthority`, so a chooser re-share of yaacc's own `ShareFileTask` output is dropped silently); API 35+ sender check, failing closed; the forward is a fresh intent with one ClipData item, the read flag only, and one single-use nonce; `SecurityException` falls back to a plain launch.
- `*/*` and other senders: the platform matches an intent type of `*/*` against any filter that has a data type, so yaacc will appear in the chooser for generic file shares. `isMediaType("*/*")` is false and the text path needs `text/plain`, so the launch is a plain one and the share is dropped (UX only, see S8).
- `ACTION_SEND_MULTIPLE`: not declared, and not handled anywhere: `LauncherForwardPolicy.decideStream`/`decide`, `StreamIntake.validate` and `ShareIntentHandler.handleSharedFile` all compare the action to exactly `ACTION_SEND` (an explicit SEND_MULTIPLE to the launcher is a plain launch; sent straight to the exported `TabBrowserActivity` it returns before any provider call). Only ClipData item 0 is ever read; a sender with five items gets one file considered, never five. `getReferrer`/`getParcelableExtra(EXTRA_STREAM)` are used nowhere else (grep).
- MIME mismatch: sender says `audio/*`, provider says `text/html` or `application/...`: `chooseMimeType(providerType, "audio/*")` rejects the provider type, then the wildcard intent type (`isAcceptableMimeType` needs an alphanumeric first subtype char) and returns null, so the share is ignored with a payload-free log. Provider `audio/x`, intent `video/*`: the provider wins. Provider `application/octet-stream` with a concrete sender type (`image/png`) is accepted on the sender's word (documented fallback; the proxy answers with `nosniff` + `Content-Security-Policy: sandbox`, `svg` included). The bytes can be anything the sender can name; the gates are the pick and the IP/expiry limits.
- ClipData vs EXTRA_STREAM mismatch, data URI present, no read flag, zero or two ClipData items, direct start of `TabBrowserActivity` without the nonce: all dropped (`StreamIntake`, `LaunchNonces.consume`), tests green.
- Pending slot: one `PendingShare` process-wide, a second offer is dropped (never replaces), 2-minute expiry, 128-bit token, `take` once; the picker is bound to a random UUID pick id and a second `PendingPick` never replaces an open one. A hostile app in the foreground can re-open the picker after every cancel (S5); it cannot swap the file the user is looking at, cannot confirm for the user and cannot keep a stale slot beyond 2 minutes.

### What holds for Group 15 (file:line)
1. Result delivery. `DeviceSheetFragment.java:316-348`: one answer per sheet (`PickSheetResult` gate: confirm with ids, or cancel from `onDismiss`, never both), the bundle carries the pick id, the confirmed flag and receiver ids only. `ReceiverPickController.java:176-195` hands it to `PendingPick.resolve`, which accepts only the open pick's id; stale, repeated or forged ids are `IGNORED`; state is cleared before callbacks run; a throwing action cannot leave the pick open. Fragment results are in-process: `FragmentManager.setFragmentResult` needs the activity's `FragmentManager`, no exported component exposes one (`LauncherActivity`, `TabBrowserActivity` and the other exported activities never take a result key or pick id from an intent), and a pending result that is saved with the instance state holds only the random pick id, a boolean and device ids. A restored sheet of a lost pick closes silently (`DeviceSheetFragment.java:392-400`, `isPickOpen`).
2. State machine. `ActionFlight` begins only at a confirm, ends through `done` (idempotent, a ticket only ends its own flight), and expires after `COPY_MAX_MILLIS + 60 s` = 6 minutes, so a lost callback cannot block picking for good. `GuardedPickedAction` ends the flight when the action throws. Every exit of `ShareIntentHandler.confirm` runs `finished` once (`once()`): wrong token, empty selection, shut-down handler, failed preparation, played, failed. A confirm that meets an active flight is refused with `onCancelled` (pending share released). Tests (`ActionFlightTest`, `PendingPickTest`, `ShareIntentHandlerFileTest`, `ShareIntentHandlerLinkPickTest`, `ContentListFragmentPickedSelectionTest`) green.
3. Allowed IP set. File: `SharedFileService.java:137-139` builds `ShareAddressResolver.resolve(upnpClient, appContext, receiverIds)` from the ids captured at confirm. Link: `ShareIntentHandler.java:336` -> `UpnpClient.createPlayableItem(Uri, Set)` -> `createHttpLinkShare(link, mime, receiverIds)` (`UpnpClient.java:1801`). The two-argument `resolve`/`createPlayableItem(Uri)` overloads are used only by the classic link path. `ShareRegistry.Entry` copies the set into an unmodifiable set, so `clearReceiverDevices()`, a restore or any later `setReceiverDeviceIds` cannot widen or change an existing share. Loopback and the phone's own addresses are added only when this device is a chosen receiver (`ShareAddresses`). "Restrict the media proxy to the chosen receivers" defaults to true in `preference.xml:139-143`, in `ProxyShareResponder.restrictToReceivers` (also on read failure). No `InetAddress.getByName` (grep).
4. Logs and toasts. Every new `YaaccLogger` call in the diff carries a fixed text, an enum name or `getClass().getSimpleName()`; no URI, name, key or proxy URL. Toasts are fixed resource strings only (`receiver_pick_busy`, `share_busy`, `share_link_failed`, `share_open_failed`). `ShareLogRedactor` is still applied at the existing sinks.
5. Memory-only state. `PendingShare`, `LaunchNonces`, `ShareRegistry`, `ActionFlight`, `PendingPick` live in memory (Application or activity). No new `SharedPreferences` key for a share (the only writes are the receiver ids). `TabBrowserActivity.onSaveInstanceState` saves the tab index only. The picker's fragment arguments hold the request key, the random pick id, a boolean, the cleaned share name and receiver ids (`DeviceSheetFragment.java:211-218`), never the URI, token or key. A rotation or process death closes the picker and releases the share; the intent copy that the system keeps is useless without a live nonce.
6. Format strings. The only parameterised string is `receiver_pick_confirm_description` (`%1$s`), filled with the fixed OK text in all 7 locales; no name, link or LAN text is ever a format argument or format string. Names reach the screen only through `DisplayName`/`MiniPlayerPresenter.cleanText` and `setText` (plain, `maxLines=2`, `ellipsize=middle`, no `autoLink`/`Linkify`).
7. Notification/MediaSession surfaces: re-checked `AbstractPlayer`, `LocalMediaSessionPlayer` (only `MediaItem.setUri`) and the Group 15b bytecode note in decisions.md; no proxy URL in titles, text or metadata. Not observed on a device.
8. Links in the new layout (`BrowserActivity`): a link becomes the single `PendingShare` (kind `LINK`), is named by a fixed text, is built and played only after a confirm with explicit ids, never falls back to `LOCAL_UID`, and a refused link or a start without a player gives the share back (`discardShareOf`).

### Critical
- None.

### Warning
- [W1: `ShareIntentHandler.java:228` (`pickHost.providesReceiverBar()`), `:234-266` and `:732-743` (classic link path, `LOCAL_UID` fallback), `TabBrowserActivity.java:139,147`, `:241`, `BrowserHost.java:58`, `AndroidManifest.xml` `TabBrowserActivity exported="true"`] **The new-layout pick for shared links can be bypassed by any app, and the new layout then falls back to this device.** `TabBrowserActivity` is exported without a filter and calls `ShareIntentHandler.handle(getIntent(), ..., this)` in `onCreate`/`onNewIntent` BEFORE `onResume` decides to relaunch for the other layout. Its host reports `providesReceiverBar() == false`, so `handle` takes the classic link branch whatever layout the user has chosen. An app that sends an explicit `ACTION_SEND` `text/plain` intent with an http(s) link straight to `TabBrowserActivity` (no permission, no nonce is needed for text) while the user is on the NEW layout therefore gets: no picker, no confirmation, `getReceiverDevicesReadyCount() == 0` (the new layout's stored selection is empty by design), `setReceiverDeviceIds({LOCAL_UID})`, a proxied share built for this device's addresses, and playback on this phone (the queued share still runs after the relaunch, because `shutdown()` is graceful). That contradicts the Group 15 contract and design decision 4 ("a share is never auto-played ... in the new layout never falls back to `LOCAL_UID`", `decisions.md` "Links in the new layout also go through the picker"), and it changes the stored receiver selection of a user who is supposed to have none. The effect is the same as the pre-existing classic link auto-play (and no worse than before Group 15), but Group 15 is the group that claims to close it for the new layout, and the files path is protected only because it needs the launcher's nonce. Fix (cheap): in `TabBrowserActivity.onCreate`/`onNewIntent`, run the layout check first and do not call `handle` (or drop the text) when `BrowserLauncher.effectiveLayout(this) != CLASSIC`, i.e. let the activity that is going to be relaunched ignore the payload, as the comment at `:238-240` intends; or have the launcher mint the nonce for links as well and make the classic and new handlers require it for text from the exported `TabBrowserActivity`. Add a JVM test of the decision (`BrowserLayoutSwitch`-style pure method: "should this activity hand its intent to the share handler?") with layout NEW and own = CLASSIC.

### Suggestion
- [S1: `ProviderKeys.UNRESOLVED`, `ProviderKeys.java`, device checks in decisions.md items 7 and "Group 15b on-device checks"] Ship checklist for the owner, before the filters reach users: (a) on Android 11+ with a real chooser share check that `resolveContentProvider` returns the provider of the shared URI (the manifest has no `<queries>`); if it does not, every provider shares the single `unresolved` key and ONE provider that never answers a `getType`/`query` call holds the only place for the life of the process, so the share intake of the whole app is then out until the process is restarted (no user interaction is needed for that probe; it needs a hostile app in the foreground; the rest of the app keeps working). If the check fails, key on the sender package (`getInitialCaller().getPackageName()` on 35+, `getReferrer()` below, untrusted but only for gating) or add a narrow `<queries>`; (b) on API 35+ what `getInitialCaller()` returns for the system chooser (it launches with the caller's identity; a check that always succeeds falls back to the documented pre-35 residual, one that throws means shares are silently ignored, both fail closed); (c) the `SecurityException` fallback; (d) whether closing a provider stream wakes a blocked read (only affects how fast an abandoned copy frees its thread).
- [S2: `MultiContentPlayer.java:89-94`, `PlayerService.java:432-449`] Local video share = `ACTION_VIEW` with `data = http://<lan-ip>:<port>/<proxy>/<key>` and the video type, `NEW_TASK`, to whichever app resolves it. Assessment: the URL is handed to ONE component (implicit activity intent, not a broadcast); the framework logs it with the path elided (`Uri.toSafeString`); it exists only when the user ticked "this device" in the picker, which is also what adds loopback and the phone's own addresses to the allowed set; the handler is the app the user (or the chooser) picked to play that very file, so it already receives the bytes. What it additionally gets is a bearer URL that lives until the player ends plus the grace period or the absolute maximum: a hostile or compromised video handler can re-fetch the same file later, or keep the URL, but it cannot reach any other share or content (key per share, IP set, 404/403 otherwise), and a remote party cannot reach the LAN address. Judged acceptable for a user-chosen local playback; not a Warning. Recommendations: state it in the Group 16 threat model; consider an in-app player for local video (no key leaves the app) as the real fix; do not let the restriction setting default change. The MediaSession/notification surfaces of audio and image (Media3, `LocalImagePlayer` explicit intent) do not carry the URL.
- [S3: `TabBrowserActivity.java:210`, `ReceiverPickController.java:140-166`] What the user can be tricked into confirming: in the classic layout a shared file opens the picker with the classic selection already ticked (default `{LOCAL_UID}` if the user never chose), so one tap on OK plays the sender's file on the user's usual receivers; in the new layout nothing is ticked, so two deliberate taps are needed. The picker shows the cleaned file name and nothing about the sender (no app name; the referrer is unverifiable) and no type, so a file can be named "Shared link" or "IMG_0001.jpg" regardless of what it is. Worst realistic outcome: unwanted audio/video/image on the user's own receivers or this phone, and a file the sender could already read being served for up to the share lifetime to those receivers' addresses. Cheap hardening: do not pre-tick in classic for a share (or pre-tick only when exactly one receiver is selected), show the MIME family ("audio", "video", "image") next to the name, and set `setFilterTouchesWhenObscured(true)` on OK/Cancel of the pick sheet.
- [S4: `DisplayName.java` `isBidiOrZeroWidth`] The cleaner removes line breaks, controls, the bidi controls and the common zero-width characters but not the rest of Unicode category Cf (soft hyphen U+00AD, U+180E, U+2061-2064, U+206A-206F, tag characters U+E0000-E007F) nor the blank letters (Hangul fillers U+115F/U+1160/U+3164/U+FFA0) or U+2800. A name made of such characters is non-empty after cleaning, so the picker's fixed fallback ("Shared file") is not used and the line under "Play on..." looks empty. Cosmetic spoofing only; strip Cf generally (keep U+200D and variation selectors for emoji) and treat a name with no visible character as empty. Add the cases to `DisplayNameTest`.
- [S5: decisions.md "Group 14 carry-overs" 3, `ShareIntentHandler.java:490-498`, `ReceiverPickController.java:114-118`] No cooldown after a user cancel: a foreground hostile app can re-open the picker (and the "busy" toasts, `Toast.LENGTH_LONG`/`SHORT` fixed texts) as often as it can start the launcher. It cannot confirm for the user, cannot replace the file shown and its slot ends after 2 minutes; Android's background-start limits keep it to the foreground. Accepted by the owner as Backlog 1; consider a simple "at most N prompts per minute, then drop silently" counter in `PendingShare` since the filters make the picker reachable by the chooser. The dropped-share toast is also user-visible to the legitimate sender case; keep it fixed text.
- [S6: `ActionFlight.java`, `ShareLimits.ACTION_FLIGHT_MAX_MILLIS`] The single flight gives the "stuck flight" a 6-minute ceiling. A user who confirmed a share whose provider hands out a non-seekable, trickling stream keeps the picker refused ("Still busy...") for up to the 5-minute copy watchdog (+1 minute), and a slow or hostile LAN content server that stalls the re-browse of play-all does the same for its timeout. Bounded, needs the user's confirm, fail closed, no data exposure. A cancel for the running action (or a shorter "no bytes for N seconds" stall limit, already listed in decisions.md W3 "Not done") would remove the lockout.
- [S7: `DeviceSheetFragment.java:211-218`] The cleaned file name of a pending share is part of the picker's fragment arguments and so of the saved instance state kept by the system for a recreated activity. It is not a URI, token or key and it is cleaned, but it is the one share-derived string that outlives the in-memory pending state; the restored sheet closes itself at once. If a share name is considered private, pass it through the host (`BrowserHost`) at show time instead of storing it in the arguments.
- [S8: UX, not security] `*/*` shares (many file managers) list yaacc as a target and end in a plain launch; a share without the read flag or with EXTRA_STREAM only (older senders) is dropped silently; a `Provider` type that is not audio/video/image is dropped silently. A fixed "this item cannot be played" toast at those drop points would help users and make the dropped-share paths observable without logging payloads.
- [S9: pre-existing, outside this diff, carried] Classic link auto-play and the `TabBrowserActivity` direct path are unchanged and owner-accepted for classic (W1 above is the new-layout inconsistency only); the picker shows a fixed "Shared link" (the Group 13 recommendation to show the cleaned host is recorded as a later improvement); redirect handling and the link policy with the proxy option off stay with Group 16.

### Verdict: FAIL
Zero Critical, one Warning (W1). The two items that the task asked to be judged explicitly:
- **Do the SEND filters (`audio/*`, `video/*`, `image/*` on `LauncherActivity`, manifest lines 68-74) introduce a vulnerability? No.** They add no capability that an explicit intent did not already have, all Group 14 gates (launcher forward, nonce, `StreamIntake`, `SenderGrantCheck`, single pending slot, copy fallback, `ProviderCallGate`, registry and proxy serving) are intact and unchanged, SEND_MULTIPLE is not declared nor handled, and a MIME or ClipData/EXTRA_STREAM mismatch is rejected. **The filters may ship**; W1 does not depend on them (it is reachable today with an explicit intent) but it is the one open hole in the Group 15 contract, so the recommended order is: fix W1, then ship the filters together with it, and run the S1 device checklist on a real device (Android 11+ and 15) before release. If the owner prefers to ship the filters first, W1 and S1 must be written down as accepted residuals in decisions.md and the Group 16 text.
- Group 15 itself (pick mode, result delivery, flight, explicit receiver ids, memory-only state, logs, strings) is sound; only W1 keeps the verdict at FAIL.

## Group 15 — Cycle 2 — 2026-10-26
Reviewing: the cycle 1 fix, `git diff 708e47bb..HEAD` (HEAD `921c09dd`, 10 files: `ShareIntakeGate` new, `ShareIntentHandler.discard`, `TabBrowserActivity.handOverShare`, `BrowserActivity.handOverShare`, `DisplayName`, two picker layouts, `ShareIntakeGateTest`, `DisplayNameTest`, `decisions.md`). Read in the code: the gate and both call sites, `ShareIntentHandler` (`handle`, `handleSharedFile`, `consume`, `discard`, `readAndClearSharedText`), `LauncherActivity`, `BrowserLauncher` (`relaunchIfOtherLayout`, `targetClass`), `BrowserLayoutSwitch`, `BrowserLayoutChoice.resolve`, `RelaunchGuard`, `LaunchNonces`, `ShareLimits`, `DisplayName`, the manifest, `device_sheet_pick_item.xml`, `fragment_device_sheet_pick.xml`, `DeviceSheetAdapter` (the only inflater of the row layout). Working tree clean; nothing edited or committed by this review.

Manifest: `git diff 071fc9d2 -- yaacc/src/main/AndroidManifest.xml` is still exactly one added `<intent-filter>` (7 added lines): `ACTION_SEND`, `DEFAULT`, `audio/*`, `video/*`, `image/*`, on `LauncherActivity`. `TabBrowserActivity` still exported without a filter, `BrowserActivity` still `exported="false"`; no gradle file changed since cycle 1. Tests: `./gradlew --offline :yaacc:cleanTestDebugUnitTest :yaacc:testDebugUnitTest` = BUILD SUCCESSFUL, 1472 tests in 120 classes, 0 failures, 0 errors, 0 skipped (this includes `ShareIntakeGateTest`, `DisplayNameTest`, `ShareIntentHandlerFileTest`, `ShareIntentHandlerLinkPickTest`, `LauncherForwardPolicy*`, and the other `de.yaacc.share.*` / `de.yaacc.browser.*` classes).

### Cycle 1 Warning status
- **W1: CLOSED.** There are exactly two call sites of `ShareIntentHandler.handle` in main code (`TabBrowserActivity.java:161`, `BrowserActivity.java:140`), and both are reached only through `handOverShare` -> `ShareIntakeGate.admitOrDiscard(own, BrowserLauncher.effectiveLayout(this), intent)`. `admits` is `effective != null && !BrowserLayoutSwitch.shouldRelaunch(own, effective)`, i.e. an activity takes the payload only if it renders the effective layout (`effectiveLayout` never returns null: `BrowserLayoutChoice.resolve` maps everything but the exact stored "new" to classic; a failed preference read also gives classic). Test `theGateAgreesWithTheRelaunchDecision` pins the equivalence for all four pairs.

### Path walk (every route a text or file share can take into a browser activity)
1. **Explicit intent to the exported `TabBrowserActivity`, user on NEW layout.** `onCreate` -> gate: own CLASSIC, effective NEW -> not admitted -> `discard` -> the handler never sees it: no pending share, no `host` assignment, no `setReceiverDeviceIds({LOCAL_UID})`, no player work. `onResume` then relaunches into `BrowserActivity` with a fresh `new Intent(activity, target)` (`BrowserLauncher.java:120`), so nothing is carried over. Explicit text, explicit stream/ClipData and a forged nonce all end the same way. Same for `onNewIntent` (an attacker's `SINGLE_TOP`/`CLEAR_TOP` delivery to a live instance): the gate runs there too, `handOverShare(intent)` uses the new intent, not `getIntent()`.
2. **Explicit intent to `TabBrowserActivity`, user on CLASSIC.** Admitted, as before: a link auto-plays on the classic selection (`LOCAL_UID` fallback), by the earlier owner decision, unchanged by this fix; a file needs a launcher nonce and then goes to the classic picker. `BrowserActivity` cannot be reached by another app (not exported), so the new layout's handler is only ever fed by the launcher forward or by this process.
3. **Launcher forward.** `LauncherActivity` starts `BrowserLauncher.targetClass(effective)` with a fresh intent, so the receiving activity is by construction the one whose own layout equals the effective layout: admitted in CLASSIC (-> `TabBrowserActivity`) and in NEW (-> `BrowserActivity`, `providesReceiverBar() == true`, so links go to `offerLinkForPick`, never to the classic branch and never to `LOCAL_UID`). Pinned by `theLauncherForwardTargetsTheActivityThatAdmitsTheShare`. The nonce is spent by `handleSharedFile` -> `launchNonces.consume` after the gate admitted it.
4. **No link auto-plays in the new layout.** In the new layout the only activity that reaches `handle` is `BrowserActivity` (gate), and `handle` sends text to the picker whenever `pickHost.providesReceiverBar()`; the classic branch (`pendingShares`, `prepareAndPlay`, `LOCAL_UID`) is reachable only from `TabBrowserActivity` while CLASSIC is effective. A layout switch between launcher and activity (or between `onCreate` and `onResume`) can only drop a share, never hand it to the wrong branch.
5. **`onCreate` vs `onNewIntent`, rotation / recreation with `getIntent()` re-delivery.** The gate runs on every delivery. A handled or discarded intent has had text, ClipData, `EXTRA_STREAM` and the nonce removed from the in-process `Intent` object, which is the object the framework hands to a configuration-change relaunch, so the recreated instance finds nothing (file: nonce missing -> dropped by `handleSharedFile`; text: `readAndClearSharedText` -> null). The action and type stay on the intent, which is harmless without payload and nonce.
6. **Relaunch loop guard keeps an activity on the wrong layout** (`RelaunchGuard`, one relaunch per ~2 s). The gate does not consult the guard: `admits` depends only on the two layouts, so a wrong-layout activity that stays put still discards. It cannot take a payload and cannot be used to push one into the wrong branch. Side effects are limited to UX: the stale activity stays visible until its next `onResume` re-check (the guard window is 2 s, so it self-heals), and a legitimate launcher share meanwhile goes to a FRESH instance of the effective class, which is admitted (the guard only concerns relaunch, not a launcher start).
7. **Task / back-stack / recents re-delivery without process death.** The server-side copy of a launcher-forwarded file intent still carries the nonce, but the store entry was spent at the first handling, so a recents re-delivery of an already handled file is dropped. A copy that the gate DISCARDED (layout flipped in the few milliseconds between launcher and activity) keeps a live store entry for at most `LAUNCH_NONCE_TTL_MILLIS` = 30 s and the store holds at most 8; only the same in-process component can present it, and the launcher's checks had passed for that very share, so the worst case is a legitimate share being admitted later in the matching layout.
8. **Process-death re-delivery.** `LaunchNonces` is memory only, so a file intent is dead. A link text is NOT: the system keeps the original intent (with `EXTRA_TEXT`) and hands it to the recreated activity, because the in-process removal does not reach the system's copy. NEW layout: the gate admits it (if the layout is still NEW) and the link is offered to the picker again, so the user sees "Play on..." once more and nothing plays without a confirm. CLASSIC: the link auto-plays again, which is the pre-existing classic behavior (see S2). If the layout changed meanwhile, the gate drops it.
9. **Wedging / permanent drop.** A discarded payload never reaches `PendingShare.offer`, `intakeBusy`, `ActionFlight` or the provider gate, so the wrong-layout route cannot occupy or fill the pending slot or `LaunchNonces`; an attacker cannot mint a nonce (only the launcher can, after its checks). Nothing is dropped except while an activity of the wrong layout is the receiver, and a launcher share is never sent to one. Residual, accepted by decisions.md: a share that races with a layout switch (about two seconds) is lost and the sender can send it again.

### `discard` and consume
`ShareIntentHandler.discard` (`:531-538`) removes `EXTRA_TEXT`, then `consume` removes ClipData (`setClipData(null)`), `EXTRA_STREAM` and the nonce extra; nothing is read first (`ShareIntakeGateTest.aDroppedShareIsNotReadBeyondWhatConsumingNeeds` uses `verifyNoMoreInteractions`), a null intent is harmless, and failures are caught and logged with the exception class only. The log text in the gate is fixed. One hardening nit in S3.

### `DisplayName` (Cf stripping) and `filterTouchesWhenObscured`
- `isInvisible` drops every code point of Unicode category Cf except U+200D and U+200C, plus U+115F, U+1160, U+3164, U+FFA0. Checked against the spoofing cases: LRM/RLM (U+200E/F), ALM (U+061C), LRE/RLE/PDF/LRO/RLO (U+202A-202E) and LRI/RLI/FSI/PDI (U+2066-2069) are Cf and gone, so RLO/LRO and the isolates can no longer reorder or hide a name; U+200B, U+2060, U+FEFF, U+00AD, U+180E, U+2061-2064, U+206A-206F, interlinear annotation U+FFF9-FFFB and the tag block U+E0000-E007F are Cf and gone; control characters and all separators (`isWhitespace`/`isSpaceChar`/U+0085) are still collapsed to one space first. The new tests cover each class, including a name of only invisible characters falling back to the fixed text. ZWJ (emoji, tested) and ZWNJ (Persian, tested) are kept on purpose and cannot reorder text. No regression of the earlier behavior: the previous list is a subset of Cf, and the truncation at 200 chars still never splits a surrogate pair. Side effects judged acceptable: tag characters in emoji flag sequences are removed (the base emoji remains), and the Arabic/Syriac Cf prefix signs (U+0600-0605, U+06DD, U+070F, U+08E2) are removed, which matters for no file name that matters here.
- `filterTouchesWhenObscured="true"` is set on the pick sheet's OK and Cancel buttons and on the row layout `device_sheet_pick_item.xml`, which `DeviceSheetAdapter.java:307` inflates for every receiver row in pick mode (the CheckBox is the whole row). A tap under an overlay is dropped for the controls that tick, confirm or cancel; a tap on the sheet scrim still dismisses the sheet, which is a cancel, so no fail-open path was added. The normal (non-pick) sheet does not confirm anything and is unchanged. Not observed on a device.

### Critical
- None.

### Warning
- None.

### Suggestion
- [S1: `DisplayName.java:103-110`] A name that is blank only because of characters that are not Cf is still shown as an empty-looking line instead of the "Shared file" fallback: ZWJ/ZWNJ alone, variation selectors (U+FE00-FE0F), U+034F (combining grapheme joiner), the Khmer inherent vowels U+17B4/U+17B5, and U+2800. Cosmetic only (the picker shows the name under the title, never an authority). Cheap fix: after cleaning, treat a result that has no code point outside Mn/Me/Cf/Zs and U+2800 as empty, and add the cases to `DisplayNameTest`. A lone surrogate is also kept (renders as a replacement glyph).
- [S2: `TabBrowserActivity.java:139`, `BrowserActivity.java:122`; carried from cycle 1 requirement "savedInstanceState guard"] Link text is re-delivered by the system after process death (the system's copy of the intent is not the object that was cleaned). New layout: it re-opens the picker for the link (no playback without a confirm, and the gate still applies). Classic: the link auto-plays again (pre-existing, owner-accepted). A `savedInstanceState != null` check before `handOverShare(getIntent())` in `onCreate` would stop both; a first launch always has null, so no legitimate share is lost. Not required for this verdict.
- [S3: `ShareIntentHandler.java:517-538`] `consume` and `discard` remove the extras in one `try` each, so if a crafted `EXTRA_STREAM` makes the bundle's lazy unparcel throw, the removals after it (including the nonce) are skipped. No effect today (the wrong-layout activity never reads the payload and the nonce is an in-process secret), but one `try` per removal, nonce first, would make `discard` complete in every case.
- [S4: `ShareIntakeGate.java:55-66`] `discard` removes the nonce from the intent only; the store entry is not spent. It expires after 30 s and the store is capped at 8, so this is harmless; spending it in the gate would need the store in a static helper and is not worth it.
- [Carried, unchanged, still open for the owner] Cycle 1 S1 device checklist (`resolveContentProvider` on Android 11+, `getInitialCaller()` for the system chooser on 35+, the `SecurityException` fallback, stream close waking a read) before the filters reach users; S2 (local video handler receives the bearer proxy URL, state in the Group 16 threat model); S3 (classic pre-ticks the saved selection for a shared file: owner question, no change); S5-S9 as recorded in decisions.md ("Backlog 1").

### Verdict: PASS
Zero Critical, zero Warning. W1 is closed: no payload can reach the share handler from an activity that is not on the effective layout, in any of the delivery routes above, and the launcher's own forward is admitted in both layouts (`targetClass(effective)` is the one activity whose own layout is the effective one). Classic behavior is unchanged (links still auto-play on the classic selection / `LOCAL_UID` fallback there by earlier decision; files still need the nonce and the classic picker). The gate cannot wedge the pending slot, the intake flag or the nonce store, and the only legitimate shares it can drop are those that race a layout switch.

**The SEND filters (`audio/*`, `video/*`, `image/*` on `LauncherActivity`, manifest lines 68-74) may ship.** Conditions that stay with the owner and are not code findings: run the cycle 1 S1 device checklist on a real device (Android 11+ and 15) before the release reaches users, and state the S2 and S9 residuals in the Group 16 threat model.

## Group 16 — Cycle 1 — 2026-10-26
Reviewing: the documentation commits 9ae596a5 and a9f86180 (`git diff 464b7518..HEAD -- docs README.md CHANGELOG.md`, 5 files) and the move of the proxy research and the risk list into `known-risks.md`. Read in full: the diff of `CHANGELOG.md`, `README.md`, `docs/index.md`, `docs/settings.md`; in `docs/tech.md` the sections "Sharing local files" (as built), "Threat model", "Known limitations", "Not verified on a device", the Group 12 research notes (2420-3147) and the older passages that mention the proxy or the share intake (1195-1235, 1970-1980); all of `known-risks.md`; security-review.md Group 13 cycle 1, Group 14 cycles 1-3, Group 15 cycles 1-2 for the open items. Docs only; no build, no source edited. Working tree clean before and after the append.

Manifest (6): `git diff 071fc9d2 -- yaacc/src/main/AndroidManifest.xml` is exactly one added `<intent-filter>` on `LauncherActivity` (`ACTION_SEND`, `DEFAULT`, `audio/*`, `video/*`, `image/*`, 7 added lines). No new permission, no other element changed. OK.

What holds:
- (1) No exploit recipe for the released proxy (5.3.x/5.4.0). The old "Research notes (c)" and finding 8 (old request flow with line numbers, unguarded range parsing, HTML echo of a path segment, keys in the log, `proxy_link_*` preference storage, readers/writers) are gone from `docs/tech.md` and sit in `known-risks.md`. The reworded `ShareUriPolicy` reason no longer says a local path was reachable through the proxy; the CHANGELOG no longer says "cannot expose local files". `grep` of the published tree for echo/unescaped/reflected/`proxy_link_`/`getPathSegments`/redirect-bypass wording finds only the retirement note ("the old `proxy_link_*` preferences are retired ... deleted at start"), which a user needs. The published "Known limitations" do not name the redirect gap, the DNS/rebinding names or the thread-pinning details; they say only that the link policy is best effort, only enforced with the proxy option on, and does not resolve host names.
- (2) Strength claims are mostly labelled: the Media3 statement is "reasoned ... not observed on a device"; "Not verified on a device" lists `getInitialCaller()` on Android 15+, the SecurityException fallback, `resolveContentProvider` on Android 11+, the real fetch address of renderers and "proxy URL not on the notification". The SEND filter statement is accurate (audio/video/image only, `*/*`, multiple files and `file:` ignored; manifest checked above). The Android 15 caveat is accurate: the sender-holds-access check exists only on API 35+ and the docs say that below it the residual is accepted by the owner.
- (3) `docs/settings.md` and `docs/index.md` match the code: strings ("Could not play the shared link/file.", "Another shared file is still being processed...", "Too many active shares...", "Play on...", "Choose at least one receiver.", "No receiver playing") exist in `values/strings.xml`; the refusal is logged at info ("Media proxy refused a request from ...", `ProxyShareResponder.java:122`) and answers 403; the key `proxy_restrict_receivers_chkbx` and the default (on) are in `preference.xml`; limits 10 min / 6 h / 8 entries / 2 min / 512 MiB match `ShareLimits` and `SharedContentPolicy`. The advice is right: switch the restriction off only when a chosen receiver is refused, only on a trusted network; with it off the random key is no protection because any control point that can query the receiver reads it. The in-app summary strings say the same.
- (4) No secrets, tokens, keys, internal hostnames, personal data or real share URLs in `docs/`, `README.md`, `CHANGELOG.md` or the spec folder (grep for key formats, e-mail addresses, private IPs, `/proxy/<22 chars>`; the only hits are example addresses in test descriptions in review.md/security-review.md).

### Critical
- None.

### Warning
- [W1: `docs/tech.md:2281`, `:2301`, `:2373` (Threat model "Resources"), published] The logging claims are stronger than the code and than `known-risks.md` item 12. The page says "nothing is logged that holds a URI, name, key or proxy URL" and "The link is never logged", and that `ShareLogRedactor` "covers the SOAP, AV-transport, image and download traces that can carry them". Verified in the code: `ReceivingAction.java:117,121,124,168,172` and `YaaccUpnpServerProtocolRequestHandler` still log full SOAP/GENA messages and bodies at VERBOSE without `ShareLogRedactor`, and `ImageDownloader` logs album-art URLs at DEBUG. The default level is above them, so the practical risk is low, but a published "nothing is logged" is an unqualified security claim that is false at verbose levels. Fix: say "not logged at the default log level; the VERBOSE protocol traces and DEBUG album-art logs are not redacted (a user who enables them and shares the log can disclose a proxy address)", or wrap those log calls with `ShareLogRedactor` before the claim stays.
- [W2: `.claude/specs/2026-10-23-unified-device-sheet/known-risks.md`] The list is not complete against the open items of security-review.md. Missing: (a) the pre-existing legacy 404 page that still puts a decoded path segment into `text/html` unescaped (`YaaccUpnpServerContentHttpHandler.java:246-249`, still in the code; Group 13 "Out of scope, pre-existing") and the server-wide default `Http1Config` with no line/header limits and the 5-minute socket timeout; (b) the residual that with "this device" chosen loopback and the phone's own addresses pass the address step, so a hostile local app is stopped only by the key (Group 13 "Residual, local apps"; item 4 covers only the video handler), and the confused-deputy residual accepted by the owner (yaacc holds broad media and SAF access; below Android 15 there is no check that the sender holds the access); (c) a receiver's `<URLBase>` host is added to the allowed set, so a receiver the user picked can name another address, e.g. this phone's LAN address (Group 13 Suggestion); (d) private LAN ranges are intentionally allowed by `LinkTargetPolicy` (NAS links), so a shared link can make the phone fetch a LAN admin page and relay the answer to the chosen receivers (Group 13 SSRF residual; item 1-3 cover only loopback names and redirects); (e) abandoned copy workers keep their output file allocated until the process ends and are invisible to the cache budget, up to 3 x 512 MiB (Group 14 cycle 1 S1; item 6 mentions only the "busy" effect); (f) the cleaned share name is stored in the picker's fragment arguments, hence in saved instance state (Group 15 cycle 1 S7). Since `known-risks.md` is now the single place for what the public docs no longer carry, add these six items (or point to the security-review.md entries).

### Suggestion
- [S1: `docs/tech.md:2384`] "With that setting off, or on a device that is a chosen receiver, the key is the only protection" is ambiguous ("a device that is a chosen receiver" reads as any receiver). Write "With that setting off, or when this phone itself is a chosen receiver (other apps on the phone then only need the key), the key is the only protection".
- [S2: `docs/settings.md`, "Restrict the media proxy..."] Say once in user terms that the restriction is a network-address check, not a login: with it on, a device that can use a chosen receiver's address, or another app on this phone when "this device" was chosen, is not stopped. The current text ("everybody else gets 403") is true for the normal case but reads as stronger than it is.
- [S3: `docs/tech.md:1205`] The older passage still spells out a crafted input that passes the scheme check (`http://a\nfile:///x`) and the test that pins it. No sink uses the text (no intent, path or file is built from it) and the source is public, so this is not blocking; consider "an http: text with trailing junk passes; tightening is on the backlog" to avoid showing a bypass string on the Pages site.
- [S4: `docs/tech.md:2362-2368`] Add "reasoned, not device-verified" to the Android 15 `getInitialCaller()` sentence in the Threat model itself (it is only qualified in "Not verified on a device").
- [S5: git history] Commit 9ae596a5 still contains the research text that a9f86180 moved out of `docs/tech.md`. If the branch is pushed or merged as is, the removed passages stay readable in the history; squash the two docs commits before publishing if that matters to the owner.

### Verdict: FAIL
Zero Critical, two Warning. The published tree contains no exploit recipe for the released proxy and no secrets, the user guidance on the restriction setting is accurate, and the manifest diff is exactly the one SEND filter with no new permission. FAIL only because of W1 (an unqualified "nothing is logged" claim that the code contradicts at VERBOSE/DEBUG level) and W2 (`known-risks.md` is missing six accepted or open items from security-review.md). Both are documentation edits; no code change is needed.

## Group 16 — Cycle 2 — 2026-10-26
Reviewing: fix commit 08d05421 (`git diff eb466097..HEAD`: `docs/settings.md`, `docs/tech.md`, `known-risks.md`; no code, manifest or build file changed). Docs only, no build, no source edited. Cross-checked the logging claims against `YaaccLogger` calls in `ReceivingAction`, `YaaccUpnpServerProtocolRequestHandler`, `ImageDownloader`, `ProxyShareResponder`, `YaaccUpnpServerContentHttpHandler`, `ShareIntentHandler`, `LauncherActivity`, `UpnpClient`, `AVTransportPlayer`, and every `YaaccLogger.e/w/i` call under `yaacc/src/main/java` that mentions a uri/url/link/name. Default log level is ERROR (`YaaccLogger.currentLogLevel = ERROR`, preference default "E").

### W1 (logging claims): closed for share data, one wording residual (S1)
- Verified true: `ProxyShareResponder` has exactly two log calls (`:122` INFO, remote address only; `:275` WARN, exception class only). `ShareIntentHandler`, `LauncherActivity`, `ReceiverPickController`, `ShareIntakeGate`, `DeviceSheetFragment` and the `share/` package log fixed text or `getSimpleName()` only. `UpnpClient` share code (`:1682-1839`, `readLinkMetadata`) logs fixed text or the class name; no link, key or name. `loggableUri` redacts `/proxy/...` in the handler's DEBUG line. `ShareLogRedactor` is applied exactly where the new text says: `HttpRequestSender` (V/D), `AvTransportMediaRenderer*` and `YaaccAVTransportService` (D), `ImageFragment` (D/E), `FileDownloader` (E), `UpnpClient` position info (D). The remaining unredacted traces are exactly the ones named: `ReceivingAction:117,121,124,172` and `YaaccUpnpServerProtocolRequestHandler:96,103,242` (VERBOSE) and `ImageDownloader:79,130` (DEBUG). "Not logged at the default level" holds for share URIs, names, keys and proxy URLs: shares carry no `ALBUM_ART_URI` (`UpnpClient:686-712` is the browse enrichment only), so the ERROR-level album-art lines in `AVTransportPlayer` cannot receive a share URL.
- `docs/settings.md` ("At log level info ... Media proxy refused a request from <address>") matches `ProxyShareResponder:122`. The new sentence that the restriction is an address check, not a login, is accurate (closes cycle 1 S2). `README.md` and `CHANGELOG.md` make no logging claim; `docs/privacy.md` talks only about GitHub Pages.

### W2 (known-risks.md completeness): not fully closed
Items 13-18 cover cycle 1 W2 (a)-(f) accurately (checked against the code: legacy 404 echo at `YaaccUpnpServerContentHttpHandler:245-249`, `ShareAddressResolver` URLBase, `ImageDownloader` any scheme with no timeout, copy-worker space). Cross-check of every Suggestion and residual line of Groups 12-16 against the list; items 1-18 cover: SSRF, redirects, names (1-3, 16), proxy-off reads (3), local video URL (4), UNRESOLVED gate (5), hung providers (6), cool-down (7), classic pre-tick (8), action flight (9), rotation/process death (10), upstream I/O threads (11), logging/ImageDownloader/silent drops/duplicate names (12), legacy 404 and HTTP limits (13), local apps and confused deputy (14), URLBase (15), abandoned copies (17), saved state (18). Fixed in code and therefore correctly absent: null-IP URL, unclaimed shares, serve-time MIME check, `RangeStreamer` guard, redactor regex, nosniff/sandbox headers, Cf stripping, layout gate. Still missing:
1. Classic layout: a shared http(s) link from any app (launcher text filter or the exported `TabBrowserActivity`) auto-plays on the stored receivers, or on this phone after the blind wait, with no prompt (Group 12 cycle 1 W2 and Group 15 cycle 1 S9; owner-accepted, pre-existing; tech.md names it only as "classic link auto-play" in Backlog 1). Items 8 and 10 cover only the file pick and the re-delivery. This is the largest accepted residual of the feature and belongs in the list.
2. The metadata reads at link intake (`MediaMetadataRetriever.setDataSource(String)`, the `HEAD` fallback) have no overall deadline (`UpnpClient.readLinkMetadata` has none; Group 14 cycle 1 Suggestion 2, decisions.md does not close it): a server that drips bytes can pin the single link-intake thread, after which at most three more links queue and further ones are dropped. Item 3 mentions that the read runs, not the pin.
3. `ShareUriPolicy` checks only scheme and length (published Backlog 1 item 8); not in the list. Low impact (no sink uses the text).
4. Link-local zone ids are stripped for comparison, so `fe80::1%wlan0` equals `fe80::1%eth0` (Group 12 cycle 1, Group 16 must 3: "accept and say so").
5. A share racing a layout switch (about 2 s) is dropped, and `DisplayName` shows an empty-looking line for names made only of ZWJ/ZWNJ, variation selectors, U+034F or U+2800 (Group 15 cycle 2 S1; cosmetic).

### Neutralised passages: accurate, no recipe
- `docs/tech.md:1204-1206` now reads "an `http:` text with trailing junk also passes" (the `http://a\nfile:///x` string is gone; grep of the published tree for `a\nfile`, `file:///x`, `sha256`, `proxy_link_` finds only the retirement note at `:2253`). Accurate: `ShareUriPolicy` checks scheme and length only, no sink builds an intent or path from the text.
- IOException wording (`:1216`, `:1858`): "before Phase 2 a failure on the worker thread was not caught" is accurate; the handler now catches `RuntimeException`/`IOException` and logs the class name (`ShareIntentHandler:342,391,686,721`). It no longer says "crashes the app".
- `:2263` "the responder logs none of them", `:2368-2370` `getInitialCaller()` "reasoned, not device-verified", `:2387` key-only protection when this phone is a chosen receiver: all accurate and no longer ambiguous (closes cycle 1 S1, S4). The published tree has no exploit recipe against 5.3.x/5.4.0; the research detail stays in `known-risks.md`.

### Critical
- None.

### Warning
- [W3: `.claude/specs/2026-10-23-unified-device-sheet/known-risks.md`, residual of cycle 1 W2] The list is still missing the open or accepted items 1 and 2 above (classic link auto-play without a prompt; no overall deadline on the link-intake metadata reads). Add them as items 19 and 20 (or point to security-review.md Group 15 cycle 1 S9 and Group 14 cycle 1 Suggestion 2), and add 3-5 in one line each so the file is the complete list it says it is. Documentation edit only.

### Suggestion
- [S1: `docs/tech.md:2376-2377`, `:2280-2284`, `:2303-2304`] "At the default log level that holds for the whole app" reads as "nothing with a URI is logged anywhere"; that is false for non-share data: `AVTransportPlayer:202,216,296,312` log album-art URIs at ERROR (the default level), and `YaaccUpnpServerContentHttpHandler:781` and `YaaccUpnpServerControlActivity:245` log SAF URIs at ERROR. Those are not share data (shares have no album art; SAF URIs are the media-server tree), but the sentence and "DEBUG album-art logs" should say "no share URI, name, key or proxy URL is written at the default level anywhere in the app" and call the album-art logs "album-art logs (DEBUG in `ImageDownloader`, ERROR in the notification code, never a share URL)". Not blocking because the share-data claim itself is correct.
- [S2: `known-risks.md` items 13 and the "Research findings" section] The spec folder is in the public repository. Item 13 and the research section describe weaknesses of released versions (reflected HTML in the legacy 404, unguarded range parsing, `proxy_link_*` storage) with line numbers. Decide whether `.claude/specs/` is published with the branch; if the repo is public, keep the 5.4.x patch release (decisions.md item 5) ahead of the merge and squash the docs commits (cycle 1 S5, `9ae596a5` still holds the removed text).
- [S3: `ShareIntentHandler.java:517-538`, `ShareIntakeGate.java:55-66`] Carried nits from Group 15 cycle 2 (S3, S4): one `try` per removal in `consume`/`discard`, nonce first; the gate removes the nonce from the intent but does not spend the store entry (expires after 30 s, store capped at 8). `ShareIntentHandler.intakeBusy` is still an instance field (Group 14 cycle 1). No action needed for this verdict.

### Verdict: FAIL
Zero Critical, one Warning. W1 is closed for what the docs claim about share data and the redaction coverage is stated accurately; the neutralised passages are accurate and the published tree carries no exploit recipe for released versions. FAIL only because W2 is not fully closed: `known-risks.md` still lacks the classic link auto-play residual and the missing deadline on the link metadata reads (W3), a three-line documentation edit.
