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
import android.media.MediaRouter;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.mediarouter.media.MediaRouteDescriptor;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouteProviderDescriptor;
import androidx.mediarouter.media.MediaRouter.ControlRequestCallback;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * MediaRouteProvider for UPnP/DLNA devices.
 * Exposes UPnP renderers as Media Router routes for system-wide volume control.
 */
public class YaaccMediaRouteProvider extends MediaRouteProvider {
    private final UpnpClient upnpClient;
    private final List<Device> upnpDevices = new ArrayList<>();

    public YaaccMediaRouteProvider(Context context, UpnpClient upnpClient) {
        super(context);
        this.upnpClient = upnpClient;
        publishRoutes();
    }

    /**
     * Publish available UPnP devices as routes.
     */
    private void publishRoutes() {
        MediaRouteProviderDescriptor.Builder builder = new MediaRouteProviderDescriptor.Builder();

        // Get UPnP devices from registry
        upnpDevices.clear();
        upnpDevices.addAll(upnpClient.getDevices());

        for (Device device : upnpDevices) {
            if (isMediaRenderer(device)) {
                MediaRouteDescriptor route = createRouteForDevice(device);
                builder.addRoute(route);
            }
        }

        setDescriptor(builder.build());
        YaaccLogger.d(getClass().getName(), "Published " + upnpDevices.size() + " UPnP routes");
    }

    /**
     * Check if device is a media renderer.
     */
    private boolean isMediaRenderer(Device device) {
        return device.findService(
                new org.fourthline.cling.model.types.UDAServiceType("AVTransport")
        ) != null;
    }

    /**
     * Create MediaRouteDescriptor for UPnP device.
     */
    private MediaRouteDescriptor createRouteForDevice(Device device) {
        String routeId = device.getIdentity().getUdn().getIdentifierString();
        String name = device.getDetails().getFriendlyName();

        return new MediaRouteDescriptor.Builder(routeId, name)
                .setDescription(device.getDetails().getModelDetails().getModelDescription())
                .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(100)
                .setVolume(50)
                .addControlFilter(new IntentFilter("android.media.action.PLAY"))
                .build();
    }

    @Nullable
    @Override
    public RouteController onCreateRouteController(@NonNull String routeId) {
        Device device = findDeviceById(routeId);
        if (device != null) {
            return new UpnpRouteController(device);
        }
        return null;
    }

    private Device findDeviceById(String routeId) {
        for (Device device : upnpDevices) {
            if (device.getIdentity().getUdn().getIdentifierString().equals(routeId)) {
                return device;
            }
        }
        return null;
    }

    /**
     * Route controller for UPnP device.
     */
    private class UpnpRouteController extends RouteController {
        private final Device device;
        private AVTransportPlayer player;

        UpnpRouteController(Device device) {
            this.device = device;
        }

        @Override
        public void onSelect() {
            YaaccLogger.d(getClass().getName(), "Route selected: " + device.getDetails().getFriendlyName());
            // Create player for this device
            String name = device.getDetails().getFriendlyName();
            player = new AVTransportPlayer(upnpClient, device, name, name, "audio/*,video/*,image/*");
        }

        @Override
        public void onUnselect() {
            YaaccLogger.d(getClass().getName(), "Route unselected");
            if (player != null) {
                player.exit();
                player = null;
            }
        }

        @Override
        public void onRelease() {
            YaaccLogger.d(getClass().getName(), "Route released");
            onUnselect();
        }

        @Override
        public void onSetVolume(int volume) {
            YaaccLogger.d(getClass().getName(), "Set volume: " + volume);
            if (player != null) {
                player.setVolume(volume);
            }
        }

        @Override
        public void onUpdateVolume(int delta) {
            YaaccLogger.d(getClass().getName(), "Update volume: " + delta);
            if (player != null) {
                int currentVolume = player.getVolume();
                player.setVolume(currentVolume + delta);
            }
        }

        @Override
        public boolean onControlRequest(android.content.Intent intent, ControlRequestCallback callback) {
            YaaccLogger.d(getClass().getName(), "Control request: " + intent.getAction());

            if (player == null) {
                return false;
            }

            String action = intent.getAction();
            if ("android.media.action.PLAY".equals(action)) {
                player.play();
                return true;
            } else if ("android.media.action.PAUSE".equals(action)) {
                player.pause();
                return true;
            } else if ("android.media.action.STOP".equals(action)) {
                player.stop();
                return true;
            }

            return false;
        }
    }

    /**
     * Refresh routes when UPnP devices change.
     * Must be called on main thread.
     */
    public void refreshRoutes() {
        new Handler(Looper.getMainLooper()).post(this::publishRoutes);
    }
}
