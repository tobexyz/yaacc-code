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

/**
 * A seekable source of known length that is read at explicit positions and closed without ever
 * blocking the caller. Plain Java (no {@code android.*}) so the closing rules can be unit tested
 * on the JVM; the Android adapter extends it and implements the two protected hooks.
 * <p>
 * Reads are positional: there is no current position, so any number of threads can read
 * different ranges of one open source. The rules:
 * <ul>
 * <li>{@link #close()} marks the source as closing and returns at once. New reads fail with an
 * {@link IOException} from then on.</li>
 * <li>The real close ({@link #closeInternal()}) happens exactly once: immediately if no read is
 * running, otherwise by the last reader that finishes. It never runs while a read is inside
 * {@link #readAtInternal}, so the underlying handle cannot be recycled under a reader.</li>
 * <li>A thread stuck in a slow read (a provider that blocks) therefore delays only the real
 * close, never the caller of {@code close()} (the registry's sweeper, the exit path).</li>
 * </ul>
 */
public abstract class PositionalSource implements AutoCloseable {

    private final long size;

    /** Guards the three fields below; never held while reading or closing. */
    private final Object stateLock = new Object();
    private int inFlight;
    private boolean closing;
    private boolean closeStarted;

    /**
     * @param size the number of readable bytes, &gt;= 0
     * @throws IllegalArgumentException for a negative size
     */
    protected PositionalSource(long size) {
        if (size < 0L) {
            throw new IllegalArgumentException("size must not be negative");
        }
        this.size = size;
    }

    /** @return the number of readable bytes */
    public final long size() {
        return size;
    }

    /**
     * Reads up to {@code len} bytes starting at {@code position}. The request is clipped at
     * {@link #size()}: the backend is never asked for a byte at or beyond it.
     *
     * @return the number of bytes read (&gt; 0), 0 only for {@code len == 0}, or -1 at or beyond
     * the end
     * @throws IOException              if the source is closing or closed, or the backend fails
     * @throws IllegalArgumentException for a negative position or an invalid buffer range
     */
    public final int readAt(long position, byte[] buf, int off, int len) throws IOException {
        if (position < 0L) {
            throw new IllegalArgumentException("position must not be negative");
        }
        if (buf == null || off < 0 || len < 0 || off > buf.length - len) {
            throw new IllegalArgumentException("invalid buffer range");
        }
        enter();
        try {
            if (len == 0) {
                return 0;
            }
            if (position >= size) {
                return -1;
            }
            int clipped = (int) Math.min((long) len, size - position);
            int read = readAtInternal(position, buf, off, clipped);
            return read <= 0 ? -1 : read;
        } finally {
            leave();
        }
    }

    /** @return true once {@link #close()} was called */
    public final boolean isClosing() {
        synchronized (stateLock) {
            return closing;
        }
    }

    /**
     * Refuses new reads and closes the backend: now if nothing is reading, else when the last
     * running read ends. Returns immediately, may be called any number of times and from any
     * thread; the backend is closed exactly once. A failure of the backend's close is swallowed
     * (the share is gone either way).
     */
    @Override
    public final void close() {
        boolean closeNow = false;
        synchronized (stateLock) {
            closing = true;
            if (inFlight == 0 && !closeStarted) {
                closeStarted = true;
                closeNow = true;
            }
        }
        if (closeNow) {
            closeBackend();
        }
    }

    /**
     * Reads from the backend. Called with {@code 0 <= position < size()}, {@code len > 0} and a
     * valid buffer range; possibly from several threads at once; never after the backend was
     * closed. May return fewer bytes than asked. Returns -1 (or 0) if the backend has no more
     * data at that position.
     */
    protected abstract int readAtInternal(long position, byte[] buf, int off, int len) throws IOException;

    /**
     * Closes the backend. Called exactly once, never while a read is running, possibly on a
     * reader's thread. Should not throw; an exception is swallowed.
     */
    protected abstract void closeInternal();

    private void enter() throws IOException {
        synchronized (stateLock) {
            if (closing) {
                throw new IOException("source is closed");
            }
            inFlight++;
        }
    }

    private void leave() {
        boolean closeNow = false;
        synchronized (stateLock) {
            inFlight--;
            if (inFlight == 0 && closing && !closeStarted) {
                closeStarted = true;
                closeNow = true;
            }
        }
        if (closeNow) {
            closeBackend();
        }
    }

    private void closeBackend() {
        try {
            closeInternal();
        } catch (RuntimeException ignored) {
            // nothing sensible to do, and nothing to log (the backend may carry a URI)
        }
    }
}
