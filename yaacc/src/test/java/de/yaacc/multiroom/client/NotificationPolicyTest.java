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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Review W2 (security) of Group 8: a server must not be able to make the app rebuild its notification at its own pace. */
public class NotificationPolicyTest {

    @Test
    public void aFloodOfSettingsWithTheSameTextNeverRebuildsTheNotificationAfterTheFirst() {
        NotificationPolicy policy = new NotificationPolicy();
        int rebuilds = 0;
        for (int i = 0; i < 10_000; i++) {
            if (policy.shouldRefresh("connecting", false, i)) {
                rebuilds++;
            }
        }
        assertEquals("only the first one is shown", 1, rebuilds);
    }

    @Test
    public void aTextThatKeepsChangingIsRebuiltAtMostOncePerSecond() {
        NotificationPolicy policy = new NotificationPolicy();
        int rebuilds = 0;
        for (int i = 0; i < 10_000; i++) { // 10 seconds, the text changes every millisecond
            if (policy.shouldRefresh(i % 2 == 0 ? "a" : "b", false, i)) {
                rebuilds++;
            }
        }
        assertTrue("rebuilds " + rebuilds, rebuilds <= 11);
    }

    @Test
    public void aChangeOfPhaseIsShownAtOnceWhateverTheInterval() {
        NotificationPolicy policy = new NotificationPolicy();
        assertTrue(policy.shouldRefresh("playing", false, 0));
        assertTrue("reconnecting is a phase change", policy.shouldRefresh("reconnecting", true, 10));
        assertTrue(policy.shouldRefresh("playing", true, 20));
        assertFalse("the same text again, even as a phase event", policy.shouldRefresh("playing", true, 30));
    }

    @Test
    public void aChangeThatIsNotAPhaseWaitsForTheMinimumInterval() {
        NotificationPolicy policy = new NotificationPolicy();
        assertTrue(policy.shouldRefresh("connecting", false, 0));
        assertFalse(policy.shouldRefresh("playing", false, 999));
        assertTrue(policy.shouldRefresh("playing", false, 1000));
    }

    @Test
    public void aRequestThatWasHeldBackIsGrantedWhenItComesAgainLater() {
        NotificationPolicy policy = new NotificationPolicy();
        policy.shouldRefresh("a", false, 0);
        assertFalse(policy.shouldRefresh("b", false, 500));
        assertTrue("the status arrives every 250 ms and asks again", policy.shouldRefresh("b", false, 1250));
    }
}
