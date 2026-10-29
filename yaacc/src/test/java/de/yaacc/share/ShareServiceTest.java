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

import org.junit.Test;

import de.yaacc.R;

/**
 * The pure parts of {@link ShareService}: the URL a receiver fetches a share from (never a URL
 * with the text "null" in it) and the toast text of a share that could not be opened. The glue
 * of {@code prepare} is in {@link ShareServicePrepareTest}.
 */
public class ShareServiceTest {

    private static final String KEY = "abcdefghijklmnopqrstuv";

    // ---- proxyUrl and hasAddress (were ShareProxyUrlTest)

    @Test
    public void buildsTheUrlAReceiverFetches() {
        assertEquals("http://192.168.1.5:49157/proxy/" + KEY, ShareService.proxyUrl("192.168.1.5", KEY));
    }

    @Test
    public void noAddressMeansNoUrl() {
        assertNull(ShareService.proxyUrl(null, KEY));
        assertNull(ShareService.proxyUrl("", KEY));
        assertNull(ShareService.proxyUrl("   ", KEY));
        assertFalse(ShareService.hasAddress(null));
        assertFalse(ShareService.hasAddress(""));
        assertFalse(ShareService.hasAddress("   "));
        assertTrue(ShareService.hasAddress("192.168.1.5"));
        assertTrue("offline the phone reports 0.0.0.0, which still counts as an address",
                ShareService.hasAddress("0.0.0.0"));
    }

    @Test
    public void noKeyMeansNoUrl() {
        assertNull(ShareService.proxyUrl("192.168.1.5", null));
        assertNull(ShareService.proxyUrl("192.168.1.5", ""));
    }

    @Test
    public void anIpv6AddressIsBracketed() {
        assertEquals("http://[fd00::1]:49157/proxy/" + KEY, ShareService.proxyUrl("fd00::1", KEY));
        assertEquals("http://[fd00::1]:49157/proxy/" + KEY, ShareService.proxyUrl("[fd00::1]", KEY));
    }

    @Test
    public void anIpv6AddressWithAZoneIdIsBracketedAndKeepsTheZone() {
        // the output of the string builder this replaced
        assertEquals("http://[fe80::1%wlan0]:49157/proxy/" + KEY, ShareService.proxyUrl("fe80::1%wlan0", KEY));
    }

    // ---- failureTextRes (were the four Kind cases of ShareFailureMessageTest)

    @Test
    public void aProviderCallRefusedForRoomIsBusy() {
        assertEquals(R.string.share_busy, ShareService.failureTextRes(true, false));
    }

    @Test
    public void aBusyCopySlotIsBusy() {
        assertEquals(R.string.share_busy, ShareService.failureTextRes(false, true));
    }

    @Test
    public void bothBusyIsBusy() {
        assertEquals(R.string.share_busy, ShareService.failureTextRes(true, true));
    }

    @Test
    public void anyOtherFailureStaysTheGenericMessage() {
        assertEquals(R.string.share_open_failed, ShareService.failureTextRes(false, false));
    }
}
