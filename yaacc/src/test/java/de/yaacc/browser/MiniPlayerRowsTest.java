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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.browser.DeviceSessionGrouper.PlayerRef;

/**
 * Unit tests for {@link MiniPlayerRows}: the rows of the mini-player list come in the device
 * sheet's session order (creation sequence ascending, oldest first; equal sequences by player
 * id, as {@link DeviceSessionGrouper} does), and the album-art key is the player id plus the art
 * URI. Plain JVM, no Android runtime.
 * <p>
 * Fixtures use ids that run against the sequence, so an id-based ordering fails.
 */
public class MiniPlayerRowsTest {

    private static final String TV = "uuid:living-room-tv";
    private static final String KITCHEN = "uuid:kitchen-speaker";

    private static PlayerRef session(long seq, int id, String deviceId, boolean playing) {
        return new PlayerRef(id, deviceId, playing, seq);
    }

    private static List<Integer> ids(List<PlayerRef> rows) {
        List<Integer> ids = new ArrayList<>();
        for (PlayerRef row : rows) {
            ids.add(row.playerId());
        }
        return ids;
    }

    @Test
    public void nullListYieldsNoRows() {
        assertTrue(MiniPlayerRows.order(null).isEmpty());
    }

    @Test
    public void emptyListYieldsNoRows() {
        assertTrue(MiniPlayerRows.order(Collections.<PlayerRef>emptyList()).isEmpty());
    }

    @Test
    public void nullEntriesAreSkipped() {
        PlayerRef real = session(1, 5, TV, false);

        List<PlayerRef> rows = MiniPlayerRows.order(Arrays.asList(null, real, null));

        assertEquals(1, rows.size());
        assertSame(real, rows.get(0));
        assertTrue(MiniPlayerRows.order(Arrays.<PlayerRef>asList(null, null)).isEmpty());
    }

    @Test
    public void singlePlayerIsOneRow() {
        PlayerRef only = session(3, 8, TV, true);

        assertEquals(Collections.singletonList(only), MiniPlayerRows.order(Collections.singletonList(only)));
    }

    @Test
    public void oldestSequenceComesFirstRegardlessOfInputOrder() {
        PlayerRef oldest = session(1, 900, TV, false);
        PlayerRef middle = session(2, 50, KITCHEN, true);
        PlayerRef newest = session(3, 7, TV, false);

        assertEquals(Arrays.asList(900, 50, 7), ids(MiniPlayerRows.order(Arrays.asList(newest, oldest, middle))));
        assertEquals(Arrays.asList(900, 50, 7), ids(MiniPlayerRows.order(Arrays.asList(middle, newest, oldest))));
        assertEquals(Arrays.asList(900, 50, 7), ids(MiniPlayerRows.order(Arrays.asList(oldest, middle, newest))));
    }

    @Test
    public void playingStateAndDeviceDoNotInfluenceTheOrder() {
        PlayerRef pausedOld = session(1, 10, TV, false);
        PlayerRef playingNew = session(2, 20, KITCHEN, true);

        assertEquals(Arrays.asList(10, 20), ids(MiniPlayerRows.order(Arrays.asList(playingNew, pausedOld))));
    }

    @Test
    public void equalSequencesFallBackToPlayerIdRegardlessOfInputOrder() {
        // sequence 0 = not (or no longer) registered in PlayerService
        PlayerRef a = session(0, 7, TV, false);
        PlayerRef b = session(0, 4, TV, false);
        PlayerRef c = session(0, 9, KITCHEN, false);

        assertEquals(Arrays.asList(4, 7, 9), ids(MiniPlayerRows.order(Arrays.asList(a, b, c))));
        assertEquals(Arrays.asList(4, 7, 9), ids(MiniPlayerRows.order(Arrays.asList(c, b, a))));
        assertEquals(Arrays.asList(4, 7, 9), ids(MiniPlayerRows.order(Arrays.asList(b, c, a))));
    }

    @Test
    public void sequenceBeatsPlayerIdInTheTieBreak() {
        PlayerRef lowIdNewer = session(5, 1, TV, false);
        PlayerRef highIdTied1 = session(2, 8, TV, false);
        PlayerRef lowIdTied2 = session(2, 3, TV, false);

        assertEquals(Arrays.asList(3, 8, 1), ids(MiniPlayerRows.order(Arrays.asList(lowIdNewer, highIdTied1, lowIdTied2))));
    }

    @Test
    public void matchesTheDeviceSheetsSessionOrder() {
        PlayerRef a = session(4, 11, TV, true);
        PlayerRef b = session(2, 12, KITCHEN, false);
        PlayerRef c = session(2, 10, null, false);
        PlayerRef d = session(9, 13, "uuid:gone", true);
        List<PlayerRef> players = Arrays.asList(a, b, c, d);

        List<Integer> sheet = new ArrayList<>();
        for (DeviceSessionGrouper.Row row : DeviceSessionGrouper.group(Collections.<String>emptyList(), players)) {
            sheet.add(row.playerId());
        }
        // The sheet groups by device when receivers exist; with no receivers every session is
        // listed, ordered by each device group's oldest session. For one session per device this
        // is exactly the list order.
        assertEquals(Arrays.asList(10, 12, 11, 13), ids(MiniPlayerRows.order(players)));
        assertEquals(Arrays.asList(10, 12, 11, 13), sheet);
    }

    @Test
    public void aDuplicatePlayerIdIsListedOnlyOnce() {
        // a RecyclerView with stable ids must never see the same id twice
        PlayerRef first = session(1, 5, TV, false);
        PlayerRef duplicate = session(2, 5, KITCHEN, true);
        PlayerRef other = session(3, 6, TV, false);

        List<PlayerRef> rows = MiniPlayerRows.order(Arrays.asList(duplicate, other, first));

        assertEquals(Arrays.asList(5, 6), ids(rows));
        assertSame(first, rows.get(0));
    }

    @Test
    public void unknownDeviceIdIsFine() {
        PlayerRef noDevice = session(1, 2, null, false);
        PlayerRef other = session(2, 3, TV, false);

        assertEquals(Arrays.asList(2, 3), ids(MiniPlayerRows.order(Arrays.asList(other, noDevice))));
    }

    @Test
    public void inputListIsNotModifiedAndTheResultIsACopy() {
        PlayerRef first = session(9, 900, TV, false);
        PlayerRef second = session(1, 3, KITCHEN, true);
        List<PlayerRef> input = new ArrayList<>(Arrays.asList(first, null, second));

        List<PlayerRef> rows = MiniPlayerRows.order(input);

        assertEquals(3, input.size());
        assertSame(first, input.get(0));
        assertNull(input.get(1));
        assertSame(second, input.get(2));
        assertNotSame(input, rows);
        rows.clear(); // the caller may do what it wants with the result
        assertEquals(3, input.size());
    }

    // ---- album-art key -------------------------------------------------------------------

    @Test
    public void artKeyIsThePlayerIdAndTheArtUri() {
        assertEquals("12:http://nas/art/1.jpg", MiniPlayerRows.artKey(12, "http://nas/art/1.jpg"));
    }

    @Test
    public void artKeyDiffersPerPlayerAndPerUri() {
        String uri = "http://nas/art/1.jpg";

        assertNotEquals(MiniPlayerRows.artKey(1, uri), MiniPlayerRows.artKey(2, uri));
        assertNotEquals(MiniPlayerRows.artKey(1, uri), MiniPlayerRows.artKey(1, "http://nas/art/2.jpg"));
        assertEquals(MiniPlayerRows.artKey(1, uri), MiniPlayerRows.artKey(1, uri));
    }

    @Test
    public void artKeyWithoutUriIsStillPerPlayer() {
        assertNotEquals(MiniPlayerRows.artKey(1, null), MiniPlayerRows.artKey(2, null));
        assertNotEquals(MiniPlayerRows.artKey(1, null), MiniPlayerRows.artKey(1, "http://nas/a.jpg"));
        assertEquals(MiniPlayerRows.artKey(1, null), MiniPlayerRows.artKey(1, null));
    }

    @Test
    public void artKeyIdAndUriCannotBeConfusedAcrossPlayers() {
        // "1" + ":" + "2:x" must not equal "12" + ":" + "x"
        assertNotEquals(MiniPlayerRows.artKey(1, "2:x"), MiniPlayerRows.artKey(12, "x"));
    }
}
