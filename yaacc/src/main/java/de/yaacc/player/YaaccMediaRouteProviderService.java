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

import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouteProviderService;

import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * Service wrapper for YAACC's MediaRouteProvider.
 *
 * <p>This service is registered in the manifest so the system and other apps can discover
 * YAACC as a remote playback receiver through the MediaRouter framework. The service wraps
 * {@link YaaccSelfDeviceMediaRouteProvider} to make it available system-wide.
 *
 * <p>When this service is started, it creates a {@link YaaccSelfDeviceMediaRouteProvider}
 * instance and publishes it to the MediaRouter framework.
 */
public class YaaccMediaRouteProviderService extends MediaRouteProviderService {
    private static final String TAG = YaaccMediaRouteProviderService.class.getName();
    private YaaccSelfDeviceMediaRouteProvider provider;

    @Override
    public MediaRouteProvider onCreateMediaRouteProvider() {
        YaaccLogger.d(TAG, "onCreateMediaRouteProvider called, thread=" + Thread.currentThread().getName());

        // Try to get UpnpClient
        UpnpClient upnpClient = getUpnpClient();

        if (upnpClient == null) {
            YaaccLogger.w(TAG, "UpnpClient is null in onCreateMediaRouteProvider, returning empty provider");
            // Create an empty provider (no routes yet)
            provider = new YaaccSelfDeviceMediaRouteProvider(this, null);

            // Start a background thread to check when UpnpClient becomes available
            new Thread(() -> {
                for (int i = 0; i < 30; i++) {  // Try for up to 15 seconds
                    try {
                        Thread.sleep(500);
                        UpnpClient retryClient = getUpnpClient();
                        if (retryClient != null) {
                            YaaccLogger.d(TAG, "UpnpClient became available after " + (i * 500) + "ms, updating provider");
                            provider.updateUpnpClient(retryClient);
                            break;
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "YaaccCastInitializer").start();

            return provider;
        }

        // Create the provider with UpnpClient
        YaaccLogger.d(TAG, "Creating YaaccSelfDeviceMediaRouteProvider with UpnpClient immediately");
        provider = new YaaccSelfDeviceMediaRouteProvider(this, upnpClient);
        YaaccLogger.d(TAG, "MediaRouteProvider created: " + provider);
        return provider;
    }

    /**
     * Get the UpnpClient instance from the application.
     */
    private UpnpClient getUpnpClient() {
        try {
            Object app = getApplication();
            android.util.Log.d(TAG, "getUpnpClient: app class = " + (app == null ? "null" : app.getClass().getName()));
            if (app instanceof de.yaacc.Yaacc) {
                UpnpClient client = ((de.yaacc.Yaacc) app).getUpnpClient();
                android.util.Log.d(TAG, "getUpnpClient: UpnpClient = " + (client == null ? "null" : "available"));
                return client;
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to get UpnpClient from application", e);
        }
        return null;
    }
}
