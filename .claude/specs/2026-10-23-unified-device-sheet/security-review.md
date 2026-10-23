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
