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
package de.yaacc.multiroom.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;

/**
 * What the link sheet of a Snapcast group shows and what a selection in it means (11.3). It is the pattern of the MusicCast link
 * sheet: <b>every client of the server</b> is listed once, in one list ordered by name, and the members of this group are
 * checked. Checking a client of another group moves it in, unchecking a member takes it out (it gets a group of its own, the
 * server does that). Pure; the sheet only binds what is decided here.
 *
 * <p><b>What is disabled.</b> The only member of a group is checked and disabled: {@code Group.SetClients} with an empty list
 * would dissolve the group, so the last member can not leave. Nothing else is disabled: a client that is not connected can
 * be linked and unlinked like any other (it is marked "offline").
 *
 * <p><b>What a selection changes.</b> {@link #changeOf} compares the selection with what the sheet <i>showed</i>: the clients
 * that were not members and are checked are added, the members that are not checked any more are removed. Everything else (a
 * client that joined the group after the sheet was opened) is left alone; {@code SnapcastControls} applies the change to the
 * group as the server has it at that moment, never to the list of the snapshot.
 *
 * <p>Names come from the server: they are cleaned and cut ({@link SnapcastMemberRows#MAX_NAME}) and never an identity.
 */
public final class SnapcastLinkChoices {

    /** The localized texts of the rows; the Android code backs them with string resources. */
    public interface Words extends SnapcastClientCandidates.Words {
        /** A client of another group: "Kitchen (in group Living)". */
        String inGroup(String client, String group);

        /** A client that is the only member of its group, which ends when it leaves: "Desk (in group Desk, which then ends)". */
        String inGroupThatEnds(String client, String group);

        /** The only member of this group, which can not leave. */
        String lastMember(String client);

        /** Marks a client that is not connected; {@code label} is the row's text so far. */
        String offline(String label);
    }

    /** One line of the sheet. Immutable. */
    public static final class Choice {
        private final String clientId;
        private final String label;
        private final boolean connected;
        private final boolean member;
        private final boolean enabled;

        Choice(String clientId, String label, boolean connected, boolean member, boolean enabled) {
            this.clientId = clientId;
            this.label = label;
            this.connected = connected;
            this.member = member;
            this.enabled = enabled;
        }

        /** The server's id of the client, for the call; never shown or logged. */
        public String getClientId() {
            return clientId;
        }

        /** The text of the row (cleaned, unique within the list). */
        public String getLabel() {
            return label;
        }

        public boolean isConnected() {
            return connected;
        }

        /** True when the client is a member of the group now (the row starts checked). */
        public boolean isMember() {
            return member;
        }

        /** False for the only member of the group: it stays checked. */
        public boolean isEnabled() {
            return enabled;
        }

        /** Numbers and flags only: the label holds names of a server. */
        @Override
        public String toString() {
            return "SnapcastLinkChoices.Choice{connected=" + connected + ", member=" + member + ", enabled=" + enabled + "}";
        }
    }

    /** What a selection changes: the clients to add and the members to remove (server ids). Immutable. */
    public static final class Change {
        private final List<String> added;
        private final List<String> removed;

        Change(Collection<String> added, Collection<String> removed) {
            this.added = Collections.unmodifiableList(new ArrayList<>(added));
            this.removed = Collections.unmodifiableList(new ArrayList<>(removed));
        }

        public List<String> getAdded() {
            return added;
        }

        public List<String> getRemoved() {
            return removed;
        }

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }

        @Override
        public String toString() {
            return "SnapcastLinkChoices.Change{added=" + added.size() + ", removed=" + removed.size() + "}";
        }
    }

    private SnapcastLinkChoices() {
    }

    /**
     * @param status  the server's status; null gives an empty list
     * @param groupId the group the sheet is for
     * @return every client of the server, by name; empty when the group is not in the status
     */
    public static List<Choice> of(SnapcastServerStatus status, String groupId, Words words) {
        SnapcastGroup group = status == null ? null : status.findGroup(groupId);
        if (group == null) {
            return Collections.emptyList();
        }
        List<SnapcastClient> clients = new ArrayList<>();
        List<SnapcastGroup> owners = new ArrayList<>();
        for (SnapcastGroup owner : status.getGroups()) {
            for (SnapcastClient client : owner.getClients()) {
                clients.add(client);
                owners.add(owner);
            }
        }
        Set<String> memberIds = new HashSet<>();
        for (SnapcastClient member : group.getClients()) {
            memberIds.add(member.getId());
        }
        boolean onlyOne = !SnapcastMemberRows.canRemove(group);
        List<Choice> result = new ArrayList<>();
        for (SnapcastClientCandidates.Candidate candidate : SnapcastClientCandidates.candidatesOf(clients, owners, words)) {
            boolean member = memberIds.contains(candidate.getClientId());
            boolean enabled = !(member && onlyOne);
            String label;
            if (!member) {
                label = candidate.leavesGroupEmpty() ? words.inGroupThatEnds(candidate.getName(), candidate.getFromGroupName())
                        : words.inGroup(candidate.getName(), candidate.getFromGroupName());
            } else {
                label = enabled ? candidate.getName() : words.lastMember(candidate.getName());
            }
            if (!candidate.isConnected()) {
                label = words.offline(label);
            }
            result.add(new Choice(candidate.getClientId(), label, candidate.isConnected(), member, enabled));
        }
        return result;
    }

    /**
     * What the user's selection changes against what the sheet showed.
     *
     * @param shown   the list the sheet showed
     * @param checked the ids that are checked now (null: none); an id that was not shown is ignored
     */
    public static Change changeOf(List<Choice> shown, Collection<String> checked) {
        Set<String> checkedIds = checked == null ? Collections.<String>emptySet() : new HashSet<>(checked);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Choice choice : shown) {
            boolean isChecked = checkedIds.contains(choice.getClientId());
            if (!choice.isMember() && isChecked) {
                added.add(choice.getClientId());
            } else if (choice.isMember() && choice.isEnabled() && !isChecked) {
                removed.add(choice.getClientId());
            }
        }
        return new Change(added, removed);
    }

    /** True when somebody is left in the group after the selection (a group without clients can not exist). */
    public static boolean keepsAMember(List<Choice> shown, Collection<String> checked) {
        Set<String> checkedIds = checked == null ? Collections.<String>emptySet() : new HashSet<>(checked);
        for (Choice choice : shown) {
            if (checkedIds.contains(choice.getClientId()) || (choice.isMember() && !choice.isEnabled())) {
                return true;
            }
        }
        return false;
    }

    /** True when at least one row can be changed; otherwise the sheet says there is nothing to link. */
    public static boolean hasAnythingToChange(List<Choice> shown) {
        for (Choice choice : shown) {
            if (choice.isEnabled()) {
                return true;
            }
        }
        return false;
    }
}
