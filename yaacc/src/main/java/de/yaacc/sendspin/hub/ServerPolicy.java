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

import de.yaacc.sendspin.session.ServerHandshake;

import java.util.HashSet;
import java.util.Set;

/**
 * What the server knows about the players it has met: which PSK to reference for a client (the long-term PSK of a paired client,
 * else the Sentinel) and which unpaired clients its operator approved. A player that is neither paired nor approved is never given
 * the {@code 'playback'} activity or a role (spec, "Unpaired Access").
 */
public interface ServerPolicy {

    ServerHandshake.PskChoice pskFor(String clientId);

    boolean isApproved(String clientId);

    void approve(String clientId);

    void revoke(String clientId);

    /** A policy that keeps its approvals in memory only (tests, and the start of the proof of concept). */
    class InMemory implements ServerPolicy {
        private final Set<String> approved = new HashSet<>();

        @Override
        public synchronized ServerHandshake.PskChoice pskFor(String clientId) {
            return ServerHandshake.PskChoice.sentinel();
        }

        @Override
        public synchronized boolean isApproved(String clientId) {
            return approved.contains(clientId);
        }

        @Override
        public synchronized void approve(String clientId) {
            approved.add(clientId);
        }

        @Override
        public synchronized void revoke(String clientId) {
            approved.remove(clientId);
        }
    }
}
