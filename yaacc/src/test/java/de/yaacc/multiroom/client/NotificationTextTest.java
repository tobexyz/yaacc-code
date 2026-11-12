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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NotificationTextTest {

    @Test
    public void thePhaseAndTheEngineStateDecideTheText() {
        assertEquals(NotificationText.RECONNECTING, NotificationText.of(SnapcastClientRuntime.Phase.RECONNECTING, EngineState.PLAYING));
        assertEquals(NotificationText.PLAYING, NotificationText.of(SnapcastClientRuntime.Phase.RUNNING, EngineState.PLAYING));
        assertEquals(NotificationText.CONNECTING, NotificationText.of(SnapcastClientRuntime.Phase.RUNNING, EngineState.BUFFERING));
        assertEquals(NotificationText.CONNECTING, NotificationText.of(SnapcastClientRuntime.Phase.CONNECTING, null));
    }

    @Test
    public void aRejectedStartShowsTheTextThatIsShownAlreadyAndTheSecondRequestForItChangesNothing() {
        NotificationPolicy policy = new NotificationPolicy();
        NotificationText playing = NotificationText.of(SnapcastClientRuntime.Phase.RUNNING, EngineState.PLAYING);
        assertEquals(true, policy.shouldRefresh(playing, true, 0));
        // a second Start repeats the notification with the current text and tells the policy: no rebuild follows for it
        assertEquals(false, policy.shouldRefresh(playing, true, 10));
    }
}
