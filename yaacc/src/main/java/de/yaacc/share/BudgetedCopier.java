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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The copy loop of the copy fallback: copies a provider stream to a file chunk by chunk and asks
 * {@link CopyBudget} before EVERY chunk is written, so a copy never goes over the byte cap or
 * below the free-space reserve. Plain Java; streams, free space and the stop flag are passed in.
 * <p>
 * The caller deletes the partial file for every outcome except {@link Outcome#COPIED}.
 */
public final class BudgetedCopier {

    /** How the copy ended. */
    public enum Outcome {
        /** All bytes were copied and there was at least one. */
        COPIED,
        /** More bytes than allowed (declared or read). */
        TOO_BIG,
        /** The volume would fall below the free-space reserve. */
        NO_SPACE,
        /** The stop flag was set (deadline, shutdown). */
        CANCELLED,
        /** The stream had no bytes at all. */
        EMPTY
    }

    /** The outcome and the number of bytes written. */
    public static final class Result {
        private final Outcome outcome;
        private final long bytes;

        Result(Outcome outcome, long bytes) {
            this.outcome = outcome;
            this.bytes = bytes;
        }

        public Outcome outcome() {
            return outcome;
        }

        public long bytes() {
            return bytes;
        }
    }

    private BudgetedCopier() {
    }

    /**
     * @param in            the provider stream
     * @param out           the target file
     * @param declaredSize  the size the provider declared, negative for unknown
     * @param maxBytes      the most that may be copied (see {@link IncomingCache#remainingCopyBudget})
     * @param usableSpace   asked before each chunk: the free bytes on the volume now
     * @param cancelled     asked before each chunk: true to stop
     * @param buffer        the chunk buffer, not empty
     * @throws IOException if reading or writing fails
     */
    public static Result copy(InputStream in, OutputStream out, long declaredSize, long maxBytes,
                              LongSupplier usableSpace, BooleanSupplier cancelled, byte[] buffer)
            throws IOException {
        if (declaredSize > maxBytes || maxBytes <= 0L) {
            return new Result(Outcome.TOO_BIG, 0L);
        }
        long written = 0L;
        while (true) {
            if (cancelled.getAsBoolean()) {
                return new Result(Outcome.CANCELLED, written);
            }
            int read = in.read(buffer, 0, buffer.length);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            long after = written + read;
            if (after > maxBytes) {
                return new Result(Outcome.TOO_BIG, written);
            }
            CopyBudget.Decision decision = CopyBudget.decide(after, declaredSize, usableSpace.getAsLong());
            if (decision == CopyBudget.Decision.ABORT_TOO_BIG) {
                return new Result(Outcome.TOO_BIG, written);
            }
            if (decision == CopyBudget.Decision.ABORT_NO_SPACE) {
                return new Result(Outcome.NO_SPACE, written);
            }
            out.write(buffer, 0, read);
            written = after;
        }
        return new Result(written == 0L ? Outcome.EMPTY : Outcome.COPIED, written);
    }
}
