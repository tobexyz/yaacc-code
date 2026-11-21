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
package de.yaacc.fcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import de.yaacc.fcast.protocol.FCastPacketCodec;

/**
 * Task 4.1: the Play request of an item. URLs go out exactly as they are (decision D9); the one refusal that is not about
 * a missing URL is a request that does not fit the packet.
 */
public class FCastPlayRequestsTest {

    private static JSONObject body(FCastPlayRequests.Result result) throws Exception {
        assertNotNull(result.getRequest());
        return new JSONObject(result.getRequest().toJson());
    }

    @Test
    public void aPlainItemBecomesAPlayWithItsMimeTypeTitleAndVolume() throws Exception {
        FCastPlayRequests.Result result = FCastPlayRequests.build("http://192.168.0.5:8080/proxy/abc", "audio/mpeg", null,
                "A Song", null, 0, 0.5);

        assertEquals(FCastPlayRequests.Refusal.NONE, result.getRefusal());
        JSONObject json = body(result);
        assertEquals("http://192.168.0.5:8080/proxy/abc", json.getString("url"));
        assertEquals("audio/mpeg", json.getString("container"));
        assertEquals(0.5, json.getDouble("volume"), 0.0001);
        assertEquals("A Song", json.getJSONObject("metadata").getString("title"));
        assertFalse("no start time for a start at 0", json.has("time"));
    }

    @Test
    public void aUrlWithUserInformationAndATokenIsSentUnchanged() throws Exception {
        String url = "http://user:pass@media.example:8096/Items/1/Download?api_key=SECRET&x=a%20b#frag";

        JSONObject json = body(FCastPlayRequests.build(url, "video/mp4", null, "T", null, 0, 1));

        assertEquals(url, json.getString("url"));
    }

    @Test
    public void theDescribedTypeWinsAndIsNormalizedThenTheExtensionTypeThenAnUnknownType() throws Exception {
        assertEquals("audio/flac", body(FCastPlayRequests.build("http://h/a", " Audio/FLAC; x=y", "audio/mpeg", "t", null, 0, 1)).getString("container"));
        assertEquals("audio/mpeg", body(FCastPlayRequests.build("http://h/a", "", "audio/mpeg", "t", null, 0, 1)).getString("container"));
        assertEquals("audio/mpeg", body(FCastPlayRequests.build("http://h/a", null, "audio/mpeg", "t", null, 0, 1)).getString("container"));
        assertEquals("the container is never empty", FCastPlayRequests.UNKNOWN_TYPE,
                body(FCastPlayRequests.build("http://h/a", null, null, "t", null, 0, 1)).getString("container"));
        assertEquals(FCastPlayRequests.UNKNOWN_TYPE,
                body(FCastPlayRequests.build("http://h/a", "  ;x", "", "t", null, 0, 1)).getString("container"));
    }

    @Test
    public void aMissingUrlIsRefusedAndNothingIsBuilt() {
        for (String url : new String[]{null, "", "   "}) {
            FCastPlayRequests.Result result = FCastPlayRequests.build(url, "audio/mpeg", null, "t", null, 0, 1);
            assertEquals(FCastPlayRequests.Refusal.NO_URL, result.getRefusal());
            assertNull(result.getRequest());
        }
    }

    @Test
    public void aRequestThatDoesNotFitThePacketIsRefused() {
        StringBuilder url = new StringBuilder("http://h/");
        while (url.length() < FCastPacketCodec.MAX_BODY) {
            url.append("a");
        }

        FCastPlayRequests.Result result = FCastPlayRequests.build(url.toString(), "audio/mpeg", null, "t", null, 0, 1);

        assertEquals(FCastPlayRequests.Refusal.TOO_LARGE, result.getRefusal());
        assertNull(result.getRequest());
    }

    @Test
    public void theSizeIsCountedInBytesNotCharacters() {
        // 11000 characters of 3 bytes each: 11000 chars fit, 33000 bytes do not
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < 11000; i++) {
            path.append('€');
        }

        FCastPlayRequests.Result result = FCastPlayRequests.build("http://h/" + path, "audio/mpeg", null, "t", null, 0, 1);

        assertEquals(FCastPlayRequests.Refusal.TOO_LARGE, result.getRefusal());
    }

    @Test
    public void aRequestJustUnderTheLimitIsAccepted() throws Exception {
        StringBuilder url = new StringBuilder("http://h/");
        String fixed = "{\"container\":\"audio/mpeg\",\"url\":\"\"}";
        while (url.length() + fixed.length() < FCastPacketCodec.MAX_BODY - 400) {
            url.append("a");
        }

        FCastPlayRequests.Result result = FCastPlayRequests.build(url.toString(), "audio/mpeg", null, "t", null, 0, 1);

        assertEquals(FCastPlayRequests.Refusal.NONE, result.getRefusal());
        assertTrue(result.getRequest().toJson().getBytes(StandardCharsets.UTF_8).length <= FCastPacketCodec.MAX_BODY);
    }

    @Test
    public void aStartTimeAndTheVolumeAreClamped() throws Exception {
        JSONObject json = body(FCastPlayRequests.build("http://h/a", "audio/mpeg", null, "t", null, 12.5, 7));
        assertEquals(12.5, json.getDouble("time"), 0.0001);
        assertEquals(1.0, json.getDouble("volume"), 0.0001);

        json = body(FCastPlayRequests.build("http://h/a", "audio/mpeg", null, "t", null, -3, -1));
        assertFalse("a negative start is no start", json.has("time"));
        assertEquals(0.0, json.getDouble("volume"), 0.0001);

        json = body(FCastPlayRequests.build("http://h/a", "audio/mpeg", null, "t", null, Double.NaN, Double.NaN));
        assertFalse(json.has("time"));
        assertFalse("an unknown volume is not sent", json.has("volume"));
    }

    @Test
    public void onlyAReachableWebAddressIsSentAsTheThumbnail() throws Exception {
        assertEquals("http://192.168.0.5/art.jpg",
                body(FCastPlayRequests.build("http://h/a", "audio/mpeg", null, "t", "http://192.168.0.5/art.jpg", 0, 1))
                        .getJSONObject("metadata").getString("thumbnailUrl"));
        for (String art : new String[]{null, "", "file:///sdcard/a.jpg", "content://media/x", "ftp://h/a.jpg", "http://localhost/a.jpg",
                "http://127.0.0.1/a.jpg", "http://[::1]/a.jpg", "not a url"}) {
            JSONObject metadata = body(FCastPlayRequests.build("http://h/a", "audio/mpeg", null, "t", art, 0, 1)).getJSONObject("metadata");
            assertFalse("art " + art, metadata.has("thumbnailUrl"));
        }
    }

    @Test
    public void aTooLongThumbnailAddressIsLeftOutButTheItemStillPlays() throws Exception {
        StringBuilder art = new StringBuilder("http://192.168.0.5/");
        while (art.length() < 5000) {
            art.append("b");
        }

        FCastPlayRequests.Result result = FCastPlayRequests.build("http://h/a", "audio/mpeg", null, "t", art.toString(), 0, 1);

        assertEquals(FCastPlayRequests.Refusal.NONE, result.getRefusal());
        assertFalse(body(result).getJSONObject("metadata").has("thumbnailUrl"));
    }

    @Test
    public void noHeadersAreEverSent() throws Exception {
        JSONObject json = body(FCastPlayRequests.build("http://h/a", "audio/mpeg", null, "t", "http://192.168.0.5/a.jpg", 1, 1));
        assertFalse(json.has("headers"));
    }
}
