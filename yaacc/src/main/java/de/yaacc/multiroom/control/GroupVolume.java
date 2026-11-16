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
package de.yaacc.multiroom.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The volume of a group, which the server does not have: the <b>average</b> of the members' percentages. Setting a new
 * group volume moves every member by the <b>same number of points</b>, so the members keep their relative levels. A
 * member that would pass 0 or 100 stops there, and the points that did not fit are shared by the members that still have
 * room (repeated until everything is placed or everyone is at the edge), so the average still reaches the wanted value
 * where the scale allows. The mute flag is not part of the calculation: a muted member moves with the others and stays
 * muted (the caller keeps its flag). Pure.
 *
 * <p>Style and idea as {@code de.yaacc.yxc.YxcGroupVolume}: compute from the levels at the start of a drag, not from the
 * last step, and dragging back restores every member's own level.
 */
public final class GroupVolume {

    private GroupVolume() {
    }

    /** The members that play now (connected ones), in the order of the group, by id. */
    public static Map<String, Integer> levels(SnapcastGroup group) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (SnapcastClient client : group.getClients()) {
            if (client.isConnected()) {
                result.put(client.getId(), client.getVolume().getPercent());
            }
        }
        return result;
    }

    /** @return the average in percent, rounded half up; -1 when there are no members */
    public static int average(Map<String, Integer> levels) {
        if (levels.isEmpty()) {
            return -1;
        }
        long sum = 0;
        for (int level : levels.values()) {
            sum += level;
        }
        return Math.round(sum / (float) levels.size());
    }

    /**
     * Moves every member by {@code delta} points.
     *
     * @param start the levels when the move began
     * @param delta the change in percentage points (negative: down)
     * @return the new level of each member, all within 0..100; the sum is the start sum plus {@code delta} times the
     * number of members, as far as the scale allows
     */
    public static Map<String, Integer> apply(Map<String, Integer> start, int delta) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (start.isEmpty()) {
            return result;
        }
        int step = Math.max(-100, Math.min(100, delta));
        long remainder = 0; // the points that did not fit, with the sign of the move
        for (Map.Entry<String, Integer> entry : start.entrySet()) {
            int from = clamp(entry.getValue());
            int wanted = from + step;
            int placed = clamp(wanted);
            remainder += wanted - placed;
            result.put(entry.getKey(), placed);
        }
        while (remainder != 0) {
            int direction = remainder > 0 ? 1 : -1;
            List<String> roomy = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : result.entrySet()) {
                if (direction > 0 ? entry.getValue() < 100 : entry.getValue() > 0) {
                    roomy.add(entry.getKey());
                }
            }
            if (roomy.isEmpty()) {
                break; // everyone is at the edge: the rest is lost
            }
            long base = remainder / roomy.size();
            long extra = Math.abs(remainder % roomy.size()); // the first members get one point more
            long given = 0;
            for (int i = 0; i < roomy.size(); i++) {
                String id = roomy.get(i);
                long share = base + (i < extra ? direction : 0);
                int before = result.get(id);
                int after = clamp(before + share);
                result.put(id, after);
                given += after - before;
            }
            if (given == 0) {
                break;
            }
            remainder -= given;
        }
        return result;
    }

    /**
     * Sets the group to a new average: the members move by the difference between the new and the current average.
     *
     * @param newAverage clamped into 0..100
     */
    public static Map<String, Integer> target(Map<String, Integer> start, int newAverage) {
        if (start.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return apply(start, Math.max(0, Math.min(100, newAverage)) - average(start));
    }

    /**
     * The members to set for a new group average, as a drag needs it: the targets are calculated from the levels at the
     * <b>start</b> of the drag (so the steps do not add up and every member can return to its own level), and only the
     * members whose target differs from their <b>current</b> level are returned. A member that is not in both maps
     * (it left or joined meanwhile, or went offline) is not touched.
     *
     * @param start      the levels when the drag began, see {@link #levels}
     * @param current    the levels now
     * @param newAverage clamped into 0..100
     * @return the new level by client id, in the order of {@code start}
     */
    public static Map<String, Integer> changes(Map<String, Integer> start, Map<String, Integer> current, int newAverage) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : target(start, newAverage).entrySet()) {
            Integer now = current.get(entry.getKey());
            if (now != null && now.intValue() != entry.getValue().intValue()) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static int clamp(long value) {
        return (int) Math.max(0, Math.min(100, value));
    }
}
