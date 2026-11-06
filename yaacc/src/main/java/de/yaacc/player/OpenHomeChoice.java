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
package de.yaacc.player;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.upnp.callback.openhome.ClingOhControl;

/**
 * Which protocol the app uses to play on a receiver that offers both OpenHome and AVTransport: OpenHome (the device owns the
 * playlist) unless the user switched the device back to AVTransport. Audio only: OpenHome has no video and no images.
 */
public final class OpenHomeChoice {
    private static final String KEY_PREFIX = "openhome_use_avtransport_";

    private OpenHomeChoice() {
    }

    /** The rule, without Android. */
    static boolean decide(boolean deviceOffersPlaylist, boolean userChoseAvTransport, boolean audioOnly) {
        return deviceOffersPlaylist && !userChoseAvTransport && audioOnly;
    }

    public static boolean useOpenHome(Context context, Device<?, ?, ?> device, boolean audioOnly) {
        if (device == null) {
            return false;
        }
        return decide(ClingOhControl.offersPlaylist(device), choseAvTransport(context, udn(device)), audioOnly);
    }

    public static boolean offersOpenHome(Device<?, ?, ?> device) {
        return ClingOhControl.offersPlaylist(device);
    }

    public static boolean choseAvTransport(Context context, String udn) {
        return preferences(context).getBoolean(KEY_PREFIX + udn, false);
    }

    public static void setUseAvTransport(Context context, String udn, boolean useAvTransport) {
        SharedPreferences.Editor editor = preferences(context).edit();
        if (useAvTransport) {
            editor.putBoolean(KEY_PREFIX + udn, true);
        } else {
            editor.remove(KEY_PREFIX + udn);
        }
        editor.apply();
    }

    private static String udn(Device<?, ?, ?> device) {
        return device.getIdentity().getUdn().getIdentifierString();
    }

    private static SharedPreferences preferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }
}
