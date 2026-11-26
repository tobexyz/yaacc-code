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

/**
 * The group volume as the controller role of the Sendspin protocol sets it: every player moves by the same delta, a player that
 * reaches a boundary is clamped and what it could not take is divided among the others, until all of it is applied or all are at a
 * boundary; the results are rounded to integers at the end. Pure.
 */
public final class GroupVolume {

    private GroupVolume() {
    }

    /**
     * @param current   the volumes (0 to 100) of the players that support volume
     * @param requested the volume asked for the whole group; outside 0 to 100 it is clamped
     * @return the volume to send to each player, in the same order
     */
    public static int[] targets(int[] current, int requested) {
        int n = current.length;
        int[] result = new int[n];
        if (n == 0) {
            return result;
        }
        double wanted = Math.max(0, Math.min(100, requested));
        double[] volume = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            volume[i] = Math.max(0, Math.min(100, current[i]));
            sum += volume[i];
        }
        double delta = wanted - sum / n;
        boolean[] clamped = new boolean[n];
        int free = n;
        for (int round = 0; round <= n && free > 0 && Math.abs(delta) > 1e-9; round++) {
            double lost = 0;
            for (int i = 0; i < n; i++) {
                if (clamped[i]) {
                    continue;
                }
                double proposed = volume[i] + delta;
                double bounded = Math.max(0, Math.min(100, proposed));
                if (bounded != proposed) {
                    clamped[i] = true;
                    free--;
                    lost += proposed - bounded;
                }
                volume[i] = bounded;
            }
            delta = free == 0 ? 0 : lost / free;
        }
        for (int i = 0; i < n; i++) {
            result[i] = (int) Math.round(volume[i]);
        }
        return result;
    }
}
