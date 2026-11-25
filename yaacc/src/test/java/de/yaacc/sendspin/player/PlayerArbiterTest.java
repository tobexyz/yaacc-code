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
package de.yaacc.sendspin.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The rules of the specification for several servers on one player ("Multiple servers (server-initiated)"), plus paired before unpaired. */
public class PlayerArbiterTest {

    private static Set<String> set(String... activities) {
        return new HashSet<>(Arrays.asList(activities));
    }

    private static PlayerArbiter.Conn conn(String id, boolean paired, String... activities) {
        PlayerArbiter.Conn c = new PlayerArbiter.Conn(id, paired);
        c.activities.addAll(Arrays.asList(activities));
        return c;
    }

    private final PlayerArbiter arbiter = new PlayerArbiter(3);

    @Test
    public void aNewConnectionThatConflictsWithNobodyIsAdmitted() {
        PlayerArbiter.Verdict v = arbiter.decide(conn("new", true), set("playback"), Collections.<PlayerArbiter.Conn>emptyList(), false, null);
        assertTrue(v.admit);
        assertTrue(v.displace.isEmpty());
    }

    @Test
    public void aPlaybackActivationDisplacesAnotherPlaybackHolderBecauseEqualRanksWin() {
        PlayerArbiter.Conn playing = conn("a", true, "playback");
        PlayerArbiter.Verdict v = arbiter.decide(conn("b", true), set("playback"), Arrays.asList(playing), false, null);
        assertTrue(v.admit);
        assertEquals(Arrays.asList(playing), v.displace);
    }

    @Test
    public void anIdleServerIsAdmittedBesideAPlayingOneWithoutDisplacingIt() {
        PlayerArbiter.Conn playing = conn("a", true, "playback");
        PlayerArbiter.Verdict v = arbiter.decide(conn("b", true), set(), Arrays.asList(playing), false, null);
        assertTrue("room is there and nothing conflicts", v.admit);
        assertTrue(v.displace.isEmpty());
    }

    @Test
    public void pairingBesideAPlayingServerIsAllowedButPairingAgainstPairingIsDecidedByRank() {
        PlayerArbiter.Conn playing = conn("a", true, "playback");
        assertTrue(arbiter.decide(conn("b", false), set("pairing"), Arrays.asList(playing), false, null).admit);
        PlayerArbiter.Conn pairing = conn("c", false, "pairing");
        PlayerArbiter.Verdict v = arbiter.decide(conn("d", false), set("pairing"), Arrays.asList(pairing), false, null);
        assertTrue("equal rank wins", v.admit);
        assertEquals(Arrays.asList(pairing), v.displace);
    }

    @Test
    public void aPairingAttemptInProgressIsNotDisplaced() {
        PlayerArbiter.Conn pairing = conn("c", false, "pairing");
        pairing.pairingInProgress = true;
        PlayerArbiter.Verdict v = arbiter.decide(conn("d", false), set("pairing"), Arrays.asList(pairing), false, null);
        assertFalse(v.admit);
    }

    @Test
    public void aLowerRankLosesAgainstAHigherOne() {
        PlayerArbiter.Conn paired = conn("c", true, "pairing", "playback");
        PlayerArbiter.Verdict v = arbiter.decide(conn("d", true), set("pairing"), Arrays.asList(paired), false, null);
        assertFalse("pairing conflicts with the pairing of the holder, which ranks playback: lower loses", v.admit);
    }

    @Test
    public void atTheCapAConnectionConflictsWithTheIdleOnesAndIsRejectedIfThereIsNone() {
        PlayerArbiter small = new PlayerArbiter(2);
        PlayerArbiter.Conn a = conn("a", true, "playback");
        PlayerArbiter.Conn b = conn("b", true, "pairing");
        assertFalse("full, nobody idle", small.decide(conn("c", true), set(), Arrays.asList(a, b), true, null).admit);
        PlayerArbiter.Conn idle = conn("i", true);
        PlayerArbiter.Verdict v = small.decide(conn("c", true), set(), Arrays.asList(a, idle), true, "c");
        assertTrue("both empty and the newcomer is the last-playback server", v.admit);
        assertEquals(Arrays.asList(idle), v.displace);
    }

    @Test
    public void whenBothAreIdleTheLastPlaybackServerWinsAndOnlyIt() {
        PlayerArbiter small = new PlayerArbiter(1);
        PlayerArbiter.Conn idle = conn("i", true);
        assertTrue(small.decide(conn("last", true), set(), Arrays.asList(idle), true, "last").admit);
        assertFalse(small.decide(conn("other", true), set(), Arrays.asList(idle), true, "last").admit);
        PlayerArbiter.Conn lastIdle = conn("last", true);
        assertFalse("the other is the last-playback server too: no win", small.decide(conn("last", true), set(), Arrays.asList(lastIdle), true, "last").admit);
    }

    @Test
    public void anUnpairedServerCanNotDisplaceAPairedOneThatPlaysButAPairedOneCan() {
        PlayerArbiter.Conn pairedPlaying = conn("a", true, "playback");
        PlayerArbiter.Verdict unpaired = arbiter.decide(conn("evil", false), set("playback"), Arrays.asList(pairedPlaying), false, null);
        assertFalse("not in the specification: a hardening of this app", unpaired.admit);
        assertTrue(arbiter.decide(conn("b", true), set("playback"), Arrays.asList(pairedPlaying), false, null).admit);
        PlayerArbiter.Conn unpairedPlaying = conn("u", false, "playback");
        assertTrue("an unpaired holder can be displaced by anybody", arbiter.decide(conn("evil", false), set("playback"),
                Arrays.asList(unpairedPlaying), false, null).admit);
    }

    @Test
    public void anUnpairedServerThatOutranksAnIdlePairedOneStillWins() {
        PlayerArbiter.Conn pairedIdle = conn("a", true);
        assertTrue(arbiter.decide(conn("u", false), set("playback"), Arrays.asList(pairedIdle), false, null).admit);
    }

    @Test
    public void anAdmittedConnectionThatChangesItsActivitiesIsComparedWithTheOthers() {
        PlayerArbiter.Conn me = conn("me", true);
        PlayerArbiter.Conn playing = conn("a", true, "playback");
        List<PlayerArbiter.Conn> admitted = new ArrayList<>(Arrays.asList(me, playing));
        PlayerArbiter.Verdict v = arbiter.decide(me, set("playback"), admitted, false, null);
        assertTrue(v.admit);
        assertEquals("it is not compared with itself", Arrays.asList(playing), v.displace);
    }
}
