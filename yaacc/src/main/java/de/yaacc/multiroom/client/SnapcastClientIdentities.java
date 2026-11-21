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

import java.util.List;

import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * The app's own Snapcast client id for the code that only wants to recognise it (task 12.11): read from the install secret that the
 * service made at its first start, with no session and no server involved. Reading never creates the secret: without one the app has
 * never started a client and no entry of it can exist on a server (the id is null then, nothing is "own"). Thin Android glue; the
 * derivation is {@link SnapcastClientIdentity}. The id is never logged.
 */
public final class SnapcastClientIdentities {

    private SnapcastClientIdentities() {
    }

    /**
     * @return the client id the phone gives every server, or null when this installation has no secret yet
     */
    public static String ownId(Context context) {
        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
            return SnapcastClientIdentity.idOfSecret(preferences.getString(SnapcastClient.KEY_SECRET, null));
        } catch (RuntimeException damaged) {
            return null;
        }
    }

    // the last answer: the ids are 3 hashes per known server, and the lists ask for them with every redraw
    private static String memoKey;
    private static SnapcastOwnIds memo;

    /**
     * The phone's current id and the legacy per-server ids of this install (task 12.19) for the given known servers and the address that
     * the listen test saved last. Needs the install secret; without one (this install never started a client) nothing is own. Never
     * creates the secret, never logs an id.
     */
    public static synchronized SnapcastOwnIds ownIds(Context context, List<SnapcastEndpoint> servers) {
        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
            String secret = preferences.getString(SnapcastClient.KEY_SECRET, null);
            if (!SnapcastClientIdentity.isSecret(secret)) {
                return SnapcastOwnIds.none();
            }
            String lastHost = preferences.getString(SnapcastClient.KEY_LAST_HOST, "");
            StringBuilder key = new StringBuilder(secret).append('|').append(lastHost);
            if (servers != null) {
                for (SnapcastEndpoint server : servers) {
                    key.append('|').append(server == null ? "" : server.toString());
                }
            }
            String wanted = key.toString();
            if (memo == null || !wanted.equals(memoKey)) {
                memo = SnapcastOwnIds.of(SnapcastClientIdentity.deriveId(secret), SnapcastLegacyClientIds.of(secret, servers, lastHost));
                memoKey = wanted;
            }
            return memo;
        } catch (RuntimeException damaged) {
            return SnapcastOwnIds.none();
        }
    }
}
