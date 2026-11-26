# Controller: the phone controls the group of another Sendspin server (#325, use cases C1 to C3)

Status: K1 to K7 built on 2026-11-24 (JVM tests, the rig test against the reference server; never run on a device, not tried with Music Assistant), with the artwork role (K9) and the mDNS search of `_sendspin-server._tcp` (K5, added the same day at the owner's request). **Open tasks (owner, 2026-11-24): K8 pairing as a client** (show a pairing token, hold the Pairing PSK, long-term records, the re-handshake as responder, answer the pairing activation); **the security review of the client side is postponed** until there are more results (the owner's decision). Before it: nothing of the client side is called reviewed. Started 2026-11-24 after the owner's go ("Start with the controller"). Use cases and the control channel:
`usecases.md`. Normative source: the Sendspin specification at `2cfe197` (sections: Establishing a Connection, Communication,
Clock Synchronization, Core messages, Controller messages, Metadata messages).

## What it is

YAACC connects as a **client** with the roles `controller@v1` and `metadata@v1` (no `player`) to a Sendspin server such as Music
Assistant, shows what the server plays in the group YAACC belongs to, and sends commands: play, pause, stop, next, previous, seek,
volume, mute, repeat, shuffle. It plays no audio and does not link players (grouping stays with the server's own UI; the
controller's `switch` command moves this client through the server's groups).

## Decisions (owner, 2026-11-24)

- **Connection: YAACC connects out** (client-initiated mode): the server is found by mDNS `_sendspin-server._tcp` or typed in; the
  phone opens the WebSocket and speaks first (`client/init`); the Noise handshake still has the **server** as initiator. No
  listening socket. (Server-initiated mode needs the opt-in listener of the player role and comes with it.)
- **UI: a card in the receiver list** (like a receiver, but it controls a server's group, it is not a place to play to): the text
  is the group and now playing, the buttons play, pause and stop and the volume slider act on the server's group.

## Behavior (from the specification)

1. **Opening**: WebSocket to the advertised address and path (no `Origin` header; path must be RFC 3986 path-absolute), then
   `client/init` (version 1, suite, `client_id` = our public key), read `server/init`, read Noise message 1 (the payload says which
   PSK the server uses), answer with message 2 under that PSK if we hold it, else the Sentinel PSK (Sentinel fallback); prologue =
   exact bytes of `client/init` and `server/init`. The client MUST NOT send other messages until the first `server/activate`,
   except `client/goodbye`.
2. **Hello**: read `server/hello`, send `client/hello` with `name`, `device_info`, `supported_roles ["controller@v1", "metadata@v1"]`,
   `supported_pair_methods {"pairing_psk": ...}`, `unpaired_access {enabled}`.
3. **Activation**: `server/activate` activates the roles the server wants (it may activate none until its operator approves this
   client). Check that it is admissible (spec table: activities allowed per PSK; roles only on a playback-capable connection);
   otherwise answer with `client/goodbye` (`unauthorized`, or `pairing_required` when we do not admit unpaired access).
4. **After activation**: the server sends `group/update` (playback state, group id, group name) and `server/state` with the
   `controller` object (supported commands, group volume, mute, repeat, shuffle, seek range) and the `metadata` object (title,
   artist, album, artwork URL, year, track, progress). A client with no `player` or `source` role may send its first
   `client/state` (`available: true`) at once.
5. **Clock**: `client/time` in bursts (at least 8 messages, bursts at most 10 s apart) so that the progress of the track can be
   extrapolated in the server's time domain: `position = track_progress + (server_now - timestamp) * speed / 1000000`, clamped to the
   duration. The controller only needs the offset, so it takes the measurement with the lowest `max_error` of each burst
   (`((T2 - T1) + (T3 - T4)) / 2`, `max_error = ((T4 - T1) - (T3 - T2)) / 2`) and smooths it; the Kalman filter of the specification
   (needed for audio sync) comes with the player role. A scheduled metadata update (future `timestamp`) is held as pending and
   applied when its time comes; the display uses the current state only.
6. **Commands**: `client/command` with `controller {command, volume | mute | position_ms | offset_ms}`; only commands in the
   latest `supported_commands`. A UI control for an unsupported command is disabled.
7. **Ends**: `server/unpair` (drop the record, `client/goodbye` `unpaired`), `client/goodbye` from the server side is a close; our
   own end sends `client/goodbye` `user_request` (the user removes the server) or `shutdown`. Reconnect after a loss with backoff,
   not after `user_request` or `unpaired`.
8. **Unpaired access**: the setting "allow unpaired servers" (default on for servers the user added by hand, which is the only
   way to get one in the first slice; see open point 2) is what `unpaired_access.enabled` reports. **Pairing as a client** (a token
   that YAACC shows, the Pairing PSK it holds, the long-term records, the re-handshake as responder) is **not** in the first slice;
   it comes with the player role (use cases P2) or earlier if Music Assistant demands it.
9. **Identity**: the client has its own key pair (not the server key), kept in the app's private preferences like the server key.
10. **Display text** from the server (names, titles, artist, album, group name) is untrusted: cleaned like `SendspinRowText.clean`;
    `artwork_url` is never fetched in the first slice.

## Plan (tests first for the pure parts)

| Id | Piece | Where | Verify |
|---|---|---|---|
| K1 | `ClientHandshake`: the client's side of the opening (client/init to transport mode, PSK candidates, Sentinel fallback), the production version of the test class `FakePlayer`; responder side of the Noise pattern exists | `de.yaacc.sendspin.client` | against `ServerHandshake` (loopback) and the reference server (rig) |
| K2 | Messages: `client/hello` (controller), parsers for `server/hello`, `server/activate` (admissibility check), `group/update`, `server/state` (controller, metadata), `server/unpair`; builders for `client/state`, `client/time`, `client/command`, `client/goodbye` | `de.yaacc.sendspin.session` | `SendspinMessagesTest` extended: bounds, hostile input |
| K3 | `OffsetEstimator` (burst minimum, smoothing) and `TrackPosition` (extrapolation, clamping, scheduled update) | `de.yaacc.sendspin.client` | tables with the specification's formula |
| K4 | `ControllerSession`: the state machine (connect, hello, activation, state, time bursts, commands, goodbye), listener with an immutable snapshot | `de.yaacc.sendspin.client` | against `ServerHandshake` plus a fake server; rig against `sendspin serve --demo` |
| K5 | Server directory: typed addresses (stored), mDNS `_sendspin-server._tcp` (the NSD wrapper pattern), the hub of controller sessions | `de.yaacc.sendspin` | unit tests with fakes |
| K6 | The card in the receiver list: synthetic device, row text and buttons, volume = group volume, settings (switch off by default, list of servers), strings in seven locales, no player is made for it | `de.yaacc.browser`, `de.yaacc.settings` | JVM tests for text and logic |
| K7 | Docs, CHANGELOG, security review of the client side | docs | |

## Open tasks (owner's list, 2026-11-24)

| Id | Task | Why open |
|---|---|---|
| K8 | ~~Pairing as a client~~ **done**: the token this app shows, the Pairing PSK flow (`client/pair-init`, `client/pair-finalize`), the re-handshake as responder, up to 8 long-term records (`SendspinClientPairings`, `ClientHandshake.rehandshake`, `ControllerSession`, `SendspinClientPairDialog`) | tested against a fake server that uses the real server-side handshake code; **not against an independent implementation** (the reference server's CLI has no way to enter a client's token, its library dials the client) and not with Music Assistant |
| K10 | Security review of the client side (general review, then the security review: hostile server input, the artwork decoder, the discovery of servers, the identity key) | postponed by the owner until there are more results |
| K12 | **Done 2026-11-26, not tried on a phone** (library decided, see `decisions.md`; step 1: the token screen of the phone shows the QR code; step 2: the *Scan QR code* button in the entry dialog). Pairing by QR code: (a) scan the pairing token of a player (`SP:0...`, the spec renders a printed Pairing PSK as a QR code of the token and says a QR code carries the token verbatim, no URI scheme) instead of pasting it; (b) show the token of YAACC as a QR code when it is a client (with K8). The QR code of the reference server's console is the address of its web player, not a pairing token. | the owner's request, 2026-11-24. A scan needs an in-app scanner: the camera app of the phone can not open a text without a URI scheme. Decision needed: the library (a ZXing based one needs the camera permission; Google's code scanner needs Play services and is not free software) |
| K13 | ~~The client-initiated mode of the server role~~ **done** (opt-in listener and announcement, `settings_sendspin_advertise`; with the legacy unencrypted wire, `settings_sendspin_legacy`; see decisions) | found in a test with a Kodi addon on 2026-11-24; not verified with the real addon |
| K14 | **One card per YAACC phone** (owner agreed 2026-11-26): the player and the server announcement of the same YAACC phone are one card. Recognised by an installation id in the TXT record of both mDNS announcements (never by IP address: Music Assistant and a Kodi addon can share one; the player key is not reused as the server key). The card's own buttons play my music on that phone; a *Remote control* entry opens the sheet of the server (K15). Servers that are not YAACC keep their own cards. | the owner found two cards for the same device confusing; needs the TXT id in both announcements and the merge in the receiver list |
| K15 | **The controller role of the YAACC server** (owner agreed 2026-11-26; **spec written: `controller-server.md`, decisions open**): the hub answers controller clients with its state and accepts commands (play, pause, stop, next, previous, volume, mute) that it maps to the playback of the app (the queue lives in the app's player, Sendspin carries the sound), plus metadata (title, artist, album, artwork). Then the server card of another YAACC phone controls that phone's playback, and any Sendspin controller can too. Spec first: which commands, how the state is mapped, who may control (paired or not), group info read-only (who is in the group). | today a client that is no player is `UNUSABLE` and gets nothing; the client side (K1-K7) already speaks the controller role |
| K16 | **Remote linking (future enhancement, to be discussed)**: linking players into a group from another device, as MusicCast does. Not part of the Sendspin protocol (groups are a server concept; Music Assistant groups in its own UI and API), so it needs a YAACC-specific extension that works between YAACC phones only, or none. The owner wants to discuss it before anything is decided. A smaller step: show the group of a YAACC server read-only (K15). | not in the protocol; the owner asked on 2026-11-26 to keep it as a future enhancement |
| K17 | **Groups are easier to read** (owner agreed 2026-11-26): the leader's line names its members, a member says whose group it plays in (also the card of this phone), members sit directly under their leader in the list. | done in the same change as this entry |
| K11 | Artwork on the card itself (a thumbnail in the row) and the other artwork channels | needs a layout change of the receiver row; the sheet shows the image |

## Test rig for Music Assistant (2026-11-24, not run)

`tools/sendspin-rig/music-assistant/` has a compose file (image `ghcr.io/music-assistant/server`, host network, `/data`, a read only
music folder, web interface 8095, Sendspin on 8927) and a script that writes three test tones. Written from the sources of Music
Assistant; **never started** (no Docker daemon where it was written). Its first run answers open point 1 below.

## Open points

1. Does Music Assistant accept client-initiated connections and which roles does it activate for an unpaired controller? Not
   verified (only the reference server). If it needs pairing as a client, K8 (pairing as a client: show the token, Pairing PSK
   candidate, long-term records, the re-handshake as responder) comes before the card is useful with it.
2. Default of "allow unpaired servers" for the controller: on, because the user names the server and the role gives it no
   audio or files; off would make the first test with Music Assistant impossible before K8. Owner may veto.
3. The card's buttons: the receiver row has play, pause, stop and a volume slider; next, previous, seek, shuffle and repeat need
   a place (the row's gear, or the player screen). Proposal: play, pause, stop and volume on the card; next and previous on the
   card too if the row has room; the rest in a sheet from the gear (as the FCast and Snapcast rows do).
