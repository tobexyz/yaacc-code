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
package de.yaacc.multiroom.protocol;

/** The offset between the server clock and the client clock, as the time sync knows it. */
public interface OffsetSource {

    /** True when enough samples were taken to trust {@link #offsetMicros()}. */
    boolean isSynchronized();

    /** The server clock minus the client clock in microseconds: server time = client time + offset. */
    long offsetMicros();

    /**
     * A number that grows whenever the offset was found anew after a jump (a server restart): timestamps taken before
     * belong to the old clock and must be dropped.
     */
    int epoch();
}
