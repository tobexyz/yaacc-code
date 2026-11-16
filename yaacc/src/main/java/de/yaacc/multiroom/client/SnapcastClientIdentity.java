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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * The id the phone gives a snapserver in its Hello (the client id every server and every controller of it shows). It is derived from
 * a random secret of this installation <b>only</b>: the same id for every server, also a public address (the owner's decision, task
 * 12.11), so the app can recognise its own client in the status of a server without a session and without knowing which server it
 * was connected to. It is never the hardware id (Android does not give one, and the design forbids it), and it is a one-way function
 * of the secret: the server learns that two of its clients are the same phone, and that two servers saw the same phone, nothing else.
 * Earlier builds derived the id per server (secret and endpoint text); an entry they left on a server is a ghost once, which
 * "Remove from server" clears. Group 9's idea of replacing the secret with the YAACC device UDN was not taken.
 */
public final class SnapcastClientIdentity {

    private static final String PREFIX = "yaacc-";
    private static final String DOMAIN = "snapcast-client-id";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SnapcastClientIdentity() {
    }

    /** A new random secret (32 hexadecimal characters) to be stored once per installation. */
    public static String newSecret(SecureRandom random) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return hex(bytes, bytes.length);
    }

    /** True for a secret as {@link #newSecret} makes it. */
    public static boolean isSecret(String text) {
        if (text == null || text.length() != 32) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param secret the secret of this installation ({@link #isSecret})
     * @return {@code yaacc-} and 16 hexadecimal characters: a one-way function of the secret and nothing else
     */
    public static String deriveId(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(secret.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(DOMAIN.getBytes(StandardCharsets.UTF_8)); // a fixed label, so the id is not the plain hash of the secret
            return PREFIX + hex(digest.digest(), 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /**
     * The id for a stored secret, for the code that only wants to recognise the app's own client.
     *
     * @return the id, or null when there is no valid secret (the app never started a client: no entry of it can exist on a server)
     */
    public static String idOfSecret(String secretOrNull) {
        return isSecret(secretOrNull) ? deriveId(secretOrNull) : null;
    }

    private static String hex(byte[] bytes, int count) {
        char[] out = new char[count * 2];
        for (int i = 0; i < count; i++) {
            out[2 * i] = HEX[(bytes[i] >> 4) & 0xf];
            out[2 * i + 1] = HEX[bytes[i] & 0xf];
        }
        return new String(out);
    }
}
