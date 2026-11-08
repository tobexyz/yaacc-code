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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the sheet offers for linking: the actions a row gets and the devices the picker lists for a server. Pure.
 * <p>
 * A device that is in another group is listed but disabled, with the reason; yaacc never dissolves a group it was
 * not asked to touch. A device that left its group but still sits on the link input (a "stuck" client) is in no
 * group and can be picked.
 */
public final class YxcLinkCandidates {

    /**
     * What the one toggle button of a row does: link devices (the device serves nobody), unlink the group (it serves one), or
     * nothing because it is a client of another device (the button is shown but disabled).
     */
    public enum Action {LINK_DEVICES, UNLINK_GROUP, LINK_UNAVAILABLE}

    public enum Reason {IN_OTHER_GROUP, NOT_REACHABLE, NOT_READY}

    /** One line of the picker. */
    public static final class Candidate {
        private final String udn;
        private final boolean selected;
        private final Reason disabledBecause;

        Candidate(String udn, boolean selected, Reason disabledBecause) {
            this.udn = udn;
            this.selected = selected;
            this.disabledBecause = disabledBecause;
        }

        public String getUdn() {
            return udn;
        }

        /**
         * @return whether the device is in the group of the server now (the picker starts with it checked). A disabled
         * member is still a member: the picker keeps it in the selection.
         */
        public boolean isSelected() {
            return selected;
        }

        public boolean isSelectable() {
            return disabledBecause == null;
        }

        /**
         * Whether the line can be ticked or unticked in the picker: a free device can be picked, and a member of the
         * group can be taken out even when it does not answer right now (it stays ticked until the user unticks it).
         * Otherwise a group with an unreachable member could never be ended.
         */
        public boolean isToggleable() {
            return disabledBecause == null || selected;
        }

        /** @return why the line is disabled, {@code null} when it can be picked */
        public Reason getDisabledBecause() {
            return disabledBecause;
        }
    }

    private YxcLinkCandidates() {
    }

    /** @return the toggle's action for a device with this state; none while it is unreachable or not read yet */
    public static Set<Action> actionsFor(YxcDeviceState state) {
        Set<Action> actions = EnumSet.noneOf(Action.class);
        if (state == null || !state.isReachable()) {
            return actions;
        }
        switch (state.getRole()) {
            case NONE:
                actions.add(Action.LINK_DEVICES);
                break;
            case SERVER:
                actions.add(Action.UNLINK_GROUP);
                break;
            case CLIENT:
                // a client that left but still sits on the link input is in no group: it can be linked
                actions.add(state.isStuckClient() ? Action.LINK_DEVICES : Action.LINK_UNAVAILABLE);
                break;
            default:
                break;
        }
        return actions;
    }

    /** Whether a device can be the server of a new group or of the group it serves. */
    static boolean canServe(YxcDeviceState state) {
        return state != null && state.isReachable()
                && (state.getRole() == YxcDistributionInfo.Role.NONE || state.getRole() == YxcDistributionInfo.Role.SERVER
                || state.isStuckClient());
    }

    /**
     * @param serverUdn the device the "Link devices" action was started on
     * @return the lines for the other watched devices, in the order of the directory; empty if the device cannot be
     * a server right now (unknown, unreachable, or a client)
     */
    public static List<Candidate> forServer(String serverUdn, YxcGroupDirectory directory) {
        List<Candidate> result = new ArrayList<>();
        YxcDeviceSnapshot server = directory.get(serverUdn);
        if (server == null || !canServe(server.getState())) {
            return result;
        }
        Set<String> members = new HashSet<>(directory.membersOf(serverUdn));
        for (YxcDeviceSnapshot device : directory.all()) {
            if (device.getUdn().equals(serverUdn)) {
                continue;
            }
            YxcDeviceState state = device.getState();
            boolean member = members.contains(device.getUdn());
            // a member that can not be reached right now stays in the selection (it is only shown as unavailable):
            // leaving it out would remove it from the group
            if (!state.isReachable()) {
                result.add(new Candidate(device.getUdn(), member, Reason.NOT_REACHABLE));
            } else if (state.getRole() == YxcDistributionInfo.Role.UNKNOWN) {
                result.add(new Candidate(device.getUdn(), member, Reason.NOT_READY));
            } else if (member) {
                result.add(new Candidate(device.getUdn(), true, null));
            } else if (state.getRole() == YxcDistributionInfo.Role.NONE || state.isStuckClient()) {
                result.add(new Candidate(device.getUdn(), false, null));
            } else {
                result.add(new Candidate(device.getUdn(), false, Reason.IN_OTHER_GROUP));
            }
        }
        return result;
    }

    /** Convenience for tests and callers that hold snapshots. */
    public static List<Candidate> forServer(String serverUdn, Collection<YxcDeviceSnapshot> devices) {
        return forServer(serverUdn, new YxcGroupDirectory(devices));
    }
}
