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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** The server's messages of the controller and metadata roles, read back by the client side's parsers (a round trip proves both halves). */
public class ControllerServerMessagesTest {

    private static JSONObject payload(String message) throws Exception {
        return new JSONObject(message).getJSONObject("payload");
    }

    @Test
    public void theControllerStateIsReadBackByTheClientSide() throws Exception {
        String message = ControllerServerMessages.serverState(
                new ControllerServerMessages.ControllerView(Arrays.asList("play", "pause", "seek"), 42, true, "all", false, 215000L), null);
        assertEquals("server/state", new JSONObject(message).getString("type"));
        ControllerMessages.ServerState state = ControllerMessages.parseServerState(payload(message));
        assertNull("an omitted object leaves that state unchanged", state.metadata);
        assertTrue(state.controller.supports("seek"));
        assertFalse(state.controller.supports("next"));
        assertEquals(42, state.controller.volume);
        assertTrue(state.controller.muted);
        assertEquals("all", state.controller.repeat);
        assertFalse(state.controller.shuffle);
        assertEquals(Long.valueOf(215000L), state.controller.seekMaxMs);
    }

    @Test
    public void seekMaxIsOmittedWhenTheRangeIsUnknown() throws Exception {
        String message = ControllerServerMessages.serverState(
                new ControllerServerMessages.ControllerView(Collections.<String>emptyList(), 100, false, "off", false, null), null);
        assertFalse(payload(message).getJSONObject("controller").has("seek_max_ms"));
        assertNull(ControllerMessages.parseServerState(payload(message)).controller.seekMaxMs);
    }

    @Test
    public void metadataCarriesTheTextsAndTheProgressAtItsTimestamp() throws Exception {
        ControllerServerMessages.MetadataView view = new ControllerServerMessages.MetadataView(5_000_000L, "Title", "Artist", null, "Album",
                "http://192.168.0.2/cover.jpg", 2024, 3, new ControllerServerMessages.Progress(12000L, 215000L, 1000));
        ControllerMessages.ServerState state = ControllerMessages.parseServerState(payload(ControllerServerMessages.serverState(null, view)));
        assertNull(state.controller);
        assertEquals(5_000_000L, state.metadata.timestamp);
        assertEquals("Title", state.metadata.title);
        assertEquals("Artist", state.metadata.artist);
        assertEquals("Album", state.metadata.album);
        assertEquals("http://192.168.0.2/cover.jpg", state.metadata.artworkUrl);
        assertEquals(12000L, state.metadata.progress.trackProgressMs);
        assertEquals(215000L, state.metadata.progress.trackDurationMs);
        assertEquals(1000, state.metadata.progress.playbackSpeed);
    }

    @Test
    public void textsAreClippedToTheLimitsOfTheProtocolOnACharacterBoundary() throws Exception {
        StringBuilder long300 = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            long300.append('ä'); // two bytes each
        }
        ControllerServerMessages.MetadataView view = new ControllerServerMessages.MetadataView(1L, long300.toString(), null, null, null, null, 0, 0, null);
        JSONObject metadata = payload(ControllerServerMessages.serverState(null, view)).getJSONObject("metadata");
        String title = metadata.getString("title");
        assertTrue(title.getBytes("UTF-8").length <= 255);
        assertTrue("whole characters only", title.length() >= 126 && title.matches("ä+"));
        assertFalse("absent fields are left out", metadata.has("artist"));
        assertFalse("a year of 0 is unknown", metadata.has("year"));
        assertFalse("a track of 0 is unknown", metadata.has("track"));
        assertFalse(metadata.has("progress"));
        ControllerMessages.parseServerState(payload(ControllerServerMessages.serverState(null, view))); // the client accepts it
    }

    @Test
    public void aTooLongArtworkUrlIsLeftOut() throws Exception {
        StringBuilder url = new StringBuilder("http://h/");
        while (url.length() < 2100) {
            url.append('x');
        }
        ControllerServerMessages.MetadataView view = new ControllerServerMessages.MetadataView(1L, "t", null, null, null, url.toString(), 0, 0, null);
        assertFalse(payload(ControllerServerMessages.serverState(null, view)).getJSONObject("metadata").has("artwork_url"));
    }

    @Test
    public void aCommandIsReadWithItsParameter() throws Exception {
        assertEquals("pause", ControllerServerMessages.parseCommand(payload(ControllerMessages.commandPlain("pause"))).command);
        ControllerServerMessages.Command volume = ControllerServerMessages.parseCommand(payload(ControllerMessages.commandVolume(37)));
        assertEquals("volume", volume.command);
        assertEquals(Integer.valueOf(37), volume.volume);
        assertEquals(Boolean.TRUE, ControllerServerMessages.parseCommand(payload(ControllerMessages.commandMute(true))).mute);
        assertEquals(Long.valueOf(9000L), ControllerServerMessages.parseCommand(payload(ControllerMessages.commandSeek(9000L))).positionMs);
        assertEquals(Long.valueOf(-5000L), ControllerServerMessages.parseCommand(payload(ControllerMessages.commandSeekRelative(-5000L))).offsetMs);
    }

    @Test
    public void aMalformedCommandIsRefusedNotTrusted() throws Exception {
        String[] bad = {
                "{\"type\":\"client/command\",\"payload\":{}}", // no controller object
                "{\"type\":\"client/command\",\"payload\":{\"controller\":{}}}", // no command
                "{\"type\":\"client/command\",\"payload\":{\"controller\":{\"command\":\"explode\"}}}",
                "{\"type\":\"client/command\",\"payload\":{\"controller\":{\"command\":\"volume\"}}}", // parameter missing
                "{\"type\":\"client/command\",\"payload\":{\"controller\":{\"command\":\"volume\",\"volume\":101}}}",
                "{\"type\":\"client/command\",\"payload\":{\"controller\":{\"command\":\"volume\",\"volume\":-1}}}",
                "{\"type\":\"client/command\",\"payload\":{\"controller\":{\"command\":\"mute\",\"mute\":\"yes\"}}}",
                "{\"type\":\"client/command\",\"payload\":{\"controller\":{\"command\":\"seek\",\"position_ms\":-1}}}",
                "{\"type\":\"client/command\",\"payload\":{\"controller\":{\"command\":\"seek_relative\"}}}",
                "{\"type\":\"client/command\",\"payload\":{\"controller\":\"pause\"}}",
        };
        for (String message : bad) {
            try {
                ControllerServerMessages.parseCommand(new JSONObject(message).getJSONObject("payload"));
                fail("accepted: " + message);
            } catch (ProtocolException expected) {
                // refused
            }
        }
    }
}
