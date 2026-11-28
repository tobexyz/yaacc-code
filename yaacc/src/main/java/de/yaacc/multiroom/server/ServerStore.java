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
package de.yaacc.multiroom.server;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.function.LongSupplier;

/**
 * Keeps the state of the server over a restart (issue #330, requirement R8): a change is written, but not more often than once a second,
 * and a damaged store is ignored when it is read.
 */
public final class ServerStore {

    /** Where the text is kept (a preference in the app, a map in a test). */
    public interface Backing {
        /** The text that was written last, or null. */
        String read();

        void write(String text);
    }

    private static final long MIN_INTERVAL_MILLIS = 1_000L;

    private final Backing backing;
    private final LongSupplier nowMillis;
    private long lastWrite = Long.MIN_VALUE / 2;

    public ServerStore(Backing backing, LongSupplier nowMillis) {
        this.backing = backing;
        this.nowMillis = nowMillis;
    }

    /** The state changed: writes it if the last write is over a second ago (a later call writes what a skipped one left). */
    public synchronized void changed(ServerState state) {
        long now = nowMillis.getAsLong();
        if (now - lastWrite < MIN_INTERVAL_MILLIS) {
            return;
        }
        lastWrite = now;
        backing.write(state.snapshot().toString());
    }

    /** Writes at once (the server stops). */
    public synchronized void flush(ServerState state) {
        lastWrite = nowMillis.getAsLong();
        backing.write(state.snapshot().toString());
    }

    /** Reads what was kept into an empty state; what is wrong with it is left out. */
    public void restoreInto(ServerState state) {
        String text = backing.read();
        if (text == null || text.length() > 1024 * 1024) {
            return;
        }
        try {
            state.restore(new JSONObject(text));
        } catch (JSONException damaged) {
            // the server starts empty
        }
    }
}
