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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.client.ArtworkStream;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** The artwork messages of the server, read back by the client side's stream (a round trip proves both halves). */
public class ArtworkWireTest {

    @Test
    public void aStreamStartIsReadBackByTheClientSide() throws Exception {
        String message = ArtworkWire.streamStart(1234L, Arrays.asList(ControllerMessages.ArtworkChannel.album("jpeg", 300, 200),
                ControllerMessages.ArtworkChannel.none()));
        JSONObject payload = new JSONObject(message).getJSONObject("payload");
        assertEquals("stream/start", new JSONObject(message).getString("type"));
        assertEquals(1234L, payload.getLong("server_transmitted"));
        List<ControllerMessages.ArtworkChannel> channels = ControllerMessages.parseStreamStartArtwork(payload);
        assertEquals(2, channels.size());
        assertEquals("album", channels.get(0).source);
        assertEquals("jpeg", channels.get(0).format);
        assertEquals(300, channels.get(0).width);
        assertEquals(200, channels.get(0).height);
        assertEquals("none", channels.get(1).source);
        assertTrue(!payload.getJSONObject("artwork").getJSONArray("channels").getJSONObject(1).has("width"));
    }

    @Test
    public void aStreamEndNamesTheArtworkRole() throws Exception {
        JSONObject end = new JSONObject(ArtworkWire.streamEnd());
        assertEquals("stream/end", end.getString("type"));
        assertEquals("artwork", end.getJSONObject("payload").getJSONArray("roles").getString(0));
    }

    @Test
    public void anImageIsSentAsAnAnnounceAndPartsThatTheClientAssembles() throws Exception {
        byte[] image = new byte[150_000];
        for (int i = 0; i < image.length; i++) {
            image[i] = (byte) i;
        }
        ArtworkStream client = new ArtworkStream();
        client.start(Arrays.asList(ControllerMessages.ArtworkChannel.album("png", 10, 10)));
        client.onMessage(8, ArtworkWire.announce(image.length, 5_000_000L), 6_000_000L);
        int offset = 0;
        int parts = 0;
        while (offset < image.length) {
            int length = Math.min(ArtworkWire.MAX_PART_DATA, image.length - offset);
            byte[] body = ArtworkWire.part(image, offset, length);
            assertTrue("a message with its type byte stays within the cap", body.length + 1 <= ArtworkWire.MAX_MESSAGE_BYTES);
            client.onMessage(8, body, 6_000_000L);
            offset += length;
            parts++;
        }
        assertEquals(3, parts);
        assertArrayEquals(image, client.current(0, 6_000_000L));
    }

    @Test
    public void anEmptyImageClearsTheChannelAndACancelDiscardsThePendingImage() throws Exception {
        ArtworkStream client = new ArtworkStream();
        client.start(Arrays.asList(ControllerMessages.ArtworkChannel.album("jpeg", 10, 10)));
        client.onMessage(8, ArtworkWire.announce(3, 1L), 2L);
        client.onMessage(8, ArtworkWire.part(new byte[]{1, 2, 3}, 0, 3), 2L);
        assertArrayEquals(new byte[]{1, 2, 3}, client.current(0, 2L));
        client.onMessage(8, ArtworkWire.announce(0, 3L), 4L); // an empty image
        assertNull(client.current(0, 4L));
        client.onMessage(8, ArtworkWire.announce(10, 5L), 4L);
        client.onMessage(8, ArtworkWire.part(new byte[]{9, 9}, 0, 2), 4L); // in flight
        client.onMessage(8, ArtworkWire.cancel(), 4L);
        client.onMessage(8, ArtworkWire.announce(0, 6L), 7L); // a new announce is allowed after the cancel
    }

    @Test
    public void theFlagsAndTheLengthsAreExactlyAsTheSpecificationHasThem() {
        byte[] announce = ArtworkWire.announce(0x01020304L, 0x1122334455667788L);
        assertEquals(1 + 8 + 4, announce.length);
        assertEquals(2, announce[0]);
        assertEquals(0x11, announce[1]);
        assertEquals(0x88, announce[8] & 0xFF);
        assertEquals(0x01, announce[9]);
        assertEquals(0x04, announce[12]);
        assertArrayEquals(new byte[]{1}, ArtworkWire.cancel());
        assertEquals(0, ArtworkWire.part(new byte[]{5}, 0, 1)[0]);
        assertEquals(8, ArtworkWire.typeOf(0));
        assertEquals(11, ArtworkWire.typeOf(3));
    }

    // ------------------------------------------------------------------ the layout

    @Test
    public void anImageIsScaledToFitKeepingItsAspectRatioAndCentredOnBlack() {
        ArtworkLayout wide = ArtworkLayout.fit(400, 200, 100, 100); // 2:1 into a square: 100 by 50, centred vertically
        assertEquals(100, wide.width);
        assertEquals(50, wide.height);
        assertEquals(0, wide.x);
        assertEquals(25, wide.y);
        ArtworkLayout tall = ArtworkLayout.fit(100, 400, 200, 100); // 1:4 into 200 by 100: 25 by 100
        assertEquals(25, tall.width);
        assertEquals(100, tall.height);
        assertEquals(87, tall.x);
        assertEquals(0, tall.y);
        ArtworkLayout same = ArtworkLayout.fit(640, 480, 640, 480);
        assertEquals(640, same.width);
        assertEquals(0, same.x);
        ArtworkLayout small = ArtworkLayout.fit(10, 10, 100, 50); // upscaled to fit, not cropped
        assertEquals(50, small.width);
        assertEquals(50, small.height);
        assertEquals(25, small.x);
    }

    @Test
    public void aDegenerateSizeStillGivesAUsableLayout() {
        ArtworkLayout thin = ArtworkLayout.fit(1, 1000, 10, 10);
        assertTrue(thin.width >= 1 && thin.height >= 1);
        assertTrue(thin.x + thin.width <= 10 && thin.y + thin.height <= 10);
    }
}
