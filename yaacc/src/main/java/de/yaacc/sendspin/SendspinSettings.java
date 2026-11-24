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
package de.yaacc.sendspin;

/**
 * The keys and defaults of the Sendspin settings (the keys that have a preference are the names of string resources). Pure.
 * The switch is <b>off by default</b> until the app part has been tried on a device (decision of 2026-11-24 in the spec).
 */
public final class SendspinSettings {
    /** The key of the switch that makes the app list and use Sendspin players. */
    public static final String KEY_ENABLED = "settings_sendspin_enabled";
    public static final boolean ENABLED_DEFAULT = false;
    /** The key of the players that the user added by address ({@code host:port}). */
    public static final String KEY_PLAYERS = "sendspin_players";
    /** The key of the ids of the players the operator approved. */
    public static final String KEY_APPROVED = "sendspin_approved";
    /** The key of the private key of this server identity (app-private storage; backup is off). */
    public static final String KEY_IDENTITY = "sendspin_identity";

    /** The most players the app remembers. */
    public static final int MAX_PLAYERS = 16;
    /** The most approvals the app remembers (the oldest are dropped). */
    public static final int MAX_APPROVALS = 64;

    /** Reads a boolean preference (the shape of {@code SharedPreferences.getBoolean}). */
    public interface BooleanSource {
        boolean getBoolean(String key, boolean defaultValue);
    }

    private SendspinSettings() {
    }

    /** @return whether Sendspin is switched on; false for no source or a value that can not be read */
    public static boolean isEnabled(BooleanSource source) {
        if (source == null) {
            return false;
        }
        try {
            return source.getBoolean(KEY_ENABLED, ENABLED_DEFAULT);
        } catch (RuntimeException damaged) {
            return false;
        }
    }
}
