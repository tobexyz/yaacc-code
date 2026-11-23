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
package de.yaacc.fcast.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/**
 * Task 2.2: the bodies of protocol v3. The golden strings are written from {@code docs/docs/protocol/v3.md} of the FCast
 * project (field names and types), not produced by the code under test.
 */
public class FCastMessagesTest {

    private static FCastMessages.Incoming parse(FCastOpcode opcode, String body) throws FCastProtocolException {
        return FCastMessages.parse(new FCastPacket(opcode.code(), body));
    }

    private static void assertRefused(FCastOpcode opcode, String body, FCastProtocolException.Kind kind) {
        try {
            parse(opcode, body);
            fail(opcode + " " + body + " was accepted");
        } catch (FCastProtocolException e) {
            assertEquals(opcode + " " + body, kind, e.getKind());
            assertFalse("a bad message does not end the connection", e.endsConnection());
        }
    }

    // ---- Version

    @Test
    public void aVersionIsAnIntegerOfOneOrMore() throws Exception {
        assertEquals(3, ((FCastMessages.Version) parse(FCastOpcode.VERSION, "{\"version\":3}")).getVersion());
        assertEquals(4, ((FCastMessages.Version) parse(FCastOpcode.VERSION, "{\"version\":4,\"x\":1}")).getVersion());
        for (String bad : new String[]{"{\"version\":0}", "{\"version\":-1}", "{\"version\":1.5}", "{\"version\":\"3\"}", "{}",
                "{\"version\":null}", "{\"version\":true}", "{\"version\":99999}"}) {
            assertRefused(FCastOpcode.VERSION, bad, FCastProtocolException.Kind.BAD_MESSAGE);
        }
    }

    // ---- Initial

    @Test
    public void anInitialOfTheAndroidReceiver() throws Exception {
        FCastMessages.Initial initial = (FCastMessages.Initial) parse(FCastOpcode.INITIAL,
                "{\"displayName\":\"FCast-tv\",\"appName\":\"FCast Receiver\",\"appVersion\":\"3\","
                        + "\"playData\":{\"container\":\"video/mp4\",\"url\":\"http://h/a.mp4\",\"time\":12.5,\"volume\":0.5,"
                        + "\"metadata\":{\"type\":0,\"title\":\"A film\",\"thumbnailUrl\":\"http://h/a.jpg\"}},"
                        + "\"experimentalCapabilities\":{\"av\":{\"livestream\":{\"whep\":true}}}}");
        assertEquals("FCast-tv", initial.getDisplayName());
        assertEquals("FCast Receiver", initial.getAppName());
        assertEquals("3", initial.getAppVersion());
        FCastMessages.PlayData data = initial.getPlayData();
        assertEquals("video/mp4", data.getContainer());
        assertEquals("http://h/a.mp4", data.getUrl());
        assertEquals("A film", data.getTitle());
        assertEquals("http://h/a.jpg", data.getThumbnailUrl());
        assertEquals(12.5, data.getTime(), 0.0);
        assertEquals(0.5, data.getVolume(), 0.0);
    }

    @Test
    public void anInitialWithNothingIsStillAnInitial() throws Exception {
        FCastMessages.Initial initial = (FCastMessages.Initial) parse(FCastOpcode.INITIAL, "{}");
        assertNull(initial.getDisplayName());
        assertNull(initial.getPlayData());
        assertNull(((FCastMessages.Initial) parse(FCastOpcode.INITIAL, "{\"playData\":null,\"displayName\":null}")).getPlayData());
    }

    @Test
    public void namesFromTheReceiverAreCleanedAndCut() throws Exception {
        StringBuilder longName = new StringBuilder("Name\u0000\n");
        for (int i = 0; i < 100; i++) {
            longName.append('x');
        }
        FCastMessages.Initial initial = (FCastMessages.Initial) parse(FCastOpcode.INITIAL,
                new JSONObject().put("displayName", longName.toString()).toString());
        assertEquals(60, initial.getDisplayName().length());
        assertTrue(initial.getDisplayName().startsWith("Namexxx"));
    }

    @Test
    public void anInitialWithWrongTypesIsRefused() {
        assertRefused(FCastOpcode.INITIAL, "{\"displayName\":5}", FCastProtocolException.Kind.BAD_MESSAGE);
        assertRefused(FCastOpcode.INITIAL, "{\"appName\":[\"a\"]}", FCastProtocolException.Kind.BAD_MESSAGE);
        assertRefused(FCastOpcode.INITIAL, "{\"playData\":\"x\"}", FCastProtocolException.Kind.BAD_MESSAGE);
        assertRefused(FCastOpcode.INITIAL, "{\"playData\":{\"time\":\"soon\"}}", FCastProtocolException.Kind.BAD_MESSAGE);
        assertRefused(FCastOpcode.INITIAL, "{\"playData\":{\"url\":3}}", FCastProtocolException.Kind.BAD_MESSAGE);
    }

    // ---- PlaybackUpdate

    @Test
    public void aFullPlaybackUpdate() throws Exception {
        FCastMessages.PlaybackUpdate update = (FCastMessages.PlaybackUpdate) parse(FCastOpcode.PLAYBACK_UPDATE,
                "{\"generationTime\":1700000000000,\"state\":1,\"time\":12.5,\"duration\":200,\"speed\":1.5,\"itemIndex\":2}");
        assertEquals(1700000000000L, update.getGenerationTime());
        assertEquals(FCastMessages.STATE_PLAYING, update.getState());
        assertEquals(12.5, update.getTime(), 0.0);
        assertEquals(200.0, update.getDuration(), 0.0);
        assertEquals(1.5, update.getSpeed(), 0.0);
        assertEquals(Integer.valueOf(2), update.getItemIndex());
    }

    @Test
    public void anIdleUpdateHasOnlyGenerationTimeAndState() throws Exception {
        FCastMessages.PlaybackUpdate update = (FCastMessages.PlaybackUpdate) parse(FCastOpcode.PLAYBACK_UPDATE,
                "{\"generationTime\":5,\"state\":0}");
        assertEquals(FCastMessages.STATE_IDLE, update.getState());
        assertNull(update.getTime());
        assertNull(update.getDuration());
        assertNull(update.getSpeed());
        assertNull(update.getItemIndex());
    }

    @Test
    public void allThreeStatesAreKnownAndNoOtherIs() throws Exception {
        for (int state = 0; state <= 2; state++) {
            assertEquals(state, ((FCastMessages.PlaybackUpdate) parse(FCastOpcode.PLAYBACK_UPDATE, "{\"state\":" + state + "}")).getState());
        }
        for (String bad : new String[]{"{\"state\":3}", "{\"state\":-1}", "{\"state\":1.5}", "{\"state\":\"1\"}", "{}", "{\"state\":null}"}) {
            assertRefused(FCastOpcode.PLAYBACK_UPDATE, bad, FCastProtocolException.Kind.BAD_MESSAGE);
        }
    }

    @Test
    public void numbersOfAPlaybackUpdateAreClamped() throws Exception {
        FCastMessages.PlaybackUpdate update = (FCastMessages.PlaybackUpdate) parse(FCastOpcode.PLAYBACK_UPDATE,
                "{\"state\":1,\"time\":-5,\"duration\":-1,\"speed\":1000,\"itemIndex\":-3,\"generationTime\":-9}");
        assertEquals(0.0, update.getTime(), 0.0);
        assertEquals(0.0, update.getDuration(), 0.0);
        assertEquals(16.0, update.getSpeed(), 0.0);
        assertNull("a negative index is no index", update.getItemIndex());
        assertEquals(0L, update.getGenerationTime());
        assertEquals(0.0625, ((FCastMessages.PlaybackUpdate) parse(FCastOpcode.PLAYBACK_UPDATE, "{\"state\":1,\"speed\":0.001}")).getSpeed(), 0.0);
    }

    @Test
    public void numbersThatAreNotFiniteAreRefused() {
        for (String bad : new String[]{"{\"state\":1,\"time\":1e999}", "{\"state\":1,\"duration\":-1e999}", "{\"state\":1,\"speed\":1e400}",
                "{\"state\":1,\"time\":NaN}", "{\"state\":1,\"time\":\"NaN\"}", "{\"state\":1,\"time\":\"12\"}", "{\"state\":1,\"time\":true}"}) {
            assertRefused(FCastOpcode.PLAYBACK_UPDATE, bad, FCastProtocolException.Kind.BAD_MESSAGE);
        }
    }

    // ---- VolumeUpdate, PlaybackError, PlayUpdate, Event

    @Test
    public void aVolumeUpdateIsClampedToZeroToOne() throws Exception {
        assertEquals(0.25, ((FCastMessages.VolumeUpdate) parse(FCastOpcode.VOLUME_UPDATE, "{\"generationTime\":1,\"volume\":0.25}")).getVolume(), 0.0);
        assertEquals(1.0, ((FCastMessages.VolumeUpdate) parse(FCastOpcode.VOLUME_UPDATE, "{\"volume\":7}")).getVolume(), 0.0);
        assertEquals(0.0, ((FCastMessages.VolumeUpdate) parse(FCastOpcode.VOLUME_UPDATE, "{\"volume\":-7}")).getVolume(), 0.0);
        assertRefused(FCastOpcode.VOLUME_UPDATE, "{}", FCastProtocolException.Kind.BAD_MESSAGE);
        assertRefused(FCastOpcode.VOLUME_UPDATE, "{\"volume\":\"loud\"}", FCastProtocolException.Kind.BAD_MESSAGE);
        assertRefused(FCastOpcode.VOLUME_UPDATE, "{\"volume\":1e999}", FCastProtocolException.Kind.BAD_MESSAGE);
    }

    @Test
    public void aPlaybackErrorTextIsCleanedAndCut() throws Exception {
        assertEquals("no codec", ((FCastMessages.PlaybackError) parse(FCastOpcode.PLAYBACK_ERROR, "{\"message\":\"no\\u0000 codec\\n\"}")).getMessage());
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            text.append('e');
        }
        assertEquals(200, ((FCastMessages.PlaybackError) parse(FCastOpcode.PLAYBACK_ERROR,
                new JSONObject().put("message", text.toString()).toString())).getMessage().length());
        assertEquals("", ((FCastMessages.PlaybackError) parse(FCastOpcode.PLAYBACK_ERROR, "{}")).getMessage());
        assertRefused(FCastOpcode.PLAYBACK_ERROR, "{\"message\":42}", FCastProtocolException.Kind.BAD_MESSAGE);
    }

    @Test
    public void aPlayUpdateCarriesWhatWasStarted() throws Exception {
        FCastMessages.PlayUpdate update = (FCastMessages.PlayUpdate) parse(FCastOpcode.PLAY_UPDATE,
                "{\"generationTime\":9,\"playData\":{\"container\":\"audio/mpeg\",\"url\":\"http://h/a.mp3\"}}");
        assertEquals(9L, update.getGenerationTime());
        assertEquals("http://h/a.mp3", update.getPlayData().getUrl());
        assertNull(((FCastMessages.PlayUpdate) parse(FCastOpcode.PLAY_UPDATE, "{\"generationTime\":9}")).getPlayData());
    }

    @Test
    public void anEventHasATypeAndMaybeTheItem() throws Exception {
        FCastMessages.Event event = (FCastMessages.Event) parse(FCastOpcode.EVENT,
                "{\"generationTime\":3,\"event\":{\"type\":1,\"item\":{\"container\":\"video/mp4\",\"url\":\"http://h/a.mp4\"}}}");
        assertEquals(FCastMessages.EVENT_MEDIA_ITEM_END, event.getType());
        assertEquals("http://h/a.mp4", event.getItemUrl());
        assertNull(((FCastMessages.Event) parse(FCastOpcode.EVENT, "{\"event\":{\"type\":0}}")).getItemUrl());
        assertRefused(FCastOpcode.EVENT, "{}", FCastProtocolException.Kind.BAD_MESSAGE);
        assertRefused(FCastOpcode.EVENT, "{\"event\":{}}", FCastProtocolException.Kind.BAD_MESSAGE);
        assertRefused(FCastOpcode.EVENT, "{\"event\":\"x\"}", FCastProtocolException.Kind.BAD_MESSAGE);
    }

    // ---- bodies that are no objects, nesting, unknown things

    @Test
    public void aBodyThatIsNoJsonObjectIsRefused() {
        for (String bad : new String[]{"", "{", "[]", "[1]", "\"x\"", "42", "null", "not json", "{\"a\":}"}) {
            assertRefused(FCastOpcode.PLAYBACK_UPDATE, bad, FCastProtocolException.Kind.BAD_BODY);
        }
        assertRefused(FCastOpcode.PLAYBACK_UPDATE, null, FCastProtocolException.Kind.BAD_BODY);
    }

    @Test
    public void aBodyNestedTooDeepIsRefusedBeforeParsing() {
        char[] open = new char[16_000];
        Arrays.fill(open, '[');
        assertRefused(FCastOpcode.INITIAL, "{\"a\":" + new String(open), FCastProtocolException.Kind.BAD_BODY);
        StringBuilder objects = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            objects.append("{\"a\":");
        }
        objects.append("1");
        for (int i = 0; i < 40; i++) {
            objects.append('}');
        }
        assertRefused(FCastOpcode.INITIAL, objects.toString(), FCastProtocolException.Kind.BAD_BODY);
    }

    @Test
    public void formsOfThePlatformParserThatJsonDoesNotHaveAreRefusedAsBadBodies() {
        char[] open = new char[20_000];
        Arrays.fill(open, '[');
        String brackets = new String(open);
        String[] bodies = {"{'state':1}", "{\"state\":1} // c", "{\"state\":1 /* \" */,\"b\":" + brackets, "{'a':'\"','b':" + brackets,
                "{\"state\":1 # \"\n,\"b\":" + brackets, "{\"state\":1;\"x\":2}"};
        for (String body : bodies) {
            assertRefused(FCastOpcode.PLAYBACK_UPDATE, body, FCastProtocolException.Kind.BAD_BODY);
            assertRefused(FCastOpcode.INITIAL, body, FCastProtocolException.Kind.BAD_BODY);
        }
    }

    @Test
    public void messagesWithoutAModelAreNull() throws Exception {
        for (FCastOpcode opcode : new FCastOpcode[]{FCastOpcode.PING, FCastOpcode.PONG, FCastOpcode.PLAY, FCastOpcode.PAUSE,
                FCastOpcode.SET_VOLUME, FCastOpcode.SUBSCRIBE_EVENT, FCastOpcode.NONE}) {
            assertNull(opcode.toString(), parse(opcode, "{}"));
        }
        assertNull("an opcode of v4", FCastMessages.parse(new FCastPacket(20, "{}")));
        assertNull(FCastMessages.parse(new FCastPacket(200, null)));
    }

    @Test
    public void unknownFieldsAreIgnored() throws Exception {
        FCastMessages.PlaybackUpdate update = (FCastMessages.PlaybackUpdate) parse(FCastOpcode.PLAYBACK_UPDATE,
                "{\"state\":2,\"future\":{\"a\":[1,2,3]},\"other\":\"x\"}");
        assertEquals(FCastMessages.STATE_PAUSED, update.getState());
    }

    // ---- what YAACC sends

    @Test
    public void versionAndInitialOfTheSender() throws Exception {
        assertEquals("{\"version\":3}", FCastMessages.version(3));
        JSONObject initial = new JSONObject(FCastMessages.initialSender("My Phone", "YAACC", "6.1.0"));
        assertEquals("My Phone", initial.getString("displayName"));
        assertEquals("YAACC", initial.getString("appName"));
        assertEquals("6.1.0", initial.getString("appVersion"));
        JSONObject sparse = new JSONObject(FCastMessages.initialSender(null, "YAACC", "  "));
        assertFalse(sparse.has("displayName"));
        assertFalse(sparse.has("appVersion"));
    }

    @Test
    public void thePlayMessageHasTheFieldsOfTheV3Document() throws Exception {
        JSONObject play = new JSONObject(new FCastMessages.PlayRequest("video/mp4", "http://192.168.1.2:8080/a.mp4")
                .time(30).volume(0.5).speed(1.25).title("A film").thumbnailUrl("http://192.168.1.2:8080/a.jpg").toJson());
        assertEquals("video/mp4", play.getString("container"));
        assertEquals("http://192.168.1.2:8080/a.mp4", play.getString("url"));
        assertEquals(30.0, play.getDouble("time"), 0.0);
        assertEquals(0.5, play.getDouble("volume"), 0.0);
        assertEquals(1.25, play.getDouble("speed"), 0.0);
        JSONObject metadata = play.getJSONObject("metadata");
        assertEquals(0, metadata.getInt("type"));
        assertEquals("A film", metadata.getString("title"));
        assertEquals("http://192.168.1.2:8080/a.jpg", metadata.getString("thumbnailUrl"));
        assertFalse("no headers are sent", play.has("headers"));
    }

    @Test
    public void aMinimalPlayHasOnlyContainerAndUrl() throws Exception {
        JSONObject play = new JSONObject(new FCastMessages.PlayRequest("audio/mpeg", "http://h/a.mp3").toJson());
        assertEquals(2, play.length());
    }

    @Test
    public void theUrlIsSentExactlyAsGiven() throws Exception {
        String[] urls = {
                "http://user:secret@host:8080/a%20b.mp4?token=abc&x=1#frag",
                "https://example.org/a b/ü.mp3",
                "http://[fe80::1%25eth0]:8080/x",
        };
        for (String url : urls) {
            JSONObject play = new JSONObject(new FCastMessages.PlayRequest("video/mp4", url).toJson());
            assertEquals(url, play.getString("url"));
        }
    }

    @Test
    public void aPlayWithoutContainerOrUrlOrWithBadNumbersIsRefused() {
        FCastMessages.PlayRequest[] bad = {
                new FCastMessages.PlayRequest(null, "http://h/a"),
                new FCastMessages.PlayRequest("  ", "http://h/a"),
                new FCastMessages.PlayRequest("video/mp4", null),
                new FCastMessages.PlayRequest("video/mp4", ""),
                new FCastMessages.PlayRequest("video/mp4", "http://h/a").time(Double.NaN),
                new FCastMessages.PlayRequest("video/mp4", "http://h/a").volume(Double.POSITIVE_INFINITY),
                new FCastMessages.PlayRequest("video/mp4", "http://h/a").speed(Double.NEGATIVE_INFINITY),
        };
        for (FCastMessages.PlayRequest request : bad) {
            try {
                request.toJson();
                fail("a bad Play was built");
            } catch (FCastProtocolException e) {
                assertEquals(FCastProtocolException.Kind.BAD_MESSAGE, e.getKind());
            }
        }
    }

    @Test
    public void numbersOfAPlayAreClamped() throws Exception {
        JSONObject play = new JSONObject(new FCastMessages.PlayRequest("video/mp4", "http://h/a").time(-4).volume(3).speed(100).toJson());
        assertEquals(0.0, play.getDouble("time"), 0.0);
        assertEquals(1.0, play.getDouble("volume"), 0.0);
        assertEquals(4.0, play.getDouble("speed"), 0.0);
    }

    @Test
    public void aTitleIsCleanedAndCut() throws Exception {
        StringBuilder title = new StringBuilder("T\u0000i\ntle ");
        for (int i = 0; i < 300; i++) {
            title.append('x');
        }
        JSONObject play = new JSONObject(new FCastMessages.PlayRequest("audio/mpeg", "http://h/a").title(title.toString()).toJson());
        String sent = play.getJSONObject("metadata").getString("title");
        assertEquals(200, sent.length());
        assertTrue(sent.startsWith("Title xxx"));
    }

    @Test
    public void aTooLongUrlDoesNotFitAPacket() throws Exception {
        char[] chars = new char[40_000];
        Arrays.fill(chars, 'a');
        String body = new FCastMessages.PlayRequest("video/mp4", "http://h/" + new String(chars)).toJson();
        try {
            FCastPacketCodec.encode(FCastOpcode.PLAY.code(), body);
            fail("a Play of 40000 bytes was encoded");
        } catch (FCastProtocolException e) {
            assertEquals(FCastProtocolException.Kind.TOO_LARGE, e.getKind());
        }
    }

    @Test
    public void seekVolumeAndSpeedBodies() throws Exception {
        assertEquals("{\"time\":5}", FCastMessages.seek(5));
        assertEquals("{\"time\":5.5}", FCastMessages.seek(5.5));
        assertEquals("{\"time\":0}", FCastMessages.seek(-3));
        assertEquals("{\"volume\":0.25}", FCastMessages.setVolume(0.25));
        assertEquals("{\"volume\":1}", FCastMessages.setVolume(9));
        assertEquals("{\"volume\":0}", FCastMessages.setVolume(-9));
        assertEquals("{\"speed\":1.5}", FCastMessages.setSpeed(1.5));
        assertEquals("{\"speed\":4}", FCastMessages.setSpeed(40));
        assertEquals("{\"speed\":0.25}", FCastMessages.setSpeed(0));
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            for (int which = 0; which < 3; which++) {
                try {
                    if (which == 0) {
                        FCastMessages.seek(bad);
                    } else if (which == 1) {
                        FCastMessages.setVolume(bad);
                    } else {
                        FCastMessages.setSpeed(bad);
                    }
                    fail("a number that is not finite was built");
                } catch (FCastProtocolException e) {
                    assertEquals(FCastProtocolException.Kind.BAD_MESSAGE, e.getKind());
                }
            }
        }
    }

    @Test
    public void aVolumeOfOneThirdPrintsWithoutExponent() throws Exception {
        String body = FCastMessages.setVolume(1.0 / 3.0);
        assertTrue(body, body.matches("\\{\"volume\":0\\.3333\\d*\\}"));
        assertNotNull(new JSONObject(body));
    }

    @Test
    public void eventSubscriptions() {
        assertEquals("{\"event\":{\"type\":1}}", FCastMessages.subscribeEvent(FCastMessages.EVENT_MEDIA_ITEM_END));
        assertEquals("{\"event\":{\"type\":1}}", FCastMessages.unsubscribeEvent(FCastMessages.EVENT_MEDIA_ITEM_END));
    }

    @Test
    public void theSenderNeverSpeaksVersion4() {
        assertEquals(3, FCastMessages.MAX_VERSION);
    }
}
