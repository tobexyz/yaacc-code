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
package de.yaacc.sendspin.player;

/** The host of the running player service, for the card of this phone (null while the service does not run). */
public final class SendspinPlayerRuntime {

    private static volatile SendspinPlayerHost host;
    private static volatile int port;

    private SendspinPlayerRuntime() {
    }

    /** The secret path of the loopback connection: made once per process, known only to the app's own hub and player. */
    private static final String LOCAL_PATH = makeLocalPath();

    private static String makeLocalPath() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder("/sendspin-local/");
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return sb.toString();
    }

    /**
     * The path that only this process knows. The hub dials its own player by it over the loopback address, and the player takes such a
     * connection without pairing; a connection over loopback by the ordinary path (any other program on the phone) gets no such trust.
     */
    public static String localPath() {
        return LOCAL_PATH;
    }

    static void set(SendspinPlayerHost running) {
        host = running;
    }

    static void setPort(int listening) {
        port = listening;
    }

    /** @return the port the player listens on, 0 while it does not run (the hub dials it over the loopback address to play on this phone) */
    public static int port() {
        return port;
    }

    /** @return the running host, or null */
    public static SendspinPlayerHost host() {
        return host;
    }
}
