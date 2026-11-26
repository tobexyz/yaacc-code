# The controller role of the YAACC server (K15)

Status: **spec, nothing built** (2026-11-26). The owner agreed to the feature; the decisions in the last section are open. Protocol facts are from the pinned specification (`roles/controller/v1.md`, `roles/metadata/v1.md`, `README.md` "Core messages"), read on 2026-11-26.

## 1. Why

Today the hub of a YAACC phone only streams audio to players. A client that offers no player role gets nothing (`HubConnection.decide`: `activateNothing`, `UNUSABLE`). The owner's use: phone A plays a queue to its players; phone B (or any Sendspin controller) should show what plays on A and control it (pause, next, volume), like the card of Music Assistant does. The client side (K1 to K7, `ControllerSession`, the server sheet) already speaks the controller role, so a YAACC server that implements the other half gives the existing server card a function and makes the card of another YAACC phone mergeable (K14).

## 2. Protocol facts that shape the design

- The `controller` role controls **the group the client is part of**, and can `switch` groups. It lists `supported_commands` (play, pause, stop, next, previous, volume, mute, repeat_off, repeat_one, repeat_all, shuffle, unshuffle, switch, seek, seek_relative); the server MUST ignore a command that is not in its current `supported_commands`; `seek` needs `seek_max_ms` and is dropped when the range is unknown.
- The `controller` state carries `volume` (group average), `muted`, `repeat`, `shuffle`; the `metadata` state carries title, artist, album_artist, album, artwork_url, year, track and `progress` (position, duration, speed, measured at `timestamp` on the server clock). `group/update` carries `playback_state` ("playing" or "stopped"; a paused group is "stopped"), `group_id` (32 bytes, base64url, 43 characters) and `group_name`.
- Every `server/state` carries the full state of each role object it includes; the server MUST NOT activate a role until it can give its complete state and MUST send it promptly after the activation.
- A client without the `player` role is placed by the server in a group of its choice; for `switch`, the cycle is the multi-client groups that play, then the single-client groups that play.
- Group volume is the average of the volumes of the players that support volume; a requested group volume moves every player by the same delta, with clamping and redistribution (the loop in `roles/controller/v1.md`). The hub's `setGroupVolume` does the delta part; **whether it follows the redistribution loop exactly has to be checked in the tests of this feature.**

## 3. The model

- A **group of the hub** is a leader (or a lone player) with the members linked to it (`SendspinGroups`). Only a group that an app **`Player`** is playing to counts as a group a controller can control (the queue lives in the app's player, `player/Player.java`; Sendspin only carries the sound).
- **Which group a controller controls** (proposal): the group that is playing; if several play, the one that started last; if none plays, the group that played last; if there never was one, none (the state says `supported_commands: []`, `playback_state: stopped`). `switch` cycles the groups that have an app player (playing first), as the protocol's cycle says; with one group it does nothing. `group_id` is 32 random bytes per group and per run of the app (it may change after a restart), `group_name` is the leader's name, plus " +N" for N members.
- **Roles activated** for a controller client: `controller@v1`, `metadata@v1`. `artwork@v1` is phase 2 (section 8). A client that also lists `player` is not given a controller (it is a player; a client with both roles is out of scope for now).

## 4. Commands and what they do (table of the mapping)

| Command | Effect on the app | `supported_commands` has it when |
|---|---|---|
| play | `Player.play()` (resumes; if nothing is loaded it is ignored) | there is a player for the group |
| pause | `Player.pause()` | there is a player |
| stop | `Player.stop()` | there is a player |
| next / previous | `Player.next()` / `previous()` | the queue has more than one item |
| volume | group volume of the leader (`SendspinHub.setGroupVolume`), integer 0 to 100, others ignored | the group has a player that supports volume |
| mute | group mute (`setGroupMuted`) | the group has a player that supports mute |
| repeat_off / repeat_one / repeat_all | `Player.setPlayMode` with the repeat part | the app's player has play modes (`PlayMode`) |
| shuffle / unshuffle | `Player.setPlayMode` with the shuffle part | as above |
| seek / seek_relative | `Player.seekTo` (relative: clamped to 0 and the duration) | the duration of the item is known (`seek_max_ms` = the duration) |
| switch | next group in the cycle of section 3 | more than one group with an app player |

Commands run on the command thread (never the main thread, never the connection thread); a command that fails changes nothing and is logged without the content. Parameters are validated (`volume` 0 to 100, `position_ms` in range, `offset_ms` bounded) and a malformed `client/command` is ignored, not fatal, except as the protocol demands.

## 5. State sent to the controller

- `server/state.controller`: `supported_commands` as above, `volume`, `muted` (group), `repeat`, `shuffle`, `seek_max_ms` when seekable.
- `server/state.metadata`: from the player's current item: `title`, `artist`, `album`, `track`, `year` where the item knows them (each clipped to the limits of the protocol: 255 bytes of UTF-8, 2048 for the URL), `artwork_url` only if the item's art is an http(s) URL of the local network, `progress` (position, duration, `playback_speed` 1000 playing and 0 paused) with `timestamp` = the hub's clock now.
- `group/update`: as in section 2, sent after the activation and on each change.
- **When**: after the activation and whenever the item, the play state, a seek, the volume, the mute, the play mode or the group changes (listeners on the player's property changes and on the hub's `fire()`); no periodic position messages (progress is extrapolated by the client, as the protocol says). Scheduled next-track metadata is not sent in the first version.
- A change that is not observable (the player gives no event) is polled every 2 seconds only while a controller is connected.

## 6. Access: who may control

Controlling is a more powerful act than listening to a stream (it changes what plays and how loud), so it is not open by default.

- **Reaching the hub**: a controller dials the hub's listener (`_sendspin-server._tcp`, the setting *Advertise this server*, off by default). Without it nothing changes from today.
- **A second setting** (proposal): *Let other devices control this phone's playback* (off by default, depends on the listener switch). Off: a client without a player role is refused as today.
- **Who**: only **paired controllers** by default. Pairing uses the client's token (the one the app shows as a client, K8; its QR code is K12 (b)): *Settings > Sendspin > Controllers* gets *Add controller* (scan the QR code or paste the token) and the list of paired controllers with a remove button. The pairing is the Pairing PSK flow that the hub already runs as a server for players, with the token stored before the controller connects (the PSK lookup is by client id).
- **Unpaired controllers** (proposal): a third switch *Allow unpaired controllers* (off by default; the connection is encrypted but does not prove who the controller is, so any device on the network could change what plays). Mirrors *Allow unpaired servers to play on this phone*.
- Limits: at most 4 controllers at once; only addresses of the local network; a controller that does not activate within 30 seconds is dropped (as for players); a flood is counted from the accept (the existing `InboundServer` caps).

## 7. Where it fits in the code

- `HubConnection.decide` branches on the roles of the client hello: a player as before; a controller (with or without metadata) goes to a new **`ControllerHost`** (server side of the controller role), which owns the per-controller state, the command mapping (section 4) and the messages (section 5). The handshake, the pairing flow and the policy are reused (they are keyed by client id and do not care about the role).
- `ControllerHost` talks to the app through a small interface (`PlaybackBridge`: the group of the hub that is controlled, its `Player`, its listeners) so that it is testable with a fake player on the JVM.
- The messages are built next to the other server messages in `session/` and parsed back with the existing `ControllerMessages` of the client side in the tests (a round trip proves both halves).
- `isListedInbound` stays: a controller never gets a receiver card (it is not a target).
- The merged card (K14) then shows a *Remote control* entry for a YAACC phone whose server offers the controller role; a YAACC server card without it is hidden.

## 8. Not in the first version (listed so that nothing is promised)

Artwork over the `artwork@v1` channel (the server would encode the cover as JPEG at the size the client asks for; the client side exists), scheduled metadata for the next track, editing or browsing the queue, the `source`, `color` and `visualizer` roles, a client with both the player and the controller role, per-controller groups, remote linking (K16, not in the protocol).

## 9. Tests (written first for the pure parts)

1. Message builders: `server/state` (controller, metadata) and `group/update` against the shapes of the specification, and read back by `ControllerMessages`.
2. Command mapping, table driven, with a fake `PlaybackBridge`: every command, every refusal (not in `supported_commands`, bad parameter), the group choice and the `switch` cycle.
3. `supported_commands` and `seek_max_ms` from the fake player's capabilities and the queue.
4. Policy: refused unpaired, accepted paired, the pairing before the first connect, the limits.
5. End to end on the JVM: a real `ControllerSession` (client) against the real host over `FakePlayer` links, from the handshake through a command to the changed state.
6. Optional with the reference implementation in the rig: the reference controller client against the hub (not run in this sandbox unless the rig works).

## 10. Decisions needed from the owner

1. **Access** (section 6): paired controllers by default and an *Allow unpaired controllers* switch (off by default), or simpler (only one of them)?
2. **Which group a controller controls** (section 3): "the one that plays, else the last that played" with `switch` for several, or always the one linked from a chosen card?
3. **Artwork**: left for phase 2, or in the first version?
4. **Other controllers**: should Music Assistant or Home Assistant UIs (any Sendspin controller) be able to control the phone, or is YAACC to YAACC enough? (The design supports both; it changes how much we test against the reference implementation.)
