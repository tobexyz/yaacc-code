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
package de.yaacc.openhome;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Track ids of the OpenHome playlist: a number from 1 to 4294967295 (0 means "none"), and the two texts that carry lists of ids: the
 * {@code IdArray} (the ids as big-endian 32 bit numbers one after the other, base64 in the XML) and the {@code IdList} of
 * {@code ReadList} (decimal numbers separated by blanks). The input of a device or a control point is read strictly and bounded.
 */
public final class OhIds {
    public static final long NONE = 0;
    public static final long MAX_ID = 0xFFFFFFFFL;
    /** What a controller accepts from a device: far above the 1000 tracks of a playlist, far below a memory problem. */
    public static final int MAX_LIST = 10_000;

    private OhIds() {
    }

    public static boolean isId(long id) {
        return id >= 1 && id <= MAX_ID;
    }

    public static String encodeIdArray(List<Long> ids) {
        ByteBuffer buffer = ByteBuffer.allocate(ids.size() * 4);
        for (long id : ids) {
            if (!isId(id)) {
                throw new IllegalArgumentException("not an id: " + id);
            }
            buffer.putInt((int) id);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /**
     * @return the ids; never null
     * @throws IllegalArgumentException for text that is no id array (not base64, not a multiple of 4 bytes, an id 0, more than
     *                                  {@link #MAX_LIST} ids)
     */
    public static List<Long> decodeIdArray(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return new ArrayList<>();
        }
        // base64 is 4 characters for 3 bytes: text that long can not be a list of at most MAX_LIST ids, whatever it decodes to
        if (base64.length() > (long) MAX_LIST * 4 * 2 + 64) {
            throw new IllegalArgumentException("id array is too long");
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id array is not base64");
        }
        if (bytes.length % 4 != 0) {
            throw new IllegalArgumentException("id array is not a multiple of 4 bytes");
        }
        if (bytes.length / 4 > MAX_LIST) {
            throw new IllegalArgumentException("id array is too long");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        List<Long> ids = new ArrayList<>(bytes.length / 4);
        while (buffer.hasRemaining()) {
            long id = buffer.getInt() & MAX_ID;
            if (id == NONE) {
                throw new IllegalArgumentException("id 0 in the id array");
            }
            ids.add(id);
        }
        return ids;
    }

    /**
     * The ids of an {@code IdList}: decimal numbers separated by blanks. Text that is no id is skipped (the reference
     * implementation skips it too); at most {@code max} ids are read.
     */
    public static List<Long> parseIdList(String text, int max) {
        List<Long> ids = new ArrayList<>();
        if (text == null) {
            return ids;
        }
        // an id has at most 10 digits: more text than max ids can take up is never looked at
        String list = text.length() > (long) max * 11 + 11 ? text.substring(0, max * 11 + 11) : text;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (String part : list.split(" ")) {
            if (part.isEmpty() || part.length() > 10) {
                continue;
            }
            boolean digits = true;
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') {
                    digits = false;
                    break;
                }
            }
            if (!digits) {
                continue;
            }
            long id = Long.parseLong(part);
            // an id twice asks for the same track twice: once is enough
            if (isId(id) && seen.add(id)) {
                ids.add(id);
                if (ids.size() >= max) {
                    break;
                }
            }
        }
        return ids;
    }

    public static String formatIdList(List<Long> ids) {
        StringBuilder text = new StringBuilder();
        for (long id : ids) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(id);
        }
        return text.toString();
    }
}
