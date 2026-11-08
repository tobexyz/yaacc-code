/*
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

import static org.junit.Assert.assertEquals;

import androidx.media3.common.Player;

import org.junit.Test;

/** When the media engine pauses or resumes by itself, the app follows. */
public class ExternalPlayStateTest {
    private static final int USER = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST;

    @Test
    public void aPauseFromOutsideIsFollowedWhileTheAppSaysItPlays() {
        assertEquals(ExternalPlayState.Change.PAUSE, ExternalPlayState.decide(false, USER, true, false));
        assertEquals(ExternalPlayState.Change.PAUSE,
                ExternalPlayState.decide(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS, true, false));
        assertEquals(ExternalPlayState.Change.PAUSE,
                ExternalPlayState.decide(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY, true, false));
    }

    @Test
    public void aPauseThatTheAppCausedItselfNeedsNothing() {
        // pause() of the app sets its state first, then the engine reports the change
        assertEquals(ExternalPlayState.Change.NONE, ExternalPlayState.decide(false, USER, false, true));
        assertEquals(ExternalPlayState.Change.NONE, ExternalPlayState.decide(false, USER, false, false));
    }

    @Test
    public void aResumeFromOutsideIsFollowedWhileTheAppSaysItIsPaused() {
        assertEquals(ExternalPlayState.Change.RESUME, ExternalPlayState.decide(true, USER, false, true));
        assertEquals(ExternalPlayState.Change.RESUME,
                ExternalPlayState.decide(true, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS, false, true));
    }

    @Test
    public void startingAndResumingByTheAppNeedsNothing() {
        assertEquals(ExternalPlayState.Change.NONE, ExternalPlayState.decide(true, USER, true, false));  // play() set its state first
        assertEquals(ExternalPlayState.Change.NONE, ExternalPlayState.decide(true, USER, false, false)); // a start, not a resume
    }

    @Test
    public void theEndOfAnItemIsNotAPause() {
        assertEquals(ExternalPlayState.Change.NONE,
                ExternalPlayState.decide(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM, true, false));
    }
}
