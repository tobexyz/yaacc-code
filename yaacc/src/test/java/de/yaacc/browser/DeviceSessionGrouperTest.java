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
import de.yaacc.upnp.UpnpClient;

/**
 * Unit tests for {@link DeviceSessionGrouper}, which nests running player
 * sessions under the receiver device they play on. Receivers and players are
 * not 1:1: the local device keeps one player per content type and a remote
 * renderer gets one {@code AVTransportPlayer} per content type, so a device
 * can carry several sessions at once. Plain JVM, no Android runtime.
 * <p>
 * Sessions are ordered by creation sequence, not player id: AVTransportPlayer
 * ids are random and local ids are fixed per type. The fixtures therefore use
 * ids that run against the sequence (e.g. id 900 created before id 3), so an
 * id-based implementation fails.
 */
public class DeviceSessionGrouperTest {

    // UpnpClient loads on the plain JVM (android.jar stubs, returnDefaultValues),
    // so the real constant is used rather than a copy of its value.
    private static final String LOCAL = UpnpClient.LOCAL_UID;
    private static final String TV = "uuid:living-room-tv";
    private static final String KITCHEN = "uuid:kitchen-speaker";
    private static final String GONE = "uuid:dropped-off-network";
    private static final String GONE_TOO = "uuid:also-dropped";

    /** A session created {@code seq}-th (higher = newer) with player id {@code id}. */
    private static PlayerRef session(long seq, int id, String deviceId, boolean playing) {
        return new PlayerRef(id, deviceId, playing, seq);
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
    public void localDeviceWithImageAndMusicSessionsListsBothOldestFirst() {
        // Music (fixed id 1) started after the image show (fixed id 3), and
        // the input order is newest first: order must come from the creation
        // sequence, not from id or input order.
        List<PlayerRef> players = Arrays.asList(
                session(2, 1, LOCAL, true),    // background music, newer
                session(1, 3, LOCAL, false));  // image show, older

        List<Row> rows = DeviceSessionGrouper.group(Collections.singletonList(LOCAL), players);

        assertEquals(Arrays.asList("R:" + LOCAL, "P:" + LOCAL + ":3", "P:" + LOCAL + ":1"),
                describe(rows));
    }

    @Test
    public void remoteRendererWithMusicAndImageSessionsNestsBothUnderThatRendererOldestFirst() {
        // Random AVTransportPlayer ids: the older session has the larger id.
        List<PlayerRef> players = Arrays.asList(
                session(6, 12, TV, true),      // music, newer
                session(5, 900, TV, false));   // image, older

        List<Row> rows = DeviceSessionGrouper.group(Arrays.asList(LOCAL, TV), players);

        assertEquals(Arrays.asList("R:" + LOCAL, "R:" + TV, "P:" + TV + ":900", "P:" + TV + ":12"),
                describe(rows));
    }

    @Test
    public void sessionOnUnknownDeviceGoesToTrailingGroupAndIsNeverDropped() {
        List<PlayerRef> players = Arrays.asList(
                session(1, 5, GONE, true),
                session(2, 2, LOCAL, false));

        List<Row> rows = DeviceSessionGrouper.group(Arrays.asList(LOCAL, TV), players);

        assertEquals(Arrays.asList("R:" + LOCAL, "P:" + LOCAL + ":2", "R:" + TV, "P:" + GONE + ":5"),
                describe(rows));
    }

    @Test
    public void unknownDevicesGetNoReceiverRowAndTheirSessionsStayContiguousPerDevice() {
        // GONE_TOO's sequence (7) lies between GONE's (6, 9): an
        // implementation that just sorts all unknown-device sessions would put
        // it between them and split the GONE group. Ids run against the
        // sequence, so sorting by id splits it too.
        List<PlayerRef> players = Arrays.asList(
                session(9, 10, GONE, false),
                session(7, 20, GONE_TOO, true),
                session(6, 30, GONE, true),
                session(1, 40, KITCHEN, false));

        List<String> rows = describe(DeviceSessionGrouper.group(
                Collections.singletonList(KITCHEN), players));

        assertEquals("one receiver row plus every session exactly once: " + rows, 5, rows.size());
        assertEquals(Arrays.asList("R:" + KITCHEN, "P:" + KITCHEN + ":40"), rows.subList(0, 2));
        List<String> trailing = rows.subList(2, rows.size());
        assertTrue("missing GONE_TOO session: " + trailing, trailing.contains("P:" + GONE_TOO + ":20"));
        // Both GONE sessions next to each other, oldest (seq 6, id 30) first.
        int older = trailing.indexOf("P:" + GONE + ":30");
        int newer = trailing.indexOf("P:" + GONE + ":10");
        assertTrue("missing older GONE session: " + trailing, older >= 0);
        assertEquals("GONE sessions must be contiguous, oldest first: " + trailing, older + 1, newer);
        for (String row : trailing) {
            assertTrue("no receiver row for a device that isn't a receiver: " + row,
                    row.startsWith("P:"));
        }
    }

    @Test
    public void trailingGroupsAreOrderedByTheirOldestSession() {
        List<PlayerRef> players = Arrays.asList(
                session(8, 1, GONE, false),
                session(3, 2, GONE_TOO, false),
                session(5, 3, GONE, true));

        List<Row> rows = DeviceSessionGrouper.group(Collections.emptyList(), players);

        assertEquals(Arrays.asList("P:" + GONE_TOO + ":2", "P:" + GONE + ":3", "P:" + GONE + ":1"),
                describe(rows));
    }

    @Test
    public void receiverOrderIsPreservedNotSorted() {
        List<PlayerRef> players = Arrays.asList(
                session(1, 1, LOCAL, true),
                session(2, 2, KITCHEN, false));

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
        List<PlayerRef> players = Arrays.asList(session(1, 3, LOCAL, false), session(2, 8, TV, true));

        List<String> rows = describe(DeviceSessionGrouper.group(Collections.emptyList(), players));

        assertEquals(2, rows.size());
        assertTrue(rows.contains("P:" + LOCAL + ":3"));
        assertTrue(rows.contains("P:" + TV + ":8"));
    }

    @Test
    public void equalSequencesFallBackToPlayerIdRegardlessOfInputOrder() {
        // Sequence 0 = not (or no longer) registered in PlayerService.
        PlayerRef a = session(0, 7, TV, false);
        PlayerRef b = session(0, 4, TV, false);

        assertEquals(Arrays.asList("R:" + TV, "P:" + TV + ":4", "P:" + TV + ":7"),
                describe(DeviceSessionGrouper.group(Collections.singletonList(TV), Arrays.asList(a, b))));
        assertEquals(Arrays.asList("R:" + TV, "P:" + TV + ":4", "P:" + TV + ":7"),
                describe(DeviceSessionGrouper.group(Collections.singletonList(TV), Arrays.asList(b, a))));
        assertSame(a, DeviceSessionGrouper.primarySession(TV, Arrays.asList(a, b)));
        assertSame(a, DeviceSessionGrouper.primarySession(TV, Arrays.asList(b, a)));
    }

    // --- untrusted device ids (security review cycle 1) ---------------------

    @Test
    public void duplicateReceiverIdIsListedOnlyAtItsFirstOccurrence() {
        // Two LAN devices can advertise the same UDN.
        List<PlayerRef> players = Arrays.asList(session(1, 1, TV, true), session(2, 2, KITCHEN, false));

        List<Row> rows = DeviceSessionGrouper.group(Arrays.asList(TV, KITCHEN, TV), players);

        assertEquals(Arrays.asList(
                        "R:" + TV, "P:" + TV + ":1",
                        "R:" + KITCHEN, "P:" + KITCHEN + ":2"),
                describe(rows));
    }

    @Test
    public void nullReceiverIdIsSkippedAndDoesNotCaptureNullDeviceSessions() {
        List<PlayerRef> players = Arrays.asList(session(2, 4, null, true), session(1, 3, TV, false));

        List<Row> rows = DeviceSessionGrouper.group(Arrays.asList(null, TV), players);

        assertEquals(Arrays.asList("R:" + TV, "P:" + TV + ":3", "P:null:4"), describe(rows));
    }

    @Test
    public void sessionsWithNullDeviceIdGoToTrailingGroupAndAreNeverDropped() {
        List<PlayerRef> players = Arrays.asList(
                session(3, 5, null, false),
                session(1, 2, LOCAL, false),
                session(2, 8, null, true));

        List<Row> rows = DeviceSessionGrouper.group(Collections.singletonList(LOCAL), players);

        assertEquals(Arrays.asList("R:" + LOCAL, "P:" + LOCAL + ":2", "P:null:8", "P:null:5"),
                describe(rows));
    }

    @Test
    public void nullListsAreTreatedAsEmpty() {
        assertTrue(DeviceSessionGrouper.group(null, null).isEmpty());
        assertEquals(Collections.singletonList("P:" + TV + ":1"),
                describe(DeviceSessionGrouper.group(null, Collections.singletonList(session(1, 1, TV, true)))));
        assertNull(DeviceSessionGrouper.primarySession(TV, null));
    }

    @Test
    public void remoteDeviceSpoofingLocalUidIsGroupedLikeAnyOtherId() {
        // A remote device advertising uuid:LOCAL_UID collides with the local
        // dummy device. LOCAL_UID gets no special treatment: it isn't hoisted
        // to the top, the duplicate is listed once, and its sessions appear once.
        List<PlayerRef> players = Arrays.asList(session(1, 1, LOCAL, true), session(2, 2, TV, false));

        List<Row> rows = DeviceSessionGrouper.group(
                Arrays.asList(TV, UpnpClient.LOCAL_UID, UpnpClient.LOCAL_UID), players);

        assertEquals(Arrays.asList(
                        "R:" + TV, "P:" + TV + ":2",
                        "R:" + LOCAL, "P:" + LOCAL + ":1"),
                describe(rows));
    }

    @Test
    public void localUidSessionWithoutLocalReceiverGoesToTrailingGroupLikeAnyUnknownId() {
        List<PlayerRef> players = Arrays.asList(session(1, 1, LOCAL, true), session(2, 2, TV, false));

        List<Row> rows = DeviceSessionGrouper.group(Collections.singletonList(TV), players);

        assertEquals(Arrays.asList("R:" + TV, "P:" + TV + ":2", "P:" + LOCAL + ":1"), describe(rows));
    }

    @Test
    public void primarySessionToleratesNullDeviceIds() {
        PlayerRef localIdle = session(1, 2, LOCAL, false);
        PlayerRef orphanPlaying = session(2, 9, null, true);
        List<PlayerRef> players = Arrays.asList(orphanPlaying, localIdle);

        assertSame(localIdle, DeviceSessionGrouper.primarySession(LOCAL, players));
        assertSame(orphanPlaying, DeviceSessionGrouper.primarySession(null, players));
    }

    // --- primarySession(...) ------------------------------------------------

    @Test
    public void primarySessionPrefersPlayingSessionOverNewerIdleOne() {
        PlayerRef playingMusic = session(1, 900, LOCAL, true);
        List<PlayerRef> players = Arrays.asList(playingMusic, session(2, 3, LOCAL, false));

        assertSame(playingMusic, DeviceSessionGrouper.primarySession(LOCAL, players));
    }

    @Test
    public void primarySessionPicksNewestAmongSeveralPlayingSessionsRegardlessOfInputOrder() {
        // Players arrive in HashMap order, so the pick must not depend on
        // which playing session comes first in the list. Ids run against the
        // sequence: highest-id or lowest-id picks both fail.
        PlayerRef olderPlaying = session(1, 900, LOCAL, true);
        PlayerRef newerPlaying = session(2, 3, LOCAL, true);
        PlayerRef newestIdle = session(3, 50, LOCAL, false);

        assertSame("older first", newerPlaying, DeviceSessionGrouper.primarySession(
                LOCAL, Arrays.asList(olderPlaying, newerPlaying, newestIdle)));
        assertSame("newer first", newerPlaying, DeviceSessionGrouper.primarySession(
                LOCAL, Arrays.asList(newestIdle, newerPlaying, olderPlaying)));
    }

    @Test
    public void primarySessionFallsBackToNewestSessionWhenNothingIsPlaying() {
        PlayerRef newest = session(3, 1, LOCAL, false);
        List<PlayerRef> players = Arrays.asList(
                session(1, 900, LOCAL, false), newest, session(2, 7, LOCAL, false));

        assertSame(newest, DeviceSessionGrouper.primarySession(LOCAL, players));
    }

    @Test
    public void primarySessionIgnoresSessionsOfOtherDevices() {
        PlayerRef tvImage = session(1, 2, TV, false);
        List<PlayerRef> players = Arrays.asList(session(2, 9, LOCAL, true), tvImage);

        assertSame(tvImage, DeviceSessionGrouper.primarySession(TV, players));
    }

    @Test
    public void primarySessionIsNullWhenDeviceHasNoSessions() {
        List<PlayerRef> players = Collections.singletonList(session(1, 4, TV, true));

        assertNull(DeviceSessionGrouper.primarySession(KITCHEN, players));
        assertNull(DeviceSessionGrouper.primarySession(KITCHEN, Collections.emptyList()));
    }
}
