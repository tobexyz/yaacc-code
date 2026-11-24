# Linking: Sendspin groups with YAACC as the server (S7)

Status: **decided by the owner on 2026-11-24 (G1 to G7 as recommended, G2 changed: every player found can be linked); built: the shared timeline (`PcmStreamer` with members), the group model and the common rate, the hub (link, unlink, group volume), the app player (group streamer, keeper thread that joins and rejoins members), the link sheet and the card. Not done: measuring two real players against each other (the rig runs one player), persistence.** Written 2026-11-24. Builds on `usecases.md` (linking belongs to the server),
`decisions.md` (the MusicCast pattern: the leader leads, members follow, "Play on" a member plays on its group; one common format;
no persistence in the first version) and the code as it is (one `HubConnection` and one `PcmStreamer` per player, one `SendspinPlayer`
per player card).

## What the user gets
1. A **link button** on the card of an approved or paired player opens a sheet (like the Snapcast and MusicCast link sheets) that lists
   the other players that are ready (approved or paired, connected) with a tick each. The card is the **leader**; ticked players are
   **members**. Unlinking is the same sheet.
2. The leader's card shows the group: members' names, one **group volume** slider and mute (the leader's controls act on the group);
   the sheet shows the volume and the output delay of each member.
3. A member's own card says "in the group of <leader>"; playing to a member plays to its group (decided earlier).
4. One queue, one stream: the same audio, the same timestamps, every member plays in sync.

## How it works (the parts that change)
- **One timeline, many senders.** Today `PcmStreamer` owns the timeline (anchor at `now + lead`, re-anchor when late) and the pacing
  for one player. A group needs **one timeline** and **one sender per member**: a `GroupStreamer` cuts the PCM into chunks with the
  group's timestamps once; each member has its own sender with its own lead (required lead time and output delay differ) and its own
  buffer accounting (`buffer_capacity`). `write` waits until the chunk is due for the member that needs it earliest, so ExoPlayer stays at
  real time; a member that fails is dropped from the group, the others go on. `PcmStreamer` becomes the one-member case of this, so the
  existing tests stay valid.
- **Common format.** One PCM format for the group: the best format that every member lists (prefer 48000 Hz, then 44100 Hz; 16 bit,
  stereo). A player that lists no common format is not offered in the sheet (with the reason). The tap (`FeedTapChain`) converts to it
  once; it is chosen when the group starts, so adding a member that needs another rate waits for the next start.
- **Joining and leaving live.** A member that is added while the group plays gets `stream/start` and then the chunks from the current
  position on (the timestamps are absolute, so it plays in sync from where the group is). A member that is removed gets `stream/end`.
  A member whose connection breaks stays in the group and **rejoins by itself** when it is back (while the group exists).
- **Group volume and mute.** Members have their own volume. The leader's slider is the group volume: like the Snapcast group it moves the
  members' volumes by the same number of points (average shown), limited to 0 to 100; the exact algorithm of the specification
  ("group volume") must be read before this is built, because a controller that follows the spec shows the same number.
- **Output delay per member** (`set_output_delay`, only if the player lists it) is set in the sheet; it does not change the group's
  timeline.
- **The player of the app.** One `SendspinPlayer` per group (on the leader); it plays to every member. `PlayerService.createSendspinPlayer`
  gets the member list; a player that leaves the group ends its part. The volume keys and the card controls of the leader act on the group.
- **Not persisted** in the first version: a restart ends the groups (the members are separate cards again).
- **Limits (proposal):** at most 8 members in a group, music only (as now), one group per leader, a player is in one group at a time.
- **Safety:** every player that is found can be linked; linking approves it (the owner's decision G2, the same operator action as playing to it). A player that demands pairing and is not paired can not be linked until it is.

## Decisions for the owner
| # | Question | Recommendation |
|---|---|---|
| G1 | Where is the link button | on the card of a player, like Snapcast and MusicCast (not in the "Play on" list) |
| G2 | May an unapproved player be linked | **owner: yes, every player found can be linked.** Ticking a player in the link sheet is the operator action that clearly means to use it, so it approves it (as playing to it does); a player that demands pairing is listed with "needs pairing" and can not be ticked until it is paired |
| G3 | A member that drops out | stays in the group and rejoins by itself |
| G4 | Group volume | the Snapcast way (average, moved by the same points); the spec's algorithm is read first |
| G5 | Common format | best common PCM format of the members (48000 Hz first); a player with none is not offered |
| G6 | Size limit | 8 members |
| G7 | Order of work | (1) `GroupStreamer` with its tests against fake players and the reference player in the rig; (2) the hub and the app player; (3) the sheet and the card; (4) group volume |

## Tests
Pure tests first for the timeline, the per-member lead and the capacity accounting (a fake clock, fake outputs, several members, one
slow member, a member that fails, a late join, a seek (`stream/clear` to all), pause and resume). Then the rig: two reference players
(the rig runs one; a second is started on another port) and the measure of the players' own sync error against each other. The real
sync of two real devices is the owner's listening test.
