/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.player;

import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.mediarouter.media.MediaControlIntent;
import androidx.mediarouter.media.MediaRouteDescriptor;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouteProviderDescriptor;

import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * MediaRouteProvider that exposes YAACC itself as a castable device.
 * <p>
 * This provider registers YAACC in Android's system Cast picker so that
 * streaming apps (Spotify, YouTube Music, etc.) can discover and cast to YAACC.
 * When selected, cast commands are delegated to {@link YaaccCastController}.
 * <p>
 * Integration points:
 * - Registered in YaaccUpnpServerService when the UPnP server starts
 * - Unregistered when the UPnP server stops
 * - Cast commands eventually route to the currently selected receiver via UpnpClient
 * <p>
 * Usage:
 * <pre>
 *   YaaccSelfDeviceMediaRouteProvider provider =
 *       new YaaccSelfDeviceMediaRouteProvider(context, upnpClient);
 *   MediaRouter.getInstance(context).addProvider(provider);
 * </pre>
 *
 * @see YaaccCastController
 */
public class YaaccSelfDeviceMediaRouteProvider extends MediaRouteProvider {

    /**
     * Stable route ID for YAACC self-device route.
     */
    public static final String YAACC_ROUTE_ID = "yaacc_self_device";

    private static final String TAG = YaaccSelfDeviceMediaRouteProvider.class.getName();
    private static final int DEFAULT_VOLUME = 50;
    private static final int MAX_VOLUME = 100;
    /**
     * Max characters for the device name shown in Cast picker.
     */
    private static final int MAX_DEVICE_NAME_LENGTH = 30;

    private UpnpClient upnpClient;
    private final String deviceName;

    /**
     * Create the provider.
     *
     * @param context    Android context (application or service context)
     * @param upnpClient YAACC UPnP client for accessing players and receivers (may be null if not yet initialized)
     */
    public YaaccSelfDeviceMediaRouteProvider(@NonNull Context context,
                                             @Nullable UpnpClient upnpClient) {
        super(context);
        this.upnpClient = upnpClient;
        this.deviceName = buildDeviceName(context);
        if (upnpClient != null) {
            publishRoute();
        } else {
            YaaccLogger.d(TAG, "UpnpClient is null, route will be published later");
        }
    }

    /**
     * Build the display name shown in Cast pickers.
     * Format: "YAACC - [server name]" (truncated to MAX_DEVICE_NAME_LENGTH).
     * Reads the server name from SharedPreferences (settings_local_server_name_key).
     */
    private String buildDeviceName(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String serverName = prefs.getString("settings_local_server_name_key", "Yaacc");
        String fullName = "YAACC - " + serverName;
        if (fullName.length() > MAX_DEVICE_NAME_LENGTH) {
            fullName = fullName.substring(0, MAX_DEVICE_NAME_LENGTH);
        }
        return fullName;
    }

    /**
     * Publish the YAACC self-device route descriptor so Android can
     * discover it in the system Cast picker.
     */
    public void publishRoute() {
        if (upnpClient == null) {
            YaaccLogger.w(TAG, "Cannot publish route - UpnpClient is null");
            return;
        }

        YaaccLogger.d(TAG, "Publishing YAACC route: " + deviceName);

        // Create control filter for remote playback
        IntentFilter controlFilter = new IntentFilter();

        // Add Google Cast category (CRITICAL - YouTube Music specifically looks for this)
        controlFilter.addCategory("com.google.android.gms.cast.CATEGORY_CAST");

        // Add standard MediaControl intent categories (required for YouTube Music, Spotify, etc.)
        controlFilter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        controlFilter.addCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO);
        controlFilter.addCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO);

        // Add standard playback actions
        controlFilter.addAction(MediaControlIntent.ACTION_PLAY);
        controlFilter.addAction(MediaControlIntent.ACTION_PAUSE);
        controlFilter.addAction(MediaControlIntent.ACTION_RESUME);
        controlFilter.addAction(MediaControlIntent.ACTION_STOP);
        controlFilter.addAction(MediaControlIntent.ACTION_SEEK);
        controlFilter.addAction(MediaControlIntent.ACTION_GET_STATUS);

        // Add data schemes for media URLs
        controlFilter.addDataScheme("http");
        controlFilter.addDataScheme("https");

        // Add data types for common media formats (wrapped in try-catch)
        try {
            controlFilter.addDataType("audio/mpeg");
            controlFilter.addDataType("audio/mp4");
            controlFilter.addDataType("audio/*");
            controlFilter.addDataType("video/mp4");
            controlFilter.addDataType("video/*");
            controlFilter.addDataType("*/*");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            YaaccLogger.w(TAG, "Failed to add data types to control filter: " + e.getMessage());
        }

        YaaccLogger.d(TAG, "Control filter categories: REMOTE_PLAYBACK, LIVE_AUDIO, LIVE_VIDEO");
        YaaccLogger.d(TAG, "Control filter actions: PLAY, PAUSE, RESUME, STOP, SEEK, GET_STATUS");
        YaaccLogger.d(TAG, "Control filter schemes: http, https");
        YaaccLogger.d(TAG, "Control filter types: audio/*, video/*, */*");

        MediaRouteDescriptor route = new MediaRouteDescriptor.Builder(YAACC_ROUTE_ID, deviceName)
                .setDescription("YAACC Media Player")
                .setPlaybackType(android.media.MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                .setPlaybackStream(AudioManager.STREAM_MUSIC)
                .setVolumeHandling(android.media.MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(MAX_VOLUME)
                .setVolume(DEFAULT_VOLUME)
                .addControlFilter(controlFilter)
                // ✓ Mark as connected to signal readiness to casting apps
                .setConnectionState(0)  // MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED = 0
                .setEnabled(true)
                .build();

        MediaRouteProviderDescriptor descriptor =
                new MediaRouteProviderDescriptor.Builder()
                        .addRoute(route)
                        .build();

        setDescriptor(descriptor);
        YaaccLogger.d(TAG, "Published YAACC cast route: " + deviceName + " (connectionState=CONNECTED)");
    }

    /**
     * Update the UpnpClient and publish the route.
     * Called when the UpnpClient becomes available after initialization.
     *
     * @param upnpClient The newly initialized UpnpClient
     */
    public void updateUpnpClient(@NonNull UpnpClient upnpClient) {
        this.upnpClient = upnpClient;
        YaaccLogger.d(TAG, "UpnpClient updated, publishing route");
        publishRoute();
        // publishRoute() calls setDescriptor() which notifies the system of the change
        YaaccLogger.d(TAG, "Descriptor updated, system will re-query the provider");
    }

    /**
     * Called by MediaRouter when a cast app selects the YAACC route.
     * Returns a {@link YaaccCastController} to handle playback commands.
     */
    @Nullable
    @Override
    public RouteController onCreateRouteController(@NonNull String routeId) {
        YaaccLogger.d(TAG, "onCreateRouteController called with routeId=" + routeId);
        if (YAACC_ROUTE_ID.equals(routeId)) {
            YaaccLogger.d(TAG, "Creating route controller for YAACC self-device route");

            // Mark the route as CONNECTED so YouTube Music will send playback commands
            markRouteConnected();

            YaaccCastController controller = new YaaccCastController(getContext(), upnpClient, this);
            YaaccLogger.d(TAG, "YaaccCastController created: " + controller);
            return controller;
        }
        YaaccLogger.d(TAG, "onCreateRouteController: unknown routeId=" + routeId + " (expected: " + YAACC_ROUTE_ID + ")");
        return null;
    }

    /**
     * Mark the route as CONNECTED and republish.
     * This tells YouTube Music and other apps that we're ready to receive playback commands.
     */
    public void markRouteConnected() {
        if (upnpClient == null) {
            YaaccLogger.w(TAG, "Cannot mark route connected - UpnpClient is null");
            return;
        }

        // Create control filter for remote playback
        IntentFilter controlFilter = new IntentFilter();
        controlFilter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        controlFilter.addCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO);
        controlFilter.addCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO);
        controlFilter.addAction(MediaControlIntent.ACTION_PLAY);
        controlFilter.addAction(MediaControlIntent.ACTION_PAUSE);
        controlFilter.addAction(MediaControlIntent.ACTION_RESUME);
        controlFilter.addAction(MediaControlIntent.ACTION_STOP);
        controlFilter.addAction(MediaControlIntent.ACTION_SEEK);
        controlFilter.addAction(MediaControlIntent.ACTION_GET_STATUS);
        controlFilter.addDataScheme("http");
        controlFilter.addDataScheme("https");

        // Build route with CONNECTED state
        MediaRouteDescriptor route = new MediaRouteDescriptor.Builder(YAACC_ROUTE_ID, deviceName)
                .setDescription("YAACC Media Player")
                .setPlaybackType(android.media.MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                .setPlaybackStream(AudioManager.STREAM_MUSIC)
                .setVolumeHandling(android.media.MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(MAX_VOLUME)
                .setVolume(DEFAULT_VOLUME)
                .addControlFilter(controlFilter)
                .setEnabled(true)
                .build();

        MediaRouteProviderDescriptor descriptor =
                new MediaRouteProviderDescriptor.Builder()
                        .addRoute(route)
                        .build();

        setDescriptor(descriptor);
        YaaccLogger.d(TAG, "✓ Route marked CONNECTED - App can now send playback commands");
    }

    /**
     * Refresh the published route descriptor, e.g. after a device name change.
     * Safe to call from any thread — posts to main thread internally.
     */
    public void refreshRoute() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(this::publishRoute);
    }
}
