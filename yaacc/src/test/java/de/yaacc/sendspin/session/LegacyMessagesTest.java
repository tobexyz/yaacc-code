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

import java.nio.ByteBuffer;
import java.util.Arrays;

/** The messages of the older, unencrypted wire of the protocol (the "transition mode"), as the Kodi add-on and the reference server use it. */
public class LegacyMessagesTest {

    /** What a legacy player sends first: the shape of the add-on's client/hello. */
    private static final String HELLO = "{\"client_id\":\"kodi-1234\",\"name\":\"Living room\",\"version\":1,"
            + "\"supported_roles\":[\"player@v1\",\"metadata@v1\",\"controller@v1\"],"
            + "\"device_info\":{\"product_name\":\"Kodi\",\"manufacturer\":\"kodi-sendspin\",\"software_version\":\"1.0.3\"},"
            + "\"player@v1_support\":{\"supported_formats\":[{\"codec\":\"pcm\",\"channels\":2,\"sample_rate\":48000,\"bit_depth\":16},"
            + "{\"codec\":\"pcm\",\"channels\":2,\"sample_rate\":44100,\"bit_depth\":16}],\"buffer_capacity\":2000000,"
            + "\"supported_commands\":[\"volume\",\"mute\"]}}";

    private static void rejects(String what, java.util.concurrent.Callable<?> parse) {
        try {
            parse.call();
            fail(what);
        } catch (ProtocolException expected) {
            // fine
        } catch (Exception e) {
            fail(what + ": " + e);
        }
    }

    @Test
    public void aLegacyHelloGivesTheIdTheFormatsAndTheCommandsOfThePlayerSupportObject() throws Exception {
        SendspinMessages.ClientHello hello = SendspinMessages.parseLegacyClientHello(new JSONObject(HELLO));
        assertEquals("kodi-1234", hello.clientId);
        assertEquals("Living room", hello.name);
        assertTrue(hello.supportsPlayer());
        assertEquals(2, hello.playerFormats.size());
        assertEquals(48000, hello.playerFormats.get(0).sampleRate);
        assertEquals(2_000_000L, hello.playerBufferCapacity);
        assertEquals(Arrays.asList("volume", "mute"), hello.playerCommands);
        assertTrue("a legacy player is never paired: it is an unpaired session", hello.unpairedAccess);
        assertEquals("Kodi", hello.productName);
    }

    @Test
    public void aLegacyHelloNeedsAnIdThatIsPlainShortText() throws Exception {
        JSONObject noId = new JSONObject(HELLO);
        noId.remove("client_id");
        rejects("no id", () -> SendspinMessages.parseLegacyClientHello(noId));
        for (String bad : new String[]{"", "x\u0007y", "line\nbreak", new String(new char[65]).replace('\0', 'x')}) {
            JSONObject o = new JSONObject(HELLO);
            o.put("client_id", bad);
            rejects("id " + bad.length(), () -> SendspinMessages.parseLegacyClientHello(o));
        }
        JSONObject uuid = new JSONObject(HELLO);
        uuid.put("client_id", "0f8fad5b-d9cb-469f-a165-70867728950e");
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", SendspinMessages.parseLegacyClientHello(uuid).clientId);
    }

    @Test
    public void theHelloOfTheEncryptedProtocolStillParsesAndHasNoCommandsInItsSupportObject() throws Exception {
        JSONObject modern = new JSONObject("{\"name\":\"n\",\"supported_roles\":[\"player@v1\"],\"player@v1_support\":{\"supported_formats\":"
                + "[{\"codec\":\"pcm\",\"channels\":2,\"sample_rate\":44100,\"bit_depth\":16}],\"buffer_capacity\":1000},"
                + "\"unpaired_access\":{\"enabled\":false}}");
        SendspinMessages.ClientHello hello = SendspinMessages.parseClientHello(modern);
        assertTrue(hello.playerCommands.isEmpty());
        assertNull(hello.clientId);
        assertFalse(hello.unpairedAccess);
    }

    @Test
    public void aLegacyStateNamesTheDelayStaticDelayAndTakesTheCommandsOfTheHello() throws Exception {
        JSONObject state = new JSONObject("{\"available\":true,\"player\":{\"volume\":40,\"muted\":false,\"static_delay_ms\":12,"
                + "\"required_lead_time_ms\":3200,\"min_buffer_ms\":3500}}");
        SendspinMessages.ClientState parsed = SendspinMessages.parseLegacyClientState(state, Arrays.asList("volume", "mute"));
        assertTrue(parsed.available);
        assertEquals(Integer.valueOf(40), parsed.player.volume);
        assertEquals(12, parsed.player.outputDelayMs);
        assertEquals(3200, parsed.player.requiredLeadTimeMs);
        assertEquals(3500, parsed.player.minBufferMs);
        assertEquals(Arrays.asList("volume", "mute"), parsed.player.supportedCommands);
    }

    @Test
    public void aLegacyStateMayAlsoUseTheNewNameAndRejectsWhatIsOutOfRange() throws Exception {
        SendspinMessages.ClientState modern = SendspinMessages.parseLegacyClientState(new JSONObject(
                "{\"available\":true,\"player\":{\"volume\":1,\"output_delay_ms\":7,\"required_lead_time_ms\":1,\"min_buffer_ms\":1}}"),
                Arrays.asList("volume"));
        assertEquals(7, modern.player.outputDelayMs);
        rejects("volume above 100", () -> SendspinMessages.parseLegacyClientState(new JSONObject(
                "{\"available\":true,\"player\":{\"volume\":101,\"static_delay_ms\":0,\"required_lead_time_ms\":1,\"min_buffer_ms\":1}}"),
                Arrays.asList("volume")));
        rejects("no delay at all", () -> SendspinMessages.parseLegacyClientState(new JSONObject(
                "{\"available\":true,\"player\":{\"volume\":1,\"required_lead_time_ms\":1,\"min_buffer_ms\":1}}"), Arrays.asList("volume")));
        rejects("not available", () -> SendspinMessages.parseLegacyClientState(new JSONObject("{\"player\":{}}"), Arrays.asList("volume")));
    }

    @Test
    public void theLegacyServerHelloCarriesTheIdentityTheRolesAndTheReason() throws Exception {
        Envelope e = Envelope.parse(SendspinMessages.legacyServerHello("server-id", "YAACC", Arrays.asList("player@v1"), "playback")
                .getBytes("UTF-8"));
        assertEquals("server/hello", e.type());
        JSONObject p = e.payload();
        assertEquals("server-id", p.getString("server_id"));
        assertEquals("YAACC", p.getString("name"));
        assertEquals(1, p.getInt("version"));
        assertEquals("playback", p.getString("connection_reason"));
        assertEquals("player@v1", p.getJSONArray("active_roles").getString(0));
    }

    @Test
    public void aLegacyAudioChunkIsTheTimestampAndTheAudioWithoutTheSendAheadField() {
        byte[] pcm = {1, 2, 3, 4};
        byte[] chunk = SendspinMessages.audioChunkLegacy(0x0102030405060708L, pcm, 0, pcm.length);
        assertEquals(12, chunk.length);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 1, 2, 3, 4}, chunk);
        assertEquals(0x0102030405060708L, ByteBuffer.wrap(chunk).getLong());
        assertEquals("the encrypted wire has four bytes more", SendspinMessages.audioChunk(1, 0, pcm, 0, 4).length, chunk.length + 4);
    }
}
