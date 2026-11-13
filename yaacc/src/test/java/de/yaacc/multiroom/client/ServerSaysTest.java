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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.control.SnapcastServerStatus;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/**
 * The owner's failing restart (Fix Group 8i): the phone's client is connected, but alone in a group of its own on the
 * idle stream "Default", while the stream "YAACC spike" is playing for the three other groups. The Server.GetStatus of that
 * moment is the fixture. Nothing here talks to a server.
 */
public class ServerSaysTest {

    private static final String PHONE = "yaacc-0123456789abcdef";

    @After
    public void reset() {
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.IDLE, SnapcastClientRuntime.Ending.NONE, 0);
    }

    private static SnapcastServerStatus fixture() throws Exception {
        String text = new String(Files.readAllBytes(Paths.get("src/test/resources/snapcast/status_phone_alone_on_idle_stream.json")),
                StandardCharsets.UTF_8);
        return SnapcastServerStatus.fromResult(new JSONObject(text).getJSONObject("result"));
    }

    private static ServerSays says() throws Exception {
        return ServerSays.of(fixture(), InetAddress.getByName("192.168.0.10"), PHONE);
    }

    @Test
    public void theOwnersStatusShowsTheClientAloneInAGroupOnTheIdleStream() throws Exception {
        ServerSays says = says();
        assertTrue(says.isReached() && says.isClientFound());
        assertEquals("5cb59c23-1111-2222-3333-444455556666", says.getGroupId());
        assertEquals("Default", says.getStreamId());
        assertFalse(says.isOwnStreamPlaying());
        assertEquals(2, says.getStreams().size());
        String text = says.text();
        assertTrue(text, text.contains("my client is connected, volume 100"));
        assertTrue(text, text.contains("my group 5cb59c23 (1 client, not muted) is on stream 'Default' (idle)"));
        assertTrue(text, text.contains("server streams: Default idle, YAACC spike playing"));
    }

    @Test
    public void withNoChunkAndAnIdleStreamTheHintSaysToMoveTheGroup() throws Exception {
        String hint = says().hint(0);
        assertTrue(hint, hint.contains("The server sends nothing"));
        assertTrue(hint, hint.contains("on a stream that is idle"));
        assertTrue(hint, hint.contains("Move my group to a stream"));
        assertEquals("chunks are coming: nothing to say", "", says().hint(12));
    }

    @Test
    public void aClientThatTheServerDoesNotListGetsItsOwnHintAndAnUnreachablePortIsSaid() throws Exception {
        ServerSays other = ServerSays.of(fixture(), InetAddress.getByName("192.168.0.10"), "somebody-else");
        assertFalse(other.isClientFound());
        assertTrue(other.text(), other.text().contains("does not list this client"));
        assertTrue(other.hint(0), other.hint(0).contains("not in a group yet"));
        ServerSays gone = ServerSays.unreachable(InetAddress.getByName("192.168.0.10"), PHONE);
        assertTrue(gone.text(), gone.text().contains("control port (1705) could not be asked"));
        assertEquals("", gone.hint(0));
    }

    @Test
    public void theServerSaysAppearsInTheStatusTextWithTheHint() throws Exception {
        EngineStatus status = new EngineStatus();
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 0);
        SnapcastClientRuntime.engine(status);
        SnapcastClientRuntime.server(says());
        String text = SnapcastClientStatusText.of(SnapcastClientRuntime.get(), 0);
        assertTrue(text, text.contains("server says: my client is connected"));
        assertTrue(text, text.contains("HINT: The server sends nothing"));
        // the view keeps the server's answer when the engine publishes a newer status
        SnapcastClientRuntime.engine(new EngineStatus());
        assertTrue(SnapcastClientRuntime.get().server != null);
        // and a new session starts without the old answer
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.CONNECTING, SnapcastClientRuntime.Ending.NONE, 0);
        assertTrue(SnapcastClientRuntime.get().server == null);
    }

    @Test
    public void theEndingOfTheLastSessionIsToldInWordsWithTheTime() {
        String line = SessionEndingText.of("NO_AUDIO", 1_000_000L, 1_000_000L + 125_000L);
        assertTrue(line, line.startsWith("last session ended 2 min ago: no audio for 15 minutes"));
        assertTrue(SessionEndingText.of("APP_EXIT", 1_000L, 1_000L + 30_000L).contains("shutdown (sleep) timer of YAACC"));
        assertTrue(SessionEndingText.of("SYSTEM", 1_000L, 1_000L + 3 * 3_600_000L).contains("3 h ago"));
        assertEquals("", SessionEndingText.of(null, 0, 0));
        assertEquals("", SessionEndingText.of("NONE", 5, 6));
        assertEquals("", SessionEndingText.of("something unknown", 5, 6));
    }

    @Test
    public void everyEndingHasWordsAndTheEndIsInTheTrace() {
        for (SnapcastClientRuntime.Ending ending : SnapcastClientRuntime.Ending.values()) {
            if (ending != SnapcastClientRuntime.Ending.NONE) {
                assertFalse(ending.name(), SnapcastClientStatusText.endingText(ending).isEmpty());
            }
        }
        EventTrace trace = new EventTrace(0);
        trace.add(1_000_000L, TraceKind.SESSION_END, SnapcastClientRuntime.Ending.NO_AUDIO.ordinal());
        assertTrue(trace.text(), trace.text().contains("session ended  reason=no_audio"));
    }

    // ---- the review's W3, S28: what the server names is shown cleaned, the raw id only goes back to the server

    private static SnapcastServerStatus withStreams(int count, String hostileOwnStream) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get("src/test/resources/snapcast/status_phone_alone_on_idle_stream.json")),
                StandardCharsets.UTF_8);
        JSONObject result = new JSONObject(text).getJSONObject("result");
        org.json.JSONArray streams = result.getJSONObject("server").getJSONArray("streams");
        for (int i = 0; i < count; i++) {
            JSONObject stream = new JSONObject(streams.getJSONObject(0).toString());
            stream.put("id", "extra-" + i);
            stream.put("status", "idle");
            streams.put(stream);
        }
        if (hostileOwnStream != null) {
            JSONObject hostile = new JSONObject(streams.getJSONObject(0).toString());
            hostile.put("id", hostileOwnStream);
            hostile.put("status", "playing");
            streams.put(hostile);
            result.getJSONObject("server").getJSONArray("groups").getJSONObject(0).put("stream_id", hostileOwnStream);
        }
        return SnapcastServerStatus.fromResult(result);
    }

    @Test
    public void aHostileStreamNameIsShownCleanedAndCutButSentBackAsItIs() throws Exception {
        String hostile = "evil\u202Ename\u0007\u200Bwith\ncontrols" + new String(new char[500]).replace('\0', 'x');
        ServerSays says = ServerSays.of(withStreams(0, hostile), InetAddress.getByName("192.0.2.1"), PHONE);
        ServerSays.Stream own = null;
        for (ServerSays.Stream stream : says.getStreams()) {
            if (stream.getId().startsWith("evil")) {
                own = stream;
            }
        }
        assertNotNull("the stream of the client's group is kept", own);
        String shown = own.getDisplayName();
        assertTrue(shown, shown.length() <= 40);
        for (char c : shown.toCharArray()) {
            assertFalse("no control, bidi or invisible character: U+" + Integer.toHexString(c),
                    Character.isISOControl(c) || c == '\u202E' || c == '\u200B');
        }
        assertEquals("the raw id is what the server named", hostile.length() > 0 ? says.getStreamId() : "", own.getId());
        assertTrue(own.getId().indexOf('\u202E') >= 0 || own.getId().indexOf('\u0007') >= 0 || own.getId().length() > 40);
        assertFalse(says.text(), says.text().indexOf('\u202E') >= 0 || says.text().indexOf('\u0007') >= 0);
    }

    @Test
    public void moreStreamsThanTheLimitAreCountedAndTheOwnStreamIsStillThere() throws Exception {
        ServerSays says = ServerSays.of(withStreams(15, "playing-own"), InetAddress.getByName("192.0.2.1"), PHONE);
        assertEquals(12 + 1, says.getStreams().size()); // the limit and the stream of the client's group
        assertTrue(says.getStreamsOmitted() >= 4);
        assertTrue(says.text(), says.text().contains(" and " + says.getStreamsOmitted() + " more"));
        assertTrue(says.text(), says.text().contains("is on stream 'playing-own' (playing)"));
        assertTrue(says.isOwnStreamPlaying());
    }
}
