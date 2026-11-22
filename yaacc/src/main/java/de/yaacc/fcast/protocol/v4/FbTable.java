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
package de.yaacc.fcast.protocol.v4;

import com.google.flatbuffers.Table;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A read-only view of one FlatBuffers table (the accessors of the library are protected; generated code subclasses {@link Table} for the
 * same reason). Slots are the vtable offsets of the schema (4 for the first field, 6 for the second, ...). Every accessor returns null
 * (or 0 for a length) for a field that is not in the buffer. A buffer that is cut or points outside itself makes the accessors throw
 * {@link IndexOutOfBoundsException} or {@link IllegalArgumentException}; the caller ({@link V4Reader}) turns that into one error. The
 * length of a vector is checked against the buffer, so a hostile length can not make a loop run.
 */
public final class FbTable extends Table {
    private FbTable() {
    }

    /** @return the root table of the buffer (the buffer is not copied) */
    static FbTable root(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 8) {
            throw new IllegalArgumentException("a FlatBuffers buffer has at least 8 bytes");
        }
        int offset = bb.getInt(0);
        if (offset < 4 || offset >= bytes.length) {
            throw new IllegalArgumentException("the root offset is outside the buffer");
        }
        return new FbTable().at(bb, offset);
    }

    private FbTable at(ByteBuffer buffer, int position) {
        __reset(position, buffer); // also reads the vtable that the accessors use
        return this;
    }

    /** True if the field is in the buffer. */
    public boolean has(int slot) {
        return __offset(slot) != 0;
    }

    public String string(int slot) {
        int o = __offset(slot);
        return o == 0 ? null : __string(o + bb_pos);
    }

    public FbTable table(int slot) {
        int o = __offset(slot);
        return o == 0 ? null : new FbTable().at(bb, __indirect(o + bb_pos));
    }

    /** An unsigned byte (a {@code ubyte}, an enum or the type of a union). */
    public Integer u8(int slot) {
        int o = __offset(slot);
        return o == 0 ? null : bb.get(o + bb_pos) & 0xff;
    }

    public Long u32(int slot) {
        int o = __offset(slot);
        return o == 0 ? null : bb.getInt(o + bb_pos) & 0xffffffffL;
    }

    public Boolean bool(int slot) {
        int o = __offset(slot);
        return o == 0 ? null : bb.get(o + bb_pos) != 0;
    }

    public Float f32(int slot) {
        int o = __offset(slot);
        return o == 0 ? null : bb.getFloat(o + bb_pos);
    }

    /** The {@code Time} struct of the protocol: microseconds as an unsigned 64 bit number; null if absent. A value over 2^63 is returned negative: callers treat it as no time. */
    public Long timeMicros(int slot) {
        int o = __offset(slot);
        return o == 0 ? null : bb.getLong(o + bb_pos);
    }

    public int vectorLength(int slot) {
        int o = __offset(slot);
        if (o == 0) {
            return 0;
        }
        int length = __vector_len(o);
        if (length < 0 || (long) length * 4 > bb.limit()) {
            throw new IllegalArgumentException("a vector longer than the buffer");
        }
        return length;
    }

    public FbTable tableAt(int slot, int index) {
        int o = __offset(slot);
        if (o == 0 || index < 0 || index >= vectorLength(slot)) {
            throw new IndexOutOfBoundsException("no element " + index);
        }
        return new FbTable().at(bb, __indirect(__vector(o) + index * 4));
    }

    public String stringAt(int slot, int index) {
        int o = __offset(slot);
        if (o == 0 || index < 0 || index >= vectorLength(slot)) {
            throw new IndexOutOfBoundsException("no element " + index);
        }
        return __string(__vector(o) + index * 4);
    }
}
