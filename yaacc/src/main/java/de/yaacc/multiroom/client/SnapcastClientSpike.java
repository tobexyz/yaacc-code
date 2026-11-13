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

import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.protocol.SnapcastConnection;

/**
 * The settings and the input of the experimental client spike (a listen test of the phone as a Snapcast client). The
 * option is <b>off by default</b>; with it off nothing of the client is reachable from the UI. Pure.
 */
public final class SnapcastClientSpike {

    /** The key of the switch in the default preferences (also the name of its string resource). */
    public static final String KEY_ENABLED = "settings_snapcast_client_spike";
    public static final boolean ENABLED_DEFAULT = false;
    /** Remembered between uses; plain text on the phone like the server list. */
    public static final String KEY_LAST_HOST = "snapcast_client_spike_host";
    public static final String KEY_LAST_LATENCY = "snapcast_client_spike_latency_ms";
    /** The per-installation secret of the Hello id, see {@link SnapcastClientIdentity}. */
    public static final String KEY_SECRET = "snapcast_client_spike_secret";

    /** Why and when the last session ended (written by the service, also when the whole app exits); read by the dialog. */
    public static final String KEY_LAST_ENDING = "snapcast_client_spike_last_ending";
    public static final String KEY_LAST_ENDING_AT = "snapcast_client_spike_last_ending_at";
    public static final String KEY_LAST_WRITE_AHEAD = "snapcast_client_spike_write_ahead_ms";
    /** The "FLAC stall guard" check box of the test screen (default on). */
    public static final String KEY_LAST_FLAC_GUARD = "snapcast_client_spike_flac_guard";
    /** How far ahead of the playback position the output is kept filled (the deep-buffer output of a phone needs more than its 128 ms queued: 250 by default). */
    public static final int MIN_WRITE_AHEAD_MS = 40;
    public static final int MAX_WRITE_AHEAD_MS = 350;
    public static final int DEFAULT_WRITE_AHEAD_MS = 250;

    public static final int MIN_LATENCY_MS = -2000;
    public static final int MAX_LATENCY_MS = 2000;

    /** What the input of the dialog turned out to be. */
    public enum Outcome {
        OK, EMPTY_HOST, INVALID_HOST, INVALID_LATENCY
    }

    /** The checked input. */
    public static final class Request {
        private final Outcome outcome;
        private final SnapcastEndpoint endpoint;
        private final int latencyMs;

        Request(Outcome outcome, SnapcastEndpoint endpoint, int latencyMs) {
            this.outcome = outcome;
            this.endpoint = endpoint;
            this.latencyMs = latencyMs;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        /** The server to connect to (port 1704 when the entry had none); null unless OK. */
        public SnapcastEndpoint getEndpoint() {
            return endpoint;
        }

        /** The manual latency, clamped; 0 when the field was empty. */
        public int getLatencyMs() {
            return latencyMs;
        }
    }

    /** Where the typed server is, as far as the text tells. */
    public enum Locality {
        /** An IP literal in the local ranges: loopback, private, link-local, unique-local. */
        LOCAL,
        /** An IP literal outside them: audio and control are unencrypted and unauthenticated on the way there. */
        PUBLIC,
        /** A host name: where it resolves is not looked up here (that would be a lookup on the main thread). */
        NAME
    }

    private SnapcastClientSpike() {
    }

    /**
     * @param hostText    {@code host[:port]}, strictly checked by {@link SnapcastEndpoint#parse(String, int)}; no scheme,
     *                    path or credentials
     * @param latencyText whole milliseconds, may be empty (0) or have a sign; a number outside the range is clamped
     */
    public static Request check(String hostText, String latencyText) {
        String host = hostText == null ? "" : hostText.trim();
        if (host.isEmpty()) {
            return new Request(Outcome.EMPTY_HOST, null, 0);
        }
        SnapcastEndpoint endpoint = SnapcastEndpoint.parse(host, SnapcastConnection.DEFAULT_PORT);
        if (endpoint == null) {
            return new Request(Outcome.INVALID_HOST, null, 0);
        }
        Integer latency = parseLatency(latencyText);
        if (latency == null) {
            return new Request(Outcome.INVALID_LATENCY, null, 0);
        }
        return new Request(Outcome.OK, endpoint, latency);
    }

    /** @return the write-ahead clamped to 40..350; the default for empty text; null for text that is no whole number */
    public static Integer parseWriteAhead(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return DEFAULT_WRITE_AHEAD_MS;
        }
        if (t.length() > 6) {
            return null;
        }
        long value = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            value = value * 10 + (c - '0');
        }
        return clampWriteAhead((int) Math.min(value, Integer.MAX_VALUE));
    }

    /** The configuration of the engine for a session of the test screen (the write-ahead is clamped, the guard as chosen). */
    public static ClientEngineConfig engineConfig(int writeAheadMs, boolean flacGuard) {
        return new ClientEngineConfig().maxAheadMs(clampWriteAhead(writeAheadMs)).smoothCorrections(true).flacTimeGuard(flacGuard);
    }

    public static int clampWriteAhead(int value) {
        return Math.max(MIN_WRITE_AHEAD_MS, Math.min(MAX_WRITE_AHEAD_MS, value));
    }

    /** @return the latency clamped to the range; 0 for empty text; null for text that is no whole number */
    public static Integer parseLatency(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return 0;
        }
        if (t.length() > 8) {
            return null;
        }
        int start = t.charAt(0) == '-' || t.charAt(0) == '+' ? 1 : 0;
        if (start == t.length()) {
            return null;
        }
        long value = 0;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            value = value * 10 + (c - '0');
        }
        if (t.charAt(0) == '-') {
            value = -value;
        }
        return (int) Math.max(MIN_LATENCY_MS, Math.min(MAX_LATENCY_MS, value));
    }

    /** @return whether the option is on; off for no source, a missing value or a value that can not be read */
    public static boolean isEnabled(de.yaacc.multiroom.SnapcastSettings.BooleanSource source) {
        if (source == null) {
            return ENABLED_DEFAULT;
        }
        try {
            return source.getBoolean(KEY_ENABLED, ENABLED_DEFAULT);
        } catch (RuntimeException damaged) {
            return ENABLED_DEFAULT;
        }
    }

    public static Locality localityOf(SnapcastEndpoint endpoint) {
        java.net.InetAddress address = endpoint.getAddress();
        if (address == null) {
            return Locality.NAME;
        }
        return de.yaacc.multiroom.discovery.SnapcastAddressFilter.isAccepted(address) ? Locality.LOCAL : Locality.PUBLIC;
    }

    /** The host name for the Hello: the name of the phone for a local server, a generic one for a public address. */
    public static String helloHostName(SnapcastEndpoint endpoint, String phoneName) {
        return localityOf(endpoint) == Locality.PUBLIC ? "YAACC" : phoneName;
    }

    /**
     * The host name for the Hello decided from the address the connection really uses (the result of the name lookup), not
     * from the text that was typed: a dynamic DNS name or a form like {@code 0x08.0x08.0x08.0x08} is a "name" for the
     * parser and a public address for the platform (review S18 of Group 8).
     */
    public static String helloHostName(java.net.InetAddress resolved, String phoneName) {
        return de.yaacc.multiroom.discovery.SnapcastAddressFilter.isAccepted(resolved) ? phoneName : "YAACC";
    }
}
