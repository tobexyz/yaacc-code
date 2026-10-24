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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.browser.DeviceSessionGrouper.PlayerRef;

/**
 * Unit tests for {@link MiniPlayerPicker}, which chooses the one session the
 * mini-player bar features across all devices: playing beats non-playing,
 * then the newest creation sequence wins, then the higher player id. Plain
 * JVM, no Android runtime.
 * <p>
 * Sessions are ordered by creation sequence, not player id: AVTransportPlayer
 * ids are random and local ids are fixed per type. The fixtures therefore use
 * ids that run against the sequence, so an id-based implementation fails.
 */
public class MiniPlayerPickerTest {

    private static final String TV = "uuid:living-room-tv";
    private static final String KITCHEN = "uuid:kitchen-speaker";
    private static final String GONE = "uuid:dropped-off-network";

    /** A session created {@code seq}-th (higher = newer) with player id {@code id}. */
    private static PlayerRef session(long seq, int id, String deviceId, boolean playing) {
        return new PlayerRef(id, deviceId, playing, seq);
    }

    @Test
    public void nullListYieldsNothing() {
        assertNull(MiniPlayerPicker.pick(null));
    }

    @Test
    public void emptyListYieldsNothing() {
        assertNull(MiniPlayerPicker.pick(Collections.<PlayerRef>emptyList()));
    }

    @Test
    public void listOfOnlyNullsYieldsNothing() {
        assertNull(MiniPlayerPicker.pick(Arrays.<PlayerRef>asList(null, null)));
    }

    @Test
    public void singleNonPlayingSessionIsPicked() {
        PlayerRef only = session(1, 5, TV, false);

        assertSame(only, MiniPlayerPicker.pick(Collections.singletonList(only)));
    }

    @Test
    public void singlePlayingSessionIsPicked() {
        PlayerRef only = session(1, 5, TV, true);

        assertSame(only, MiniPlayerPicker.pick(Collections.singletonList(only)));
    }

    @Test
    public void playingSessionBeatsNewerNonPlayingSession() {
        PlayerRef playing = session(1, 900, TV, true);
        PlayerRef newerPaused = session(2, 3, TV, false);

        assertSame(playing, MiniPlayerPicker.pick(Arrays.asList(newerPaused, playing)));
    }

    @Test
    public void playingSessionOnAnotherDeviceBeatsNewerNonPlayingSession() {
        PlayerRef playingOnKitchen = session(1, 900, KITCHEN, true);
        PlayerRef newerPausedOnTv = session(5, 3, TV, false);

        assertSame(playingOnKitchen,
                MiniPlayerPicker.pick(Arrays.asList(newerPausedOnTv, playingOnKitchen)));
    }

    @Test
    public void amongPlayingSessionsTheHighestSequenceWins() {
        PlayerRef older = session(1, 3, TV, true);
        PlayerRef newest = session(7, 900, KITCHEN, true);
        PlayerRef middle = session(4, 50, TV, true);

        assertSame(newest, MiniPlayerPicker.pick(Arrays.asList(older, newest, middle)));
    }

    @Test
    public void whenNothingPlaysTheHighestSequenceWins() {
        PlayerRef older = session(1, 3, TV, false);
        PlayerRef newest = session(7, 900, KITCHEN, false);
        PlayerRef middle = session(4, 50, TV, false);

        assertSame(newest, MiniPlayerPicker.pick(Arrays.asList(older, newest, middle)));
    }

    @Test
    public void equalSequenceAmongPlayingSessionsFallsBackToHigherPlayerId() {
        PlayerRef lowerId = session(4, 10, TV, true);
        PlayerRef higherId = session(4, 20, KITCHEN, true);

        assertSame(higherId, MiniPlayerPicker.pick(Arrays.asList(higherId, lowerId)));
        assertSame(higherId, MiniPlayerPicker.pick(Arrays.asList(lowerId, higherId)));
    }

    @Test
    public void allNonPlayingWithEqualSequenceFallsBackToHigherPlayerId() {
        PlayerRef lowerId = session(2, 10, TV, false);
        PlayerRef middleId = session(2, 15, KITCHEN, false);
        PlayerRef higherId = session(2, 20, GONE, false);

        assertSame(higherId, MiniPlayerPicker.pick(Arrays.asList(middleId, higherId, lowerId)));
        assertSame(higherId, MiniPlayerPicker.pick(Arrays.asList(lowerId, middleId, higherId)));
    }

    @Test
    public void resultDoesNotDependOnInputOrder() {
        PlayerRef a = session(1, 900, TV, false);
        PlayerRef b = session(2, 3, KITCHEN, true);
        PlayerRef c = session(3, 50, TV, false);

        assertSame(b, MiniPlayerPicker.pick(Arrays.asList(a, b, c)));
        assertSame(b, MiniPlayerPicker.pick(Arrays.asList(c, b, a)));
        assertSame(b, MiniPlayerPicker.pick(Arrays.asList(b, c, a)));
    }

    @Test
    public void nullEntriesAreSkipped() {
        PlayerRef real = session(1, 5, TV, false);

        assertSame(real, MiniPlayerPicker.pick(Arrays.asList(null, real, null)));
    }

    @Test
    public void sessionWithNullDeviceIdCanBePicked() {
        PlayerRef noDevice = session(9, 5, null, true);
        PlayerRef other = session(1, 6, TV, false);

        assertSame(noDevice, MiniPlayerPicker.pick(Arrays.asList(other, noDevice)));
    }

    @Test
    public void sessionOnUnknownDeviceCompetesLikeAnyOther() {
        PlayerRef onGoneDevice = session(9, 5, GONE, false);
        PlayerRef olderOnTv = session(1, 6, TV, false);

        assertSame(onGoneDevice, MiniPlayerPicker.pick(Arrays.asList(olderOnTv, onGoneDevice)));
    }

    @Test
    public void inputListIsNotModified() {
        PlayerRef first = session(1, 900, TV, false);
        PlayerRef second = session(9, 3, KITCHEN, true);
        List<PlayerRef> input = new ArrayList<>(Arrays.asList(first, null, second));

        MiniPlayerPicker.pick(input);

        assertEquals(3, input.size());
        assertSame(first, input.get(0));
        assertNull(input.get(1));
        assertSame(second, input.get(2));
    }
}
