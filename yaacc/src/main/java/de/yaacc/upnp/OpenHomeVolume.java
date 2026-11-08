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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.upnp;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.openhome.OhControlException;
import de.yaacc.openhome.OhRemote;
import de.yaacc.upnp.callback.openhome.ClingOhControl;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.util.YaaccLogger;

/**
 * Volume and mute of a receiver that speaks OpenHome and has no RenderingControl service: they are set and read with the OpenHome
 * volume service. Blocks: not for the main thread.
 */
final class OpenHomeVolume {
    private OpenHomeVolume() {
    }

    /** @param hasRenderingControl whether the device has a RenderingControl service (that one is used when it is there) */
    static boolean handles(Device<?, ?, ?> device, boolean hasRenderingControl) {
        return device != null && !hasRenderingControl && ClingOhControl.findService(device, "Volume") != null
                && ClingOhControl.offersPlaylist(device);
    }

    private static OhRemote remote(Device<?, ?, ?> device, HttpRequestSender sender) {
        return new OhRemote(new ClingOhControl(device, sender));
    }

    static int percent(Device<?, ?, ?> device, HttpRequestSender sender) {
        try {
            return remote(device, sender).volumePercent();
        } catch (OhControlException | RuntimeException e) {
            YaaccLogger.d(OpenHomeVolume.class.getName(), "OpenHome volume not read: " + e.getMessage());
            return 0;
        }
    }

    static boolean muted(Device<?, ?, ?> device, HttpRequestSender sender) {
        try {
            return remote(device, sender).muted();
        } catch (OhControlException | RuntimeException e) {
            YaaccLogger.d(OpenHomeVolume.class.getName(), "OpenHome mute not read: " + e.getMessage());
            return false;
        }
    }

    static void setPercent(Device<?, ?, ?> device, HttpRequestSender sender, int percent) {
        try {
            remote(device, sender).setVolumePercent(percent);
        } catch (OhControlException | RuntimeException e) {
            YaaccLogger.d(OpenHomeVolume.class.getName(), "OpenHome volume not set: " + e.getMessage());
        }
    }

    static void setMuted(Device<?, ?, ?> device, HttpRequestSender sender, boolean mute) {
        try {
            remote(device, sender).setMute(mute);
        } catch (OhControlException | RuntimeException e) {
            YaaccLogger.d(OpenHomeVolume.class.getName(), "OpenHome mute not set: " + e.getMessage());
        }
    }
}
