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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.browser.DeviceSessionGrouper.PlayerRef;
import de.yaacc.browser.DeviceSessionGrouper.Row;

/**
 * Unit tests for {@link DeviceSessionGrouper}, which nests running player
 * sessions under the receiver device they play on. Receivers and players are
 * not 1:1: the local device keeps one player per content type and a remote
 * renderer gets one {@code AVTransportPlayer} per content type, so a device
 * can carry several sessions at once. Plain JVM, no Android runtime.
 */
public class DeviceSessionGrouperTest {

    // Same value as UpnpClient.LOCAL_UID; UpnpClient itself isn't referenced
    // because it pulls in Android classes.
    private static final String LOCAL = "LOCAL_UID";
    private static final String TV = "uuid:living-room-tv";
    private static final String KITCHEN = "uuid:kitchen-speaker";
    private static final String GONE = "uuid:dropped-off-network";
    private static final String GONE_TOO = "uuid:also-dropped";

    private static PlayerRef player(int id, String deviceId, boolean playing) {
        return new PlayerRef(id, deviceId, playing);
    }

    /** Renders rows as "R:device" / "P:device:playerId" for readable assertions. */
    private static List<String> describe(List<Row> rows) {
        List<String> out = new ArrayList<>();
        for (Row row : rows) {
            if (row.kind() == Row.Kind.RECEIVER) {
                out.add("R:" + row.deviceId());
            } else {
                out.add("P:" + row.deviceId() + ":" + row.playerId());
            }
        }
        return out;
    }

    // --- group(...) ---------------------------------------------------------

    @Test
    public void receiverWithoutSessionsYieldsOnlyItsReceiverRow() {
        List<Row> rows = DeviceSessionGrouper.group(
                Collections.singletonList(KITCHEN), Collections.emptyList());

        assertEquals(Collections.singletonList("R:" + KITCHEN), describe(rows));
    }

    @Test
    public void localDeviceWithImageAndMusicSessionsListsBothInAscendingPlayerIdOrder() {
        // Image player created after the music player, but passed in first:
        // order must come from playerId, not from input order.
        List<PlayerRef> players = Arrays.asList(
                player(7, LOCAL, false),   // image show
                player(3, LOCAL, true));   // background music

        List<Row> rows = DeviceSessionGrouper.group(Collections.singletonList(LOCAL), players);

        assertEquals(Arrays.asList("R:" + LOCAL, "P:" + LOCAL + ":3", "P:" + LOCAL + ":7"),
                describe(rows));
    }

    @Test
    public void remoteRendererWithMusicAndImageSessionsNestsBothUnderThatRenderer() {
        List<PlayerRef> players = Arrays.asList(
                player(12, TV, true),     // music AVTransportPlayer
                player(11, TV, false));   // image AVTransportPlayer

        List<Row> rows = DeviceSessionGrouper.group(Arrays.asList(LOCAL, TV), players);

        assertEquals(Arrays.asList("R:" + LOCAL, "R:" + TV, "P:" + TV + ":11", "P:" + TV + ":12"),
                describe(rows));
    }

    @Test
    public void sessionOnUnknownDeviceGoesToTrailingGroupAndIsNeverDropped() {
        List<PlayerRef> players = Arrays.asList(
                player(5, GONE, true),
                player(2, LOCAL, false));

        List<Row> rows = DeviceSessionGrouper.group(Arrays.asList(LOCAL, TV), players);

        assertEquals(Arrays.asList("R:" + LOCAL, "P:" + LOCAL + ":2", "R:" + TV, "P:" + GONE + ":5"),
                describe(rows));
    }

    @Test
    public void unknownDevicesGetNoReceiverRowAndTheirSessionsStayContiguousPerDevice() {
        // GONE_TOO's id (7) lies between GONE's ids (6, 9): an implementation
        // that just sorts all unknown-device sessions by playerId would put
        // 7 between 6 and 9 and split the GONE group.
        List<PlayerRef> players = Arrays.asList(
                player(9, GONE, false),
                player(7, GONE_TOO, true),
                player(6, GONE, true),
                player(1, KITCHEN, false));

        List<String> rows = describe(DeviceSessionGrouper.group(
                Collections.singletonList(KITCHEN), players));

        assertEquals("one receiver row plus every session exactly once: " + rows, 5, rows.size());
        assertEquals(Arrays.asList("R:" + KITCHEN, "P:" + KITCHEN + ":1"), rows.subList(0, 2));
        List<String> trailing = rows.subList(2, rows.size());
        assertTrue("missing GONE_TOO session 7: " + trailing, trailing.contains("P:" + GONE_TOO + ":7"));
        // Both GONE sessions next to each other, ascending playerId.
        int gone6 = trailing.indexOf("P:" + GONE + ":6");
        int gone9 = trailing.indexOf("P:" + GONE + ":9");
        assertTrue("missing GONE session 6: " + trailing, gone6 >= 0);
        assertEquals("GONE sessions must be contiguous, 6 before 9: " + trailing, gone6 + 1, gone9);
        for (String row : trailing) {
            assertTrue("no receiver row for a device that isn't a receiver: " + row,
                    row.startsWith("P:"));
        }
    }

    @Test
    public void receiverOrderIsPreservedNotSorted() {
        List<PlayerRef> players = Arrays.asList(
                player(1, LOCAL, true),
                player(2, KITCHEN, false));

        List<Row> rows = DeviceSessionGrouper.group(Arrays.asList(TV, LOCAL, KITCHEN), players);

        assertEquals(Arrays.asList(
                        "R:" + TV,
                        "R:" + LOCAL, "P:" + LOCAL + ":1",
                        "R:" + KITCHEN, "P:" + KITCHEN + ":2"),
                describe(rows));
    }

    @Test
    public void emptyReceiversAndPlayersYieldNoRows() {
        List<Row> rows = DeviceSessionGrouper.group(Collections.emptyList(), Collections.emptyList());

        assertTrue(rows.isEmpty());
    }

    @Test
    public void noReceiversStillListsEverySession() {
        List<PlayerRef> players = Arrays.asList(player(3, LOCAL, false), player(8, TV, true));

        List<String> rows = describe(DeviceSessionGrouper.group(Collections.emptyList(), players));

        assertEquals(2, rows.size());
        assertTrue(rows.contains("P:" + LOCAL + ":3"));
        assertTrue(rows.contains("P:" + TV + ":8"));
    }

    // --- primarySession(...) ------------------------------------------------

    @Test
    public void primarySessionPrefersPlayingSessionOverHigherPlayerId() {
        PlayerRef playingMusic = player(3, LOCAL, true);
        List<PlayerRef> players = Arrays.asList(playingMusic, player(7, LOCAL, false));

        assertSame(playingMusic, DeviceSessionGrouper.primarySession(LOCAL, players));
    }

    @Test
    public void primarySessionPicksHighestPlayerIdAmongSeveralPlayingSessionsRegardlessOfInputOrder() {
        // Players arrive in HashMap order, so the pick must not depend on
        // which playing session comes first in the list.
        PlayerRef olderPlaying = player(4, LOCAL, true);
        PlayerRef newerPlaying = player(8, LOCAL, true);
        PlayerRef newestIdle = player(9, LOCAL, false);

        assertSame("lower id first", newerPlaying, DeviceSessionGrouper.primarySession(
                LOCAL, Arrays.asList(olderPlaying, newerPlaying, newestIdle)));
        assertSame("higher id first", newerPlaying, DeviceSessionGrouper.primarySession(
                LOCAL, Arrays.asList(newestIdle, newerPlaying, olderPlaying)));
    }

    @Test
    public void primarySessionFallsBackToHighestPlayerIdWhenNothingIsPlaying() {
        PlayerRef newest = player(7, LOCAL, false);
        List<PlayerRef> players = Arrays.asList(player(3, LOCAL, false), newest, player(5, LOCAL, false));

        assertSame(newest, DeviceSessionGrouper.primarySession(LOCAL, players));
    }

    @Test
    public void primarySessionIgnoresSessionsOfOtherDevices() {
        PlayerRef tvImage = player(2, TV, false);
        List<PlayerRef> players = Arrays.asList(player(9, LOCAL, true), tvImage);

        assertSame(tvImage, DeviceSessionGrouper.primarySession(TV, players));
    }

    @Test
    public void primarySessionIsNullWhenDeviceHasNoSessions() {
        List<PlayerRef> players = Collections.singletonList(player(4, TV, true));

        assertNull(DeviceSessionGrouper.primarySession(KITCHEN, players));
        assertNull(DeviceSessionGrouper.primarySession(KITCHEN, Collections.emptyList()));
    }
}
