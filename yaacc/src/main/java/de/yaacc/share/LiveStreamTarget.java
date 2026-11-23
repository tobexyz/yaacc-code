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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The target of a {@link ShareRegistry.Kind#LIVE_STREAM} entry: the live stream of this phone (the device audio or the screen)
 * that is captured only as long as the share exists. Closing it (the registry does that once, when the entry is removed or
 * expires) gives the capture back; the capture stops when nobody else needs it. Plain Java: the capture is behind {@link Capture}.
 */
public final class LiveStreamTarget implements AutoCloseable {

    /** What the share needs from the capture of the phone. */
    public interface Capture {
        /** A new stream for one client, or null if the capture is not running. */
        InputStream open() throws IOException;

        /** The share does not need the capture any more. */
        void release();
    }

    private final boolean audio;
    private final Capture capture;
    private final AtomicBoolean closed = new AtomicBoolean();

    public LiveStreamTarget(boolean audio, Capture capture) {
        if (capture == null) {
            throw new IllegalArgumentException("capture must not be null");
        }
        this.audio = audio;
        this.capture = capture;
    }

    /** @return true for the device audio, false for the screen cast */
    public boolean isAudio() {
        return audio;
    }

    /** @return a new stream for one client, or null if the share is closed or the capture is not running */
    public InputStream open() throws IOException {
        return closed.get() ? null : capture.open();
    }

    /** Gives the capture back, once. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            capture.release();
        }
    }
}
