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
