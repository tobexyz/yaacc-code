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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

import de.yaacc.fcast.protocol.v4.V4In;
import de.yaacc.fcast.protocol.v4.V4Reader;
import de.yaacc.fcast.protocol.v4.V4Schema;
import de.yaacc.fcast.protocol.v4.V4Writer;

/** Task 7.3: what v4 messages mean for the session (the same objects a v3 receiver gives) and what the session's commands become. */
public class FCastV4TranslatorTest {
    private final FCastV4Translator translator = new FCastV4Translator();

    private List<FCastMessages.Incoming> in(String golden) throws Exception {
        return translator.incoming(V4Golden.get(golden));
    }

    private static <T extends FCastMessages.Incoming> T only(List<FCastMessages.Incoming> messages, Class<T> type) {
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).getClass().getName(), type.isInstance(messages.get(0)));
        return type.cast(messages.get(0));
    }

    // ---- incoming

    @Test
    public void theIntroductionIsTheInitialOfAV3Receiver() throws Exception {
        FCastMessages.Initial initial = only(in("receiverIntroduction"), FCastMessages.Initial.class);
        assertEquals("FCast-pc", initial.getDisplayName());
        assertEquals("FCast Receiver Desktop", initial.getAppName());
        assertEquals("3.0.4", initial.getAppVersion());
        assertNull(initial.getPlayData());
    }

    @Test
    public void aLoadIsAPlayUpdateWithWhatTheV3ReceiverWouldReport() throws Exception {
        FCastMessages.PlayData data = only(in("loadFull"), FCastMessages.PlayUpdate.class).getPlayData();
        assertEquals("video/mp4", data.getContainer());
        assertEquals("http://192.168.0.5:8080/a.mp4", data.getUrl());
        assertEquals("Song", data.getTitle());
        assertEquals("http://h/a.jpg", data.getThumbnailUrl());
        assertEquals(12.5, data.getTime(), 1e-9);
        assertEquals(0.25, data.getVolume(), 1e-6);
        assertEquals(1.5, data.getSpeed(), 1e-6);
    }

    @Test
    public void theStateAloneIsAnUpdateWithoutPositionAndAPositionBeforeAnyStateIsNothing() throws Exception {
        assertTrue("no state yet: a position alone says nothing about playing", in("progress").isEmpty());
        FCastMessages.PlaybackUpdate update = only(in("statePlaying"), FCastMessages.PlaybackUpdate.class);
        assertEquals(FCastMessages.STATE_PLAYING, update.getState());
        assertEquals("the position that came first is kept", 1.5, update.getTime(), 1e-9);
        assertEquals(61.0, update.getDuration(), 1e-9);
    }

    @Test
    public void progressAfterTheStateIsACompleteUpdate() throws Exception {
        in("statePlaying");
        FCastMessages.PlaybackUpdate update = only(in("progress"), FCastMessages.PlaybackUpdate.class);
        assertEquals(FCastMessages.STATE_PLAYING, update.getState());
        assertEquals(1.5, update.getTime(), 1e-9);
        assertEquals(61.0, update.getDuration(), 1e-9);
        assertNull(update.getSpeed());
    }

    @Test
    public void pausedAndBufferingAndSpeed() throws Exception {
        assertEquals(FCastMessages.STATE_PAUSED, only(in("statePaused"), FCastMessages.PlaybackUpdate.class).getState());
        assertEquals("buffering is on the way to playing", FCastMessages.STATE_PLAYING,
                only(in("stateBuffering"), FCastMessages.PlaybackUpdate.class).getState());
        assertEquals(1.25, only(in("speed"), FCastMessages.PlaybackUpdate.class).getSpeed(), 1e-6);
    }

    @Test
    public void theEndOfAnItemIsPausedAtTheDurationAndTheEndEventOfThatItem() throws Exception {
        in("loadFull");
        in("statePlaying");
        translator.incoming(V4Writer.progress(30, 61));
        List<FCastMessages.Incoming> ended = in("stateEnded");
        assertEquals(2, ended.size());
        FCastMessages.PlaybackUpdate update = (FCastMessages.PlaybackUpdate) ended.get(0);
        assertEquals(FCastMessages.STATE_PAUSED, update.getState());
        assertEquals("at the duration, as the Android receiver of v3 says it", 61.0, update.getTime(), 1e-9);
        FCastMessages.Event event = (FCastMessages.Event) ended.get(1);
        assertEquals(FCastMessages.EVENT_MEDIA_ITEM_END, event.getType());
        assertEquals("http://192.168.0.5:8080/a.mp4", event.getItemUrl());
    }

    @Test
    public void idleAndStopAreAnIdleUpdateAndLaterProgressSaysNothing() throws Exception {
        in("statePlaying");
        FCastMessages.PlaybackUpdate idle = only(in("stop"), FCastMessages.PlaybackUpdate.class);
        assertEquals(FCastMessages.STATE_IDLE, idle.getState());
        assertNull(idle.getTime());
        assertTrue(in("progress").isEmpty());
        assertEquals(FCastMessages.STATE_IDLE, only(in("stateIdle"), FCastMessages.PlaybackUpdate.class).getState());
    }

    @Test
    public void aNewLoadForgetsWhatTheOldItemReported() throws Exception {
        in("statePlaying");
        in("progress");
        in("loadFull");
        assertTrue("the position of the new item needs the new item's state first", in("progress").isEmpty());
        FCastMessages.PlaybackUpdate update = only(in("statePlaying"), FCastMessages.PlaybackUpdate.class);
        assertEquals(1.5, update.getTime(), 1e-9);
    }

    @Test
    public void volumeIsAVolumeUpdateAndZeroIsAVolume() throws Exception {
        assertEquals(0.5, only(in("volume"), FCastMessages.VolumeUpdate.class).getVolume(), 1e-6);
        assertEquals(0.0, only(in("volumeZero"), FCastMessages.VolumeUpdate.class).getVolume(), 0.0);
    }

    @Test
    public void anErrorNamesItsKind() throws Exception {
        String message = only(in("error"), FCastMessages.PlaybackError.class).getMessage();
        assertTrue(message, message.contains("UnsupportedFormat"));
    }

    @Test
    public void messagesOfNoUseToTheSessionGiveNothing() throws Exception {
        assertTrue(in("tracks").isEmpty());
        assertTrue(in("changeTrack").isEmpty());
    }

    @Test
    public void aBufferThatIsNoMessageIsAProtocolErrorOfTheBodyAndTheTranslatorGoesOn() throws Exception {
        try {
            translator.incoming(new byte[]{1, 2, 3});
            fail();
        } catch (FCastProtocolException expected) {
            assertEquals(FCastProtocolException.Kind.BAD_BODY, expected.getKind());
        }
        assertEquals(FCastMessages.STATE_PLAYING, only(in("statePlaying"), FCastMessages.PlaybackUpdate.class).getState());
    }

    @Test
    public void textFromTheReceiverIsCleanedAndCutLikeInV3() throws Exception {
        StringBuilder longUrl = new StringBuilder("http://h/");
        for (int i = 0; i < 5000; i++) {
            longUrl.append('x');
        }
        byte[] load = V4Writer.load("video/\u0007mp4", longUrl.toString(), null, null, null, "Ti\u202etle\n", null);
        FCastMessages.PlayData data = only(translator.incoming(load), FCastMessages.PlayUpdate.class).getPlayData();
        assertEquals("video/mp4", data.getContainer());
        assertEquals(FCastText.MAX_URL, data.getUrl().length());
        assertEquals("Title", data.getTitle());
    }

    // ---- outgoing

    private static V4In out(FCastOpcode opcode, String body) throws Exception {
        byte[] bytes = FCastV4Translator.outgoing(opcode, body);
        return V4Reader.read(bytes);
    }

    @Test
    public void aPlayRequestBecomesALoadWithTheUrlAsGiven() throws Exception {
        FCastMessages.PlayRequest request = new FCastMessages.PlayRequest("audio/mpeg", "http://Host:8080/My Song.mp3").time(12.5).volume(0.5)
                .speed(1.25).title("Song").thumbnailUrl("http://h/a.jpg");
        V4In load = out(FCastOpcode.PLAY, request.toJson());
        assertEquals(V4In.Kind.LOAD, load.kind);
        assertEquals("audio/mpeg", load.item.container);
        assertEquals("the URL is sent as given (D9)", "http://Host:8080/My Song.mp3", load.item.url);
        assertEquals(12.5, load.item.startSeconds, 1e-9);
        assertEquals(0.5, load.item.volume, 1e-6);
        assertEquals(1.25, load.item.speed, 1e-6);
        assertEquals("Song", load.item.title);
        assertEquals("http://h/a.jpg", load.item.thumbnailUrl);
    }

    @Test
    public void aPlayRequestWithoutOptionsHasNone() throws Exception {
        V4In load = out(FCastOpcode.PLAY, new FCastMessages.PlayRequest("image/jpeg", "http://h/a.jpg").toJson());
        assertNull(load.item.startSeconds);
        assertNull(load.item.volume);
        assertNull(load.item.speed);
        assertNull(load.item.title);
    }

    @Test
    public void theTransportCommandsAreV4Messages() throws Exception {
        assertEquals(Integer.valueOf(V4Schema.STATE_PAUSED), out(FCastOpcode.PAUSE, null).code);
        assertEquals(Integer.valueOf(V4Schema.STATE_PLAYING), out(FCastOpcode.RESUME, null).code);
        assertEquals(V4In.Kind.STOP, out(FCastOpcode.STOP, null).kind);
        assertEquals(90.5, out(FCastOpcode.SEEK, FCastMessages.seek(90.5)).positionSeconds, 1e-9);
        assertEquals(0.3, out(FCastOpcode.SET_VOLUME, FCastMessages.setVolume(0.3)).number, 1e-6);
        assertEquals(2.0, out(FCastOpcode.SET_SPEED, FCastMessages.setSpeed(2.0)).number, 1e-6);
    }

    @Test
    public void eventSubscriptionsAreNothingInV4AndOtherOpcodesAreRefused() throws Exception {
        assertNull(FCastV4Translator.outgoing(FCastOpcode.SUBSCRIBE_EVENT, FCastMessages.subscribeEvent(1)));
        assertNull(FCastV4Translator.outgoing(FCastOpcode.UNSUBSCRIBE_EVENT, FCastMessages.unsubscribeEvent(1)));
        try {
            FCastV4Translator.outgoing(FCastOpcode.INITIAL, "{}");
            fail();
        } catch (FCastProtocolException expected) {
            assertEquals(FCastProtocolException.Kind.UNSUPPORTED, expected.getKind());
        }
    }

    @Test
    public void aBodyThatIsNotWhatTheBuildersMakeIsRefused() throws Exception {
        for (String body : new String[]{"not json", "{}", "{\"url\":\"http://h\"}", null}) {
            try {
                FCastV4Translator.outgoing(FCastOpcode.PLAY, body);
                fail("body " + body);
            } catch (FCastProtocolException expected) {
                assertEquals(FCastProtocolException.Kind.BAD_MESSAGE, expected.getKind());
            }
        }
    }
}
