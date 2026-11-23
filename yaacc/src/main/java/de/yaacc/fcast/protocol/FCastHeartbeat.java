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
package de.yaacc.fcast.protocol;

/**
 * When to ping a receiver and when to give it up (pure, driven by a clock value, so tests do not sleep). The receivers ping
 * a sender themselves (the desktop receiver after 3 s of silence and it ends the session after 6 s, the Android receiver
 * every 2.5 s, see {@code docs/tech.md}); this class covers the receiver that does not: after {@code pingAfter} without any
 * packet it asks for one Ping, after {@code deadAfter} without any packet the connection is dead. Any packet, whatever it is,
 * counts as life. A Ping is asked for once per silence, not again until a packet came. Not thread safe: the watchdog thread.
 */
public final class FCastHeartbeat {
    /** What to do now. */
    public enum Action {
        NONE, PING, DEAD
    }

    private final long pingAfterMillis;
    private final long deadAfterMillis;
    private long lastPacketAt;
    private boolean pingAsked;

    /**
     * @param now             the clock when the connection was made
     * @param pingAfterMillis silence after which one Ping is sent
     * @param deadAfterMillis silence after which the connection is given up (more than {@code pingAfterMillis})
     */
    public FCastHeartbeat(long now, long pingAfterMillis, long deadAfterMillis) {
        if (pingAfterMillis <= 0 || deadAfterMillis <= pingAfterMillis) {
            throw new IllegalArgumentException("0 < pingAfter < deadAfter is needed");
        }
        this.lastPacketAt = now;
        this.pingAfterMillis = pingAfterMillis;
        this.deadAfterMillis = deadAfterMillis;
    }

    /** A packet arrived (any opcode). */
    public void onPacket(long now) {
        lastPacketAt = now;
        pingAsked = false;
    }

    /** @return what to do at this time; {@code PING} is returned once per silence */
    public Action tick(long now) {
        long silence = now - lastPacketAt;
        if (silence >= deadAfterMillis) {
            return Action.DEAD;
        }
        if (silence >= pingAfterMillis && !pingAsked) {
            pingAsked = true;
            return Action.PING;
        }
        return Action.NONE;
    }
}
