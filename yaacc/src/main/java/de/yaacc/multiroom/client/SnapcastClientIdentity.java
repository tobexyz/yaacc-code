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
 * The id the phone gives a snapserver in its Hello. It is <b>derived per server</b> from a random secret of this
 * installation and the key of the server, so two servers can not tell they talk to the same phone, and it is never the
 * hardware id (Android does not give one, and the design forbids it). Group 9 replaces the secret with the YAACC device
 * UDN; the derivation stays. The server and every controller of it see the id.
 */
public final class SnapcastClientIdentity {

    private static final String PREFIX = "yaacc-";
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
     * @param secret    the secret of this installation
     * @param serverKey the canonical text of the server's endpoint
     * @return {@code yaacc-} and 16 hexadecimal characters: a one-way function of both
     */
    public static String deriveId(String secret, String serverKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(secret.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(serverKey.getBytes(StandardCharsets.UTF_8));
            return PREFIX + hex(digest.digest(), 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
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
