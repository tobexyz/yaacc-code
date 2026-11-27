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
package de.yaacc.sendspin.client;

import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.session.ControllerMessages;
import de.yaacc.sendspin.session.SecureChannel;

import java.io.IOException;

/**
 * The clock synchronisation of a client, shared by the controller session and the player session (issue #325, spec "Clock
 * Synchronization"): a burst of {@value #BURST_SIZE} {@code client/time} messages, one after the answer to the last, every few seconds;
 * a burst whose answer does not come in time ends with what it has. The measurements go to a {@link Sink}. Used from the connection's
 * thread only.
 */
final class ClockBursts {

    /** Where the measurements go (the session's server clock, under the session's lock). */
    interface Sink {
        /** One round trip: the client's send time, the server's receive and send time, the client's receive time (microseconds). */
        void add(long t1, long t2, long t3, long t4);

        void endBurst();

        /** The burst is over: the session tells its listener. */
        void changed();
    }

    static final int BURST_SIZE = 8;
    private static final long BURST_TIMEOUT_MICROS = 2_000_000L;

    private final HubClock clock;
    private final Sink sink;
    private volatile long intervalMillis = 3000L;
    private int sent;
    private long t1;
    private long sentAt;
    private long nextAt;

    ClockBursts(HubClock clock, Sink sink) {
        this.clock = clock;
        this.sink = sink;
    }

    /** For tests: the pause between two bursts. */
    void setIntervalMillis(long millis) {
        intervalMillis = millis;
    }

    /** The roles are active: the first burst may start at once. */
    void reset() {
        nextAt = 0;
    }

    /** Starts a burst when it is time and the roles are active; ends one that got no answer. */
    void serve(SecureChannel c, boolean ready) throws IOException {
        if (!ready) {
            return;
        }
        long now = clock.nowMicros();
        if (sent > 0 && now - sentAt > BURST_TIMEOUT_MICROS) {
            finish(now); // no answer in time: the burst ends with what it has
            return;
        }
        if (sent == 0 && now >= nextAt) {
            send(c, now);
        }
    }

    private void send(SecureChannel c, long now) throws IOException {
        t1 = now;
        sentAt = now;
        sent++;
        c.sendJson(ControllerMessages.clientTime(now));
    }

    /** {@code server/time}: {@code [client_transmitted, server_received, server_transmitted]}. */
    void handleServerTime(SecureChannel c, long[] t) throws IOException {
        long t4 = clock.nowMicros();
        if (sent == 0 || t[0] != t1) {
            return; // not the answer to the message in flight
        }
        sink.add(t[0], t[1], t[2], t4);
        if (sent >= BURST_SIZE) {
            finish(t4);
        } else {
            send(c, t4);
        }
    }

    private void finish(long now) {
        sink.endBurst();
        sent = 0;
        nextAt = now + intervalMillis * 1000L;
        sink.changed();
    }
}
