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
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** {@link ShareProxyUrl}: never a URL with the text "null" in it. Plain JVM. */
public class ShareProxyUrlTest {

    private static final String KEY = "abcdefghijklmnopqrstuv";

    @Test
    public void buildsTheUrlAReceiverFetches() {
        assertEquals("http://192.168.1.5:49157/proxy/" + KEY,
                ShareProxyUrl.build("192.168.1.5", 49157, "proxy", KEY));
    }

    @Test
    public void noAddressMeansNoUrl() {
        assertNull(ShareProxyUrl.build(null, 49157, "proxy", KEY));
        assertNull(ShareProxyUrl.build("", 49157, "proxy", KEY));
        assertNull(ShareProxyUrl.build("   ", 49157, "proxy", KEY));
    }

    @Test
    public void noKeyOrPathMeansNoUrl() {
        assertNull(ShareProxyUrl.build("192.168.1.5", 49157, "proxy", null));
        assertNull(ShareProxyUrl.build("192.168.1.5", 49157, "proxy", ""));
        assertNull(ShareProxyUrl.build("192.168.1.5", 49157, "", KEY));
        assertNull(ShareProxyUrl.build("192.168.1.5", 49157, null, KEY));
    }

    @Test
    public void anIpv6AddressIsBracketed() {
        assertEquals("http://[fd00::1]:49157/proxy/" + KEY,
                ShareProxyUrl.build("fd00::1", 49157, "proxy", KEY));
        assertEquals("http://[fd00::1]:49157/proxy/" + KEY,
                ShareProxyUrl.build("[fd00::1]", 49157, "proxy", KEY));
    }
}
