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
package de.yaacc.multiroom;

import android.content.Context;
import android.content.SharedPreferences;

import de.yaacc.multiroom.feed.SnapcastStreams;

/**
 * Keeps the per-install marker of YAACC's Snapcast stream name ({@code YAACC <phone name> <marker>}) in a preferences
 * file of its own, private to the app (and outside every backup: the manifest turns them off). The marker is 8 random
 * lower-case hex characters made by {@link SnapcastStreams} from a {@code SecureRandom}; it is never derived from the
 * Android id, a serial number, a MAC address or the UPnP UDN. It is an identifier that every controller of the server
 * can read in the stream name, not a secret.
 *
 * <p>Loading is strict: anything that is not exactly 8 characters of {@code 0-9a-f} counts as "nothing stored" (a new
 * marker is made then), so a changed or damaged file can never put anything else into a stream name or a URI. Saving
 * refuses the same.
 */
public final class SnapcastMarkerStore implements SnapcastStreams.MarkerStore {

    /** The preferences file. */
    static final String FILE = "snapcast_feed";
    static final String KEY = "marker";
    static final int LENGTH = 8;

    /** A string in a place that survives restarts; the app uses a private preferences file. */
    interface Storage {
        String read();

        void write(String text);
    }

    private final Storage storage;

    SnapcastMarkerStore(Storage storage) {
        this.storage = storage;
    }

    /** The marker in the private preferences of the app. */
    public static SnapcastMarkerStore forContext(Context context) {
        final SharedPreferences preferences = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return new SnapcastMarkerStore(new Storage() {
            @Override
            public String read() {
                return preferences.getString(KEY, null);
            }

            @Override
            public void write(String text) {
                preferences.edit().putString(KEY, text).apply();
            }
        });
    }

    @Override
    public String load() {
        String stored = storage.read();
        return isValid(stored) ? stored : null;
    }

    @Override
    public void save(String marker) {
        if (isValid(marker)) {
            storage.write(marker);
        }
    }

    /** True for exactly 8 characters of 0-9a-f. */
    static boolean isValid(String text) {
        if (text == null || text.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = text.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }
}
