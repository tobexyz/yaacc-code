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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

/** The JSON guards and what the server reads of the messages of the player: every field range and every refusal. */
public class SendspinMessagesTest {

    private static Envelope parse(String json) throws ProtocolException {
        return Envelope.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void expectRefusal(String json) {
        try {
            parse(json);
            fail("expected a refusal of " + json);
        } catch (ProtocolException expected) {
            // fine
        }
    }

    @Test
    public void anEnvelopeNeedsATypeAndAPayloadObject() throws Exception {
        Envelope envelope = parse("{\"type\":\"client/time\",\"payload\":{\"client_transmitted\":7}}");
        assertEquals("client/time", envelope.type());
        expectRefusal("[]");
        expectRefusal("{\"payload\":{}}");
        expectRefusal("{\"type\":\"x\"}");
        expectRefusal("{\"type\":1,\"payload\":{}}");
        expectRefusal("{\"type\":\"x\",\"payload\":[]}");
        expectRefusal("{\"type\":\"x\",\"payload\":\"s\"}");
        expectRefusal("nonsense");
    }

    @Test
    public void bytesThatAreNotUtf8AreRefused() {
        try {
            Envelope.parse(new byte[]{'{', (byte) 0xC3, 0x28, '}'});
            fail("not UTF-8");
        } catch (ProtocolException expected) {
            // fine
        }
    }

    @Test
    public void theSizeLimitIsEnforcedBeforeParsing() {
        byte[] big = new byte[Envelope.MAX_BYTES + 1];
        Arrays.fill(big, (byte) ' ');
        try {
            Envelope.parse(big);
            fail("too big");
        } catch (ProtocolException expected) {
            // fine
        }
    }

    @Test
    public void nestingBeyondTenLevelsIsRefusedAndTenIsFine() throws Exception {
        // the root object is level 1; every {"a": adds one level
        assertEquals("x", parse(nested(9)).type()); // level 10
        expectRefusal(nested(10)); // level 11
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            huge.append('[');
        }
        expectRefusal(huge.toString()); // does not reach the parser, and cannot overflow its stack
    }

    private static String nested(int levels) {
        StringBuilder sb = new StringBuilder("{\"type\":\"x\",\"payload\":");
        for (int i = 0; i < levels; i++) {
            sb.append("{\"a\":");
        }
        sb.append("1");
        for (int i = 0; i < levels; i++) {
            sb.append("}");
        }
        return sb.append("}").toString();
    }

    @Test
    public void bracesInsideStringsDoNotCountAsNesting() {
        assertEquals(1, Envelope.maxDepth("{\"a\":\"{{{{{{{{{{{{{\"}"));
        assertEquals(1, Envelope.maxDepth("{\"a\":\"\\\"{{{{{{{{{{{{{\"}"));
        assertEquals(3, Envelope.maxDepth("{\"a\":[1,{\"b\":2}]}"));
        assertEquals(Integer.MAX_VALUE, Envelope.maxDepth("{\"a\":\"unterminated"));
    }

    private static String hello(String playerSupport, String extra) {
        return "{\"name\":\"Kitchen\",\"supported_roles\":[\"player@v1\"]," + playerSupport
                + ",\"supported_pair_methods\":{\"pairing_psk\":{}},\"unpaired_access\":{\"enabled\":true}" + extra + "}";
    }

    private static final String GOOD_SUPPORT = "\"player@v1_support\":{\"supported_formats\":[{\"codec\":\"pcm\",\"channels\":2,"
            + "\"sample_rate\":48000,\"bit_depth\":16},{\"codec\":\"opus\",\"channels\":2,\"sample_rate\":48000}],\"buffer_capacity\":1000000}";

    @Test
    public void aClientHelloIsReadWithItsFormatsAndAccess() throws Exception {
        SendspinMessages.ClientHello hello = SendspinMessages.parseClientHello(new JSONObject(hello(GOOD_SUPPORT, ",\"device_info\":{\"product_name\":\"X1\"}")));
        assertEquals("Kitchen", hello.name);
        assertTrue(hello.supportsPlayer());
        assertEquals(2, hello.playerFormats.size());
        assertTrue(hello.playerFormats.get(0).isPcm(48000, 2, 16));
        assertFalse(hello.playerFormats.get(0).isPcm(44100, 2, 16));
        assertEquals(1000000L, hello.playerBufferCapacity);
        assertTrue(hello.unpairedAccess);
        assertEquals(Collections.singletonList("pairing_psk"), hello.pairMethods);
        assertEquals("X1", hello.productName);
    }

    private static void expectHelloRefusal(String json) throws Exception {
        try {
            SendspinMessages.parseClientHello(new JSONObject(json));
            fail("expected a refusal of " + json);
        } catch (ProtocolException expected) {
            // fine
        }
    }

    @Test
    public void aClientHelloThatBreaksTheRulesIsRefused() throws Exception {
        expectHelloRefusal("{\"supported_roles\":[\"player@v1\"]}"); // no name
        expectHelloRefusal(hello(GOOD_SUPPORT, "").replace("\"Kitchen\"", "\"" + new String(new char[65]).replace('\0', 'x') + "\"")); // 65 bytes
        expectHelloRefusal("{\"name\":\"a\"}"); // no roles
        expectHelloRefusal("{\"name\":\"a\",\"supported_roles\":[\"player@v1\"]}"); // no support object although the role is listed
        expectHelloRefusal(hello("\"player@v1_support\":{\"supported_formats\":[],\"buffer_capacity\":5}", ""));
        expectHelloRefusal(hello("\"player@v1_support\":{\"supported_formats\":[{\"codec\":\"pcm\",\"channels\":2,\"sample_rate\":48000}],\"buffer_capacity\":5}", ""));
        expectHelloRefusal(hello("\"player@v1_support\":{\"supported_formats\":[{\"codec\":\"pcm\",\"channels\":2,\"sample_rate\":48000,\"bit_depth\":16}]}", ""));
        expectHelloRefusal(hello("\"player@v1_support\":{\"supported_formats\":[{\"codec\":\"pcm\",\"channels\":0,\"sample_rate\":48000,\"bit_depth\":16}],\"buffer_capacity\":5}", ""));
    }

    @Test
    public void aClientHelloWithoutThePlayerRoleNeedsNoSupportObject() throws Exception {
        SendspinMessages.ClientHello hello = SendspinMessages.parseClientHello(new JSONObject("{\"name\":\"remote\",\"supported_roles\":[\"controller@v1\"]}"));
        assertFalse(hello.supportsPlayer());
        assertFalse(hello.unpairedAccess);
    }

    @Test
    public void aClientStateIsReadAndItsRangesAreChecked() throws Exception {
        SendspinMessages.ClientState state = SendspinMessages.parseClientState(new JSONObject("{\"available\":true,\"player\":{\"volume\":25,"
                + "\"muted\":false,\"output_delay_ms\":10,\"required_lead_time_ms\":250,\"min_buffer_ms\":250,"
                + "\"supported_commands\":[\"volume\",\"mute\"]}}"));
        assertTrue(state.available);
        assertEquals(Integer.valueOf(25), state.player.volume);
        assertEquals(Boolean.FALSE, state.player.muted);
        assertEquals(10, state.player.outputDelayMs);
        assertEquals(250, state.player.requiredLeadTimeMs);
        assertEquals(Arrays.asList("volume", "mute"), state.player.supportedCommands);
        assertNull(SendspinMessages.parseClientState(new JSONObject("{\"available\":false}")).player);

        String[] bad = {
                "{}",
                "{\"available\":\"yes\"}",
                "{\"available\":true,\"player\":{\"volume\":101,\"output_delay_ms\":0,\"required_lead_time_ms\":0,\"min_buffer_ms\":0,\"supported_commands\":[]}}",
                "{\"available\":true,\"player\":{\"volume\":-1,\"output_delay_ms\":0,\"required_lead_time_ms\":0,\"min_buffer_ms\":0,\"supported_commands\":[]}}",
                "{\"available\":true,\"player\":{\"output_delay_ms\":5001,\"required_lead_time_ms\":0,\"min_buffer_ms\":0,\"supported_commands\":[]}}",
                "{\"available\":true,\"player\":{\"output_delay_ms\":0,\"required_lead_time_ms\":-1,\"min_buffer_ms\":0,\"supported_commands\":[]}}",
                "{\"available\":true,\"player\":{\"output_delay_ms\":0,\"required_lead_time_ms\":0,\"min_buffer_ms\":5001,\"supported_commands\":[]}}",
                "{\"available\":true,\"player\":{\"output_delay_ms\":0,\"required_lead_time_ms\":0,\"min_buffer_ms\":0}}",
                "{\"available\":true,\"player\":{\"output_delay_ms\":1.5,\"required_lead_time_ms\":0,\"min_buffer_ms\":0,\"supported_commands\":[]}}",
                "{\"available\":true,\"player\":{\"muted\":\"no\",\"output_delay_ms\":0,\"required_lead_time_ms\":0,\"min_buffer_ms\":0,\"supported_commands\":[]}}",
                "{\"available\":true,\"player\":5}",
        };
        for (String json : bad) {
            try {
                SendspinMessages.parseClientState(new JSONObject(json));
                fail("expected a refusal of " + json);
            } catch (ProtocolException expected) {
                // fine
            }
        }
    }

    @Test
    public void timeAndGoodbyeAreRead() throws Exception {
        assertEquals(123456789012L, SendspinMessages.parseClientTime(new JSONObject("{\"client_transmitted\":123456789012}")));
        try {
            SendspinMessages.parseClientTime(new JSONObject("{\"client_transmitted\":\"1\"}"));
            fail("not an integer");
        } catch (ProtocolException expected) {
            // fine
        }
        assertEquals("another_server", SendspinMessages.parseGoodbyeReason(new JSONObject("{\"reason\":\"another_server\"}")));
    }

    @Test
    public void theMessagesTheServerWritesCarryTheDocumentedFields() throws Exception {
        JSONObject activate = new JSONObject(SendspinMessages.serverActivate(Collections.singletonList("playback"), Collections.singletonList("player@v1")));
        assertEquals("server/activate", activate.getString("type"));
        assertEquals("playback", activate.getJSONObject("payload").getJSONArray("activities").getString(0));
        assertEquals("player@v1", activate.getJSONObject("payload").getJSONArray("active_roles").getString(0));

        JSONObject time = new JSONObject(SendspinMessages.serverTime(1, 2, 3)).getJSONObject("payload");
        assertEquals(1L, time.getLong("client_transmitted"));
        assertEquals(2L, time.getLong("server_received"));
        assertEquals(3L, time.getLong("server_transmitted"));

        JSONObject start = new JSONObject(SendspinMessages.streamStartPcm(99, 44100, 2, 16)).getJSONObject("payload");
        assertEquals(99L, start.getLong("server_transmitted"));
        assertEquals("pcm", start.getJSONObject("player").getString("codec"));
        assertEquals(44100, start.getJSONObject("player").getInt("sample_rate"));
        assertFalse("no codec_header for pcm", start.getJSONObject("player").has("codec_header"));

        JSONObject clear = new JSONObject(SendspinMessages.streamClear(5, null)).getJSONObject("payload");
        assertFalse(clear.has("from_timestamp"));
        assertEquals("player", clear.getJSONArray("roles").getString(0));
        assertEquals(77L, new JSONObject(SendspinMessages.streamClear(5, 77L)).getJSONObject("payload").getLong("from_timestamp"));

        assertEquals("player", new JSONObject(SendspinMessages.streamEnd()).getJSONObject("payload").getJSONArray("roles").getString(0));
        JSONObject volume = new JSONObject(SendspinMessages.commandVolume(40)).getJSONObject("payload").getJSONObject("player");
        assertEquals("volume", volume.getString("command"));
        assertEquals(40, volume.getInt("volume"));
        JSONObject mute = new JSONObject(SendspinMessages.commandMute(true)).getJSONObject("payload").getJSONObject("player");
        assertEquals("mute", mute.getString("command"));
        assertTrue(mute.getBoolean("mute"));
        JSONObject group = new JSONObject(SendspinMessages.groupUpdate(true, "g", "n")).getJSONObject("payload");
        assertEquals("playing", group.getString("playback_state"));
        assertEquals("stopped", new JSONObject(SendspinMessages.groupUpdate(false, "g", "n")).getJSONObject("payload").getString("playback_state"));
    }

    @Test
    public void anAudioChunkHasTheLayoutOfTheSpecification() {
        byte[] audio = {1, 2, 3, 4};
        byte[] body = SendspinMessages.audioChunk(0x0102030405060708L, 1500000L, audio, 0, 4);
        assertEquals(12 + 4, body.length);
        ByteBuffer buffer = ByteBuffer.wrap(body);
        assertEquals(0x0102030405060708L, buffer.getLong());
        assertEquals(1500000, buffer.getInt());
        assertArrayEquals(audio, Arrays.copyOfRange(body, 12, 16));
    }

    @Test
    public void sendAheadIsZeroWhenLateAndSaturatesWhenTooLong() {
        assertEquals(0, ByteBuffer.wrap(SendspinMessages.audioChunk(1, -5, new byte[0], 0, 0)).getInt(8));
        assertEquals(0, ByteBuffer.wrap(SendspinMessages.audioChunk(1, 0, new byte[0], 0, 0)).getInt(8));
        assertEquals(-1, ByteBuffer.wrap(SendspinMessages.audioChunk(1, 5_000_000_000L, new byte[0], 0, 0)).getInt(8)); // 4294967295 as uint32
    }
}
