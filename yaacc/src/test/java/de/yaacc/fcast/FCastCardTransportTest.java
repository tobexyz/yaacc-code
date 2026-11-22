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

import org.junit.Test;

import de.yaacc.fcast.FCastCardTransport.Action;
import de.yaacc.fcast.FCastCardTransport.Command;
import de.yaacc.fcast.protocol.FCastReceiverState.Playback;

/** Task 6.2: the buttons of a card act on the playback that another sender started, by what the receiver reports. */
public class FCastCardTransportTest {

    @Test
    public void pauseAndStopActOnWhatPlays() {
        assertEquals(Command.PAUSE, FCastCardTransport.commandFor(Action.PAUSE, Playback.PLAYING));
        assertEquals(Command.STOP, FCastCardTransport.commandFor(Action.STOP, Playback.PLAYING));
        assertEquals(Command.NONE, FCastCardTransport.commandFor(Action.PLAY, Playback.PLAYING));
    }

    @Test
    public void playResumesWhatIsPausedAndStopStopsIt() {
        assertEquals(Command.RESUME, FCastCardTransport.commandFor(Action.PLAY, Playback.PAUSED));
        assertEquals(Command.STOP, FCastCardTransport.commandFor(Action.STOP, Playback.PAUSED));
        assertEquals(Command.NONE, FCastCardTransport.commandFor(Action.PAUSE, Playback.PAUSED));
    }

    @Test
    public void anIdleReceiverHasNothingToControl() {
        for (Action action : Action.values()) {
            assertEquals(action.name(), Command.NONE, FCastCardTransport.commandFor(action, Playback.IDLE));
        }
    }

    @Test
    public void whenTheStateIsNotKnownYetTheCommandIsSentAnyway() {
        assertEquals(Command.RESUME, FCastCardTransport.commandFor(Action.PLAY, Playback.UNKNOWN));
        assertEquals(Command.PAUSE, FCastCardTransport.commandFor(Action.PAUSE, Playback.UNKNOWN));
        assertEquals(Command.STOP, FCastCardTransport.commandFor(Action.STOP, Playback.UNKNOWN));
        assertEquals(Command.STOP, FCastCardTransport.commandFor(Action.STOP, null));
    }
}
