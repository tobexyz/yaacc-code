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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Task 2.6: when a receiver's state says that the item ended. */
public class FCastReceiverStateTest {

    private static FCastReceiverState state(FCastReceiverState.Playback playback, Double position, Double duration) {
        return FCastReceiverState.INITIAL.toBuilder().playback(playback, position, duration, 1.0).build();
    }

    @Test
    public void pausedAtTheDurationIsTheEnd() {
        assertTrue(state(FCastReceiverState.Playback.PAUSED, 30.526, 30.526).hasEnded());
        assertTrue("within a second", state(FCastReceiverState.Playback.PAUSED, 29.6, 30.526).hasEnded());
        assertTrue(state(FCastReceiverState.Playback.PAUSED, 200.0, 200.0).hasEnded());
    }

    @Test
    public void aPauseInTheMiddleIsNotTheEnd() {
        assertFalse(state(FCastReceiverState.Playback.PAUSED, 5.7, 30.5).hasEnded());
        assertFalse("1.01 s before the end", state(FCastReceiverState.Playback.PAUSED, 28.99, 30.0).hasEnded());
    }

    @Test
    public void onlyPausedCanBeTheEndAndOnlyWithBothNumbers() {
        assertFalse(state(FCastReceiverState.Playback.PLAYING, 30.5, 30.5).hasEnded());
        assertFalse(state(FCastReceiverState.Playback.IDLE, null, null).hasEnded());
        assertFalse(state(FCastReceiverState.Playback.UNKNOWN, null, null).hasEnded());
        assertFalse(state(FCastReceiverState.Playback.PAUSED, null, 30.5).hasEnded());
        assertFalse(state(FCastReceiverState.Playback.PAUSED, 30.5, null).hasEnded());
    }

    @Test
    public void nearTheEndCoversPlayingAndPausedWithinOneSecond() {
        assertTrue(state(FCastReceiverState.Playback.PLAYING, 30.425, 30.527).isNearEnd());
        assertTrue(state(FCastReceiverState.Playback.PAUSED, 30.527, 30.527).isNearEnd());
        assertFalse(state(FCastReceiverState.Playback.PLAYING, 29.0, 30.527).isNearEnd());
        assertFalse(state(FCastReceiverState.Playback.IDLE, 30.4, 30.5).isNearEnd());
        assertFalse(state(FCastReceiverState.Playback.PLAYING, null, 30.5).isNearEnd());
        assertFalse(state(FCastReceiverState.Playback.PLAYING, 0.0, 0.0).isNearEnd());
    }

    @Test
    public void aDurationOfZeroIsNoEnd() {
        assertFalse("a live stream or an unknown length reports 0", state(FCastReceiverState.Playback.PAUSED, 0.0, 0.0).hasEnded());
    }
}
