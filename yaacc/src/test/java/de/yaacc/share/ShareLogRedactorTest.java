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

/** Tests for {@link ShareLogRedactor}: a share key must never reach a log line. */
public class ShareLogRedactorTest {

    private static final String KEY = "AbCdEfGhIjKlMnOpQrStUv";

    @Test
    public void aProxyUrlLosesItsKey() {
        String out = ShareLogRedactor.redact("play http://192.168.1.5:49157/proxy/" + KEY + " now");
        assertFalse(out.contains(KEY));
        assertEquals("play http://192.168.1.5:49157/proxy/[redacted] now", out);
    }

    @Test
    public void theFormWithADeviceIdIsRedactedAsAWhole() {
        String out = ShareLogRedactor.redact("http://h:1/proxy/uuid%3Aabc-def/" + KEY);
        assertFalse(out.contains(KEY));
        assertFalse(out.contains("abc-def"));
    }

    @Test
    public void aUrlInsideXmlIsRedactedUpToTheClosingTag() {
        String out = ShareLogRedactor.redact("<CurrentURI>http://h:1/proxy/" + KEY + "</CurrentURI><X/>");
        assertEquals("<CurrentURI>http://h:1/proxy/[redacted]</CurrentURI><X/>", out);
    }

    @Test
    public void severalUrlsAreAllRedacted() {
        String out = ShareLogRedactor.redact("/proxy/" + KEY + " and /proxy/" + KEY);
        assertFalse(out.contains(KEY));
        assertEquals("/proxy/[redacted] and /proxy/[redacted]", out);
    }

    @Test
    public void textWithoutAProxyPathIsUnchanged() {
        String text = "http://h:1/res/12345 and plain text";
        assertEquals(text, ShareLogRedactor.redact(text));
    }

    @Test
    public void nullStaysNullAndEmptyStaysEmpty() {
        assertNull(ShareLogRedactor.redact(null));
        assertEquals("", ShareLogRedactor.redact(""));
    }

    @Test
    public void aVeryLongInputIsHandled() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append("/proxy/").append(KEY).append(' ');
        }
        String out = ShareLogRedactor.redact(sb.toString());
        assertFalse(out.contains(KEY));
        assertTrue(out.startsWith("/proxy/[redacted] "));
    }

    @Test
    public void redactsUpperAndMixedCaseProxyPaths() {
        assertEquals("http://h:1/proxy/[redacted] x", ShareLogRedactor.redact("http://h:1/PROXY/" + KEY + " x"));
        assertEquals("http://h:1/proxy/[redacted]", ShareLogRedactor.redact("http://h:1/Proxy/" + KEY));
    }

    @Test
    public void redactsPercentEncodedProxyPaths() {
        assertEquals("http://h:1/proxy/[redacted]", ShareLogRedactor.redact("http://h:1%2Fproxy%2F" + KEY));
        assertEquals("a /proxy/[redacted] b", ShareLogRedactor.redact("a %2fPROXY%2f" + KEY + " b"));
    }

    @Test
    public void leavesOtherPathsContainingTheWordProxyAlone() {
        String text = "http://h:1/proxying/info and /myproxy/x";
        assertEquals(text, ShareLogRedactor.redact(text));
    }
}
