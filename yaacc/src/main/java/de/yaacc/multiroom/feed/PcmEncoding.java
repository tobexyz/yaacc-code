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
package de.yaacc.multiroom.feed;

/** The sample encodings {@link PcmFormatConverter} reads. All are little endian. */
public enum PcmEncoding {
    /** 8 bit, unsigned (silence is 128). */
    UNSIGNED_8(1),
    /** 16 bit, signed. */
    SIGNED_16(2),
    /** 24 bit, signed, packed in three bytes. */
    SIGNED_24(3),
    /** 32 bit, signed integer. */
    SIGNED_32(4),
    /** 32 bit IEEE float, full scale is -1.0 to 1.0. */
    FLOAT_32(4);

    /** Bytes of one sample. */
    public final int bytesPerSample;

    PcmEncoding(int bytesPerSample) {
        this.bytesPerSample = bytesPerSample;
    }
}
