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
package de.yaacc.multiroom.client;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Whether the phone's own Snapcast client starts a new session by itself after one ended by a failure, while it is
 * switched on. The switch ({@link SnapcastClientPower}) says "this phone is connected to the server"; a session that fell
 * over (the audio engine failed, the address could not be resolved because the network was away) must not leave it
 * switched on and disconnected. Pure Java; the service asks it at every end.
 *
 * <p>Only endings that can get better by themselves restart: {@link SnapcastClientRuntime.Ending#ENGINE_FAILED} and
 * {@link SnapcastClientRuntime.Ending#UNRESOLVED}. The user's stop, a refusal by the server, a bad address, the option being
 * off, 15 minutes without audio and the end of the app never restart. A client that keeps failing is not restarted for
 * ever: {@link #MAX_RESTARTS} in {@link #WINDOW_MILLIS}, then the failure stays on the screen as it was before.
 */
final class SessionRestartPolicy {

    static final int MAX_RESTARTS = 4;
    static final long WINDOW_MILLIS = 10 * 60 * 1000L;
    static final long DELAY_MILLIS = 3_000L;

    private final Deque<Long> restarts = new ArrayDeque<>();

    static boolean isRecoverable(SnapcastClientRuntime.Ending ending) {
        return ending == SnapcastClientRuntime.Ending.ENGINE_FAILED || ending == SnapcastClientRuntime.Ending.UNRESOLVED;
    }

    /**
     * @param ending    why the session ended
     * @param switchedOn the persisted switch is on and the option is enabled
     * @param nowMillis a monotonic time
     * @return true if a new session is to be started (the restart is counted)
     */
    synchronized boolean shouldRestart(SnapcastClientRuntime.Ending ending, boolean switchedOn, long nowMillis) {
        if (!switchedOn || !isRecoverable(ending)) {
            return false;
        }
        while (!restarts.isEmpty() && nowMillis - restarts.peekFirst() > WINDOW_MILLIS) {
            restarts.pollFirst();
        }
        if (restarts.size() >= MAX_RESTARTS) {
            return false;
        }
        restarts.addLast(nowMillis);
        return true;
    }
}
