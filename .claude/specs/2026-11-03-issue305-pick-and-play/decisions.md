# Decisions: pick and play (#305)

## 2026-11-03 - one share per player, only the current item
**Context**: The spec said current and next item. Review cycle 1 showed that with one window per player a queue needs 2 x (types x receivers) shares against the registry limit of 8 (one speaker plus this phone with three types already used all 8). The players have no next-URI prefetch (`AVTransportPlayer` sets the URI when the item starts), so the next share is not used before the item before it ends.
**Decision**: A player holds only the share of its current item (`Lease.moveToItems(current, null)`). The lease API still takes a second item, so a prefetch can come back later. Types x receivers (up to 8 players) fit the registry.
**Rationale**: Capacity and the smaller exposure; the cost is a short gap between two items while the next file is opened (a non-seekable provider copies the file first).

## 2026-11-03 - items are found by identity
**Context**: A playlist can be edited after `setItems` (remove, move, `addToPlaylist`), so positions say nothing about the queue.
**Decision**: The window maps the queue's placeholder items by identity; the binder writes onto that very object. Items that are not part of the queue (added later) need no share and are played as they are.

## 2026-11-03 - addresses fixed at the confirm
**Decision**: `PlayerService.createQueuePlayers` resolves the allowed receiver addresses once per player (`ShareAddressResolver`), `ShareService.prepareFor` registers every later share with them (SI-4).

## 2026-11-03 - separate items per player, direct content URIs on this phone
**Decision**: Every player gets its own `PlayableItem` list (the intake shares one list between players). This phone's audio and image players read the picked `content:` uri directly with the app's grant (no share); video on this phone and every remote receiver use just-in-time shares. The `Local receiver` task therefore changed no player file: `LocalMediaSessionPlayer` (ExoPlayer `DefaultDataSource`) and `ImageFragment` already read `content:` uris. To be confirmed on a device.

## 2026-11-03 - unknown duration
**Decision**: Queue items have duration -1, so `AbstractPlayer` starts no timer for the next item; remote receivers advance on their transport state, ExoPlayer on its own. Videos on this phone (external app) do not advance by themselves yet. Open: read the duration of a video when its share is bound.

## 2026-11-03 - accepted suggestions of review cycle 1 (not changed)
- The proxy url stays on the item after the release (a request for the removed key gets 404); design.md is corrected.
- `application/octet-stream` files of a folder count as "not media" (no extension guessing).
- `PlayableItem` fields written on the timer thread without `volatile` (same as every other player path).

## 2026-11-04 - owner feedback after the first device test
- **Duration**: the receiver showed 0:00:00 for picked files (no duration in the DIDL resource). The duration is now read when the share is bound (`ContentDuration`, `MediaMetadataRetriever`, bounded) and put into the item and its DIDL resource. This also gives videos on this phone a timer for the next video (the earlier open point). Not re-reviewed yet.
- **Entry point**: a fixed, tappable row "Play from this device" with a phone icon above the UPnP server section replaces the file icon button in the header (new layout) and at the bottom right (classic). The row opens the same menu (files / folder).
- **Entry point, second round**: the fixed row and its popup menu looked technical (flat box covering the footer). Replaced by a card in the style of the server cards, appended to the server list with a `ConcatAdapter` (`PickCardAdapter`, `browse_pick_item.xml`): phone icon, title, and two outlined buttons with icon and short text ("Files", "Folder") that open the system pickers directly. Same card in the classic layout (shared fragment). Not seen on a device yet.
- **Entry point, third round**: the two buttons of the card show only their icon (like the stream buttons of the Yaacc card); the content descriptions stay for screen readers and the short texts were removed from all 7 languages.
- **Default action of the card**: a tap (or centre/enter of a D-pad) on the card opens the file picker; D-pad right moves on to the file button, then the folder button (as on the server cards). Not tried on a TV or with a D-pad yet.
