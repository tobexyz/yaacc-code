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
package de.yaacc.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for {@link ShareAddresses}: which addresses may fetch a share. Remote receivers
 * contribute their own addresses; the loopback addresses and this phone's addresses are added
 * ONLY when this device is a chosen receiver.
 */
public class ShareAddressesTest {

    /** The canonical form the policy uses for ::1. */
    private static final String LOOPBACK6 = ProxyAccessPolicy.normalize("::1");

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    @Test
    public void aRemoteReceiverContributesItsAddress() {
        Set<String> result = ShareAddresses.build(Arrays.asList("192.168.1.20"), false,
                Arrays.asList("192.168.1.5"));
        assertEquals(setOf("192.168.1.20"), result);
    }

    @Test
    public void theLocalDeviceAddsLoopbackAndTheOwnAddressesOnlyWhenChosen() {
        Set<String> without = ShareAddresses.build(Arrays.asList("192.168.1.20"), false,
                Arrays.asList("192.168.1.5"));
        assertFalse(without.contains("127.0.0.1"));
        assertFalse(without.contains(LOOPBACK6));
        assertFalse(without.contains("192.168.1.5"));

        Set<String> with = ShareAddresses.build(Arrays.asList("192.168.1.20"), true,
                Arrays.asList("192.168.1.5", "fe80::1%wlan0"));
        assertTrue(with.contains("192.168.1.20"));
        assertTrue(with.contains("127.0.0.1"));
        assertTrue(with.contains(LOOPBACK6));
        assertTrue(with.contains("192.168.1.5"));
        assertTrue("zone stripped", with.contains(ProxyAccessPolicy.normalize("fe80::1")));
    }

    @Test
    public void onlyTheLocalDeviceChosenGivesLoopbackAndOwnAddresses() {
        Set<String> result = ShareAddresses.build(Collections.emptyList(), true,
                Arrays.asList("10.0.0.7"));
        assertEquals(setOf("127.0.0.1", LOOPBACK6, "10.0.0.7"), result);
    }

    @Test
    public void noReceiverAtAllGivesAnEmptySetWhichDeniesEverythingWhenRestricting() {
        Set<String> result = ShareAddresses.build(Collections.emptyList(), false,
                Arrays.asList("10.0.0.7"));
        assertTrue(result.isEmpty());
        assertFalse(ProxyAccessPolicy.isAllowed(true, result, "10.0.0.7"));
        assertFalse(ProxyAccessPolicy.isAllowed(true, result, "127.0.0.1"));
    }

    @Test
    public void bracketedIpv6AndMappedAddressesAreNormalized() {
        Set<String> result = ShareAddresses.build(
                Arrays.asList("[2001:DB8::1]", "::ffff:192.168.1.30"), false, Collections.emptyList());
        assertTrue(result.contains(ProxyAccessPolicy.normalize("2001:db8::1")));
        assertTrue(result.contains("192.168.1.30"));
        assertEquals(2, result.size());
    }

    @Test
    public void hostNamesNullsAndGarbageAreDropped() {
        Set<String> result = ShareAddresses.build(
                Arrays.asList("tv.local", null, "", "not an address", "010.0.0.1", "192.168.1.20"),
                false, Collections.emptyList());
        assertEquals(setOf("192.168.1.20"), result);
    }

    @Test
    public void nullCollectionsAreTreatedAsEmpty() {
        assertTrue(ShareAddresses.build(null, false, null).isEmpty());
        assertEquals(setOf("127.0.0.1", LOOPBACK6), ShareAddresses.build(null, true, null));
    }

    @Test
    public void theResultCanNotBeChangedByTheCaller() {
        Set<String> result = ShareAddresses.build(Arrays.asList("192.168.1.20"), false, null);
        try {
            result.add("10.0.0.1");
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // fine
        }
    }

    @Test
    public void theResultWorksWithTheAccessPolicy() {
        Set<String> result = ShareAddresses.build(Arrays.asList("192.168.1.20"), true,
                Arrays.asList("192.168.1.5"));
        assertTrue(ProxyAccessPolicy.isAllowed(true, result, "192.168.1.20"));
        assertTrue(ProxyAccessPolicy.isAllowed(true, result, "127.0.0.1"));
        assertTrue(ProxyAccessPolicy.isAllowed(true, result, "::ffff:192.168.1.5"));
        assertFalse(ProxyAccessPolicy.isAllowed(true, result, "192.168.1.21"));
    }
}
