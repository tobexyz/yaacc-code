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
