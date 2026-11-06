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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The id lists of the OpenHome playlist. Plain JVM. */
public class OhIdsTest {

    @Test
    public void anIdArrayIsTheIdsAsBigEndianNumbersInBase64() {
        // 1, 2 and 4294967295 as 4 bytes each: 00000001 00000002 FFFFFFFF
        assertEquals("AAAAAQAAAAL/////", OhIds.encodeIdArray(Arrays.asList(1L, 2L, 4294967295L)));
        assertEquals(Arrays.asList(1L, 2L, 4294967295L), OhIds.decodeIdArray("AAAAAQAAAAL/////"));
        assertEquals("", OhIds.encodeIdArray(Collections.<Long>emptyList()));
        assertTrue(OhIds.decodeIdArray("").isEmpty());
        assertTrue(OhIds.decodeIdArray(null).isEmpty());
    }

    @Test
    public void theIdArrayRoundTrips() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 1000; i++) {
            ids.add(i * 4_000_000L);
        }
        assertEquals(ids, OhIds.decodeIdArray(OhIds.encodeIdArray(ids)));
    }

    @Test
    public void anIdArrayThatIsNotOneIsRefused() {
        for (String bad : new String[]{"not base64 !!", "AAAB" /* 3 bytes */, "AAAAAA==" /* id 0 */, "AAAAAQAAAA==" /* 7 bytes */}) {
            try {
                OhIds.decodeIdArray(bad);
                fail("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
    }

    @Test
    public void aHugeIdArrayIsRefusedBeforeItIsBuilt() {
        byte[] bytes = new byte[(OhIds.MAX_LIST + 1) * 4];
        for (int i = 3; i < bytes.length; i += 4) {
            bytes[i] = 1;
        }
        try {
            OhIds.decodeIdArray(java.util.Base64.getEncoder().encodeToString(bytes));
            fail();
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    public void onlyOneToFourBillionAreIds() {
        assertFalse(OhIds.isId(0));
        assertFalse(OhIds.isId(-1));
        assertFalse(OhIds.isId(4294967296L));
        assertTrue(OhIds.isId(1));
        assertTrue(OhIds.isId(4294967295L));
        try {
            OhIds.encodeIdArray(Arrays.asList(0L));
            fail();
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    public void anIdListIsDecimalNumbersSeparatedByBlanksAndWhatIsNoIdIsSkipped() {
        assertEquals(Arrays.asList(1L, 22L, 333L), OhIds.parseIdList("1 22  333", 100));
        assertEquals(Arrays.asList(5L), OhIds.parseIdList("x 5 -3 0 4294967296 1.5 +7 \t", 100));
        assertTrue(OhIds.parseIdList("", 100).isEmpty());
        assertTrue(OhIds.parseIdList(null, 100).isEmpty());
        assertEquals(Arrays.asList(1L, 2L), OhIds.parseIdList("1 2 3 4", 2));
        assertEquals("1 22 333", OhIds.formatIdList(Arrays.asList(1L, 22L, 333L)));
    }
}
