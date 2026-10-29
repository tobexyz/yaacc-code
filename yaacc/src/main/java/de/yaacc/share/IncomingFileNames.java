/*
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
package de.yaacc.share;

import java.util.function.Consumer;

/**
 * File names in the app's {@code cache/incoming/} directory (copies of provider files that cannot
 * seek). Plain Java. A name is {@code share-} plus 32 random lower-case hex chars plus
 * {@code .bin}: never derived from provider data (a provider name could hold path separators or
 * collide), and recognisable strictly, so the sweep at app start deletes only files this app
 * created there.
 */
public final class IncomingFileNames {

    private static final String PREFIX = "share-";
    private static final String SUFFIX = ".bin";
    private static final int RANDOM_BYTES = 16;
    private static final int HEX_LENGTH = RANDOM_BYTES * 2;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private IncomingFileNames() {
    }

    /**
     * @param randomBytes fills the given array with random bytes (e.g. {@code secureRandom::nextBytes})
     * @return a new name such as {@code share-0123456789abcdef0123456789abcdef.bin}
     * @throws IllegalArgumentException if the random source is null
     */
    public static String randomName(Consumer<byte[]> randomBytes) {
        if (randomBytes == null) {
            throw new IllegalArgumentException("random source required");
        }
        byte[] bytes = new byte[RANDOM_BYTES];
        randomBytes.accept(bytes);
        StringBuilder sb = new StringBuilder(PREFIX.length() + HEX_LENGTH + SUFFIX.length());
        sb.append(PREFIX);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return sb.append(SUFFIX).toString();
    }

    /**
     * @return true only for exactly the shape {@link #randomName} produces: the prefix, 32
     * lower-case ASCII hex chars, the suffix; nothing is trimmed or case-folded, null is false
     */
    public static boolean isOurIncomingName(String name) {
        if (name == null || name.length() != PREFIX.length() + HEX_LENGTH + SUFFIX.length()
                || !name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < PREFIX.length() + HEX_LENGTH; i++) {
            char c = name.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
