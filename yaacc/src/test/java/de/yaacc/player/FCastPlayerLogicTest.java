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
package de.yaacc.player;

import static de.yaacc.player.FCastPlayerLogic.Action;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.fcast.protocol.FCastMessages;
import de.yaacc.fcast.protocol.FCastReceiverState;
import de.yaacc.fcast.protocol.FCastReceiverState.Pending;
import de.yaacc.fcast.protocol.FCastReceiverState.Playback;

/**
 * Task 4.2: the rules of design.md section 5 for the end of an item, the receiver that stops, the errors, the volume and the
 * mute, with the real sequences of the two reference receivers.
 */
public class FCastPlayerLogicTest {

    private static final String URL = "http://192.168.0.5:8080/proxy/abc";

    private final AtomicLong now = new AtomicLong(100_000);
    private FCastPlayerLogic logic;

    @Before
    public void setUp() {
        logic = new FCastPlayerLogic(now::get);
    }

    private FCastReceiverState state(Playback playback, Double position, Double duration) {
        return state(playback, position, duration, Pending.NONE, false);
    }

    private FCastReceiverState state(Playback playback, Double position, Double duration, Pending pending, boolean elsewhere) {
        return FCastReceiverState.INITIAL.toBuilder()
                .link(FCastReceiverState.Link.CONNECTED)
                .playback(playback, position, duration, 1.0)
                .item(URL, "audio/mpeg", "t", elsewhere)
                .pending(pending)
                .playbackUpdatedAt(now.get())
                .build();
    }

    private void startItem() {
        logic.onPlayCommand(URL);
    }

    // ---- the end of an item

    @Test
    public void theDesktopReceiversRealSequenceEndsTheItemOnTheEventAlone() {
        startItem();
        // Playing updates every 0.5 s up to 30.4 of 30.5 s, then nothing but the event
        for (double position = 0.5; position <= 30.4; position += 0.5) {
            assertEquals(Action.NONE, logic.onState(state(Playback.PLAYING, position, 30.5)));
            now.addAndGet(500);
        }

        assertEquals(Action.ITEM_ENDED, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));
        assertEquals("once per item", Action.NONE, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));
        now.addAndGet(5_000);
        assertEquals("the safety net does not fire after the event", Action.NONE,
                logic.onTick(state(Playback.PLAYING, 30.0, 30.5)));
    }

    @Test
    public void theAndroidReceiversSequenceEndsTheItemOnThePausedUpdateAndTheEventAddsNothing() {
        startItem();
        assertEquals(Action.NONE, logic.onState(state(Playback.PLAYING, 29.0, 30.0)));

        assertEquals(Action.ITEM_ENDED, logic.onState(state(Playback.PAUSED, 30.0, 30.0)));
        assertEquals(Action.NONE, logic.onState(state(Playback.PAUSED, 30.0, 30.0)));
        assertEquals("the event after the update", Action.NONE, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));
    }

    @Test
    public void theEventBeforeThePausedUpdateEndsTheItemOnceToo() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.0, 30.0));

        assertEquals(Action.ITEM_ENDED, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, null));
        assertEquals(Action.NONE, logic.onState(state(Playback.PAUSED, 30.0, 30.0)));
    }

    @Test
    public void theSafetyNetEndsTheItemTwoSecondsAfterTheLastUpdateNearTheEnd() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.6, 30.0));
        now.addAndGet(1_999);
        assertEquals(Action.NONE, logic.onTick(state(Playback.PLAYING, 29.6, 30.0).toBuilder().playbackUpdatedAt(100_000).build()));

        now.addAndGet(1);

        assertEquals(Action.ITEM_ENDED, logic.onTick(state(Playback.PLAYING, 29.6, 30.0).toBuilder().playbackUpdatedAt(100_000).build()));
        assertEquals(Action.NONE, logic.onTick(state(Playback.PLAYING, 29.6, 30.0).toBuilder().playbackUpdatedAt(100_000).build()));
    }

    @Test
    public void theSafetyNetDoesNotFireInTheMiddleOfAnItemOrWithoutADuration() {
        startItem();
        now.addAndGet(60_000);
        assertEquals(Action.NONE, logic.onTick(state(Playback.PLAYING, 10.0, 30.0).toBuilder().playbackUpdatedAt(100_000).build()));
        assertEquals(Action.NONE, logic.onTick(state(Playback.PLAYING, 29.9, 0.0).toBuilder().playbackUpdatedAt(100_000).build()));
        assertEquals(Action.NONE, logic.onTick(state(Playback.PLAYING, 29.9, null).toBuilder().playbackUpdatedAt(100_000).build()));
    }

    @Test
    public void aPauseOfOurOwnNearTheEndIsNoEnd() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.5, 30.0));
        logic.onPauseCommand();

        assertEquals(Action.NONE, logic.onState(state(Playback.PAUSED, 29.8, 30.0, Pending.PAUSE, false)));
        assertEquals(Action.NONE, logic.onState(state(Playback.PAUSED, 29.8, 30.0)));
        now.addAndGet(10_000);
        assertEquals("no safety net while paused by us", Action.NONE,
                logic.onTick(state(Playback.PAUSED, 29.8, 30.0).toBuilder().playbackUpdatedAt(100_000).build()));
        assertEquals("the pause was ours: nothing to adopt", Action.NONE, logic.onState(state(Playback.PAUSED, 29.8, 30.0)));
    }

    @Test
    public void aPausedUpdateAtTheDurationWhileOurPauseIsPendingIsNoEnd() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.9, 30.0));
        logic.onPauseCommand();

        assertEquals(Action.NONE, logic.onState(state(Playback.PAUSED, 30.0, 30.0, Pending.PAUSE, false)));
    }

    @Test
    public void afterAResumeOfOurOwnTheItemCanEndAgain() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.0, 30.0));
        logic.onPauseCommand();
        logic.onState(state(Playback.PAUSED, 29.2, 30.0));
        logic.onResumeCommand();
        logic.onState(state(Playback.PLAYING, 29.3, 30.0));

        assertEquals(Action.ITEM_ENDED, logic.onState(state(Playback.PAUSED, 30.0, 30.0)));
    }

    @Test
    public void aNewItemMakesTheNextEndCountAgain() {
        startItem();
        logic.onState(state(Playback.PLAYING, 1.0, 30.0));
        assertEquals(Action.ITEM_ENDED, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));

        startItem();
        logic.onState(state(Playback.PLAYING, 1.0, 30.0));
        assertEquals(Action.ITEM_ENDED, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));
    }

    @Test
    public void anEventAboutAnotherItemOrOfAnotherTypeIsNoEnd() {
        startItem();
        logic.onState(state(Playback.PLAYING, 1.0, 30.0));

        assertEquals(Action.NONE, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, "http://other.example/x"));
        assertEquals(Action.NONE, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_START, URL));
        assertEquals(Action.NONE, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_CHANGE, URL));
        assertEquals(Action.NONE, logic.onEvent(77, URL));
        assertEquals("the item itself still ends", Action.ITEM_ENDED, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));
    }

    @Test
    public void theEventUrlIsComparedInTheCleanedAndCutForm() {
        String longUrl = "http://192.168.0.5/" + "a".repeat(3000);
        logic.onPlayCommand(longUrl);
        logic.onState(state(Playback.PLAYING, 1.0, 30.0));

        String received = longUrl.substring(0, 2048);

        assertEquals(Action.ITEM_ENDED, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, received));
    }

    @Test
    public void anItemFromElsewhereNeverEndsOrStopsOurQueue() {
        startItem();
        logic.onState(state(Playback.PLAYING, 1.0, 30.0));

        assertEquals(Action.NONE, logic.onState(state(Playback.PAUSED, 30.0, 30.0, Pending.NONE, true)));
        assertEquals(Action.NONE, logic.onState(state(Playback.IDLE, null, null, Pending.NONE, true)));
    }

    @Test
    public void nothingEndsWhenNoItemWasStartedOrAfterOurStop() {
        assertEquals(Action.NONE, logic.onState(state(Playback.PAUSED, 30.0, 30.0)));
        assertEquals(Action.NONE, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));

        startItem();
        logic.onState(state(Playback.PLAYING, 1.0, 30.0));
        logic.onStopCommand();

        assertEquals(Action.NONE, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));
        assertEquals(Action.NONE, logic.onState(state(Playback.IDLE, null, null)));
        assertEquals(Action.NONE, logic.onTick(state(Playback.PLAYING, 29.9, 30.0).toBuilder().playbackUpdatedAt(0).build()));
    }

    // ---- the receiver stops, other senders

    @Test
    public void anIdleBeforeTheItemPlayedIsNothing() {
        startItem();

        assertEquals(Action.NONE, logic.onState(state(Playback.IDLE, null, null)));
        assertEquals(Action.NONE, logic.onState(state(Playback.IDLE, null, null, Pending.PLAY, false)));
        assertEquals("the item still runs", Action.NONE, logic.onState(state(Playback.PLAYING, 0.5, 30.0)));
        assertEquals(Action.ITEM_ENDED, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));
    }

    @Test
    public void anIdleAfterPlayingWithoutAStopOfOursMeansTheReceiverWasStopped() {
        startItem();
        logic.onState(state(Playback.PLAYING, 5.0, 30.0));

        assertEquals(Action.STOPPED_BY_RECEIVER, logic.onState(state(Playback.IDLE, null, null)));
        assertEquals("only once", Action.NONE, logic.onState(state(Playback.IDLE, null, null)));
        assertEquals("a stopped player does not advance", Action.NONE, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));
    }

    @Test
    public void anIdleWhileOurStopIsPendingIsNotTheReceiversDoing() {
        startItem();
        logic.onState(state(Playback.PLAYING, 5.0, 30.0));

        assertEquals(Action.NONE, logic.onState(state(Playback.IDLE, null, null, Pending.STOP, false)));
    }

    @Test
    public void aPauseFromOutsideIsAdoptedOnceAndOurNextResumeClearsIt() {
        startItem();
        logic.onState(state(Playback.PLAYING, 5.0, 30.0));

        assertEquals(Action.EXTERNAL_PAUSE, logic.onState(state(Playback.PAUSED, 5.5, 30.0)));
        assertEquals(Action.NONE, logic.onState(state(Playback.PAUSED, 5.5, 30.0)));
        assertEquals("an end after an adopted pause still needs the end rules", Action.NONE,
                logic.onState(state(Playback.PAUSED, 30.0, 30.0)));
        logic.onResumeCommand();
        logic.onState(state(Playback.PLAYING, 6.0, 30.0));
        assertEquals(Action.ITEM_ENDED, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));
    }

    @Test
    public void aResumeFromOutsideAfterOurPauseIsAdopted() {
        startItem();
        logic.onState(state(Playback.PLAYING, 5.0, 30.0));
        logic.onPauseCommand();
        logic.onState(state(Playback.PAUSED, 5.2, 30.0));

        assertEquals(Action.NONE, logic.onState(state(Playback.PAUSED, 5.2, 30.0, Pending.RESUME, false)));
        assertEquals(Action.EXTERNAL_RESUME, logic.onState(state(Playback.PLAYING, 5.4, 30.0)));
        assertEquals(Action.NONE, logic.onState(state(Playback.PLAYING, 5.9, 30.0)));
    }

    @Test
    public void aPlayingUpdateRightAfterOurPauseCommandIsNotAResume() {
        startItem();
        logic.onState(state(Playback.PLAYING, 5.0, 30.0));
        logic.onPauseCommand();

        assertEquals("the receiver has not paused yet", Action.NONE, logic.onState(state(Playback.PLAYING, 5.4, 30.0, Pending.PAUSE, false)));
    }

    // ---- the guards of the safety net and of the end rules

    private FCastReceiverState nearEnd(Playback playback, Pending pending, boolean elsewhere) {
        return state(playback, 29.8, 30.0, pending, elsewhere).toBuilder().playbackUpdatedAt(100_000).build();
    }

    @Test
    public void theSafetyNetNeverFiresAfterAPauseOfOurOwn() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.0, 30.0));
        logic.onPauseCommand();
        now.addAndGet(10_000);

        assertEquals(Action.NONE, logic.onTick(nearEnd(Playback.PLAYING, Pending.NONE, false)));
    }

    @Test
    public void theSafetyNetNeverFiresWhileACommandIsPending() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.0, 30.0));
        now.addAndGet(10_000);

        for (Pending pending : new Pending[]{Pending.SEEK, Pending.PAUSE, Pending.RESUME, Pending.STOP, Pending.PLAY}) {
            assertEquals("pending " + pending, Action.NONE, logic.onTick(nearEnd(Playback.PLAYING, pending, false)));
        }
    }

    @Test
    public void theSafetyNetNeverFiresForAnotherSendersItemOrAnItemThatIsNotPlaying() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.0, 30.0));
        now.addAndGet(10_000);

        assertEquals(Action.NONE, logic.onTick(nearEnd(Playback.PLAYING, Pending.NONE, true)));
        assertEquals("a paused item near its end is paused, not ended", Action.NONE, logic.onTick(nearEnd(Playback.PAUSED, Pending.NONE, false)));
        assertEquals(Action.NONE, logic.onTick(nearEnd(Playback.IDLE, Pending.NONE, false)));
        assertEquals(Action.NONE, logic.onTick(nearEnd(Playback.UNKNOWN, Pending.NONE, false)));
        assertEquals("and with the item itself it fires", Action.ITEM_ENDED, logic.onTick(nearEnd(Playback.PLAYING, Pending.NONE, false)));
    }

    @Test
    public void theSafetyNetDoesNotJudgeTheOldItemsStateAfterOurPlay() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.0, 30.0));
        FCastReceiverState oldItem = nearEnd(Playback.PLAYING, Pending.NONE, false); // taken at 100_000
        now.addAndGet(5_000);
        startItem(); // the next item; the receiver has not answered yet

        now.addAndGet(5_000);
        assertEquals(Action.NONE, logic.onTick(oldItem));
    }

    @Test
    public void aPausedUpdateWhileOurResumeOrSeekIsPendingIsNoEnd() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.0, 30.0));

        assertEquals(Action.NONE, logic.onState(state(Playback.PAUSED, 30.0, 30.0, Pending.RESUME, false)));
        assertEquals(Action.NONE, logic.onState(state(Playback.PAUSED, 30.0, 30.0, Pending.SEEK, false)));
        assertEquals(Action.ITEM_ENDED, logic.onState(state(Playback.PAUSED, 30.0, 30.0)));
    }

    @Test
    public void anIdleWhileOurPlayIsPendingIsTheOldItemClearing() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.0, 30.0));
        startItem(); // the next item; a stale Playing update of the old one arrives
        logic.onState(state(Playback.PLAYING, 29.5, 30.0, Pending.PLAY, false));

        assertEquals(Action.NONE, logic.onState(state(Playback.IDLE, null, null, Pending.PLAY, false)));
    }

    @Test
    public void anIdleAfterTheEndIsNoStopOfTheReceiver() {
        startItem();
        logic.onState(state(Playback.PLAYING, 29.0, 30.0));
        assertEquals(Action.ITEM_ENDED, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));

        assertEquals(Action.NONE, logic.onState(state(Playback.IDLE, null, null)));
    }

    @Test
    public void theSerialChangesWithEveryStartStopAndErrorSoThatDeferredActionsCanBeDropped() {
        int start = logic.serial();
        startItem();
        int afterPlay = logic.serial();
        assertTrue(afterPlay != start);
        assertTrue(logic.isActive());
        logic.onPauseCommand();
        logic.onResumeCommand();
        logic.onState(state(Playback.PLAYING, 1.0, 30.0));
        assertEquals("pause, resume and reports do not change it", afterPlay, logic.serial());

        logic.onStopCommand();
        int afterStop = logic.serial();
        assertTrue(afterStop != afterPlay);
        assertFalse(logic.isActive());

        startItem();
        int afterSecondPlay = logic.serial();
        logic.onError(true);
        assertTrue(logic.serial() != afterSecondPlay);
        assertFalse(logic.isActive());
    }

    @Test
    public void everyMethodThatTouchesTheStateIsSynchronized() {
        // the logic is called from the executor of the session, the timer thread of the player, the main thread and the screen
        for (java.lang.reflect.Method method : FCastPlayerLogic.class.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(modifiers) || method.isSynthetic() || java.lang.reflect.Modifier.isPrivate(modifiers)) {
                continue;
            }
            assertTrue(method.getName() + " is not synchronized", java.lang.reflect.Modifier.isSynchronized(modifiers));
        }
    }

    // ---- errors

    @Test
    public void anUnreadableItemIsSkippedTwiceAndTheThirdFailureStops() {
        startItem();
        assertEquals(TrackFailurePolicy.Decision.SKIP, logic.onError(true));
        startItem();
        assertEquals(TrackFailurePolicy.Decision.SKIP, logic.onError(true));
        startItem();
        assertEquals(TrackFailurePolicy.Decision.STOP, logic.onError(true));
    }

    @Test
    public void aFailingLastItemStopsAndAPlayingItemResetsTheCount() {
        startItem();
        assertEquals(TrackFailurePolicy.Decision.STOP, logic.onError(false));

        logic.reset();
        startItem();
        assertEquals(TrackFailurePolicy.Decision.SKIP, logic.onError(true));
        startItem();
        logic.onState(state(Playback.PLAYING, 0.5, 30.0));
        assertEquals(TrackFailurePolicy.Decision.SKIP, logic.onError(true));
        startItem();
        assertEquals(TrackFailurePolicy.Decision.SKIP, logic.onError(true));
    }

    @Test
    public void afterAnErrorTheItemIsOverAndAnIdleIsNoReceiverStop() {
        startItem();
        logic.onState(state(Playback.PLAYING, 5.0, 30.0));
        logic.onError(true);

        assertEquals(Action.NONE, logic.onState(state(Playback.IDLE, null, null)));
        assertEquals(Action.NONE, logic.onEvent(FCastMessages.EVENT_MEDIA_ITEM_END, URL));
    }

    // ---- volume and mute

    @Test
    public void theVolumeMapsBetweenPercentAndTheProtocolRange() {
        assertEquals(0.0, FCastPlayerLogic.toProtocolVolume(-5), 0.0);
        assertEquals(0.37, FCastPlayerLogic.toProtocolVolume(37), 1e-9);
        assertEquals(1.0, FCastPlayerLogic.toProtocolVolume(250), 0.0);
        assertEquals(0, FCastPlayerLogic.toPercent(null));
        assertEquals(0, FCastPlayerLogic.toPercent(Double.NaN));
        assertEquals(37, FCastPlayerLogic.toPercent(0.37));
        assertEquals(100, FCastPlayerLogic.toPercent(1.7));
        assertEquals(0, FCastPlayerLogic.toPercent(-0.2));
    }

    @Test
    public void muteSetsTheVolumeToZeroAndUnmuteRestoresIt() {
        assertEquals(0.0, logic.onMuteRequested(true, 0.7), 0.0);
        assertTrue(logic.isMuted(0.0));
        assertFalse("not muted while the volume is not 0", logic.isMuted(0.4));

        assertEquals(0.7, logic.onMuteRequested(false, 0.0), 0.0);
        assertFalse(logic.isMuted(0.7));
    }

    @Test
    public void aVolumeChangeFromOutsideForgetsTheMute() {
        logic.onMuteRequested(true, 0.7);

        logic.onVolumeUpdate(0.0);
        assertTrue("our own zero keeps the mute", logic.isMuted(0.0));
        logic.onVolumeUpdate(0.3);

        assertFalse(logic.isMuted(0.3));
        assertTrue("an unmute after that restores nothing", Double.isNaN(logic.onMuteRequested(false, 0.3)));
    }

    @Test
    public void muteAtVolumeZeroHasNothingToRestore() {
        assertEquals(0.0, logic.onMuteRequested(true, 0.0), 0.0);
        assertTrue(logic.isMuted(0.0));

        assertTrue(Double.isNaN(logic.onMuteRequested(false, 0.0)));
        assertFalse(logic.isMuted(0.0));
    }

    @Test
    public void muteWithAnUnknownVolumeStillMutes() {
        assertEquals(0.0, logic.onMuteRequested(true, null), 0.0);
        assertTrue(logic.isMuted(null));
        assertTrue(Double.isNaN(logic.onMuteRequested(false, 0.0)));
    }

    // ---- position, seek

    @Test
    public void thePositionIsExtrapolatedWhilePlayingAndCorrectedByTheNextUpdate() {
        FCastReceiverState playing = state(Playback.PLAYING, 10.0, 30.0).toBuilder().playbackUpdatedAt(100_000).build();
        now.set(101_500);

        assertEquals(11_500, FCastPlayerLogic.positionMillis(playing, now.get(), false));
        assertEquals("never past the duration", 30_000,
                FCastPlayerLogic.positionMillis(playing, now.get() + 60_000, false));
        assertEquals("a paused item stands still", 10_000,
                FCastPlayerLogic.positionMillis(state(Playback.PAUSED, 10.0, 30.0), now.get() + 5_000, false));
        assertEquals("a pause of ours stands still before the receiver confirms it", 10_000,
                FCastPlayerLogic.positionMillis(playing, now.get(), true));
    }

    @Test
    public void theExtrapolationFollowsTheSpeedAndSurvivesMissingValues() {
        FCastReceiverState fast = FCastReceiverState.INITIAL.toBuilder().playback(Playback.PLAYING, 10.0, 100.0, 2.0)
                .playbackUpdatedAt(100_000).build();
        assertEquals(13_000, FCastPlayerLogic.positionMillis(fast, 101_500, false));

        assertEquals(0, FCastPlayerLogic.positionMillis(FCastReceiverState.INITIAL, 5, false));
        FCastReceiverState noClock = FCastReceiverState.INITIAL.toBuilder().playback(Playback.PLAYING, 4.0, 10.0, 1.0).build();
        assertEquals("no update time: the reported position", 4_000, FCastPlayerLogic.positionMillis(noClock, 999_999, false));
        assertEquals(30_000, FCastPlayerLogic.durationMillis(state(Playback.PLAYING, 1.0, 30.0)));
        assertEquals(0, FCastPlayerLogic.durationMillis(FCastReceiverState.INITIAL));
    }

    @Test
    public void aSeekIsInSecondsAndStaysInsideTheItem() {
        assertEquals(12.5, FCastPlayerLogic.seekSeconds(12_500, 30.0), 0.0);
        assertEquals(0.0, FCastPlayerLogic.seekSeconds(-4_000, 30.0), 0.0);
        assertEquals(30.0, FCastPlayerLogic.seekSeconds(99_000, 30.0), 0.0);
        assertEquals("an unknown duration does not limit", 99.0, FCastPlayerLogic.seekSeconds(99_000, null), 0.0);
        assertEquals(99.0, FCastPlayerLogic.seekSeconds(99_000, 0.0), 0.0);
    }

    @Test
    public void onlyAnImageTypeIsAPicture() {
        assertTrue(FCastPlayerLogic.isImage("image/jpeg"));
        assertTrue(FCastPlayerLogic.isImage(" Image/PNG; x=1"));
        assertFalse(FCastPlayerLogic.isImage("video/mp4"));
        assertFalse(FCastPlayerLogic.isImage(null));
        assertFalse(FCastPlayerLogic.isImage("application/octet-stream"));
    }

    @Test
    public void aVolumeKeyMovesTheVolumeByOneStepAndStaysBetween0And100() {
        assertEquals(55, FCastPlayerLogic.stepVolume(50, true));
        assertEquals(45, FCastPlayerLogic.stepVolume(50, false));
        assertEquals(100, FCastPlayerLogic.stepVolume(98, true));
        assertEquals(100, FCastPlayerLogic.stepVolume(100, true));
        assertEquals(0, FCastPlayerLogic.stepVolume(3, false));
        assertEquals(0, FCastPlayerLogic.stepVolume(0, false));
        assertEquals(5, FCastPlayerLogic.stepVolume(0, true));
    }
}
