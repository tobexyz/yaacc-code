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
package de.yaacc.sendspin.player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Who may play when several servers are connected to the phone: the rules of the specification, section "Multiple servers
 * (server-initiated)". A connection is <i>provisional</i> until its first {@code server/activate}; every activation is arbitrated.
 * An activation <b>conflicts</b> with an admitted connection that already holds an activity it declares ({@code playback} or
 * {@code pairing}); at the cap of admitted connections, a provisional one conflicts with the admitted connections that have no activity
 * (and is rejected if there is none). Ranks: {@code playback} above {@code pairing} above nothing. The activation wins against a
 * connection when it ranks higher or equal; a connection with a pairing attempt in progress is never displaced; with nothing on both
 * sides it wins only as the last-playback server. It is admitted if it wins against every conflict, and then displaces them.
 * <p>
 * <b>One rule that is not in the specification</b> (the owner's decision, 2026-11-25): an unpaired server can not displace a paired one
 * on an equal rank (a server on the Wi-Fi that was never paired must not take the music from the server the user paired); it
 * still wins against a paired connection that has a lower rank, and against any unpaired one. Pure.
 */
public final class PlayerArbiter {

    public static final String PLAYBACK = "playback";
    public static final String PAIRING = "pairing";

    /** A connection as the rules see it. */
    public static final class Conn {
        public final String serverId;
        /** True if the connection's PSK is a long-term one (the server proved who it is). */
        public final boolean paired;
        public final Set<String> activities = new HashSet<>();
        public boolean pairingInProgress;

        public Conn(String serverId, boolean paired) {
            this.serverId = serverId;
            this.paired = paired;
        }
    }

    /** What the rules decide. */
    public static final class Verdict {
        public final boolean admit;
        /** The connections the newcomer displaces (they get {@code another_server}); empty if it is rejected. */
        public final List<Conn> displace;

        Verdict(boolean admit, List<Conn> displace) {
            this.admit = admit;
            this.displace = displace;
        }
    }

    private final int cap;

    /** @param cap how many connections the phone admits at once */
    public PlayerArbiter(int cap) {
        this.cap = cap;
    }

    private static int rank(Set<String> activities) {
        if (activities.contains(PLAYBACK)) {
            return 2;
        }
        return activities.contains(PAIRING) ? 1 : 0;
    }

    /**
     * @param activating   the connection that activates
     * @param declared     the activities its activation declares
     * @param admitted     the connections that are admitted now (the activating one is left out of the comparison if it is among them)
     * @param provisional  true if the activating connection is not admitted yet
     * @param lastPlayback the {@code server_id} of the last-playback server, or null
     */
    public Verdict decide(Conn activating, Set<String> declared, List<Conn> admitted, boolean provisional, String lastPlayback) {
        List<Conn> others = new ArrayList<>();
        for (Conn c : admitted) {
            if (c != activating) {
                others.add(c);
            }
        }
        List<Conn> conflicts = new ArrayList<>();
        for (Conn c : others) {
            for (String activity : declared) {
                if ((PLAYBACK.equals(activity) || PAIRING.equals(activity)) && c.activities.contains(activity)) {
                    conflicts.add(c);
                    break;
                }
            }
        }
        if (provisional && others.size() >= cap && conflicts.isEmpty()) {
            for (Conn c : others) {
                if (c.activities.isEmpty()) {
                    conflicts.add(c);
                }
            }
            if (conflicts.isEmpty()) {
                return new Verdict(false, new ArrayList<Conn>());
            }
        }
        int mine = rank(declared);
        for (Conn c : conflicts) {
            if (c.pairingInProgress) {
                return new Verdict(false, new ArrayList<Conn>());
            }
            int theirs = rank(c.activities);
            boolean wins;
            if (mine > theirs) {
                wins = true;
            } else if (mine < theirs) {
                wins = false;
            } else if (mine == 0) {
                wins = lastPlayback != null && lastPlayback.equals(activating.serverId) && !lastPlayback.equals(c.serverId);
            } else {
                wins = !(c.paired && !activating.paired); // equal ranks: the activation wins, unless an unpaired server meets a paired one
            }
            if (!wins) {
                return new Verdict(false, new ArrayList<Conn>());
            }
        }
        return new Verdict(true, conflicts);
    }
}
