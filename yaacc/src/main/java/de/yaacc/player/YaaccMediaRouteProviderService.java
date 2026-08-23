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

import androidx.annotation.NonNull;
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
        YaaccLogger.d(TAG, "Creating MediaRouteProvider for YAACC self-device");
        
        // Get the YAACC application context to access UpnpClient
        UpnpClient upnpClient = getUpnpClient();
        if (upnpClient == null) {
            YaaccLogger.e(TAG, "Failed to get UpnpClient - provider will not be created");
            return null;
        }

        // Create the provider
        provider = new YaaccSelfDeviceMediaRouteProvider(this, upnpClient);
        provider.publishRoute();
        return provider;
    }

    /**
     * Get the UpnpClient instance from the application.
     */
    private UpnpClient getUpnpClient() {
        try {
            Object app = getApplication();
            if (app instanceof de.yaacc.Yaacc) {
                return ((de.yaacc.Yaacc) app).getUpnpClient();
            }
        } catch (Exception e) {
            YaaccLogger.e(TAG, "Failed to get UpnpClient from application", e);
        }
        return null;
    }
}
