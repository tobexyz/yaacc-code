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
package de.yaacc.sendspin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubConnection;

import org.junit.Test;

/** The device of the lists, the text of its card and the reconnect rules of the hub. */
public class SendspinReceiverTest {

    private static final SendspinRowText.Words WORDS = new SendspinRowText.Words() {
        @Override
        public String type() {
            return "Sendspin";
        }

        @Override
        public String state(SendspinRowText.State state) {
            return state.name().toLowerCase();
        }

        @Override
        public String stateWithTitle(String state, String title) {
            return state + ": " + title;
        }
    };

    @Test
    public void aDeviceIsNamedByTheAddressUntilThePlayerAnnouncesItsName() {
        Endpoint endpoint = SendspinAddress.parse("192.168.0.7:9000");
        SendspinReceiverDevice before = SendspinReceiverDevice.of(endpoint, null);
        assertEquals("192.168.0.7:9000", before.getName());
        assertEquals("192.168.0.7:9000 (Sendspin)", before.getDisplayString());
        assertEquals("sendspin:192.168.0.7:9000", before.getReceiverId());
        assertEquals("sendspin:192.168.0.7:9000", before.getIdentity().getUdn().getIdentifierString());
        SendspinReceiverDevice after = SendspinReceiverDevice.of(endpoint, "Kitchen");
        assertEquals("Kitchen (Sendspin)", after.getDisplayString());
        assertEquals(before.getIdentity().getUdn(), after.getIdentity().getUdn());
    }

    @Test
    public void aDeviceHasNoUpnpServicesAndShowsTheSameOnlyWithTheSameName() {
        SendspinReceiverDevice device = SendspinReceiverDevice.of(SendspinAddress.parse("h.local"), "Kitchen");
        assertNull(device.getServices());
        assertNull(device.getRoot());
        assertNull(device.findDevice(null));
        assertTrue(device.showsSame("Kitchen"));
        assertTrue(device.showsSame("  Kitchen  "));
        assertFalse(device.showsSame("Bath"));
        assertFalse(device.showsSame(null));
        assertFalse("no address and no name in the text", device.toString().contains("h.local"));
        assertNull(SendspinReceiverDevice.of(null, "x"));
    }

    @Test
    public void aNameFromTheNetworkIsCleanedAndShortened() {
        SendspinReceiverDevice device = SendspinReceiverDevice.of(SendspinAddress.parse("h.local"), "Kit‮chen\r\nroom\u0000");
        assertFalse(device.getName().contains("‮"));
        assertFalse(device.getName().contains("\n"));
        assertTrue(SendspinRowText.clean(new String(new char[500]).replace('\0', 'x')).length() <= SendspinRowText.MAX_TEXT);
        assertEquals("", SendspinRowText.clean(null));
    }

    @Test
    public void everyPhaseHasAStateInWords() {
        assertEquals(SendspinRowText.State.NOT_CONNECTED, SendspinRowText.stateOf(null, null, false, false));
        for (HubConnection.Phase phase : HubConnection.Phase.values()) {
            assertNotNull(phase.name(), SendspinRowText.stateOf(phase, HubConnection.EndReason.NONE, false, false));
        }
        assertEquals(SendspinRowText.State.NEEDS_APPROVAL, SendspinRowText.stateOf(HubConnection.Phase.NEEDS_APPROVAL, null, false, false));
        assertEquals(SendspinRowText.State.PLAYING, SendspinRowText.stateOf(HubConnection.Phase.READY, null, true, false));
        assertEquals(SendspinRowText.State.PAUSED, SendspinRowText.stateOf(HubConnection.Phase.READY, null, false, true));
        assertEquals(SendspinRowText.State.READY, SendspinRowText.stateOf(HubConnection.Phase.READY, null, false, false));
        assertEquals(SendspinRowText.State.IN_USE_BY_ANOTHER_SERVER,
                SendspinRowText.stateOf(HubConnection.Phase.ENDED, HubConnection.EndReason.ANOTHER_SERVER, false, false));
        assertEquals(SendspinRowText.State.UNREACHABLE, SendspinRowText.stateOf(HubConnection.Phase.ENDED, HubConnection.EndReason.FAILED, false, false));
        assertEquals(SendspinRowText.State.DISCONNECTED, SendspinRowText.stateOf(HubConnection.Phase.ENDED, HubConnection.EndReason.SHUTDOWN, false, false));
    }

    @Test
    public void theCardTextHasTypeNameAddressAndState() {
        assertEquals("Sendspin · Kitchen · h.local:8928 · ready",
                SendspinRowText.of("Kitchen", "h.local:8928", SendspinRowText.State.READY, "", WORDS));
        assertEquals("Sendspin · h.local:8928 · needs_approval",
                SendspinRowText.of(null, "h.local:8928", SendspinRowText.State.NEEDS_APPROVAL, null, WORDS));
        assertEquals("Sendspin · Kitchen · h.local:8928 · playing: Song",
                SendspinRowText.of("Kitchen", "h.local:8928", SendspinRowText.State.PLAYING, "Song", WORDS));
    }

    @Test
    public void onlyTheReasonsTheGuidanceAllowsAreRetriedWithoutTheUser() {
        assertFalse(SendspinHub.mayRetry(HubConnection.EndReason.ANOTHER_SERVER));
        assertFalse(SendspinHub.mayRetry(HubConnection.EndReason.UNPAIRED));
        assertFalse(SendspinHub.mayRetry(HubConnection.EndReason.PAIRING_REQUIRED));
        assertFalse(SendspinHub.mayRetry(HubConnection.EndReason.UNAUTHORIZED));
        assertFalse(SendspinHub.mayRetry(HubConnection.EndReason.USER_REQUEST));
        assertFalse(SendspinHub.mayRetry(HubConnection.EndReason.PROTOCOL_ERROR));
        assertFalse(SendspinHub.mayRetry(HubConnection.EndReason.CLOSED_BY_APP));
        assertTrue(SendspinHub.mayRetry(HubConnection.EndReason.FAILED));
        assertTrue(SendspinHub.mayRetry(HubConnection.EndReason.SHUTDOWN));
        assertTrue(SendspinHub.mayRetry(HubConnection.EndReason.RESTART));
        assertTrue(SendspinHub.mayRetry(HubConnection.EndReason.CONCURRENT_ATTEMPT));
    }
}
