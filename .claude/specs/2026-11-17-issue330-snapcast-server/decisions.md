# Decisions: a Snapcast server in YAACC (#330)

## 2026-11-28 Owner's answers to the questions of `analysis.md`

1. **Scope of the first version: own playback and external feeders.** The server takes the audio of the app's own playback and also accepts external feeders on tcp stream sources (the port range 4953 to 4962 of a snapserver). The two are one mechanism: the app's own feed (Phase A of #319) creates a tcp stream with `Stream.AddStream` and pushes PCM to it, exactly as it does to an external snapserver; so the built-in server is, for the app, just another server on `127.0.0.1`.
2. **Codecs: PCM and FLAC from the start.** FLAC needs an encoder in the server (see `design.md`, "FLAC").
3. **Access: off by default, open on the local network.** An explicit switch; only local-network addresses are served; limits on connections, frames and memory; no shared secret in the first version (a stock snapclient has no way to present one). The docs and the switch text say plainly that any device on the network can then join, change groups and volume, and feed audio.
4. **Controllers of the first version: the app's own UI and stock snapclients.** The JSON-RPC subset is what the app's own control path (`multiroom.control`) needs, plus what a snapclient needs to join. Snapdroid, Home Assistant and Snapweb are not goals of the first version (their methods may work where they are in the subset; no promise).

## Defaults taken by the author (to be confirmed or changed by the owner)

- **Announcement:** when the switch is on, the server announces itself by mDNS (`_snapcast._tcp` and `_snapcast-ctrl._tcp`), as a snapserver does, so that clients and controllers find it; the announcement carries a name that tells it is a phone ("YAACC <phone name>"), see the risk of a second snapserver on the network in `analysis.md`.
- **Persistence:** groups (membership, stream, mute, name) and client settings (name, volume, latency) survive a restart; streams created at run time by the app's feed do not (the feed makes them again).
- **Lifecycle:** a foreground service with the usual rules (locks only while a stream has listeners and audio; the autostart and idle rules of the app apply).

## Standing decisions that this work revises

- #319 `decisions.md`: "YAACC is never a snapserver" and the design rule "no listener socket" of Phase B. Both are replaced for the server feature by `requirements.md` section "Security" and `design.md`. The Phase B client keeps its rule: it opens no listener.

## Related work on another branch

- The FLAC encoder written for the Sendspin hub (`feat/issue325`, `de.yaacc.sendspin.hub.FlacEncoder`, pure Java, checked by ffmpeg) does what this server needs (one FLAC frame per chunk, codec header). It is not on this branch. See `design.md`, "FLAC", for how the two are brought together.
