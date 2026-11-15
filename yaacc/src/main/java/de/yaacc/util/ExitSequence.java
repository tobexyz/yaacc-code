/*
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
package de.yaacc.util;

import java.util.ArrayList;
import java.util.List;

/**
 * The steps of the exit of the app in the order they are added (tasks 12.2 and 12.9): the players first (they release their media
 * session and their notification, and a Snapcast player gives the group its stream back), then the services, then the notifications,
 * then the end of the process. A step that throws is noted and the next one runs all the same: the process must end, and one stuck
 * player must not leave the services, the notifications or the process behind.
 * <p>
 * Plain Java, the steps are injected; {@code Yaacc.exit()} is the one that builds it (a source guard pins that).
 */
public final class ExitSequence {

    public static final String STEP_MARK = "mark the exit";
    public static final String STEP_SHARES = "close the shares";
    public static final String STEP_PLAYERS = "exit the players";
    public static final String STEP_SERVICES = "stop the services";
    public static final String STEP_NOTIFICATIONS = "cancel the notifications";
    public static final String STEP_TASKS = "finish the tasks";
    public static final String STEP_END = "end the process";

    /** One step; may throw anything. */
    public interface Step {
        void run() throws Exception;
    }

    /** What happened. */
    public static final class Result {
        private final List<String> ran = new ArrayList<>();
        private final List<String> failed = new ArrayList<>();

        /** The names of the steps that were run, in order (failed ones too). */
        public List<String> ran() {
            return new ArrayList<>(ran);
        }

        /** The names of the steps that threw, in order. */
        public List<String> failed() {
            return new ArrayList<>(failed);
        }

        public boolean allSucceeded() {
            return failed.isEmpty();
        }
    }

    private final List<String> names = new ArrayList<>();
    private final List<Step> steps = new ArrayList<>();

    /** Adds a step at the end. */
    public ExitSequence add(String name, Step step) {
        names.add(name);
        steps.add(step);
        return this;
    }

    /** Runs the steps in order; every one is isolated. Never throws. */
    public Result run() {
        Result result = new Result();
        for (int i = 0; i < steps.size(); i++) {
            String name = names.get(i);
            result.ran.add(name);
            try {
                Step step = steps.get(i);
                if (step == null) {
                    throw new IllegalStateException("no step");
                }
                step.run();
            } catch (Throwable t) { // an Error of a library is no reason to leave the process half closed
                result.failed.add(name);
            }
        }
        return result;
    }

    /**
     * Whether to look again later: something (a service, a feed that gives the group its stream back) still runs and the time
     * allowed is not used up. The wait is bounded, the exit is never held for good.
     */
    public static boolean shouldKeepWaiting(boolean somethingStillRunning, long waitedMillis, long maxMillis) {
        return somethingStillRunning && waitedMillis < maxMillis;
    }
}
