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
package de.yaacc.sendspin.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** The player role seen from the client: what the phone says and what it reads of a server. */
public class PlayerMessagesTest {

    private static JSONObject payload(String json) throws Exception {
        return new JSONObject(json);
    }

    @Test
    public void theHelloOffersThePlayerRoleWithItsFormatsBufferAndCommandsAndIsReadBackByTheServerSide() throws Exception {
        String hello = PlayerMessages.clientHello("Phone", "YAACC", false,
                Arrays.asList(new int[]{48000, 2, 16}, new int[]{44100, 2, 16}), 1_000_000L,
                Arrays.asList("player@v1", "controller@v1"));
        SendspinMessages.ClientHello parsed = SendspinMessages.parseClientHello(Envelope.parse(hello.getBytes("UTF-8")).payload());
        assertEquals("Phone", parsed.name);
        assertTrue(parsed.supportedRoles.contains("player@v1"));
        assertTrue(parsed.supportedRoles.contains("controller@v1"));
        assertEquals(2, parsed.playerFormats.size());
        assertEquals(48000, parsed.playerFormats.get(0).sampleRate);
        assertEquals("pcm", parsed.playerFormats.get(0).codec);
        assertEquals(1_000_000L, parsed.playerBufferCapacity);
        assertFalse(parsed.unpairedAccess);
        assertTrue(parsed.pairMethods.contains("pairing_psk"));
    }

    @Test
    public void theStateCarriesTheVolumeTheMuteTheDelayAndTheLeadTheServerNeedsToKnow() throws Exception {
        String state = PlayerMessages.clientState(true, 40, false, 25, 500, 800, Arrays.asList("volume", "mute", "set_output_delay"));
        SendspinMessages.ClientState parsed = SendspinMessages.parseClientState(Envelope.parse(state.getBytes("UTF-8")).payload());
        assertTrue(parsed.available);
        assertEquals(Integer.valueOf(40), parsed.player.volume);
        assertEquals(Boolean.FALSE, parsed.player.muted);
        assertEquals(25, parsed.player.outputDelayMs);
        assertEquals(500, parsed.player.requiredLeadTimeMs);
        assertEquals(800, parsed.player.minBufferMs);
        assertTrue(parsed.player.supportedCommands.contains("set_output_delay"));
    }

    @Test
    public void aStateWithoutAPlayerIsJustAvailability() throws Exception {
        SendspinMessages.ClientState parsed = SendspinMessages.parseClientState(
                Envelope.parse(PlayerMessages.clientStateUnavailable().getBytes("UTF-8")).payload());
        assertFalse(parsed.available);
        assertNull(parsed.player);
    }

    @Test
    public void aVolumeAndAMuteAndAnOutputDelayCommandAreRead() throws Exception {
        PlayerMessages.Command volume = PlayerMessages.parseCommand(payload("{\"player\":{\"command\":\"volume\",\"volume\":55}}"));
        assertEquals("volume", volume.command);
        assertEquals(Integer.valueOf(55), volume.volume);
        PlayerMessages.Command mute = PlayerMessages.parseCommand(payload("{\"player\":{\"command\":\"mute\",\"mute\":true}}"));
        assertEquals(Boolean.TRUE, mute.mute);
        PlayerMessages.Command delay = PlayerMessages.parseCommand(payload("{\"player\":{\"command\":\"set_output_delay\",\"output_delay_ms\":120}}"));
        assertEquals(Integer.valueOf(120), delay.outputDelayMs);
    }

    @Test
    public void aCommandThatIsNotForThePlayerIsNull() throws Exception {
        assertNull(PlayerMessages.parseCommand(payload("{\"other\":{}}")));
    }

    @Test
    public void aBadCommandIsAProtocolError() throws Exception {
        for (String bad : new String[]{"{\"player\":{\"command\":\"volume\",\"volume\":101}}", "{\"player\":{\"command\":\"volume\"}}",
                "{\"player\":{\"command\":\"mute\",\"mute\":1}}", "{\"player\":{\"command\":\"set_output_delay\",\"output_delay_ms\":-1}}",
                "{\"player\":{}}", "{\"player\":5}"}) {
            try {
                PlayerMessages.parseCommand(payload(bad));
                fail(bad);
            } catch (ProtocolException expected) {
                // refused
            }
        }
    }

    @Test
    public void theStreamStartOfThePlayerIsRead() throws Exception {
        PlayerMessages.StreamStart start = PlayerMessages.parseStreamStart(payload(
                "{\"server_transmitted\":123,\"player\":{\"codec\":\"pcm\",\"sample_rate\":48000,\"channels\":2,\"bit_depth\":16}}"));
        assertEquals("pcm", start.codec);
        assertEquals(48000, start.sampleRate);
        assertEquals(2, start.channels);
        assertEquals(16, start.bitDepth);
        assertNull("a start without a player object is for other roles", PlayerMessages.parseStreamStart(payload("{\"artwork\":{}}")));
    }

    @Test
    public void aBadStreamStartIsAProtocolError() throws Exception {
        for (String bad : new String[]{"{\"player\":{\"codec\":\"pcm\",\"sample_rate\":0,\"channels\":2,\"bit_depth\":16}}",
                "{\"player\":{\"codec\":\"pcm\",\"sample_rate\":48000,\"channels\":99,\"bit_depth\":16}}", "{\"player\":{\"codec\":\"pcm\"}}"}) {
            try {
                PlayerMessages.parseStreamStart(payload(bad));
                fail(bad);
            } catch (ProtocolException expected) {
                // refused
            }
        }
    }

    @Test
    public void aClearAndAnEndAreForThePlayerWhenTheyNameItOrNameNoRole() throws Exception {
        assertTrue(PlayerMessages.clearsPlayer(payload("{\"roles\":[\"player\"]}")));
        assertTrue(PlayerMessages.clearsPlayer(payload("{}")));
        assertFalse(PlayerMessages.clearsPlayer(payload("{\"roles\":[\"artwork\"]}")));
        assertEquals(Long.valueOf(77), PlayerMessages.clearFrom(payload("{\"from_timestamp\":77}")));
        assertNull(PlayerMessages.clearFrom(payload("{}")));
    }

    @Test
    public void anAudioChunkIsReadAsATimestampASendAheadAndTheAudio() {
        byte[] chunk = SendspinMessages.audioChunk(1_234_567L, 500_000L, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 0, 8);
        PlayerMessages.AudioChunk parsed = PlayerMessages.parseAudioChunk(chunk);
        assertEquals(1_234_567L, parsed.timestampMicros);
        assertEquals(500_000L, parsed.sendAheadMicros);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, parsed.audio);
        assertNull("too short", PlayerMessages.parseAudioChunk(new byte[5]));
        assertEquals(0, PlayerMessages.parseAudioChunk(new byte[12]).audio.length);
    }
}
