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
package de.yaacc.yxc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a send to a receiver really goes. A client of a group plays what its server plays, so what is sent to a
 * linked client goes to the server of its group instead (the owner's decision); when the server is not known
 * the send to that client is refused rather than put on the client. Devices that are in no group, servers, stuck
 * clients and receivers that do not speak the protocol are left as they are. Pure.
 */
public final class YxcPlaybackRedirect {

    /** The outcome for a selection of receivers. */
    public static final class Plan {
        private final List<String> targets;
        private final Map<String, String> redirected;
        private final List<String> refused;

        Plan(List<String> targets, Map<String, String> redirected, List<String> refused) {
            this.targets = targets;
            this.redirected = redirected;
            this.refused = refused;
        }

        /** @return the receivers to play on, in order, without duplicates */
        public List<String> getTargets() {
            return targets;
        }

        /** @return linked client to the server that plays for it */
        public Map<String, String> getRedirected() {
            return redirected;
        }

        /** @return linked clients whose server is not known; nothing is sent to them */
        public List<String> getRefused() {
            return refused;
        }
    }

    private YxcPlaybackRedirect() {
    }

    public static Plan plan(Collection<String> selected, YxcGroupDirectory directory) {
        Set<String> targets = new LinkedHashSet<>();
        Map<String, String> redirected = new LinkedHashMap<>();
        List<String> refused = new ArrayList<>();
        for (String udn : selected) {
            YxcDeviceSnapshot device = directory.get(udn);
            boolean linkedClient = device != null && device.getState().getRole() == YxcDistributionInfo.Role.CLIENT
                    && !device.getState().isStuckClient();
            if (!linkedClient) {
                targets.add(udn);
                continue;
            }
            String server = directory.serverOf(udn);
            if (server == null) {
                refused.add(udn);
            } else {
                targets.add(server);
                redirected.put(udn, server);
            }
        }
        return new Plan(new ArrayList<>(targets), redirected, refused);
    }
}
