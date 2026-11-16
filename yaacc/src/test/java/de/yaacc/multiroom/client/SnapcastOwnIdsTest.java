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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** Task 12.19: the phone's current client id and the legacy ids of this install, as one value. Plain JVM. */
public class SnapcastOwnIdsTest {

    private static final String NEW = "yaacc-aaaaaaaaaaaaaaaa";
    private static final String OLD1 = "yaacc-1111111111111111";
    private static final String OLD2 = "yaacc-2222222222222222";

    @Test
    public void theCurrentIdAndTheLegacyIdsAreAllOwnAndTheCurrentOneIsTheCanonicalOne() {
        SnapcastOwnIds ids = SnapcastOwnIds.of(NEW, Arrays.asList(OLD1, OLD2));

        assertTrue(ids.isCurrent(NEW));
        assertFalse(ids.isLegacy(NEW));
        assertTrue(ids.isLegacy(OLD1));
        assertTrue(ids.isLegacy(OLD2));
        assertFalse(ids.isCurrent(OLD1));
        assertTrue(ids.isOwn(NEW) && ids.isOwn(OLD1) && ids.isOwn(OLD2));
        assertEquals(NEW, ids.canonical(OLD1));
        assertEquals(NEW, ids.canonical(NEW));
        assertEquals("another install's id is its own canonical form", "yaacc-bbbbbbbbbbbbbbbb", ids.canonical("yaacc-bbbbbbbbbbbbbbbb"));
    }

    @Test
    public void otherIdsAreNotOwn() {
        SnapcastOwnIds ids = SnapcastOwnIds.of(NEW, Arrays.asList(OLD1));

        for (String other : new String[]{"yaacc-bbbbbbbbbbbbbbbb", "YAACC-1111111111111111", OLD1 + " ", "", null, "00:11:22:33:44:55"}) {
            assertFalse(String.valueOf(other), ids.isOwn(other));
            assertFalse(String.valueOf(other), ids.isLegacy(other));
        }
        assertEquals("canonical of null and empty stays", "", ids.canonical(""));
        assertNull(ids.canonical(null));
    }

    @Test
    public void withoutACurrentIdNothingIsOwnNotEvenALegacyId() {
        // no secret: no legacy id can exist either
        for (SnapcastOwnIds ids : new SnapcastOwnIds[]{SnapcastOwnIds.none(), SnapcastOwnIds.of(null, Arrays.asList(OLD1)),
                SnapcastOwnIds.of("", Arrays.asList(OLD1))}) {
            assertFalse(ids.isOwn(OLD1));
            assertFalse(ids.isLegacy(OLD1));
            assertNull(ids.current());
            assertTrue(ids.legacy().isEmpty());
        }
    }

    @Test
    public void theCurrentIdIsNeverLegacyAndEmptyOrNullEntriesAreDropped() {
        SnapcastOwnIds ids = SnapcastOwnIds.of(NEW, Arrays.asList(NEW, "", null, OLD1));

        assertFalse(ids.isLegacy(NEW));
        assertEquals(1, ids.legacy().size());
        assertTrue(ids.legacy().contains(OLD1));
    }

    @Test
    public void theValueNeverShowsAnId() {
        assertFalse(SnapcastOwnIds.of(NEW, Arrays.asList(OLD1)).toString().contains("1111"));
        assertFalse(SnapcastOwnIds.of(NEW, Arrays.asList(OLD1)).toString().contains("aaaa"));
    }
}
