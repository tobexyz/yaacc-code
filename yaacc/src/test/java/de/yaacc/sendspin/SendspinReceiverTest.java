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

        @Override
        public String serverType() {
            return "Sendspin server";
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
    public void pairingPhasesHaveTheirOwnStatesAndOnlyTheOnesThatNeedATokenOfferTheEntryOfOne() {
        HubConnection.PairingFailure none = HubConnection.PairingFailure.NONE;
        assertEquals(SendspinRowText.State.PAIRING, SendspinRowText.stateOf(HubConnection.Phase.PAIRING, null, none, false, false, false));
        assertEquals(SendspinRowText.State.PAIRING_REJECTED, SendspinRowText.stateOf(HubConnection.Phase.PAIRING_FAILED, null,
                HubConnection.PairingFailure.TOKEN_REJECTED, false, false, false));
        for (HubConnection.PairingFailure failure : new HubConnection.PairingFailure[]{HubConnection.PairingFailure.TIMEOUT,
                HubConnection.PairingFailure.PLAYER_ABORTED, HubConnection.PairingFailure.CANNOT_STORE, HubConnection.PairingFailure.NOT_OFFERED}) {
            assertEquals(SendspinRowText.State.PAIRING_FAILED,
                    SendspinRowText.stateOf(HubConnection.Phase.PAIRING_FAILED, null, failure, false, false, false));
        }
        assertEquals("a paired player that is ready says so", SendspinRowText.State.READY_PAIRED,
                SendspinRowText.stateOf(HubConnection.Phase.READY, null, none, true, false, false));
        assertEquals("playing wins over paired", SendspinRowText.State.PLAYING,
                SendspinRowText.stateOf(HubConnection.Phase.READY, null, none, true, true, false));
        for (SendspinRowText.State state : SendspinRowText.State.values()) {
            boolean expected = state == SendspinRowText.State.PAIRING_REQUIRED || state == SendspinRowText.State.RE_PAIR_NEEDED
                    || state == SendspinRowText.State.PAIRING_REJECTED || state == SendspinRowText.State.PAIRING_FAILED;
            assertEquals(state.name(), expected, SendspinRowText.offersPairing(state));
        }
    }

    @Test
    public void aPlayerThatConnectedToThePhoneKeepsItsIdWhenItComesBackFromAnotherPort() {
        de.yaacc.sendspin.hub.Endpoint inbound = de.yaacc.sendspin.hub.Endpoint.inbound("192.168.0.9");
        assertEquals("sendspin-in:192.168.0.9", SendspinAddress.idOf(inbound));
        assertEquals(inbound, SendspinAddress.endpointOf("sendspin-in:192.168.0.9"));
        assertEquals("the port it came from is not part of the id", SendspinAddress.idOf(inbound),
                SendspinAddress.idOf(de.yaacc.sendspin.hub.Endpoint.inbound("192.168.0.9")));
    }

    @Test
    public void theCardOfThisPhoneHasAnIdOfItsOwnAndIsNeitherAServerNorAPlayerToPlayTo() {
        SendspinReceiverDevice self = SendspinReceiverDevice.ofSelf("  YAACC Phone ");
        assertTrue(self.isSelf());
        assertFalse(self.isServer());
        assertEquals(SendspinAddress.SELF_ID, self.getReceiverId());
        assertTrue(SendspinAddress.isSelfId(self.getReceiverId()));
        assertFalse(SendspinAddress.isSendspinId(SendspinAddress.SELF_ID));
        assertFalse(SendspinAddress.isServerId(SendspinAddress.SELF_ID));
        assertEquals("YAACC Phone", self.getName());
        assertTrue(self.showsSame("YAACC Phone"));
        assertFalse(self.showsSame("Another"));
        assertEquals("YAACC", SendspinReceiverDevice.ofSelf("").getName());
    }

    @Test
    public void aServerCardIsAnotherKindOfDeviceWithItsOwnIdAndName() {
        Endpoint endpoint = SendspinAddress.parseServer("music.local");
        SendspinReceiverDevice server = SendspinReceiverDevice.ofServer(endpoint, "Music Assistant");
        assertTrue(server.isServer());
        assertFalse(SendspinReceiverDevice.of(endpoint, "x").isServer());
        assertEquals("sendspin-server:music.local:8927", server.getReceiverId());
        assertEquals("sendspin-server:music.local:8927", server.getIdentity().getUdn().getIdentifierString());
        assertEquals("Music Assistant (Sendspin server)", server.getDisplayString());
        assertTrue(SendspinAddress.isServerId(server.getReceiverId()));
        assertFalse("a server id is not a player id", SendspinAddress.isSendspinId(server.getReceiverId()));
        assertFalse(SendspinAddress.isServerId("sendspin:music.local:8927"));
        assertEquals(endpoint, SendspinAddress.serverEndpointOf(server.getReceiverId()));
        assertNull(SendspinAddress.serverEndpointOf("sendspin:music.local:8927"));
        assertNull(SendspinAddress.endpointOf(server.getReceiverId()));
        assertEquals("the server port is the default", 8927, SendspinAddress.parseServer("music.local").port());
        assertEquals("the player port is the default", 8928, SendspinAddress.parse("music.local").port());
    }

    @Test
    public void aPlayerThatConnectedToThePhoneHasAnIdOfItsOwnAndIsNeverDialed() {
        Endpoint inbound = Endpoint.inbound("192.168.0.4");
        assertTrue(inbound.isInbound());
        assertFalse(SendspinAddress.parse("192.168.0.4:8928").isInbound());
        assertEquals("sendspin-in:192.168.0.4", SendspinAddress.idOf(inbound));
        assertEquals("192.168.0.4", SendspinAddress.format(inbound));
        assertTrue(SendspinAddress.isSendspinId("sendspin-in:192.168.0.4"));
        assertTrue(SendspinAddress.isSendspinId("sendspin:192.168.0.4:8928"));
        assertFalse(SendspinAddress.isServerId("sendspin-in:192.168.0.4"));
        assertEquals(inbound, SendspinAddress.endpointOf("sendspin-in:192.168.0.4"));
        assertEquals(Endpoint.inbound("fe80::1"), SendspinAddress.endpointOf("sendspin-in:[fe80::1]"));
        assertEquals("sendspin-in:[fe80::1]", SendspinAddress.idOf(Endpoint.inbound("fe80::1")));
        assertNull("not an address", SendspinAddress.endpointOf("sendspin-in:not an address"));
        assertNull(SendspinAddress.endpointOf("sendspin-in:"));
        SendspinReceiverDevice device = SendspinReceiverDevice.of(inbound, "Kodi");
        assertEquals("sendspin-in:192.168.0.4", device.getReceiverId());
        assertEquals("Kodi (Sendspin)", device.getDisplayString());
    }

    @Test
    public void everyStateOfAServerSessionHasAStateInWords() {
        assertEquals(SendspinRowText.State.NOT_CONNECTED, SendspinRowText.stateOfServer(null, null, false));
        for (de.yaacc.sendspin.client.ControllerSession.Phase phase : de.yaacc.sendspin.client.ControllerSession.Phase.values()) {
            assertNotNull(phase.name(), SendspinRowText.stateOfServer(phase, de.yaacc.sendspin.client.ControllerSession.EndReason.NONE, false));
        }
        de.yaacc.sendspin.client.ControllerSession.Phase active = de.yaacc.sendspin.client.ControllerSession.Phase.ACTIVE;
        assertEquals(SendspinRowText.State.SERVER_CONNECTED, SendspinRowText.stateOfServer(active, null, false));
        assertEquals(SendspinRowText.State.PLAYING, SendspinRowText.stateOfServer(active, null, true));
        assertEquals(SendspinRowText.State.SERVER_WAITING,
                SendspinRowText.stateOfServer(de.yaacc.sendspin.client.ControllerSession.Phase.WAITING_FOR_SERVER, null, false));
        assertEquals(SendspinRowText.State.CONNECTING,
                SendspinRowText.stateOfServer(de.yaacc.sendspin.client.ControllerSession.Phase.WAITING_FOR_ACTIVATION, null, false));
        de.yaacc.sendspin.client.ControllerSession.Phase ended = de.yaacc.sendspin.client.ControllerSession.Phase.ENDED;
        assertEquals(SendspinRowText.State.SERVER_NEEDS_PAIRING, SendspinRowText.stateOfServer(ended,
                de.yaacc.sendspin.client.ControllerSession.EndReason.PAIRING_REQUIRED, false));
        assertEquals(SendspinRowText.State.SERVER_REFUSED, SendspinRowText.stateOfServer(ended,
                de.yaacc.sendspin.client.ControllerSession.EndReason.UNAUTHORIZED, false));
        assertEquals(SendspinRowText.State.UNREACHABLE, SendspinRowText.stateOfServer(ended,
                de.yaacc.sendspin.client.ControllerSession.EndReason.FAILED, false));
        assertEquals(SendspinRowText.State.DISCONNECTED, SendspinRowText.stateOfServer(ended,
                de.yaacc.sendspin.client.ControllerSession.EndReason.SERVER_CLOSED, false));
        for (SendspinRowText.State state : SendspinRowText.State.values()) {
            assertFalse("a server card does not offer the entry of a pairing token (pairing as a client is not part of this version)",
                    state == SendspinRowText.State.SERVER_NEEDS_PAIRING && SendspinRowText.offersPairing(state));
        }
    }

    @Test
    public void aServerCardTextHasTheServerTypeAndNowPlaying() {
        assertEquals("Sendspin server · Music Assistant · music.local:8927 · playing: Band - Song",
                SendspinRowText.ofServer("Music Assistant", "music.local:8927", SendspinRowText.State.PLAYING, "Band - Song", WORDS));
        assertEquals("Sendspin server · music.local:8927 · server_connected",
                SendspinRowText.ofServer(null, "music.local:8927", SendspinRowText.State.SERVER_CONNECTED, "", WORDS));
    }

    @Test
    public void theNowPlayingTextJoinsArtistAndTitleAndLeavesOutWhatIsMissing() {
        assertEquals("Band - Song", SendspinRowText.nowPlaying("Song", "Band"));
        assertEquals("Song", SendspinRowText.nowPlaying("Song", null));
        assertEquals("Band", SendspinRowText.nowPlaying("", "Band"));
        assertEquals("", SendspinRowText.nowPlaying(null, null));
        assertEquals("control characters of the network are cleaned", "Band - Song", SendspinRowText.nowPlaying("Song\u0007", "Band\n"));
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
