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
    /** The key of the switch that allows players of the older, unencrypted wire (a protocol that the specification does not define). */
    public static final String KEY_LEGACY = "settings_sendspin_legacy";
    /** Off: only the encrypted protocol of the specification is spoken. */
    public static final boolean LEGACY_DEFAULT = false;
    /** The key of the switch that makes this phone a server that players find and connect to (client-initiated mode). */
    public static final String KEY_ADVERTISE = "settings_sendspin_advertise";
    /** Off: nothing listens on the phone and nothing is announced. */
    public static final boolean ADVERTISE_DEFAULT = false;
    /** The key of the switch for the mDNS search; it works only while {@link #KEY_ENABLED} is on. */
    public static final String KEY_DISCOVERY = "settings_sendspin_discovery";
    /** The search is on by default (nothing is played to a player that was only found; it is connected to and approved by playing). */
    public static final boolean DISCOVERY_DEFAULT = true;
    /** The key of the players that were selected in the last run ({@code host:port}); they stay listed until they are seen again. */
    public static final String KEY_REMEMBERED = "sendspin_remembered";
    /** The key of the players that the user added by address ({@code host:port}). */
    public static final String KEY_PLAYERS = "sendspin_players";
    /** The key of the ids of the players the operator approved. */
    public static final String KEY_APPROVED = "sendspin_approved";
    /** The names the players announced, by client id (only to show them in the lists of paired and approved players). */
    public static final String KEY_NAMES = "sendspin_names";
    /** The key of the private key of this server identity (app-private storage; backup is off). */
    public static final String KEY_IDENTITY = "sendspin_identity";
    /** Pairing tokens the operator pasted, not yet used: {@code <client id>:<pairing PSK, base64url>}. A credential. */
    public static final String KEY_TOKENS = "sendspin_pairing_tokens";
    /** The long-term PSKs of paired players: {@code <client id>:<PSK, base64url>}. A credential. */
    public static final String KEY_PAIRINGS = "sendspin_pairings";

    /** The key of the switch that makes the app list the Sendspin servers it controls (the controller role). */
    public static final String KEY_CONTROLLER_ENABLED = "settings_sendspin_controller_enabled";
    public static final boolean CONTROLLER_ENABLED_DEFAULT = false;
    /** The key of the switch that lets the controller connect to a server it is not paired with (spec: unpaired access). */
    public static final String KEY_UNPAIRED_SERVERS = "settings_sendspin_unpaired_servers";
    /** On: a server is one the user named, and the roles give it no audio and no files; pairing as a client is not part of the first version. */
    public static final boolean UNPAIRED_SERVERS_DEFAULT = true;
    /** The key of the switch for the mDNS search of servers; it works only while {@link #KEY_CONTROLLER_ENABLED} is on. */
    public static final String KEY_SERVER_DISCOVERY = "settings_sendspin_server_discovery";
    public static final boolean SERVER_DISCOVERY_DEFAULT = true;
    /** The key of the servers the user added by address ({@code host:port}). */
    public static final String KEY_SERVERS = "sendspin_servers";
    /** The key of the private key of the identity this app uses as a Sendspin client (app-private storage; backup is off). */
    public static final String KEY_CLIENT_IDENTITY = "sendspin_client_identity";
    /** The most servers the user can add. */
    public static final int MAX_SERVERS = 8;

    /** The most players the user can add. */
    public static final int MAX_PLAYERS = 16;
    /** The most players the app lists and connects to at all (added, remembered and found together). */
    public static final int MAX_LISTED = 32;
    /** The most approvals the app remembers (the oldest are dropped). */
    public static final int MAX_APPROVALS = 64;
    /** The most paired players kept; a further pairing is refused until one is removed. */
    public static final int MAX_PAIRINGS = 64;
    /** The most pasted tokens kept at a time. */
    public static final int MAX_TOKENS = 16;

    /** Reads a boolean preference (the shape of {@code SharedPreferences.getBoolean}). */
    public interface BooleanSource {
        boolean getBoolean(String key, boolean defaultValue);
    }

    private SendspinSettings() {
    }

    /**
     * @return whether the network is searched: Sendspin is on <b>and</b> the search switch is on (its default is on); false for no
     * source or a value that can not be read
     */
    public static boolean isDiscoveryEnabled(BooleanSource source) {
        if (!isEnabled(source)) {
            return false;
        }
        try {
            return source.getBoolean(KEY_DISCOVERY, DISCOVERY_DEFAULT);
        } catch (RuntimeException damaged) {
            return false;
        }
    }

    /** True if a changed preference key is one that decides whether the search runs. */
    public static boolean isDiscoveryRelevantKey(String key) {
        return KEY_ENABLED.equals(key) || KEY_DISCOVERY.equals(key);
    }

    /** @return whether the network is searched for servers: the controller is on <b>and</b> its search switch is on (default on) */
    public static boolean isServerDiscoveryEnabled(BooleanSource source) {
        if (!isControllerEnabled(source)) {
            return false;
        }
        try {
            return source.getBoolean(KEY_SERVER_DISCOVERY, SERVER_DISCOVERY_DEFAULT);
        } catch (RuntimeException damaged) {
            return false;
        }
    }

    /** True if a changed preference key is one that decides whether the search of servers runs. */
    public static boolean isServerDiscoveryRelevantKey(String key) {
        return KEY_CONTROLLER_ENABLED.equals(key) || KEY_SERVER_DISCOVERY.equals(key);
    }

    /** @return whether a player of the older, unencrypted wire may connect; false for no source or a value that can not be read */
    public static boolean isLegacyAllowed(BooleanSource source) {
        if (source == null) {
            return false;
        }
        try {
            return source.getBoolean(KEY_LEGACY, LEGACY_DEFAULT);
        } catch (RuntimeException damaged) {
            return false;
        }
    }

    /** @return whether this phone listens for players and announces itself: Sendspin is on <b>and</b> the switch is on (default off) */
    public static boolean isAdvertiseEnabled(BooleanSource source) {
        if (!isEnabled(source)) {
            return false;
        }
        try {
            return source.getBoolean(KEY_ADVERTISE, ADVERTISE_DEFAULT);
        } catch (RuntimeException damaged) {
            return false;
        }
    }

    /** True if a changed preference key is one that decides whether the listener and the announcement run. */
    public static boolean isAdvertiseRelevantKey(String key) {
        return KEY_ENABLED.equals(key) || KEY_ADVERTISE.equals(key);
    }

    /** @return whether the controller (Sendspin servers as cards) is switched on; false for no source or a value that can not be read */
    public static boolean isControllerEnabled(BooleanSource source) {
        if (source == null) {
            return false;
        }
        try {
            return source.getBoolean(KEY_CONTROLLER_ENABLED, CONTROLLER_ENABLED_DEFAULT);
        } catch (RuntimeException damaged) {
            return false;
        }
    }

    /** @return whether the controller may connect to a server it is not paired with */
    public static boolean isUnpairedServersAllowed(BooleanSource source) {
        if (source == null) {
            return false;
        }
        try {
            return source.getBoolean(KEY_UNPAIRED_SERVERS, UNPAIRED_SERVERS_DEFAULT);
        } catch (RuntimeException damaged) {
            return false;
        }
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
