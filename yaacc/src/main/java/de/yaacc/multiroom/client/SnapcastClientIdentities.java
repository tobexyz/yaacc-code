/*
 *
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
package de.yaacc.multiroom.client;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/**
 * The app's own Snapcast client id for the code that only wants to recognise it (task 12.11): read from the install secret that the
 * service made at its first start, with no session and no server involved. Reading never creates the secret: without one the app has
 * never started a client and no entry of it can exist on a server (the id is null then, nothing is "own"). Thin Android glue; the
 * derivation is {@link SnapcastClientIdentity}. The id is never logged.
 */
public final class SnapcastClientIdentities {

    private SnapcastClientIdentities() {
    }

    /** @return the client id the phone gives every server, or null when this installation has no secret yet */
    public static String ownId(Context context) {
        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
            return SnapcastClientIdentity.idOfSecret(preferences.getString(SnapcastClientSpike.KEY_SECRET, null));
        } catch (RuntimeException damaged) {
            return null;
        }
    }
}
