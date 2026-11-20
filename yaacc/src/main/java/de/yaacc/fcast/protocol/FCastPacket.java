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
package de.yaacc.fcast.protocol;

/** One packet of the wire: the raw opcode number and the body text (null when the packet had no body). Immutable. */
public final class FCastPacket {
    private final int opcode;
    private final String body;

    public FCastPacket(int opcode, String body) {
        this.opcode = opcode;
        this.body = body;
    }

    /** The wire value of the opcode (0 to 255). */
    public int getOpcodeCode() {
        return opcode;
    }

    /** The opcode, or null if the number is not one of protocol v3. */
    public FCastOpcode getOpcode() {
        return FCastOpcode.fromCode(opcode);
    }

    public String getBody() {
        return body;
    }

    @Override
    public String toString() {
        FCastOpcode known = getOpcode();
        return "FCastPacket{" + (known == null ? "opcode " + opcode : known) + (body == null ? "" : ", " + body.length() + " chars") + "}";
    }
}
