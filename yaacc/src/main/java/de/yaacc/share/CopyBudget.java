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
 * The stop rule of the copy fallback (a provider file whose descriptor cannot seek is copied to the
 * app's incoming directory). Plain Java. The copy loop asks before it writes each chunk, passing
 * the number of bytes the file will have once that chunk is written, and the free space it just
 * read from the volume.
 * <ul>
 * <li>{@link Decision#ABORT_TOO_BIG}: more than {@link SharedContentPolicy#MAX_COPY_BYTES} bytes
 * (exactly the cap is still fine), or the provider already DECLARED a size above the cap (nothing
 * is copied then). A negative byte count is a caller bug and aborts too.</li>
 * <li>{@link Decision#ABORT_NO_SPACE}: the usable space minus the bytes still to come (the
 * declared size minus the bytes already copied, if the size is known) would fall below
 * {@link ShareLimits#COPY_FREE_SPACE_RESERVE_BYTES}. A negative or zero space (the volume could
 * not be asked) aborts.</li>
 * <li>{@link Decision#CONTINUE} otherwise. Too big wins over no space.</li>
 * </ul>
 * The bytes already written are already gone from the usable space the caller reads, so they are
 * not counted twice.
 */
public final class CopyBudget {

    /** What the copy loop does next. */
    public enum Decision {
        CONTINUE,
        ABORT_TOO_BIG,
        ABORT_NO_SPACE
    }

    private CopyBudget() {
    }

    /**
     * @param bytesSoFar       bytes the copy will hold including the chunk about to be written
     * @param declaredSize     the size the provider declared, negative for unknown
     * @param usableSpaceBytes the free space on the volume right now
     */
    public static Decision decide(long bytesSoFar, long declaredSize, long usableSpaceBytes) {
        long cap = SharedContentPolicy.MAX_COPY_BYTES;
        if (bytesSoFar < 0L || bytesSoFar > cap || declaredSize > cap) {
            return Decision.ABORT_TOO_BIG;
        }
        long stillToCome = declaredSize >= 0L ? Math.max(declaredSize - bytesSoFar, 0L) : 0L;
        // both terms are small (at most the cap and the reserve), so the sum cannot overflow
        if (usableSpaceBytes < ShareLimits.COPY_FREE_SPACE_RESERVE_BYTES + stillToCome) {
            return Decision.ABORT_NO_SPACE;
        }
        return Decision.CONTINUE;
    }
}
