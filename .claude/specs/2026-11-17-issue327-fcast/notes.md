# Notes: FCast protocol and receivers (research for #327)

Read first-hand on 2026-11-17 from a shallow clone of `https://github.com/futo-org/fcast` at commit `ea3982b31d751957e3cb6a114ee65504c7b91105` (2026-10-26, MIT licence). The hosted docs sites (`docs.fcast.org`, `fcast-docs.pages.dev`, `docs.rs`) were not reachable from the sandbox, so the markdown sources in the repository were used. Paths below are relative to that repository. Nothing here comes from memory unless marked (memory). The verified parts were moved into `docs/tech.md` (section "FCast (issue #327)") by task 1.1 on 2026-11-20; that section is the maintained one, this file keeps the longer research notes.

## What FCast is
An open protocol (MIT) to push audio, video and images from a sender to a receiver on the LAN. The sender gives the receiver a **URL** (HLS, DASH, mp4, ...) and then controls playback; the receiver fetches and plays the media itself (`README.md`). That is the same model as UPnP AVTransport, so YAACC's "play this item on that receiver" maps well. The FCast project ships receivers for Android (TV first, also phones and tablets, Android 7 and later; `receivers/android/README.md`) and desktop (Linux, Windows, macOS; the Rust receiver in `crates/receiver-*`), senders for desktop and terminal, and a sender SDK. Grayjay is a sender.

## Versions (`docs/docs/protocol/index.md`)
- v1 and v2: older, see `protocol/v1.md`, `v2.md`. v2 added `Version`, `Ping`, `Pong`, `PlaybackError`, `SetSpeed`.
- **v3**: JSON bodies over plain TCP. Adds `Initial`, `PlayUpdate`, `SetPlaylistItem`, `SubscribeEvent`, `UnsubscribeEvent`, `Event`, media metadata, playlists.
- **v4**: a new design (`protocol/v4.md`, 734 lines): TLS 1.3 in place on the same TCP connection, server authenticated by a pinned public key hash, **FlatBuffers** messages (opcode 20), a resource opcode (21) for the "companion" protocol, queue, tracks, subtitles, screen mirroring over WebRTC.
- The Android receiver in the repository announces and speaks v3 (`PROTOCOL_VERSION = 3L` in `receivers/android/.../models/Packets.kt`). The Rust receiver announces `v=4` and the fingerprint (`crates/receiver-core/src/application.rs` line 1391 to 1393) **and still serves sessions of v1, v2 and v3** (`SessionVersion::V3`, "Legacy" paths and `send_legacy_*` in `crates/receiver-core/src/fcast.rs`). Verify against a running receiver that a v3 sender is accepted.

## Transport and framing (v3, `protocol/v3.md`)
- TCP, default port **46899**.
- Packet: `uint32 size` (little endian, counts opcode plus body), `uint8 opcode`, then the body. No body: `size = 1`. Body: UTF-8 JSON. v3: the whole packet is at most 32 KB (`32000 - 1` bytes of body). (v4 raises the limit to 512 KiB and requires a disconnect on size 0 or an oversized packet.)
- Opcodes (v3): 1 Play, 2 Pause, 3 Resume, 4 Stop, 5 Seek, 6 PlaybackUpdate, 7 VolumeUpdate, 8 SetVolume, 9 PlaybackError, 10 SetSpeed, 11 Version, 12 Ping, 13 Pong, 14 Initial, 15 PlayUpdate, 16 SetPlaylistItem, 17 SubscribeEvent, 18 UnsubscribeEvent, 19 Event.
- Handshake: both sides send `Version {"version": n}` on connect; the higher one downgrades or disconnects; for v3 and above each side then sends `Initial`.
- Several senders can be connected to one receiver; the receiver relays `PlayUpdate`, `PlaybackUpdate`, `VolumeUpdate` and errors to all of them.

## Bodies (v3)
- `PlayMessage`: `container` (MIME, required), `url`, `content` (inline manifest or json), `time` (start, seconds), `volume` (0 to 1), `speed`, `headers` (map), `metadata` (`{type: 0, title, thumbnailUrl, custom}`). `container = application/json` lets the receiver take a `PlaylistContent` (`items` of `MediaItem`, `offset`, `forwardCache`, ...) from `url` or `content`.
- `SeekMessage {time}`, `SetVolumeMessage {volume}`, `SetSpeedMessage {speed}`, `SetPlaylistItemMessage {itemIndex}`.
- `PlaybackUpdateMessage`: `generationTime` (unix ms), `state` (0 Idle, 1 Playing, 2 Paused), `time`, `duration`, `speed`, `itemIndex` (all but the first two optional in v3).
- `VolumeUpdateMessage {generationTime, volume}`, `PlaybackErrorMessage {message}`.
- `InitialSenderMessage {displayName, appName, appVersion}`; `InitialReceiverMessage` adds `playData` (what is playing now) and, in the reference receiver, `experimentalCapabilities.av.livestream.whep` (`crates/fcast-protocol/src/v3.rs`).
- Events: `SubscribeEvent {event: {type}}` with `MediaItemStart` 0, `MediaItemEnd` 1, `MediaItemChange` 2, `KeyDown` 3, `KeyUp` 4 (keys: ArrowLeft, ArrowRight, ArrowUp, ArrowDown, Enter); the receiver answers with `Event {generationTime, event}`.
- **There is no mute message and no receiver settings message.** Volume is a float 0 to 1.

## Discovery
- mDNS / DNS-SD service type **`_fcast._tcp`**; the port is in the SRV record (`protocol/v4.md`, "Receiver discovery"; the Android receiver registers `_fcast._tcp` with `NsdManager`, `DiscoveryService.kt`).
- TXT records differ by generation: v4 (Rust receiver) `v` (highest protocol version) and `fp` (base64 SHA-256 of the certificate's SPKI); the Android v3 receiver sets `version`, `appName`, `appVersion`. Treat all of them as optional hints, never as authentication.
- Receivers are named `FCast-<hostname>` by default (`crates/receiver-core/src/mdns.rs`, `fcast_device_name`).
- Manual connection: IP and port (default 46899) (`docs/docs/receiver/index.md`). v4 adds a connection URL `fcast://r/<base64url JSON>` (QR code) with `name`, `addresses`, `services`, `txt`.
- The receiver docs say automatic discovery "may not work in all network configurations".

## v4 security (summary, for the later phase)
TLS 1.3 only, the sender is the TLS client and the receiver the server; the receiver certificate is self-signed and pinned by SPKI SHA-256 (`fp`); the sender must also verify `CertificateVerify`; `fp` from mDNS protects only against passive eavesdroppers (an attacker can spoof mDNS answers), the QR code channel is the trusted one; a sender that knows a receiver supports v4 **should refuse to fall back** to an earlier version (a downgrade attack on the plaintext `Version` exchange).

## What a receiver "has as settings"
The protocol has **no** operation to read or change a receiver's configuration. Configuration is local: the Rust receiver has a `config.toml` (`crates/receiver-core/src/config.rs`: discovery interface filter, enabled protocols, broadcast name, interface, video, log); the Android receiver has an on-screen player UI (`PlayerSettingsView.kt`). What a sender can control remotely: play, pause, resume, stop, seek, volume, speed (v3); v4 adds subtitle and audio **track selection** (`TracksAvailable`, `ChangeTrack`), adding a subtitle source, a queue (insert, remove, select), the progress update interval, and the receiver's advertised capabilities (formats, volume step). So a "settings" entry on a card can offer exactly those, plus app-side options and device information.

## Brand rules (`TRADEMARK.md`)
FCast is a registered trademark of FUTO. Third parties: do not use "FCast" as or at the start of a product name or as a published package name; descriptive use ("compatible with FCast", "supports the FCast protocol") is fine; do not use the logo as the app icon; the logo may be shown inside the app where it tells the user about protocol compatibility; the app must carry the disclaimer: "This application is an independent community implementation of the FCast protocol. It is not affiliated with FUTO. FCast is a registered trademark of FUTO." in the About screen, the README or the store listing. A question can go to the FUTO chat.

## Sender SDK (not used)
`sdk/sender` is a Rust SDK with Android (Kotlin/UniFFI) and Flutter bindings, so it carries native code. YAACC avoids native code and the protocol is small, so the plan is a plain Java implementation written from the documents (MIT would allow reuse, the reason not to is the native build, not the licence).

## Real receivers for a notebook
The project's website (`website/dist/index.html`) links the official downloads: Android APK (`dl.fcast.org/android/fcast-release.apk`), **Windows installer** (msi), **macOS image** (dmg), **Linux Flatpak**. The desktop receiver has a settings menu and a config file (`~/.config/fcast-receiver/config.toml`; `[fcast] name`, `[discovery] exclude_interfaces` and others) and a `headless` setting (`crates/receiver-core/config.example.toml`). There is no official container image; building the receiver from source compiles a pinned GStreamer fork (`receivers/desktop/README.md`). Receiver version at the recorded commit: 3.0.4 (2026-08-26, `receivers/desktop/CHANGELOG.md`).

## Test tools in the repository
`tools/fast` ("FCast's Automated Set of Tests") drives a real receiver through scripted protocol exchanges (`cargo fast run-all`); it is a Rust tool the owner could run against a receiver to see what a conforming receiver does. `senders/terminal` is a command line sender (`cargo`), and `receivers/android/README.md` explains port redirection to reach a receiver in the Android TV emulator (`redir add tcp:46899:46899`).

## Not checked
See the end of the FCast section of `docs/tech.md` ("Still to verify"): what is left is the owner's gate with real receivers.
