# YAACC Changelog

## [Unreleased] - 2026-02-21

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

### From 4.4.1 to Unreleased
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
