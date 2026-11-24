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
package de.yaacc.sendspin.hub;

/**
 * The clock of the server role: microseconds on a monotonic clock, which is the time domain of every timestamp the server sends
 * (the protocol does not need the epoch, only one clock for {@code server/time}, {@code server_transmitted} and the chunk
 * timestamps). Sleeping is part of it so that a test can run the pacing without waiting.
 */
public interface HubClock {

    long nowMicros();

    /** Waits for about this long; may return early on an interrupt (which is thrown). */
    void sleepMicros(long micros) throws InterruptedException;

    HubClock SYSTEM = new HubClock() {
        @Override
        public long nowMicros() {
            return System.nanoTime() / 1000L;
        }

        @Override
        public void sleepMicros(long micros) throws InterruptedException {
            if (micros > 0) {
                Thread.sleep(micros / 1000L, (int) ((micros % 1000L) * 1000L));
            }
        }
    };
}
