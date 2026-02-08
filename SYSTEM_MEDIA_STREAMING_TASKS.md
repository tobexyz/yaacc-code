# System Media Capture & Streaming - Implementation Tasks

## Overview
Add system audio and screen casting capabilities to YAACC's UPnP server, allowing Android device audio/video to be streamed to UPnP renderers in the network.

**Requirements:**
- Android 10+ (API 29) for audio/video capture
- MediaProjection API for capture
- Real-time encoding (audio: MP3/AAC, video: H.264/VP8)
- UPnP content directory integration
- HTTP streaming endpoint

---

## Phase 1: Core Infrastructure

### Task 1.1: Add MediaProjection Permission Handling
- [x] Add permission request in settings/UI
- [x] Handle user consent flow (system dialog)
- [x] Store MediaProjection instance
- [x] Handle permission revocation

**Files created:**
- `MediaProjectionHelper.java`

**Files modified:**
- `YaaccUpnpServerControlActivity.java`

### Task 1.2: Create Audio Capture Service
- [x] Implement AudioPlaybackCapture (Android 10+)
- [x] Handle buffer management
- [ ] Encode to MP3/AAC in real-time
- [ ] Handle apps that block capture (DRM)

**Files created:**
- `SystemAudioCaptureService.java`

**Files modified:**
- `YaaccUpnpServerService.java`

### Task 1.3: Create Video Capture Service
- [ ] Implement VirtualDisplay for screen capture
- [ ] Encode to H.264/VP8
- [ ] Handle resolution/bitrate configuration
- [ ] Handle orientation changes

**Files to create:**
- `ScreenCastCaptureService.java`
- `VideoEncoder.java`

### Task 1.4: Create Streaming Endpoint
- [x] Add HTTP streaming handler to existing server
- [x] Proper MIME types and headers
- [ ] Support range requests for seeking
- [ ] Handle multiple concurrent connections

**Files modified:**
- `YaaccUpnpServerContentHttpHandler.java`
- `LiveStreamFolderBrowser.java`

---

## Phase 2: UPnP Integration

### Task 2.1: Add Virtual Media Items
- [ ] Create "Live Audio Stream" item in content directory
- [ ] Create "Screen Cast" item in content directory
- [ ] Update content directory service
- [ ] Proper DLNA metadata

**Files to modify:**
- `YaaccContentDirectory.java`

### Task 2.2: Create LiveStreamFolderBrowser
- [x] New browser class for live streams
- [x] Returns system audio and screen cast items
- [x] Only shows items when capture is active
- [x] Proper UPnP container structure
- [x] Integrated into RootFolderBrowser
- [x] Integrated into YaaccContentDirectory

**Files to create:**
- `LiveStreamFolderBrowser.java`

### Task 2.3: Advertise Streams
- [ ] Notify UPnP clients when streams become available
- [ ] Update SystemUpdateID when starting/stopping
- [ ] Handle stream lifecycle events

**Files to modify:**
- `YaaccContentDirectory.java`
- `YaaccUpnpServerService.java`

---

## Phase 3: UI & Settings

### Task 3.1: Add Settings Options
- [x] Add checkbox: "System Audio Stream" in `preference.xml`
- [x] Add checkbox: "Screen Cast Stream" in `preference.xml`
- [x] Add string resources for titles/summaries (English only for now)
- [x] Add version gating in SettingsFragment
- [ ] Add quality/bitrate configuration options (deferred)

**Files to modify:**
- `yaacc/src/main/res/xml/preference.xml`
- `yaacc/src/main/res/values/strings.xml`
- `yaacc/src/main/res/values/setting_strings.xml`
- `yaacc/src/main/res/values-de/strings.xml`
- `yaacc/src/main/res/values-nl/strings.xml`
- `yaacc/src/main/res/values-fr/strings.xml`
- `yaacc/src/main/res/values-pt/strings.xml`
- `yaacc/src/main/res/values-es/strings.xml`
- `yaacc/src/main/res/values-zh/strings.xml`

**String keys to add:**
```
settings_local_server_serve_system_audio_chkbx
settings_local_server_serve_system_audio_title
settings_local_server_serve_system_audio_on
settings_local_server_serve_system_audio_off
settings_local_server_serve_screen_cast_chkbx
settings_local_server_serve_screen_cast_title
settings_local_server_serve_screen_cast_on
settings_local_server_serve_screen_cast_off
```

### Task 3.1b: Extend Server Control Activity
- [x] Add two checkboxes in `layout/activity_yaacc_upnp_server_control.xml` (portrait)
- [x] Add two checkboxes in `layout-land/activity_yaacc_upnp_server_control.xml` (landscape)
- [x] Wire up checkboxes in `YaaccUpnpServerControlActivity.java`
- [x] Read from SharedPreferences
- [x] Save on change
- [x] Hide/disable on Android < 10

**Checkboxes to add:**
- `systemAudioEnabled` (below `proxyEnabled`)
- `screenCastEnabled` (below `systemAudioEnabled`)

**Files to modify:**
- `yaacc/src/main/res/layout/activity_yaacc_upnp_server_control.xml`
- `yaacc/src/main/res/layout-land/activity_yaacc_upnp_server_control.xml`
- `yaacc/src/main/java/de/yaacc/upnp/server/configuration/YaaccUpnpServerControlActivity.java`

### Task 3.2: Add Control UI
- [ ] Start/stop capture buttons
- [ ] Status indicator (capturing/idle)
- [ ] Notification for active capture (required by Android)
- [ ] Show active stream info

**Files to modify:**
- `YaaccUpnpServerService.java` (notification)
- Server tab UI (optional)

---

## Phase 4: Polish

### Task 4.1: Version Gating
- [ ] Runtime checks for Android 10+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`)
- [ ] Hide/disable checkboxes on older devices
- [ ] Show informative messages ("Requires Android 10+")
- [ ] Graceful degradation

**Files to modify:**
- `YaaccUpnpServerControlActivity.java`
- `SettingsFragment.java`

### Task 4.2: Error Handling
- [ ] Handle app opt-out (DRM content blocking capture)
- [ ] Handle encoding failures
- [ ] Handle MediaProjection permission denial
- [ ] User-friendly error messages
- [ ] Logging for debugging

**Files to modify:**
- All capture/encoding services

### Task 4.3: Testing
- [ ] Test with various UPnP renderers (speakers, TVs, etc.)
- [ ] Test with different Android versions (8.1, 10, 13)
- [ ] Test with DRM apps (Spotify, YouTube)
- [ ] Battery/performance testing
- [ ] Memory leak testing
- [ ] Network interruption handling

---

## Technical Notes

### MediaProjection Code Pattern
```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    MediaProjectionManager manager = (MediaProjectionManager) 
        getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    Intent intent = manager.createScreenCaptureIntent();
    startActivityForResult(intent, REQUEST_CODE);
    
    // After permission granted:
    MediaProjection projection = manager.getMediaProjection(resultCode, data);
    
    // Audio capture
    AudioPlaybackCaptureConfiguration config = 
        new AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build();
    
    // Video capture
    VirtualDisplay display = projection.createVirtualDisplay(...);
}
```

### UPnP Content Structure
```
Root Folder
├── Music
├── Videos  
├── Images
├── SAF (Storage Access Framework)
└── Live Streams (NEW)
    ├── System Audio Stream (audio/mpeg)
    └── Screen Cast Stream (video/mp4)
```

### Limitations
- Apps can block audio capture using `setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)`
- Spotify, YouTube, and most DRM content will be blocked
- Local music players and non-DRM apps should work
- Requires foreground service notification (Android requirement)

---

## Progress Tracking
- [x] Phase 1: Core Infrastructure (1/4 tasks) - In Progress
- [x] Phase 2: UPnP Integration (1/3 tasks) - In Progress
- [x] Phase 3: UI & Settings (2/2 tasks) ✓
- [ ] Phase 4: Polish (0/3 tasks)

**Total: 4/12 major tasks completed**

---

## Notes
- Feature only available on Android 10+ (API 29)
- minSdkVersion: 27, targetSdkVersion: 33
- Majority of users will have access to this feature
