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

import java.util.List;
import java.util.Set;

/**
 * The one PCM format of a group (linking): every member gets the same audio, so the rate is one that all members list. 48000 Hz is
 * preferred, then 44100 Hz, then the highest other rate that every member has. A rate that does not give whole chunks of 20 ms (a
 * multiple of 50, from 8000 to 384000) is never chosen. Pure.
 */
public final class GroupFormat {

    private GroupFormat() {
    }

    /** A rate that gives whole chunks of 20 ms and that the streamer takes (a multiple of 50, 8000 to 384000 Hz). */
    public static boolean usable(int rate) {
        return rate >= 8000 && rate <= 384000 && rate % 50 == 0;
    }

    /**
     * @param memberRates for every member the PCM rates (16 bit, stereo) it lists
     * @return the rate of the group, or 0 if there is none (no member, or no rate that all have)
     */
    public static int commonRate(List<Set<Integer>> memberRates) {
        if (memberRates == null || memberRates.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (Integer candidate : memberRates.get(0)) {
            if (candidate == null || !usable(candidate)) {
                continue;
            }
            boolean everybody = true;
            for (Set<Integer> rates : memberRates) {
                if (!rates.contains(candidate)) {
                    everybody = false;
                    break;
                }
            }
            if (everybody && rank(candidate) > rank(best)) {
                best = candidate;
            }
        }
        return best;
    }

    /** 48000 first, 44100 second, anything else by its size. */
    private static long rank(int rate) {
        if (rate == 48000) {
            return Long.MAX_VALUE;
        }
        if (rate == 44100) {
            return Long.MAX_VALUE - 1;
        }
        return rate;
    }
}
