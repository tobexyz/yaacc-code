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
package de.yaacc.sendspin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which players are linked into whose group (the owner's decision G1 to G7 in {@code groups.md}). A group has a <b>leader</b> (the card
 * the owner linked from) and <b>members</b> in the order they were linked; a player is in one group at a time, a member does not lead
 * and a leader is no member. At most {@link #MAX_MEMBERS} members besides the leader (eight players in all). Not persisted. By the
 * ids of the receivers. Pure; thread safe.
 */
public final class SendspinGroups {

    public static final int MAX_MEMBERS = 7;

    public enum Result {
        LINKED, ALREADY, SAME, FULL, LEADER_IS_A_MEMBER, MEMBER_IS_A_LEADER, INVALID
    }

    private final Map<String, List<String>> members = new LinkedHashMap<>();
    private final Map<String, String> leaderOfMember = new LinkedHashMap<>();

    private static boolean valid(String id) {
        return id != null && !id.isEmpty();
    }

    public synchronized Result link(String leader, String member) {
        if (!valid(leader) || !valid(member)) {
            return Result.INVALID;
        }
        if (leader.equals(member)) {
            return Result.SAME;
        }
        if (leaderOfMember.containsKey(leader)) {
            return Result.LEADER_IS_A_MEMBER;
        }
        if (isLeader(member)) {
            return Result.MEMBER_IS_A_LEADER;
        }
        if (leader.equals(leaderOfMember.get(member))) {
            return Result.ALREADY;
        }
        List<String> mine = members.get(leader);
        if (mine != null && mine.size() >= MAX_MEMBERS) {
            return Result.FULL;
        }
        unlink(member); // a member of another group is moved
        if (mine == null) {
            mine = new ArrayList<>();
            members.put(leader, mine);
        }
        mine.add(member);
        leaderOfMember.put(member, leader);
        return Result.LINKED;
    }

    /** Takes a member out of its group; a group that has no member left is gone. Harmless for a player that is in none. */
    public synchronized void unlink(String member) {
        String leader = member == null ? null : leaderOfMember.remove(member);
        if (leader == null) {
            return;
        }
        List<String> mine = members.get(leader);
        if (mine != null) {
            mine.remove(member);
            if (mine.isEmpty()) {
                members.remove(leader);
            }
        }
    }

    /** Ends the group of a leader; the members are separate players again. */
    public synchronized void dissolve(String leader) {
        List<String> mine = leader == null ? null : members.remove(leader);
        if (mine != null) {
            for (String member : mine) {
                leaderOfMember.remove(member);
            }
        }
    }

    /** A player that is removed from the app leaves its group, or ends it if it leads one. */
    public synchronized void remove(String id) {
        unlink(id);
        dissolve(id);
    }

    /** @return the leader of the group this player is a member of, else null */
    public synchronized String leaderOf(String member) {
        return member == null ? null : leaderOfMember.get(member);
    }

    /** @return the members of the group, in the order they were linked; empty if the player leads no group */
    public synchronized List<String> membersOf(String leader) {
        List<String> mine = leader == null ? null : members.get(leader);
        return mine == null ? Collections.<String>emptyList() : new ArrayList<>(mine);
    }

    public synchronized boolean isLeader(String id) {
        return id != null && members.containsKey(id);
    }

    public synchronized boolean isInGroup(String id) {
        return isLeader(id) || (id != null && leaderOfMember.containsKey(id));
    }

    /**
     * The list with every member directly after its leader (in the order the list had), so that a group reads as one block. A member
     * whose leader is not in the list, and everything that is no player of a group, stays where it was. Pure.
     *
     * @param idOf     the receiver id of an item, null for an item that is no Sendspin player
     * @param leaderOf the leader of the group a receiver id is a member of, null if it is none
     */
    public static <T> List<T> groupedOrder(List<T> sorted, java.util.function.Function<T, String> idOf,
                                           java.util.function.Function<String, String> leaderOf) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (T item : sorted) {
            String id = idOf.apply(item);
            if (id != null) {
                ids.add(id);
            }
        }
        List<T> result = new ArrayList<>(sorted.size());
        for (T item : sorted) {
            String id = idOf.apply(item);
            String leader = id == null ? null : leaderOf.apply(id);
            if (leader != null && ids.contains(leader)) {
                continue; // placed under its leader
            }
            result.add(item);
            if (id == null) {
                continue;
            }
            for (T other : sorted) {
                String otherId = idOf.apply(other);
                if (otherId != null && id.equals(leaderOf.apply(otherId))) {
                    result.add(other);
                }
            }
        }
        return result;
    }
}
