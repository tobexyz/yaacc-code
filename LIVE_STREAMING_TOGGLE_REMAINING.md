# Live Streaming Toggle Buttons - Remaining Work

## Current Status
✅ UI buttons added to local server device item
✅ Buttons show only on Android 10+
✅ Buttons hidden for remote devices

## Remaining Implementation

### 1. Wire up button click handlers
Location: `BrowseDeviceAdapter.ViewHolder` constructor

```java
streamAudioButton.setOnClickListener((v) -> {
    // Toggle audio streaming
    // Request MediaProjection permission if needed
    // Start/stop audio capture
});

streamVideoButton.setOnClickListener((v) -> {
    // Toggle video streaming  
    // Request MediaProjection permission if needed
    // Start/stop video capture
});
```

### 2. Handle MediaProjection permission from Fragment
- ServerListFragment needs to handle `startActivityForResult`
- Pass callback to adapter for permission requests
- Update button states after permission granted

### 3. Visual feedback for button states
- Use `setIconTint` to show active/inactive state
- Active: accent color
- Inactive: normal color

### 4. Start/stop streaming
- Call MediaProjectionHelper when buttons toggled
- Start capture services (Task 1.2, 1.3)
- Update content directory (notify SystemUpdateID changed)

### 5. Clean up old implementation
- Remove checkboxes from Settings (preference.xml)
- Remove checkboxes from Server Control Activity
- Remove setting strings (keep for now, might reuse)
- Remove MediaProjectionHelper.hasPermission() checks in activity

### 6. Persist button states in memory only
- Use static variables or application context
- Reset on app restart
- No SharedPreferences storage

## Testing Plan
1. Build and verify buttons appear on local server
2. Click buttons (will do nothing yet)
3. Implement click handlers
4. Test permission flow
5. Implement actual streaming

## Notes
- Buttons use audiotrack and devices icons (temporary)
- Can add better icons later
- Permission dialog behavior (navigating to app) is Android quirk, acceptable
