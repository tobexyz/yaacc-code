# YAACC Changelog

## [Unreleased]

### Added
- **Device sheet** (new layout): Tapping the receiver bar at the bottom of the screen (or selecting it with the D-pad) opens a sheet listing every receiver with its controls (status, play/pause/stop, volume, mute) and, under each receiver, the yaacc players running on it in the order they were started; tapping a player opens its player screen. It opens at half height on phones (drag up for full height, drag down or Back to close) and at full height on Android TV, and its status updates live while it is open. The sheet has no selection checkboxes; receivers are chosen when you start something (see "Receiver picker"). The classic layout does not have the sheet: its Receiver and Player tabs are unchanged.
- **New single-screen layout**: An optional layout without tabs, chosen in *Settings* > *Appearance* > *Layout* ("Classic (tabs)" stays the default, also on Android TV). The top level is the server list with the refresh button, the live-stream buttons and the server switch; tapping a server shows its content, and back goes folder by folder, then to the server list, then exits. The toolbar has the shutdown timer as a single symbol (tap while it runs to stop it; otherwise it opens the duration picker and confirming a duration above 0 starts the timer; the remaining time is shown while it runs), settings, log, about and exit. The receiver bar at the bottom of every level shows the receivers that are playing and opens the device sheet. A mini-player above it shows every running player as one compact row (album art, title, receiver, previous, play/pause, next and stop buttons; tapping a row opens that player's screen), in the same order as the sheet's players, with about three rows visible and the list scrolling inside; it is hidden when nothing is running. A change of the setting applies when you return to the app screen, and running players are not interrupted.
- **Share a file to yaacc**: Choose yaacc in another app's share menu to play one audio, video or image file on receivers in your network. The file is not copied when the sending app lets yaacc read it directly (seeking works); if it cannot be read that way, it is copied to the app's cache first (up to 512 MiB, deleted afterwards). Nothing is opened or played until you pick receivers; one share can wait at a time and it is discarded after two minutes, on cancel, or when the screen is rotated. Several files, other file types and `file:` shares are ignored. A shared file is served through the media proxy's built-in HTTP server, which always runs while the app runs, whatever the proxy and local server settings say; nothing is started or stopped for a share.
- **Receiver picker "Play on…"** (new layout): Play, play all, tapping an item and add to playlist, and every shared link or file, first open a list of receivers with checkboxes (nothing ticked at first). "OK" starts the action on exactly the ticked receivers and then clears the selection; cancel, back, tapping outside or dragging down change nothing. The classic layout is unchanged except that a shared file opens the same list there, with the selected receivers ticked and restored afterwards; a shared link still plays on the selected receivers in classic.
- **Setting "Restrict the media proxy to the chosen receivers"** (on by default): A shared file or link is only served to the network addresses of the receivers chosen for it (this phone's own addresses only if this device was chosen); other requests get "403". Switch it off if a chosen receiver is refused (for example one that fetches from another address than it announces). With it off, any device that knows the share's address can fetch it while it is shared; the random key in the address is visible to any UPnP control point that can query the receiver, so it is not a protection then.

### Changed
- **Receiver bar and selection (new layout)**: The receiver bar shows the names of the receivers that currently have a player ("No receiver playing" otherwise) instead of a selection, and there is no persistent receiver selection in the new layout any more. The classic layout keeps its receiver tab selection and its static two-line footer.
- **Media proxy**: Shared links are no longer stored in the app's settings. Each share gets a random address kept in memory, it ends 10 minutes after its player ended (at most 6 hours), up to 8 shares are active at once, and entries left in the settings by earlier versions are deleted at start.
- **No Android backup**: Yaacc no longer takes part in Android's automatic backup (Google Drive / device transfer), so shared proxy links, the receiver selection and the settings are not copied off the device. After reinstalling or moving to a new phone the settings have to be set up again.
- **Sharing into yaacc**: Shared text must be one `http` or `https` link of up to 2048 characters; with the media proxy on, links to this phone itself or to link-local addresses are refused. Shared `file:` links and paths, and URIs that arrive as clip data with a text share, no longer work; files are shared as described above.
- **Launcher entry**: The app now has a separate invisible launcher entry that forwards to the chosen layout, and it only passes on a shared link or one shared file, nothing else of the incoming intent. It now also appears in the share menu for audio, video and image files.
- **Idle exit**: One process-wide timer now quits the app after 5 minutes in the background when nothing is playing and the server, renderer and proxy options are off, instead of one timer per screen; a screen rotation or a layout change no longer leaves a stale timer behind.

### Fixed
- **Receiver with several players**: When one receiver runs more than one yaacc player (e.g. an image show with background music), its row in the Receiver tab now always shows and opens the most recently started one that is playing (or, if none is playing, the most recently started one), instead of an arbitrary one.
- **Leaks**: The shutdown timer listener, the screen-capture stop callback and the share handler's worker thread are released when their screen goes away, instead of keeping the old screen alive. Browser screens that were destroyed also stop receiving device updates.
- **Shutdown timer state**: The Server tab now takes the shutdown timer's state (switch and remaining time) from the running timer itself, so it is correct whenever the screen is recreated or the layout is switched, and a destroyed screen is no longer kept alive by the timer.

---

## [5.4.0] - 2026-10-22

### Added
- **Sort by Name/Date (#252)**: Added a "Name"/"Date" sort toggle to the Content tab's folder header. Uses server-side UPnP `SortCriteria` (`-dc:date`) when the browsed server supports it, with a client-side fallback sort once a folder finishes loading for servers that don't. The Date option is disabled when a folder has no `dc:date` metadata at all. The chosen sort order is persisted across app restarts.
- **Server-side Sort Honoring (#252)**: yaacc's own UPnP/DLNA server now honors the `SortCriteria` other apps/devices send when browsing yaacc's shared media, instead of silently ignoring it. `GetSortCapabilities` now advertises `dc:title,dc:date` instead of an empty list, and a `Browse` request for `+dc:title`/`-dc:title` (name) or `+dc:date`/`-dc:date` (date) returns music, images, video, and SAF-backed folder results in that order across every content type the server exposes. A request for an unsupported sort property is rejected with `UNSUPPORTED_SORT_CRITERIA` rather than being accepted and ignored. Browsing without `SortCriteria` is unaffected and keeps today's existing order.
- **Android TV support (#244)**: Added a leanback launcher entry so yaacc can be launched from Android TV's home screen.

### Fixed
- **Play-all sort order**: "Play all from here" now follows the active on-screen sort order instead of silently reverting to the server's default order for every item after the one tapped.
- **Path display**: Fixed the sort toggle overlapping the content list on the root folder, and several path-label layout/alignment bugs (server name not always shown, incorrect indentation in landscape).
- **httpclient5/httpcore5 crash**: Reverted a dependency update that crashed on Android; pinned back to the last known-safe versions.
- **Oversized bitmap decoding (#285)**: Several code paths (the full-screen image viewer, thumbnail/icon loading, and embedded album art decoding) decoded bitmaps at full native resolution with no downsampling, which was slow and could crash with `Canvas: trying to draw too large bitmap` for high-resolution photos (e.g. 50MP camera output). Bitmaps are now downsampled during decode instead of being decoded at native resolution and scaled down afterward.

---

## [5.3.0] - 2026-09-24

### Added
- Images are now displayed based on their EXIF orientation.

### Changed
- Volume/mute controls are now hidden when the device does not support them.

### Fixed
- Fixed icon tinting in light mode.
- Fixed pause/stop/play on the local player.
- Reduced flickering of volume controls and fixed volume percentage text misalignment.

---

## [5.2.0] - 2026-06-27

### Added
- Allow stopping the app via the action button in the notification.

### Changed
- Improved media type extraction and fallback title for SAF content.
- Server now uses the media filename or title as the filename in the content URL.
- Stop the app completely if idle in the background.

### Fixed
- Fixed app crash on unknown HTTP codes in UPnP responses.
- Fixed pause/play on the yaacc renderer.

---

## [5.1.0] - 2026-05-17

### Added
- The network interface the app listens on is now selectable.
- Allow usage of a local mobile hotspot.

### Changed
- Server control settings icon is now always visible in the server tab.

### Fixed
- General bug fixes (not itemized in the original release notes).

---

## [5.0.1] - 2026-04-09

### Fixed
- Fixed crash on startup if no network device is found.
- Fixed problems with device lookup.

---

## [5.0.0] - 2026-02-21

### Added
- **SAF Short ID System**: Implemented short numeric ID mapping to fix UPnP browsing errors caused by long ObjectIDs (97% size reduction)
- **Clear Cache Button**: Added button in Server Control Activity to clear SAF cache and trigger reindexing
- **Image Preload**: Extended preload indexing to include image files (previously only audio/video)
- **Preload Timeout**: Added 5-minute timeout to prevent indefinite hanging during indexing
- **Permission Checks**: Added read permission checks before traversing SAF folders
- **Notification Permission**: Added POST_NOTIFICATIONS permission request on startup (Android 13+)
- **Lightweight Content Wrappers**: Created YaaccItem, YaaccMusicTrack, YaaccPhoto, YaaccRes classes for faster browsing
- **ProtocolInfo Cache**: Pre-created ProtocolInfo for common MIME types to eliminate repeated parsing

### Changed
- **Renamed**: DurationCacheManager → SAFCacheManager (better reflects actual purpose)
- **Extended Cache**: Now caches duration, MIME type, file size, and shortId for all files
- **Optimized Item Creation**: Eliminated Cling library overhead (~5.5x faster, from ~250ms to ~45ms per item)
- **Improved Logging**: Better logging for cache operations and preload progress
- **ID Persistence**: ID counter now persisted in SharedPreferences to maintain stable IDs across restarts

### Fixed
- Fixed SAF folder browsing hanging on folders without read permission
- Fixed preload getting stuck on large or inaccessible folders
- Fixed ID counter not persisting, causing ID collisions after restart
- Fixed cache not including image files
- Fixed UPnP `UPNP_E_BAD_RESPONSE` errors caused by excessively long ObjectIDs

### Performance
- SAF browsing: 70% faster after initial cache population (3.75s → 1.1s for 15 files)
- Item creation: 5.5x faster (~250ms → ~45ms per item)
- ObjectID length: 97% reduction (250 chars → 8 chars)
- Cache hit time: <1ms (memory), <50ms (disk)

### Breaking Changes
- Cache format changed: Old cache entries automatically migrated to include shortId
- ID format changed: ObjectIDs now use short numeric IDs instead of Base64-encoded URIs
- Desktop UPnP clients may need to clear cache and re-browse after update

---

## [4.4.1] - Previous Release

### Added
- Modernized image viewer with zoom support (PhotoView library)
- Media controls on lock screen
- Volume controls using Media Router API (standard Android implementation)
- System audio streaming (Android 10+)
- Screen cast streaming using MJPEG
- Experimental MPEG-TS streaming
- Smart logger for better debugging
- Unit tests for core functionality

### Changed
- Refactored player service to align with recent Android APIs
- Implemented ExoPlayer and Media3 MediaSession
- Smart WiFi lock management to reduce battery drain
- Improved DLNA metadata
- Modernized TabBrowser activity
- Improved performance in content browsing
- LRU cache for SAF file scanning

### Fixed
- Fixed renderer service issues
- Fixed album art in notifications
- Fixed restart during pause
- Fixed background service not stopping correctly
- Fixed issue #151 (network stack)
- Fixed issue #186 (media controls)
- Fixed issue #187 (media resource selection)
- Fixed issue #110 (network stack refactoring)
- Fixed issue #105 (player service refactoring)
- Fixed issue #93 (volume controls)
- Fixed issue #81 (audio streaming)
- Fixed issue #121 (screen cast)

### Removed
- Removed dead classes after refactoring
- Cleaned up deprecated player implementations

---

## Migration Notes

### From 4.4.1 to 5.0.0
1. **SAF Cache**: Existing cache entries will be automatically migrated on first access
2. **Desktop Clients**: Clear UPnP client cache and re-browse YAACC server after update
3. **Permissions**: Grant POST_NOTIFICATIONS permission when prompted (Android 13+)
4. **Cache Management**: Use new "Clear SAF Cache" button in Server Control Activity if needed

---

## Technical Details

### SAF Short ID Implementation
- Bidirectional URI ↔ shortId mapping in SAFCacheManager
- Persistent ID counter in SharedPreferences (`saf_id_counter`)
- Automatic migration of old cache entries
- ObjectID format: `1100999` + shortId (e.g., `11009991`, `11009992`)
- URL format: `http://ip:port/saf/11009991/1.mp3`

### Performance Optimizations
- Eliminated Cling Property/ArrayList overhead in item creation
- Cached ProtocolInfo for common MIME types
- Lightweight wrapper classes instead of heavy Cling objects
- Deferred Cling conversion until UPnP response generation

### Cache Improvements
- Memory cache: LRU with 1000 entry limit
- Disk cache: SharedPreferences with automatic trimming
- Preload: Background indexing with timeout and permission checks
- Metadata: Duration, MIME type, file size, shortId for all media files

---

## Known Issues
- Preload may take several minutes for large SAF folders (5-minute timeout)
- Desktop UPnP clients must refresh cache after YAACC cache clear
- Local device in Receiver tab doesn't show status badge (not a UPnP renderer)

---

## Credits
- SAF Short ID implementation: tobexyz
- Content browsing optimization: tobexyz
- Cache management improvements: tobexyz
