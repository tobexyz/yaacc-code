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

/** Review W3 (security) of Group 8: the locks are held only while audio plays, and a session without audio ends. */
public class SessionPowerPolicyTest {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60_000L;

    @Test
    public void aSessionThatIsStartingHoldsTheLocksOnlyForTheGracePeriod() {
        SessionPowerPolicy policy = new SessionPowerPolicy(0);
        assertTrue(policy.update(1 * SECOND, false).holdLocks);
        assertTrue(policy.update(SessionPowerPolicy.GRACE_MS - 1, false).holdLocks);
        SessionPowerPolicy.Decision after = policy.update(SessionPowerPolicy.GRACE_MS, false);
        assertFalse(after.holdLocks);
        assertTrue("released once, at the moment it was decided", after.releaseNow);
        assertFalse(policy.update(SessionPowerPolicy.GRACE_MS + SECOND, false).releaseNow);
    }

    @Test
    public void aServerThatConnectsAndNeverSendsAudioNeverHoldsTheLocksBeyondTheGraceAndTheSessionEndsAtTheCeiling() {
        SessionPowerPolicy policy = new SessionPowerPolicy(0);
        boolean heldAfterGrace = false;
        boolean ended = false;
        long endedAt = -1;
        for (long now = 0; now <= 40 * MINUTE; now += 2 * SECOND) { // the watchdog tick of the service
            SessionPowerPolicy.Decision d = policy.update(now, false);
            if (now >= SessionPowerPolicy.GRACE_MS && d.holdLocks) {
                heldAfterGrace = true;
            }
            if (d.end && !ended) {
                ended = true;
                endedAt = now;
            }
        }
        assertFalse("no lock after the grace period", heldAfterGrace);
        assertTrue(ended);
        assertEquals(SessionPowerPolicy.NO_AUDIO_CEILING_MS, endedAt);
        assertEquals("the ceiling is 15 minutes", 15 * MINUTE, SessionPowerPolicy.NO_AUDIO_CEILING_MS);
    }

    @Test
    public void playingTakesTheLocksAgainAndRestartsBothClocks() {
        SessionPowerPolicy policy = new SessionPowerPolicy(0);
        policy.update(2 * MINUTE, false); // locks released
        SessionPowerPolicy.Decision playing = policy.update(3 * MINUTE, true);
        assertTrue(playing.holdLocks);
        assertTrue("taken again once", playing.acquireNow);
        assertFalse(policy.update(3 * MINUTE + 2 * SECOND, true).acquireNow);
        // 14 minutes without audio after playing is not the end; 15 is
        long lastPlayed = 3 * MINUTE + 2 * SECOND;
        assertFalse(policy.update(lastPlayed + 14 * MINUTE, false).end);
        assertTrue(policy.update(lastPlayed + 15 * MINUTE, false).end);
    }

    @Test
    public void aPauseOfTheStreamKeepsTheLocksForTheGraceAndThenReleasesThem() {
        SessionPowerPolicy policy = new SessionPowerPolicy(0);
        policy.update(10 * SECOND, true);
        assertTrue(policy.update(10 * SECOND + 30 * SECOND, false).holdLocks);
        assertFalse(policy.update(10 * SECOND + SessionPowerPolicy.GRACE_MS, false).holdLocks);
    }

    @Test
    public void theWakeLockIsRenewedOnlyWhileTheLocksAreHeld() {
        SessionPowerPolicy policy = new SessionPowerPolicy(0);
        int renewals = 0;
        for (long now = 0; now <= 3 * 60 * MINUTE; now += 10 * SECOND) {
            SessionPowerPolicy.Decision d = policy.update(now, true); // three hours of playing
            if (d.renewNow) {
                renewals++;
            }
            assertTrue(d.holdLocks);
        }
        assertEquals("every 30 minutes", 6, renewals);

        SessionPowerPolicy idle = new SessionPowerPolicy(0);
        for (long now = 0; now <= 14 * MINUTE; now += 10 * SECOND) {
            assertFalse("no renewal without the locks (minute " + now / MINUTE + ")", idle.update(now, false).renewNow);
        }
    }

    @Test
    public void aSessionThatPlaysAllTheTimeNeverEnds() {
        SessionPowerPolicy policy = new SessionPowerPolicy(0);
        for (long now = 0; now <= 24 * 60 * MINUTE; now += 30 * SECOND) {
            assertFalse(policy.update(now, true).end);
        }
    }
}
