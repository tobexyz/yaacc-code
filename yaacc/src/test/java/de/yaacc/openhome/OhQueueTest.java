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
package de.yaacc.openhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The play queue of the OpenHome renderer. Plain JVM. */
public class OhQueueTest {

    private static final String A = "http://192.0.2.1/a.mp3";
    private static final String B = "http://192.0.2.1/b.mp3";
    private static final String C = "http://192.0.2.1/c.mp3";

    private static void assertCode(int code, Runnable action) {
        try {
            action.run();
            fail("expected error " + code);
        } catch (OhError e) {
            assertEquals(code, e.getCode());
        }
    }

    @Test
    public void tracksFollowTheTrackTheyAreInsertedAfter() {
        OhQueue queue = new OhQueue(10);
        long a = queue.insert(OhIds.NONE, A, "");
        long c = queue.insert(a, C, "");
        long b = queue.insert(a, B, "<meta/>");

        assertEquals(Arrays.asList(a, b, c), queue.idArray());
        assertEquals(B, queue.read(b).getUri());
        assertEquals("<meta/>", queue.read(b).getMetadata());
        assertEquals(1, queue.indexOf(b));
        assertEquals(c, queue.idAt(2));
    }

    @Test
    public void noIdMeansTheStartOfTheQueue() {
        OhQueue queue = new OhQueue(10);
        long a = queue.insert(OhIds.NONE, A, "");
        long b = queue.insert(OhIds.NONE, B, "");

        assertEquals(Arrays.asList(b, a), queue.idArray());
    }

    @Test
    public void idsStartAtOneAndAreNotUsedAgainAfterADelete() {
        OhQueue queue = new OhQueue(10);
        long a = queue.insert(0, A, "");
        long b = queue.insert(a, B, "");
        assertEquals(1, a);
        assertEquals(2, b);

        queue.delete(b);
        long c = queue.insert(a, C, "");
        queue.deleteAll();
        long d = queue.insert(0, A, "");

        assertEquals(3, c);
        assertEquals(4, d);
    }

    @Test
    public void anUnknownIdIsError800() {
        final OhQueue queue = new OhQueue(10);
        queue.insert(0, A, "");
        assertCode(800, () -> queue.insert(99, B, ""));
        assertCode(800, () -> queue.delete(99));
        assertCode(800, () -> queue.read(99));
        assertCode(800, () -> queue.delete(0));
    }

    @Test
    public void aFullQueueIsError801AndNothingChanges() {
        final OhQueue queue = new OhQueue(2);
        long a = queue.insert(0, A, "");
        long b = queue.insert(a, B, "");
        long token = queue.token();

        assertCode(801, () -> queue.insert(0, C, ""));

        assertEquals(Arrays.asList(a, b), queue.idArray());
        assertEquals(token, queue.token());
        queue.delete(a);
        queue.insert(b, C, ""); // room again
        assertEquals(2, queue.size());
    }

    @Test
    public void anAddressOrMetadataThatIsEmptyOrTooLongIsRefused() {
        final OhQueue queue = new OhQueue(10);
        assertCode(402, () -> queue.insert(0, "", ""));
        assertCode(402, () -> queue.insert(0, null, ""));
        assertCode(402, () -> queue.insert(0, "http://192.0.2.1/" + new String(new char[OhQueue.MAX_URI_LENGTH]).replace('\0', 'a'), ""));
        assertCode(402, () -> queue.insert(0, A, new String(new char[OhQueue.MAX_METADATA_LENGTH + 1]).replace('\0', 'm')));
        assertEquals(0, queue.size());
        queue.insert(0, A, null); // metadata may be missing
        assertEquals("", queue.read(queue.idAt(0)).getMetadata());
    }

    @Test
    public void everyEditChangesTheTokenAndAReadDoesNot() {
        OhQueue queue = new OhQueue(10);
        long t0 = queue.token();
        long a = queue.insert(0, A, "");
        long t1 = queue.token();
        queue.read(a);
        queue.idArray();
        queue.readList(Collections.singletonList(a));
        assertEquals(t1, queue.token());
        queue.delete(a);
        long t2 = queue.token();
        queue.deleteAll(); // nothing to delete: not an edit
        assertEquals(t2, queue.token());

        assertNotEquals(t0, t1);
        assertNotEquals(t1, t2);
    }

    @Test
    public void readListSkipsUnknownIdsAndKeepsTheOrderAsked() {
        OhQueue queue = new OhQueue(10);
        long a = queue.insert(0, A, "");
        long b = queue.insert(a, B, "");

        List<OhQueue.Entry> entries = queue.readList(Arrays.asList(b, 99L, a, b));

        assertEquals(3, entries.size());
        assertEquals(b, entries.get(0).getId());
        assertEquals(a, entries.get(1).getId());
    }

    @Test
    public void aPositionOutsideTheQueueIsError802() {
        final OhQueue queue = new OhQueue(10);
        queue.insert(0, A, "");
        assertCode(802, () -> queue.idAt(1));
        assertCode(802, () -> queue.idAt(-1));
    }

    @Test
    public void aThousandTracksFitAndTheThousandAndFirstDoesNot() {
        final OhQueue queue = new OhQueue(OhQueue.DEFAULT_TRACKS_MAX);
        long after = 0;
        for (int i = 0; i < OhQueue.DEFAULT_TRACKS_MAX; i++) {
            after = queue.insert(after, "http://192.0.2.1/" + i + ".mp3", "");
        }
        assertEquals(1000, queue.size());
        assertCode(801, () -> queue.insert(0, A, ""));
        assertTrue(queue.snapshot().size() == 1000);
    }

    @Test
    public void theIdCounterWrapsAroundAfterTheLargestIdAndSkipsIdsThatAreInUse() {
        OhQueue queue = new OhQueue(10, OhIds.MAX_ID - 1);

        long last = queue.insert(0, A, "");
        long first = queue.insert(last, B, "");   // wrapped: 0 is no id
        long second = queue.insert(first, C, "");

        assertEquals(OhIds.MAX_ID, last);
        assertEquals(1, first);
        assertEquals(2, second);

        // the counter comes round to an id that is still in the queue: it is skipped
        OhQueue other = new OhQueue(10, OhIds.MAX_ID - 1);
        long x = other.insert(0, A, "");           // MAX_ID
        long y = other.insert(x, B, "");           // 1
        for (int i = 0; i < 3; i++) {
            other.delete(y);
            y = other.insert(x, B, "");
            assertTrue(OhIds.isId(y));
        }
        assertEquals(2, other.size());
    }

    @Test
    public void allTracksTogetherHaveABudgetAndDeletingGivesItBack() {
        OhQueue queue = new OhQueue(1000);
        String big = "x".repeat(OhQueue.MAX_METADATA_LENGTH);
        int fit = (OhQueue.MAX_TOTAL_LENGTH - 100) / (OhQueue.MAX_METADATA_LENGTH + 1);
        long first = 0;
        for (int i = 0; i < fit; i++) {
            long id = queue.insert(0, "u", big);
            if (i == 0) {
                first = id;
            }
        }
        try {
            queue.insert(0, "u", big);
            org.junit.Assert.fail();
        } catch (OhError e) {
            assertEquals(OhError.PLAYLIST_FULL, e.getCode());
        }

        queue.delete(first);

        queue.insert(0, "u", big); // room again
        queue.deleteAll();
        queue.insert(0, "u", big);
    }

    @Test
    public void metadataOfARealTrackFits() {
        assertEquals(true, OhQueue.MAX_METADATA_LENGTH >= 8 * 1024);
    }

    @Test
    public void moveKeepsTheIdAndChangesTheOrderAndTheToken() {
        OhQueue queue = new OhQueue(10);
        long a = queue.insert(0, "a", "");
        long b = queue.insert(a, "b", "");
        long c = queue.insert(b, "c", "");
        long token = queue.token();

        queue.move(c, 0);
        assertEquals(java.util.Arrays.asList(c, a, b), queue.idArray());
        queue.move(c, b);
        assertEquals(java.util.Arrays.asList(a, b, c), queue.idArray());
        queue.move(a, a); // nothing to do
        assertEquals(java.util.Arrays.asList(a, b, c), queue.idArray());
        assertEquals(true, queue.token() > token);
        assertEquals("a", queue.read(a).getUri());
    }

    @Test
    public void moveWithAnUnknownIdFails() {
        OhQueue queue = new OhQueue(10);
        long a = queue.insert(0, "a", "");
        for (long[] ids : new long[][]{{4711, a}, {a, 4711}}) {
            try {
                queue.move(ids[0], ids[1]);
                org.junit.Assert.fail();
            } catch (OhError e) {
                assertEquals(OhError.ID_NOT_FOUND, e.getCode());
            }
        }
    }
}
