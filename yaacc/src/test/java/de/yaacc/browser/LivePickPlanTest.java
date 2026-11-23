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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LivePickPlanTest {

    @Test
    public void aStreamTheServerServesIsSentWithTheServersLink() {
        assertEquals(LivePickPlan.Action.SEND_SERVER_LINK, LivePickPlan.decide(true, true, true));
        assertEquals("also when the permissions of a stream of our own are not there", LivePickPlan.Action.SEND_SERVER_LINK,
                LivePickPlan.decide(true, true, false));
    }

    @Test
    public void withoutTheServerTheStreamGetsALinkOfItsOwn() {
        assertEquals(LivePickPlan.Action.SEND_OWN_LINK, LivePickPlan.decide(true, false, true));
    }

    @Test
    public void withoutTheServerAndWithoutThePermissionsTheyAreAskedFirst() {
        assertEquals(LivePickPlan.Action.ASK_PERMISSION_THEN_SEND, LivePickPlan.decide(true, false, false));
    }

    @Test
    public void anOldAndroidHasNoLiveStreams() {
        assertEquals(LivePickPlan.Action.NOT_SUPPORTED, LivePickPlan.decide(false, true, true));
        assertEquals(LivePickPlan.Action.NOT_SUPPORTED, LivePickPlan.decide(false, false, false));
    }
}
