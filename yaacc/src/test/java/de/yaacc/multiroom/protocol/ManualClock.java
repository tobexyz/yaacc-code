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

import java.util.concurrent.atomic.AtomicLong;

/** A clock that moves only when the test says so (microseconds), shared by threads. */
final class ManualClock implements MicroClock {

    private final AtomicLong micros;

    ManualClock(long startMicros) {
        micros = new AtomicLong(startMicros);
    }

    @Override
    public long nowMicros() {
        return micros.get();
    }

    void advanceMicros(long delta) {
        micros.addAndGet(delta);
    }

    void advanceMillis(long delta) {
        micros.addAndGet(delta * 1000L);
    }

    void set(long value) {
        micros.set(value);
    }
}
