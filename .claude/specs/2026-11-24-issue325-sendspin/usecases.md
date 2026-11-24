# Use cases: what YAACC is in a Sendspin network (#325)

Status: DRAFT for the owner's review (2026-11-24, after the pairing work). It orders what the protocol allows YAACC to be, which
use cases need which role, and what exists. The protocol facts are from the Sendspin specification at `2cfe197` (section names are
its); the status column is from this branch. Nothing here is a decision of the owner yet; the open points are at the end.

## Roles of a Sendspin network

- **Server**: connects to players (server-initiated mode, the recommended one), pairs with them, activates roles, creates the audio
  stream with timestamps, **owns the groups** (a client is in exactly one group, and only the server moves it) and the queue.
- **Client**: a device with one or more roles. **Player** (plays the stream, reports and takes volume, mute, output delay),
  **controller** (controls the group it is in), **metadata** and **artwork** (show what plays), **source** (sends audio into the
  server), visualizer, color.
- Several servers can know the same client. The client admits one connection at a time (it may admit more, but only one with the
  `'playback'` activity and one with `'pairing'`); the player's rules decide who gets it (spec, "Multiple servers").

## What YAACC can be

| YAACC is | Meaning | Needs | Status |
|---|---|---|---|
| **Server** | plays its queue to Sendspin players | outgoing connections only | one player at a time works; groups open |
| **Player** | the phone is a speaker, driven by any server | a listening socket (opt-in), an audio engine with a time filter, mDNS announcement | not started |
| **Server and player at once** | the phone is one member of its own group | an in-process driver, no sockets | not started (design: `PlayerEndpoint`) |
| **Controller** | the phone controls the group of *another* server (Music Assistant) | a client connection with the `controller` role | not started |
| **Metadata and artwork client** | shows what another server plays | the roles, a UI | not started |
| **Source** | the phone feeds audio into another server | the `source` role | not planned |

**Linking (grouping) belongs to the server.** Only a server can put players into one group; a player does not decide it, and the
controller role cannot link arbitrary players (it can control its own group, and `switch` the client through the server's cycle of
groups). So linking in YAACC exists only while YAACC is the server (MusicCast: the devices group, YAACC sends the commands;
Snapcast: the Snapcast server groups; Sendspin: YAACC groups).

## Use cases

### Server (YAACC plays to players)

| Id | Use case | Status |
|---|---|---|
| S1 | See players: found by mDNS `_sendspin._tcp`, or added by address; name, address and state in words | done (never run on a device) |
| S2 | Play the queue to one player (PCM, play, pause, stop, seek, next, volume, mute) | done; verified with the reference player, not by ear |
| S3 | Admit a player that does not know YAACC: approval by playing to it; list and revoke approvals | done; the list shows the first 8 characters of the id |
| S4 | Pair with a player that refuses unpaired servers: paste its token (Pairing PSK); remove a pairing; re-pair after a mismatch | done; verified with the reference player (token injected, not displayed by a real player) |
| S5 | Pair with a code (dynamic or static pairing code, CPace) | not done (Group 11) |
| S6 | Coexist with another server: take a player only while playing; show "in use by another server"; take it back by playing again | done: idle connections carry no activity; `'playback'` is declared on play (also in the first activation of a connection made for playing) and dropped when the player is destroyed; verified with two servers on the reference player |
| S7 | Link players into a group: one queue, one stream, the same timestamps, group volume and mute, per-player output delay, "Play on" a member plays on its group | not done (Groups 6 and 7) |
| S8 | Show what the controller role and now-playing of other servers show, for the group YAACC leads | not planned |

### Player (the phone is a speaker)

| Id | Use case | Status |
|---|---|---|
| P1 | The phone is announced (mDNS) and driven by Music Assistant or another server; opt-in, off by default | not started (Groups 8 to 10) |
| P2 | Pairing as the client: show the pairing token (copy), keep paired servers with remove, setting "allow unpaired servers" with a warning | not started |
| P3 | Be one member of YAACC's own group, in process | not started |
| P4 | Take part in the multi-server rules as a client (activity ranking, last-playback server, `another_server`, `concurrent_attempt`) | not started |

### Controller and display (the phone controls another server)

| Id | Use case | Status |
|---|---|---|
| C1 | Control the group the phone is in on another server: play, pause, stop, next, previous, seek, volume, mute, repeat, shuffle | not started (Group 11.2) |
| C2 | Show now playing and artwork of that group | not started |
| C3 | `switch`: move the phone through the groups of that server | not started |

## The control channel (what a controller or a settings screen can reach)

| Direction | What | Where |
|---|---|---|
| player to server | state: `available`, volume, muted, output delay, required lead time, minimum buffer, `supported_commands`; the preferred format | `client/state` |
| server to player | commands: `volume`, `mute`, `set_output_delay`, only those the player lists; group volume is computed by the server with the spec's algorithm | `server/command` |
| controller to server | `client/command` with the `controller` object: play, pause, stop, next, previous, volume, mute, repeat, shuffle, seek, seek_relative, switch | controller role |
| server to controller | group volume, mute, repeat, shuffle, supported commands, seek range | `server/state` |
| server to display | metadata, artwork | metadata and artwork roles |
| **local only, no remote setting in the spec** | the name (from the player's hello), whether it admits unpaired servers, its pairing records, its audio device, its pairing token | the player's own UI |

The reference implementation (aiosendspin) has a management interface of its own (for example for its Pairing PSK); it is not in
the specification and YAACC does not use it.

## Rules that follow, for the code

1. **A server declares `'playback'` only while it plays to that client, and drops it when it stops** (spec, `server/activate`); at
   other times the connection idles with empty activities. This keeps YAACC from displacing Music Assistant by merely connecting.
2. Ties go to the activating side, so a playing server takes a player from another playing server: playing to a player is an
   explicit operator action and may do that; connecting is not.
3. A group is a server concept; the controller role never links players.
4. The phone as player and as server are separate features with separate switches (both off by default).

## Open points for the owner

1. **Activity timing (S6). Done and agreed by the owner (2026-11-24).** The code declared `'playback'` as soon as an approved or paired player says hello, whether or
   not anything plays. Recommended fix: declare it when a YAACC player starts to play to that player, drop it on stop; recommended
   before the first test with a second server on the network.
2. **Order of the next work.** Suggested: S6 fix, then the controller and display (C1 and C2, small, makes YAACC useful with Music
   Assistant), then groups (S7), then the phone as player (P1 to P4, the largest: listening socket, time filter, audio engine).
3. **Does the phone as player matter** if YAACC's own groups can already use the phone's speaker through the in-process path (P3)?
   P1 is what makes Music Assistant able to play on the phone.
4. Formats in groups (a common format for all members, or one converted stream per member), and whether groups survive a restart.
