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
package de.yaacc.sendspin.secure;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Pre-shared keys of the protocol (spec, "Pre-Shared Key"): the id by which a client finds the PSK of a server, the three
 * categories, and the Sentinel PSK that stands in when no other PSK applies. The Sentinel is a published constant: it gives no
 * authentication.
 */
public final class SendspinPsk {

    private SendspinPsk() {
    }

    /** The category the server declares for the PSK it references (the {@code psk_category} of Noise message 1). */
    public enum Category {
        LONG_TERM("lt"), PAIRING("pr"), SENTINEL("sn");

        private final String code;

        Category(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /** @return the category of the code, or null for a code outside the three defined ones */
        public static Category fromCode(String code) {
            for (Category category : values()) {
                if (category.code.equals(code)) {
                    return category;
                }
            }
            return null;
        }
    }

    private static final byte[] ID_LABEL = "sendspin-psk-id-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SENTINEL = NoiseKkPsk2.sha256("sendspin-sentinel-psk-v1".getBytes(StandardCharsets.UTF_8));

    /** @return a copy of the Sentinel PSK, SHA-256 of {@code sendspin-sentinel-psk-v1} */
    public static byte[] sentinel() {
        return SENTINEL.clone();
    }

    /** {@code base64url(SHA-256("sendspin-psk-id-v1" || PSK))}: 43 characters. */
    public static String pskId(byte[] psk) {
        return Base64Url.encode(NoiseKkPsk2.sha256(NoiseKkPsk2.concat(ID_LABEL, psk)));
    }

    public static String sentinelId() {
        return pskId(SENTINEL);
    }

    public static boolean isSentinel(byte[] psk) {
        return Arrays.equals(SENTINEL, psk);
    }
}
