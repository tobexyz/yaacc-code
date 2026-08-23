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

import android.os.Bundle;
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
 *
 * This provider registers YAACC in Android's system Cast picker so that
 * streaming apps (Spotify, YouTube Music, etc.) can discover and cast to YAACC.
 * When selected, cast commands are delegated to {@link YaaccCastController}.
 *
 * Integration points:
 * - Registered in YaaccUpnpServerService when the UPnP server starts
 * - Unregistered when the UPnP server stops
 * - Cast commands eventually route to the currently selected receiver via UpnpClient
 *
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

    /** Stable route ID for YAACC self-device route. */
    public static final String YAACC_ROUTE_ID = "yaacc_self_device";

    private static final String TAG = YaaccSelfDeviceMediaRouteProvider.class.getName();
    private static final int DEFAULT_VOLUME = 50;
    private static final int MAX_VOLUME = 100;
    /** Max characters for the device name shown in Cast picker. */
    private static final int MAX_DEVICE_NAME_LENGTH = 30;

    private final UpnpClient upnpClient;
    private final String deviceName;

    /**
     * Create the provider.
     *
     * @param context    Android context (application or service context)
     * @param upnpClient YAACC UPnP client for accessing players and receivers
     */
    public YaaccSelfDeviceMediaRouteProvider(@NonNull Context context,
                                              @NonNull UpnpClient upnpClient) {
        super(context);
        this.upnpClient = upnpClient;
        this.deviceName = buildDeviceName(context);
        publishRoute();
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
        // Remote playback filter: announces that this route handles streaming playback
        // from a casting app (e.g. Spotify, YouTube Music).
        IntentFilter remotePlaybackFilter = new IntentFilter();
        remotePlaybackFilter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        remotePlaybackFilter.addCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO);
        remotePlaybackFilter.addCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO);
        //remotePlaybackFilter.addCategory("com.google.android.gms.cast.CATEGORY_CAST");
        //remotePlaybackFilter.addCategory("android.media.route.feature.LIVE_VIDEO");
        //remotePlaybackFilter.addCategory("android.media.route.feature.LIVE_AUDIO");
        remotePlaybackFilter.addCategory("android.media.route.feature.REMOTE_PLAYBACK");
        remotePlaybackFilter.addAction(MediaControlIntent.ACTION_PLAY);
        remotePlaybackFilter.addAction(MediaControlIntent.ACTION_PAUSE);
        remotePlaybackFilter.addAction(MediaControlIntent.ACTION_RESUME);
        remotePlaybackFilter.addAction(MediaControlIntent.ACTION_STOP);
        remotePlaybackFilter.addAction(MediaControlIntent.ACTION_SEEK);
        remotePlaybackFilter.addAction(MediaControlIntent.ACTION_GET_STATUS);
        remotePlaybackFilter.addDataScheme("http");
        remotePlaybackFilter.addDataScheme("https");
        //remotePlaybackFilter.addDataScheme("cast");
        //remotePlaybackFilter.addDataAuthority("CC32E753", null); //sptfy

        // Bundle extras = new Bundle();
        // extras.putString("com.google.android.gms.cast.EXTRA_CAST_APPLICATION_ID", "CC32E753");

        // Live audio filter: generic audio routing category
        IntentFilter liveAudioFilter = new IntentFilter();
        liveAudioFilter.addCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO);

        // Live video filter: video streaming and mirroring category
        IntentFilter liveVideoFilter = new IntentFilter();
        liveVideoFilter.addCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO);

        MediaRouteDescriptor route = new MediaRouteDescriptor.Builder(YAACC_ROUTE_ID, deviceName)
                .setDescription("YAACC Media Player")
                .setPlaybackType(android.media.MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                .setPlaybackStream(AudioManager.STREAM_MUSIC)
                .setVolumeHandling(android.media.MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(MAX_VOLUME)
                .setVolume(DEFAULT_VOLUME)
                .addControlFilter(remotePlaybackFilter)
          //      .setExtras(extras)
                //.addControlFilter(liveAudioFilter)
                //.addControlFilter(liveVideoFilter)
                .setEnabled(true)
                .build();

        MediaRouteProviderDescriptor descriptor =
                new MediaRouteProviderDescriptor.Builder()
                        .addRoute(route)
                        .build();

        setDescriptor(descriptor);
        YaaccLogger.d(TAG, "Published YAACC cast route: " + deviceName);
    }

    /**
     * Called by MediaRouter when a cast app selects the YAACC route.
     * Returns a {@link YaaccCastController} to handle playback commands.
     */
    @Nullable
    @Override
    public RouteController onCreateRouteController(@NonNull String routeId) {
        if (YAACC_ROUTE_ID.equals(routeId)) {
            YaaccLogger.d(TAG, "Creating route controller for YAACC self-device route");
            return new YaaccCastController(getContext(), upnpClient);
        }
        YaaccLogger.d(TAG, "onCreateRouteController: unknown routeId=" + routeId);
        return null;
    }

    /**
     * Refresh the published route descriptor, e.g. after a device name change.
     * Safe to call from any thread — posts to main thread internally.
     */
    public void refreshRoute() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(this::publishRoute);
    }
}
