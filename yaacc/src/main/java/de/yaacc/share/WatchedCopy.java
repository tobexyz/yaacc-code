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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The copy fallback with a watchdog. Plain Java.
 * <p>
 * {@link BudgetedCopier} asks for cancellation only BETWEEN reads, so a provider whose pipe never
 * produces a byte would block the read for ever, on the thread that waits for the copy and while
 * holding the copy slot (and, behind it, the intake gate). Here the copy runs on its own daemon
 * worker ({@link AbandonableCall}) and the caller waits at most {@code timeoutMillis}. At the
 * deadline the caller, from its own thread, closes the {@link Input} (closing a descriptor wakes a
 * blocked libcore read), deletes the partial file, gives the copy slot back and reports
 * {@link Reason#TIMED_OUT}, even if the worker stays stuck: it is abandoned. A worker that wakes up
 * later finds out, stops, and cannot leave anything behind: the file was deleted and is only ever
 * opened for writing without creating it, and a held copy it still produces is closed (which
 * deletes the file) instead of being handed out.
 * <p>
 * One copy at a time ({@code BUSY} otherwise); at most {@code maxStuckWorkers} abandoned workers
 * at once, beyond that a copy is turned away as busy.
 */
public final class WatchedCopy {

    /** Why a copy did not happen. */
    public enum Reason {
        /** Not readable, empty, or the provider failed. */
        UNUSABLE,
        /** More bytes than allowed. */
        TOO_BIG,
        /** Not enough free space, or no budget left. */
        NO_SPACE,
        /** Another copy is running, or too many stuck copy workers are still alive. */
        BUSY,
        /** The source did not finish within the time. */
        TIMED_OUT
    }

    /** The copy failed; {@link #getReason()} says why. The message holds no payload. */
    public static final class Failure extends IOException {
        private final Reason reason;

        Failure(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }

    /** What is copied. {@link #close()} must be idempotent and safe to call from any thread. */
    public interface Input {
        /** Opens the stream (on the worker). */
        InputStream open() throws IOException;

        /** @return the length the provider declared, or a negative number if unknown */
        long declaredLength();

        /** Closes the stream and the descriptor behind it; wakes a blocked read. */
        void close();
    }

    /** Creates the empty target file (before the worker starts, so the caller owns its deletion). */
    public interface FileCreator {
        File create() throws IOException;
    }

    /** Holds the finished copy (reopens it as a seekable source). */
    public interface Holder {
        PositionalSource hold(File copy) throws IOException;
    }

    private static final int BUFFER_BYTES = 64 * 1024;

    private final Semaphore slot = new Semaphore(1);
    private final AbandonableCall worker;
    private final long timeoutMillis;

    /**
     * @param timeoutMillis   the longest a copy may take, positive
     * @param maxStuckWorkers abandoned workers allowed to stay alive, at least 1
     */
    public WatchedCopy(long timeoutMillis, int maxStuckWorkers) {
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("a positive timeout is required");
        }
        this.timeoutMillis = timeoutMillis;
        this.worker = new AbandonableCall(maxStuckWorkers, "share-copy");
    }

    /**
     * Copies {@code input} into a new file and returns a source that holds it and deletes it when
     * closed. Closes {@code input} and deletes the file in every failure case.
     *
     * @param budget the most bytes the copy may take, evaluated after the slot was taken
     * @param usable the free space on the volume, asked before every chunk
     * @throws Failure for every failure
     */
    public PositionalSource copy(Input input, FileCreator creator, LongSupplier budget, LongSupplier usable,
                                 Holder holder) throws Failure {
        if (!slot.tryAcquire()) {
            input.close();
            throw new Failure(Reason.BUSY);
        }
        File target = null;
        try {
            long maxBytes = budget.getAsLong();
            if (maxBytes <= 0L) {
                throw new Failure(Reason.NO_SPACE);
            }
            target = creator.create();
            final File file = target;
            PositionalSource source = worker.call(
                    abandoned -> copyOnWorker(input, file, maxBytes, usable, holder, abandoned),
                    timeoutMillis, input::close, PositionalSource::close);
            // from here on the source deletes the file when it is closed
            target = null;
            return source;
        } catch (Failure e) {
            throw e;
        } catch (TimeoutException e) {
            throw new Failure(Reason.TIMED_OUT);
        } catch (RejectedExecutionException e) {
            throw new Failure(Reason.BUSY);
        } catch (Exception e) {
            throw new Failure(Reason.UNUSABLE);
        } finally {
            input.close();
            if (target != null) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            slot.release();
        }
    }

    /** @return abandoned copy workers that are still alive (for tests and diagnostics) */
    public int stuckWorkers() {
        return worker.abandonedAliveCount();
    }

    /** The worker. Never creates the file: a late worker must not bring a deleted file back. */
    private PositionalSource copyOnWorker(Input input, File file, long maxBytes, LongSupplier usable, Holder holder,
                                          BooleanSupplier abandoned) throws Exception {
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        BooleanSupplier cancelled = () -> abandoned.getAsBoolean() || System.nanoTime() - deadline > 0L;
        BudgetedCopier.Result result;
        try (InputStream in = input.open();
             OutputStream out = Files.newOutputStream(file.toPath(), StandardOpenOption.WRITE)) {
            result = BudgetedCopier.copy(in, out, input.declaredLength(), maxBytes, usable, cancelled,
                    new byte[BUFFER_BYTES]);
        } finally {
            input.close();
        }
        switch (result.outcome()) {
            case COPIED:
                break;
            case TOO_BIG:
                throw new Failure(Reason.TOO_BIG);
            case NO_SPACE:
                throw new Failure(Reason.NO_SPACE);
            case CANCELLED:
                throw new Failure(Reason.TIMED_OUT);
            default:
                throw new Failure(Reason.UNUSABLE);
        }
        if (abandoned.getAsBoolean()) {
            // the caller is gone and has deleted the file: hold nothing
            throw new Failure(Reason.TIMED_OUT);
        }
        PositionalSource held = holder.hold(file);
        return new DeleteOnCloseSource(held, file);
    }
}
