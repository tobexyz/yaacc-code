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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The group volume of a server and its clients. The devices do not pass the volume on by themselves (measured on real
 * devices), so the app does: the slider shows the <b>average</b> of the members' volumes (in percent of each device's own
 * scale), and while it is dragged every member is moved by the <b>same number of percentage points</b> from where it was
 * when the drag began, clamped to 0..100 for each device. Moving from the start values (not from the last step) means
 * dragging back restores every member's own level, also after a member was clamped. Pure.
 */
public final class YxcGroupVolume {

    private YxcGroupVolume() {
    }

    /**
     * @return the volume of each member in percent of its own scale, in the order given; members that report no
     * volume (not read yet, unreachable) are left out
     */
    public static Map<String, Integer> levels(Collection<YxcDeviceSnapshot> members) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (YxcDeviceSnapshot member : members) {
            YxcDeviceState state = member.getState();
            if (state.getVolume() == null) {
                continue;
            }
            int max = state.getMaxVolume() == null || state.getMaxVolume() <= 0 ? 100 : state.getMaxVolume();
            result.put(member.getUdn(), (int) Math.max(0, Math.min(100, Math.round(state.getVolume() * 100d / max))));
        }
        return result;
    }

    /** Reads the volume of one device in percent of its scale; -1 when it can not be read. */
    public interface Reader {
        int readPercent(String udn);
    }

    /**
     * Reads the members' volumes <b>in parallel</b>, so a member that does not answer holds nobody else up; a member that
     * is not answered within the time is left out. The values come from the devices themselves (never from stored events).
     */
    public static Map<String, Integer> readLevels(Collection<String> udns, Reader reader, ExecutorService executor,
                                                  long timeoutMillis) {
        List<Callable<Integer>> jobs = new ArrayList<>();
        List<String> order = new ArrayList<>(udns);
        for (String udn : order) {
            jobs.add(() -> reader.readPercent(udn));
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        try {
            List<Future<Integer>> futures = executor.invokeAll(jobs, timeoutMillis, TimeUnit.MILLISECONDS);
            for (int i = 0; i < futures.size(); i++) {
                Future<Integer> future = futures.get(i);
                if (future.isCancelled()) {
                    continue;
                }
                try {
                    int percent = future.get();
                    if (percent >= 0) {
                        result.put(order.get(i), percent);
                    }
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // this member is left out
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.RejectedExecutionException shutDown) {
            // the pool was closed meanwhile: nothing was read
        }
        return result;
    }

    /** @return the average in percent, rounded; -1 when there are no levels */
    public static int average(Map<String, Integer> levels) {
        if (levels.isEmpty()) {
            return -1;
        }
        int sum = 0;
        for (int level : levels.values()) {
            sum += level;
        }
        return Math.round(sum / (float) levels.size());
    }

    /**
     * @param start the levels when the drag began
     * @param delta how far the slider moved from its start position, in percentage points (negative: down)
     * @return the level for each member, every one clamped to 0..100
     */
    public static Map<String, Integer> apply(Map<String, Integer> start, int delta) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : start.entrySet()) {
            result.put(entry.getKey(), Math.max(0, Math.min(100, entry.getValue() + delta)));
        }
        return result;
    }
}
