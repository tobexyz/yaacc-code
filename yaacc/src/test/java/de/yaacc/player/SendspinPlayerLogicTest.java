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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import de.yaacc.sendspin.hub.HubConnection;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** What the phase of the connection means for a start, the texts of the problems and the stream's answer to a pause. */
public class SendspinPlayerLogicTest {

    @Test
    public void aReadyConnectionStartsAndAWaitingOneKeepsWaiting() {
        assertEquals(SendspinPlayerLogic.Wait.READY, SendspinPlayerLogic.waitFor(HubConnection.Phase.READY));
        for (HubConnection.Phase phase : new HubConnection.Phase[]{HubConnection.Phase.CONNECTING, HubConnection.Phase.HANDSHAKING,
                HubConnection.Phase.WAITING_FOR_HELLO, HubConnection.Phase.ACTIVATING}) {
            assertEquals(phase.name(), SendspinPlayerLogic.Wait.WAIT, SendspinPlayerLogic.waitFor(phase));
        }
    }

    @Test
    public void aPlayerThatWaitsForTheOperatorIsApprovedByPlayingToIt() {
        assertEquals(SendspinPlayerLogic.Wait.APPROVE, SendspinPlayerLogic.waitFor(HubConnection.Phase.NEEDS_APPROVAL));
    }

    @Test
    public void everyPhaseThatCanNotPlayGivesUpWithItsOwnProblem() {
        assertEquals(SendspinPlayerLogic.Wait.FAIL, SendspinPlayerLogic.waitFor(HubConnection.Phase.PAIRING_REQUIRED));
        assertEquals(SendspinPlayerLogic.Wait.FAIL, SendspinPlayerLogic.waitFor(HubConnection.Phase.RE_PAIR_NEEDED));
        assertEquals(SendspinPlayerLogic.Wait.FAIL, SendspinPlayerLogic.waitFor(HubConnection.Phase.PAIRING_FAILED));
        assertEquals("a pairing that runs is waited for", SendspinPlayerLogic.Wait.WAIT, SendspinPlayerLogic.waitFor(HubConnection.Phase.PAIRING));
        assertEquals(SendspinPlayerLogic.Problem.PAIRING_FAILED,
                SendspinPlayerLogic.problemOf(HubConnection.Phase.PAIRING_FAILED, HubConnection.EndReason.NONE));
        assertEquals(SendspinPlayerLogic.Wait.FAIL, SendspinPlayerLogic.waitFor(HubConnection.Phase.UNUSABLE));
        assertEquals(SendspinPlayerLogic.Wait.FAIL, SendspinPlayerLogic.waitFor(HubConnection.Phase.ENDED));
        assertEquals(SendspinPlayerLogic.Problem.PAIRING_REQUIRED,
                SendspinPlayerLogic.problemOf(HubConnection.Phase.PAIRING_REQUIRED, HubConnection.EndReason.NONE));
        assertEquals(SendspinPlayerLogic.Problem.RE_PAIR_NEEDED,
                SendspinPlayerLogic.problemOf(HubConnection.Phase.RE_PAIR_NEEDED, HubConnection.EndReason.NONE));
        assertEquals(SendspinPlayerLogic.Problem.UNUSABLE, SendspinPlayerLogic.problemOf(HubConnection.Phase.UNUSABLE, HubConnection.EndReason.NONE));
        assertEquals(SendspinPlayerLogic.Problem.IN_USE, SendspinPlayerLogic.problemOf(HubConnection.Phase.ENDED, HubConnection.EndReason.ANOTHER_SERVER));
        assertEquals(SendspinPlayerLogic.Problem.IN_USE, SendspinPlayerLogic.problemOf(HubConnection.Phase.ENDED, HubConnection.EndReason.CONCURRENT_ATTEMPT));
        assertEquals(SendspinPlayerLogic.Problem.LOST, SendspinPlayerLogic.problemOf(HubConnection.Phase.ENDED, HubConnection.EndReason.FAILED));
        assertEquals(SendspinPlayerLogic.Problem.NOT_READY, SendspinPlayerLogic.problemOf(HubConnection.Phase.CONNECTING, HubConnection.EndReason.NONE));
    }

    @Test
    public void everyProblemHasItsOwnText() {
        Set<Integer> texts = new HashSet<>();
        for (SendspinPlayerLogic.Problem problem : SendspinPlayerLogic.Problem.values()) {
            int text = SendspinPlayerLogic.textFor(problem);
            assertNotEquals(problem.name(), 0, text);
            texts.add(text);
        }
        assertEquals(SendspinPlayerLogic.Problem.values().length, texts.size());
    }

    @Test
    public void aPauseEndsTheStreamAndAResumeBeginsItAgain() {
        assertEquals(SendspinPlayerLogic.StreamAction.END, SendspinPlayerLogic.onPlayWhenReadyChanged(false));
        assertEquals(SendspinPlayerLogic.StreamAction.BEGIN, SendspinPlayerLogic.onPlayWhenReadyChanged(true));
    }
}
