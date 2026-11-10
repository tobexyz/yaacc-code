# Decisions: Snapcast support (#319)

## 2026-11-09 - Scope: target + controls first, client second, no linking, no server role
**Context**: Snapcast clients take their server from their own command line; the server has no method to move a client (`notes.md`, Discussion 3). The owner wants a maintained Android option (Snapdroid is stale) but not the dynamic linking.
**Decision**: Phase A = YAACC feeds a snapserver and controls it. Phase B = the phone as a client (plain Java protocol). YAACC is never a snapserver. Phase B starts only after Phase A is released and used (own gate).
**Rationale**: Phase A is small, useful alone and testable with the rig; Phase B carries the real risk (sync on Android) and gets kill criteria.

## 2026-11-09 - One receiver per Snapcast group
**Context**: UPnP receivers are devices; a Snapcast group is the unit that has one stream and a set of clients.
**Decision**: A group is the receiver row; clients are members with own volume. Group changes use `Group.SetClients` / `Group.SetStream`. A group that disappears (all clients gone) disappears from the list.
**Rationale**: owner's choice (recommended option).

## 2026-11-09 - Runtime stream creation
**Context**: The documentation says `Stream.AddStream` accepts only pipe, file, tcp, alsa, jack, meta (a security restriction), but no such check was found in the sources (`docs/tech.md`); until the rig shows otherwise assume the server accepts any scheme. YAACC only ever creates `tcp` sources and never takes a stream URI from server data.
**Decision**: YAACC creates a tcp stream per phone (name "YAACC <device name>") on demand and removes it when idle for a while; fallback = documented `snapserver.conf` line when the server answers an error.
**Rationale**: owner's choice; no manual server setup.

## 2026-11-09 - Neutral package name
**Context**: MusicCast rule: respect trademarks, nominative use only, neutral code names.
**Decision**: Package `de.yaacc.multiroom` (sub-packages `protocol`, `control`, `discovery`, `feed`, `client`); "Snapcast" appears only as the product name in strings, docs and a README notice. Open for veto: Snapcast is GPL software by its author, not a brand like Yamaha, so `de.yaacc.snapcast` is acceptable if the owner prefers.
**Rationale**: consistent with `de.yaacc.yxc`, cheap to change now, costly later.

## 2026-11-09 - Authentication out of scope
**Context**: Snapcast 0.35 can require authorization (Basic) for control and for the Hello message of clients.
**Decision**: Not supported in Phase A/B; a server that demands it gives a clear error row. Revisit when a user needs it.
**Rationale**: no credential storage design yet; keeps the first release small.

## 2026-11-10 - Group 2 (control library): choices made while building it
**Context**: Group 2 (`de.yaacc.multiroom.control`) deviated from the wording of `tasks.md` in a few places, and the review of cycle 1 asked to record them.
**Decision**:
- *Fake server address*: the loopback fake server (`FakeSnapcastServer`) binds and is addressed by the literal `127.0.0.1`, not by `setCanonicalHostName("localhost")` as in the HTTP fake servers of other tests. A literal needs no name lookup, so no test depends on the resolver.
- *Timeouts*: a call has an **overall deadline of 10 s** (the figure of `design.md` "Security rules"), and a read has a **timeout of 5 s** (idle between two lines is normal; a stall inside a line drops the connection). The "5 s timeout" in the title of task 2.2 and in `design.md` "Control: JSON-RPC" meant the read timeout; task 2.2 is aligned, the design sentence is left as it is.
- *"Serial worker"*: calls run on the caller's thread (the blocking API is for the worker of a server, never the main thread). The **serial worker is the listener executor**: one task at a time, in the order of the lines, for notifications, lifecycle events and the ordered results of `callOrdered`. Besides that there is one reader thread per connection attempt and one writer thread (so a server that stops reading cannot hold a caller beyond its deadline).
- *Group volume*: the group volume is the average of the **connected** members; every member moves by the same number of points; a **muted member moves with the group and keeps its mute flag**; a disconnected member is not touched. At 0 and 100 the points that did not fit are **redistributed** among the members that still have room (the MusicCast version only clamps), so the average reaches the wanted value where the scale allows. A drag passes the levels from its start separately from the current levels (`GroupVolume.changes`), so steps do not add up.
- *Names*: the classes in the neutral package carry "Snapcast" in their names (`SnapcastRpc`, `SnapcastGroup`, ...) because `tasks.md` prescribes them. This is the one place where the word is used outside strings and docs, and the package name stays neutral. Open for veto together with the package decision above; renaming later is mechanical.
**Rationale**: each point was the simplest thing that satisfies the security rules and the tests; recording them stops the reviewer from finding them again as deviations.

## 2026-11-10 - Group 2 fixes (review cycle 1): how the findings were solved
**Context**: Review cycle 1 of Group 2 found six warnings (W1 to W6) and a list of suggestions.
**Decision**:
- *W1, order*: the result of a call that changes the model is handed to the listener executor **as an event in the same queue as the notifications** (`SnapcastCaller.callOrdered`, implemented by `SnapcastRpc`); the caller waits until the handler has run. `SnapcastOperations(caller, updater)` uses it for the status and for every change of our own. Alternative rejected: numbering the lines and letting `setStatus(status, line)` keep newer notifications, because a notification that is applied before the status arrives would have to be kept somewhere. `callOrdered` from a listener fails at once with `IllegalStateException` (it would wait for its own thread).
- *W2, scan*: a `"` outside a string is accepted only directly after `{`, `[`, `,` or `:` (white space aside), as Android's `JSONTokener` decides where a string starts. `de.yaacc.yxc.YxcJson.bracketDepth` has the same gap; it is older code and out of scope here (reported to the orchestrator).
- *W3, queue*: notifications of methods outside `SnapcastModelUpdater.isKnown` are dropped at the reader and only set the "read again" flag. The queue has a byte budget (4 MiB of lines, `SnapcastLimits.MAX_QUEUED_NOTIFICATION_BYTES`) besides the count (256); a notification is always taken into an empty queue, so the memory bound is the budget plus one line (1 MiB). A batch shares the size of its line between its notifications.
- *W4, silent peer*: a call that ends at its deadline closes the connection when its request is not out yet, **or** when no byte at all was received since the request was written. Any traffic (a notification, a part of an answer) shows that the peer is alive. The reconnect cycle (backoff, ceiling of 5) takes over. `reconnect()` replaces a connection from any state except CONNECTING and CLOSED.
- *W5, rig switch*: the one switch is the system property `snapcast.rig`, forwarded in `yaacc/build.gradle` only when it is set. The environment variable `SNAPCAST_RIG` was removed so that there is one documented way.
- *Own changes*: the server does not notify the connection that made a change (`server/server.cpp`), so `SnapcastOperations` patches the model after each successful call with the notification the others get; `Stream.AddStream` and `Stream.RemoveStream` cannot be patched and ask for a re-read.
- *Exceptions*: `SnapcastException.io` and the JSON syntax errors keep **no cause** (the text of a network exception or of an `org.json` error can hold the address or the server's text). `INTERNAL` keeps its cause: it comes from our own code or an `Error`. `toString()` of stream and server info prints fixed words and numbers only.
- *`setGroupClients`*: `null` and an empty list are caller errors (`IllegalArgumentException`, as documented for a wrong argument); an empty list would dissolve the group.
**Rationale**: smallest change that closes each finding without a second mechanism; the ordering fix also gives the own-change patch its ordering for free.

## 2026-11-10 - Stream.OnProperties arrives without params
**Context**: the model ignores the stream metadata; keeping 1 MB payloads in the notification queue only costs memory.
**Decision**: `Stream.OnProperties` is delivered to listeners with empty params and weight 1. A later group that needs track metadata must add the params for that method deliberately.
**Rationale**: security review rule "unknown or unused notifications are not queued with their payload"; found in review cycle 2/3 of Group 2.
