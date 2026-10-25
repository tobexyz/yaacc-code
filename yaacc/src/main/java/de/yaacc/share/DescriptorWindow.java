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

/**
 * Clamps the window of a file descriptor handed out by a content provider. The provider
 * declares a start offset and a length ({@code AssetFileDescriptor}); both come from another
 * app and are untrusted, so they are limited to the real size of the file behind the descriptor
 * before any read. Plain Java, long arithmetic that cannot overflow.
 */
public final class DescriptorWindow {

    private DescriptorWindow() {
    }

    /**
     * @param startOffset    declared start offset of the data in the file
     * @param declaredLength declared length; any negative value means "to the end of the file"
     * @param realSize       size of the file as reported by {@code fstat}
     * @return the number of readable bytes ({@code > 0}), clamped so that
     * {@code startOffset + length <= realSize}; or -1 if the window is unusable: negative
     * offset or size, an offset at or beyond the end, or no bytes left
     */
    public static long clampLength(long startOffset, long declaredLength, long realSize) {
        if (startOffset < 0L || realSize <= 0L || startOffset >= realSize) {
            return -1L;
        }
        long available = realSize - startOffset;
        long length = declaredLength < 0L ? available : Math.min(declaredLength, available);
        return length > 0L ? length : -1L;
    }
}
