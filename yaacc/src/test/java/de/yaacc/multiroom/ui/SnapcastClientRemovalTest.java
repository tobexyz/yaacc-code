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
package de.yaacc.multiroom.ui;

import static de.yaacc.multiroom.ui.UiFixtures.client;
import static de.yaacc.multiroom.ui.UiFixtures.group;
import static de.yaacc.multiroom.ui.UiFixtures.status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

import de.yaacc.multiroom.control.SnapcastServerStatus;

/**
 * What may be removed from a Snapcast server (task 12.8): only an offline client that is in the fresh status, and never the
 * client this phone is connected with, also not when it looks offline. Pure.
 */
public class SnapcastClientRemovalTest {

    private static SnapcastServerStatus fresh() {
        return status(Arrays.asList(
                group("g1", "Living", "Default", client("live", "Kitchen", true, 40, false),
                        client("ghost", "Phone", false, 40, false)),
                group("g2", "Other", "Default", client("yaacc-1111111111111111", "Phone", false, 10, false))));
    }

    @Test
    public void anOfflineClientOfTheFreshStatusMayBeRemoved() {
        assertEquals(SnapcastClientRemoval.Verdict.REMOVABLE,
                SnapcastClientRemoval.decide(fresh(), "ghost", SnapcastClientRemoval.Own.none()));
    }

    @Test
    public void aConnectedClientIsNeverRemoved() {
        assertEquals(SnapcastClientRemoval.Verdict.CONNECTED,
                SnapcastClientRemoval.decide(fresh(), "live", SnapcastClientRemoval.Own.none()));
    }

    @Test
    public void aClientThatIsNotInTheFreshStatusIsGone() {
        assertEquals(SnapcastClientRemoval.Verdict.NOT_FOUND,
                SnapcastClientRemoval.decide(fresh(), "other", SnapcastClientRemoval.Own.none()));
        assertEquals(SnapcastClientRemoval.Verdict.NOT_FOUND,
                SnapcastClientRemoval.decide(null, "ghost", SnapcastClientRemoval.Own.none()));
        assertEquals(SnapcastClientRemoval.Verdict.NOT_FOUND,
                SnapcastClientRemoval.decide(fresh(), "", SnapcastClientRemoval.Own.none()));
        assertEquals(SnapcastClientRemoval.Verdict.NOT_FOUND,
                SnapcastClientRemoval.decide(fresh(), null, SnapcastClientRemoval.Own.none()));
    }

    @Test
    public void theClientThePhoneIsConnectedWithIsNeverRemovedEvenWhenItLooksOffline() {
        SnapcastClientRemoval.Own own = SnapcastClientRemoval.Own.session("yaacc-1111111111111111");

        assertEquals(SnapcastClientRemoval.Verdict.OWN_CLIENT,
                SnapcastClientRemoval.decide(fresh(), "yaacc-1111111111111111", own));
        assertEquals("another offline client is still removable", SnapcastClientRemoval.Verdict.REMOVABLE,
                SnapcastClientRemoval.decide(fresh(), "ghost", own));
    }

    @Test
    public void whileASessionStartsAndItsIdIsNotKnownYetNothingIsRemoved() {
        SnapcastClientRemoval.Own starting = SnapcastClientRemoval.Own.session(null);

        assertEquals(SnapcastClientRemoval.Verdict.OWN_CLIENT_UNKNOWN, SnapcastClientRemoval.decide(fresh(), "ghost", starting));
        assertEquals("a connected client is still reported as connected first or as unknown, never removable", false,
                SnapcastClientRemoval.decide(fresh(), "live", starting) == SnapcastClientRemoval.Verdict.REMOVABLE);
    }

    @Test
    public void withoutASessionTheIdOfAnEarlierSessionProtectsNothing() {
        assertEquals(SnapcastClientRemoval.Verdict.REMOVABLE,
                SnapcastClientRemoval.decide(fresh(), "yaacc-1111111111111111", SnapcastClientRemoval.Own.none()));
    }

    @Test
    public void theRowOffersTheActionOnlyForAnOfflineClientThatIsNotTheOwn() {
        SnapcastClientRemoval.Own own = SnapcastClientRemoval.Own.session("mine");

        assertTrue(SnapcastClientRemoval.offered(false, "ghost", own));
        assertFalse("connected", SnapcastClientRemoval.offered(true, "ghost", own));
        assertFalse("the phone's own client", SnapcastClientRemoval.offered(false, "mine", own));
        assertFalse("session starting, id unknown", SnapcastClientRemoval.offered(false, "ghost", SnapcastClientRemoval.Own.session(null)));
        assertTrue(SnapcastClientRemoval.offered(false, "mine", SnapcastClientRemoval.Own.none()));
        assertFalse("no id", SnapcastClientRemoval.offered(false, "", SnapcastClientRemoval.Own.none()));
    }

    @Test
    public void textsNeverCarryTheIdOrTheName() {
        assertEquals("Own{none}", SnapcastClientRemoval.Own.none().toString());
        assertFalse(SnapcastClientRemoval.Own.session("secret-id").toString().contains("secret-id"));
    }

    @Test
    public void theRuntimeMapsToTheOwnClientOfTheRunningSessionOnly() {
        assertEquals(SnapcastClientRemoval.Verdict.OWN_CLIENT, SnapcastClientRemoval.decide(fresh(), "ghost",
                SnapcastClientRemoval.ownOf(true, "ghost")));
        assertEquals(SnapcastClientRemoval.Verdict.OWN_CLIENT_UNKNOWN, SnapcastClientRemoval.decide(fresh(), "ghost",
                SnapcastClientRemoval.ownOf(true, null)));
        assertEquals(SnapcastClientRemoval.Verdict.OWN_CLIENT_UNKNOWN, SnapcastClientRemoval.decide(fresh(), "ghost",
                SnapcastClientRemoval.ownOf(true, "")));
        assertEquals(SnapcastClientRemoval.Verdict.REMOVABLE, SnapcastClientRemoval.decide(fresh(), "ghost",
                SnapcastClientRemoval.ownOf(false, "ghost")));
    }
}
