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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link ProxyAccessPolicy}: the IP step of the media proxy. With the restriction
 * off every address passes (key and expiry are checked elsewhere); with it on only the entry's
 * receiver addresses pass and everything unclear is denied. Plain JVM, no network, no DNS.
 */
public class ProxyAccessPolicyTest {

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static boolean restricted(Set<String> allowed, String remote) {
        return ProxyAccessPolicy.isAllowed(true, allowed, remote);
    }

    // ---- restriction off

    @Test
    public void unrestrictedAllowsAnyRemoteAddress() {
        assertTrue(ProxyAccessPolicy.isAllowed(false, set("192.168.1.5"), "10.0.0.9"));
    }

    @Test
    public void unrestrictedAllowsMissingRemoteAddress() {
        assertTrue(ProxyAccessPolicy.isAllowed(false, set("192.168.1.5"), null));
        assertTrue(ProxyAccessPolicy.isAllowed(false, set("192.168.1.5"), ""));
    }

    @Test
    public void unrestrictedAllowsGarbageRemoteAddress() {
        assertTrue(ProxyAccessPolicy.isAllowed(false, set("192.168.1.5"), "not an address"));
    }

    @Test
    public void unrestrictedAllowsWithNullOrEmptyAllowedSet() {
        assertTrue(ProxyAccessPolicy.isAllowed(false, null, "192.168.1.5"));
        assertTrue(ProxyAccessPolicy.isAllowed(false, Collections.<String>emptySet(), "192.168.1.5"));
    }

    // ---- restriction on: basic match

    @Test
    public void restrictedAllowsAnAddressInTheSet() {
        assertTrue(restricted(set("192.168.1.5"), "192.168.1.5"));
    }

    @Test
    public void restrictedAllowsOneOfSeveralReceivers() {
        assertTrue(restricted(set("192.168.1.5", "192.168.1.6", "10.0.0.2"), "192.168.1.6"));
    }

    @Test
    public void restrictedDeniesAnAddressNotInTheSet() {
        assertFalse(restricted(set("192.168.1.5"), "192.168.1.6"));
    }

    @Test
    public void restrictedDeniesAnAddressThatOnlySharesAPrefixAsText() {
        assertFalse(restricted(set("192.168.1.5"), "192.168.1.55"));
        assertFalse(restricted(set("192.168.1.55"), "192.168.1.5"));
        assertFalse(restricted(set("192.168.1.5"), "192.168.1.5.1"));
    }

    @Test
    public void restrictedDeniesWithEmptySet() {
        assertFalse(restricted(Collections.<String>emptySet(), "192.168.1.5"));
    }

    @Test
    public void restrictedDeniesWithNullSet() {
        assertFalse(restricted(null, "192.168.1.5"));
    }

    @Test
    public void restrictedDeniesNullRemoteAddress() {
        assertFalse(restricted(set("192.168.1.5"), null));
    }

    @Test
    public void restrictedDeniesEmptyAndBlankRemoteAddress() {
        assertFalse(restricted(set("192.168.1.5"), ""));
        assertFalse(restricted(set("192.168.1.5"), "   "));
    }

    @Test
    public void restrictedDeniesGarbageRemoteAddress() {
        assertFalse(restricted(set("192.168.1.5"), "garbage"));
        assertFalse(restricted(set("192.168.1.5"), "192.168.1.5; DROP"));
    }

    @Test
    public void garbageInTheSetNeverMatchesTheSameGarbage() {
        assertFalse(restricted(set("garbage"), "garbage"));
        assertFalse(restricted(set("localhost"), "localhost"));
    }

    @Test
    public void restrictedIgnoresNullAndEmptyElementsInTheSet() {
        Set<String> allowed = new HashSet<>(Arrays.asList(null, "", "192.168.1.5"));

        assertTrue(restricted(allowed, "192.168.1.5"));
        assertFalse(restricted(allowed, "192.168.1.6"));
    }

    @Test
    public void networksAndWildcardsInTheSetNeverMatch() {
        Set<String> allowed = set("*", "0.0.0.0/0", "192.168.1.0/24", "192.168.1.*", "::/0");

        assertFalse(restricted(allowed, "192.168.1.5"));
        assertFalse(restricted(allowed, "10.0.0.1"));
        assertFalse(restricted(allowed, "::1"));
    }

    @Test
    public void hostNamesAreNeverResolvedOrMatched() {
        assertFalse(restricted(set("192.168.1.5"), "receiver.local"));
        assertFalse(restricted(set("receiver.local"), "receiver.local"));
    }

    // ---- restriction on: normalization

    @Test
    public void surroundingWhitespaceIsIgnoredOnBothSides() {
        assertTrue(restricted(set("192.168.1.5"), " 192.168.1.5\n"));
        assertTrue(restricted(set(" 192.168.1.5 "), "192.168.1.5"));
    }

    @Test
    public void ipv4MappedIpv6RemoteMatchesTheIpv4Entry() {
        assertTrue(restricted(set("192.168.1.5"), "::ffff:192.168.1.5"));
    }

    @Test
    public void ipv4MappedIpv6RemoteInHexFormMatchesTheIpv4Entry() {
        assertTrue(restricted(set("192.168.1.5"), "::ffff:c0a8:105"));
    }

    @Test
    public void ipv4MappedIpv6EntryMatchesAnIpv4Remote() {
        assertTrue(restricted(set("::ffff:192.168.1.5"), "192.168.1.5"));
    }

    @Test
    public void ipv4MappedIpv6IsCaseInsensitive() {
        assertTrue(restricted(set("192.168.1.5"), "::FFFF:192.168.1.5"));
    }

    @Test
    public void ipv4MappedIpv6OfAnotherAddressIsDenied() {
        assertFalse(restricted(set("192.168.1.5"), "::ffff:192.168.1.6"));
    }

    @Test
    public void ipv4CompatibleIpv6IsNotTreatedAsIpv4() {
        assertFalse(restricted(set("192.168.1.5"), "::192.168.1.5"));
    }

    @Test
    public void ipv6HexDigitsAreCaseInsensitive() {
        assertTrue(restricted(set("fe80::abcd"), "FE80::ABCD"));
        assertTrue(restricted(set("FE80::ABCD"), "fe80::abcd"));
    }

    @Test
    public void ipv6ZoneIdIsStrippedFromTheRemote() {
        assertTrue(restricted(set("fe80::1"), "fe80::1%wlan0"));
    }

    @Test
    public void ipv6ZoneIdIsStrippedFromTheEntry() {
        assertTrue(restricted(set("fe80::1%wlan0"), "fe80::1"));
    }

    @Test
    public void ipv6CompressedAndExpandedFormsAreTheSameAddress() {
        assertTrue(restricted(set("2001:db8::1"), "2001:0db8:0000:0000:0000:0000:0000:0001"));
        assertTrue(restricted(set("2001:0db8:0:0:0:0:0:1"), "2001:db8::1"));
    }

    @Test
    public void differentIpv6AddressesAreDenied() {
        assertFalse(restricted(set("2001:db8::1"), "2001:db8::2"));
    }

    // ---- restriction on: loopback is not special

    @Test
    public void ipv4LoopbackIsDeniedWhenNotInTheSet() {
        assertFalse(restricted(set("192.168.1.5"), "127.0.0.1"));
    }

    @Test
    public void ipv6LoopbackIsDeniedWhenNotInTheSet() {
        assertFalse(restricted(set("192.168.1.5"), "::1"));
        assertFalse(restricted(set("192.168.1.5"), "0:0:0:0:0:0:0:1"));
    }

    @Test
    public void ipv4LoopbackIsAllowedWhenInTheSet() {
        assertTrue(restricted(set("127.0.0.1", "192.168.1.5"), "127.0.0.1"));
    }

    @Test
    public void ipv6LoopbackIsAllowedWhenInTheSetInAnyNotation() {
        assertTrue(restricted(set("::1"), "::1"));
        assertTrue(restricted(set("::1"), "0:0:0:0:0:0:0:1"));
        assertTrue(restricted(set("0:0:0:0:0:0:0:1"), "::1"));
    }

    @Test
    public void ipv4LoopbackInTheSetDoesNotAllowIpv6Loopback() {
        assertFalse(restricted(set("127.0.0.1"), "::1"));
    }

    @Test
    public void otherAddressesOfTheLoopbackRangeAreNotAllowedByTheLoopbackEntry() {
        assertFalse(restricted(set("127.0.0.1"), "127.0.0.2"));
    }

    @Test
    public void mappedLoopbackMatchesTheIpv4LoopbackEntry() {
        assertTrue(restricted(set("127.0.0.1"), "::ffff:127.0.0.1"));
    }

    // ---- normalize

    @Test
    public void normalizeKeepsAPlainIpv4Address() {
        assertEquals("192.168.1.5", ProxyAccessPolicy.normalize("192.168.1.5"));
    }

    @Test
    public void normalizeTrimsWhitespace() {
        assertEquals("192.168.1.5", ProxyAccessPolicy.normalize("  192.168.1.5\t"));
    }

    @Test
    public void normalizeMapsIpv4MappedIpv6ToIpv4() {
        assertEquals("192.168.1.5", ProxyAccessPolicy.normalize("::ffff:192.168.1.5"));
        assertEquals("192.168.1.5", ProxyAccessPolicy.normalize("::FFFF:C0A8:0105"));
        assertEquals("192.168.1.5", ProxyAccessPolicy.normalize("0:0:0:0:0:ffff:c0a8:105"));
    }

    @Test
    public void normalizeExpandsLoopbackIpv6() {
        assertEquals("0:0:0:0:0:0:0:1", ProxyAccessPolicy.normalize("::1"));
    }

    @Test
    public void normalizeLowercasesAndStripsLeadingZerosOfIpv6Groups() {
        assertEquals("2001:db8:0:0:0:0:0:1",
                ProxyAccessPolicy.normalize("2001:0DB8::0001"));
    }

    @Test
    public void normalizeStripsTheIpv6ZoneId() {
        assertEquals("fe80:0:0:0:0:0:0:1", ProxyAccessPolicy.normalize("fe80::1%wlan0"));
    }

    @Test
    public void normalizeKeepsTheDeprecatedIpv4CompatibleFormAsIpv6() {
        assertEquals("0:0:0:0:0:0:c0a8:105", ProxyAccessPolicy.normalize("::192.168.1.5"));
    }

    @Test
    public void normalizeReturnsNullForNullEmptyAndBlank() {
        assertNull(ProxyAccessPolicy.normalize(null));
        assertNull(ProxyAccessPolicy.normalize(""));
        assertNull(ProxyAccessPolicy.normalize("   "));
    }

    @Test
    public void normalizeReturnsNullForHostNamesAndGarbage() {
        assertNull(ProxyAccessPolicy.normalize("localhost"));
        assertNull(ProxyAccessPolicy.normalize("receiver.local"));
        assertNull(ProxyAccessPolicy.normalize("not an address"));
        assertNull(ProxyAccessPolicy.normalize("192.168.1.5 192.168.1.6"));
    }

    @Test
    public void normalizeReturnsNullForMalformedIpv4() {
        assertNull(ProxyAccessPolicy.normalize("256.1.1.1"));
        assertNull(ProxyAccessPolicy.normalize("1.2.3"));
        assertNull(ProxyAccessPolicy.normalize("1.2.3.4.5"));
        assertNull(ProxyAccessPolicy.normalize("1.2.3."));
        assertNull(ProxyAccessPolicy.normalize("-1.2.3.4"));
        assertNull(ProxyAccessPolicy.normalize("+1.2.3.4"));
        assertNull(ProxyAccessPolicy.normalize("1.2.3.4/24"));
    }

    @Test
    public void normalizeReturnsNullForAmbiguousIpv4Spellings() {
        assertNull(ProxyAccessPolicy.normalize("192.168.01.5"));
        assertNull(ProxyAccessPolicy.normalize("0x7f.0.0.1"));
        assertNull(ProxyAccessPolicy.normalize("127.1"));
        assertNull(ProxyAccessPolicy.normalize("2130706433"));
    }

    @Test
    public void normalizeReturnsNullForMalformedIpv6() {
        assertNull(ProxyAccessPolicy.normalize(":::"));
        assertNull(ProxyAccessPolicy.normalize("1::2::3"));
        assertNull(ProxyAccessPolicy.normalize("1:2:3:4:5:6:7:8:9"));
        assertNull(ProxyAccessPolicy.normalize("12345::1"));
        assertNull(ProxyAccessPolicy.normalize("g::1"));
        assertNull(ProxyAccessPolicy.normalize("1:2:3:4:5:6:7"));
    }

    @Test
    public void normalizeReturnsNullForBracketsAndEmptyZone() {
        assertNull(ProxyAccessPolicy.normalize("[::1]"));
        assertNull(ProxyAccessPolicy.normalize("fe80::1%"));
    }

    @Test
    public void normalizeReturnsNullForAZoneOnIpv4() {
        assertNull(ProxyAccessPolicy.normalize("192.168.1.5%eth0"));
    }

    @Test
    public void normalizeIsIdempotent() {
        String once = ProxyAccessPolicy.normalize("FE80::ABCD%wlan0");

        assertEquals("fe80:0:0:0:0:0:0:abcd", once);
        assertEquals(once, ProxyAccessPolicy.normalize(once));
    }
}
