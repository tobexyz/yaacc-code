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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for {@link HttpLinkTarget}: only http and https links can ever be fetched. */
public class HttpLinkTargetTest {

    @Test
    public void anHttpLinkIsAccepted() {
        HttpLinkTarget target = HttpLinkTarget.of("http://example.org/a.mp3");
        assertNotNull(target);
        assertEquals("http://example.org/a.mp3", target.getUrl());
    }

    @Test
    public void anHttpsLinkIsAcceptedWithQueryAndPort() {
        assertEquals("https://example.org:8443/a?x=1&y=2#f",
                HttpLinkTarget.of("https://example.org:8443/a?x=1&y=2#f").getUrl());
    }

    @Test
    public void theSchemeIsCaseInsensitiveAndNormalizedToLowerCase() {
        assertEquals("http://Example.org/A", HttpLinkTarget.of("HTTP://Example.org/A").getUrl());
        assertEquals("https://example.org/", HttpLinkTarget.of("HtTpS://example.org/").getUrl());
    }

    @Test
    public void otherSchemesAreRefused() {
        assertNull(HttpLinkTarget.of("file:///data/data/de.yaacc/shared_prefs/x.xml"));
        assertNull(HttpLinkTarget.of("content://media/external/video/media/1"));
        assertNull(HttpLinkTarget.of("ftp://example.org/a"));
        assertNull(HttpLinkTarget.of("jar:http://example.org/a.jar!/x"));
        assertNull(HttpLinkTarget.of("javascript:alert(1)"));
        assertNull(HttpLinkTarget.of("/sdcard/Music/a.mp3"));
        assertNull(HttpLinkTarget.of("example.org/a"));
        assertNull(HttpLinkTarget.of("httpx://example.org/a"));
        assertNull(HttpLinkTarget.of("ttp://example.org/a"));
    }

    @Test
    public void aSchemeThatIsOnlyAPrefixOfTheTextIsNotEnough() {
        assertNull(HttpLinkTarget.of("http:example.org"));
        assertNull(HttpLinkTarget.of("http:/example.org"));
        assertNull(HttpLinkTarget.of("http://"));
        assertNull(HttpLinkTarget.of("https:///path"));
    }

    @Test
    public void nullEmptyAndBlankAreRefused() {
        assertNull(HttpLinkTarget.of(null));
        assertNull(HttpLinkTarget.of(""));
        assertNull(HttpLinkTarget.of("   "));
    }

    @Test
    public void leadingBlanksAreNotTrimmed() {
        assertNull(HttpLinkTarget.of(" http://example.org/a"));
    }

    @Test
    public void whitespaceAndControlCharactersAreRefused() {
        assertNull(HttpLinkTarget.of("http://example.org/a b"));
        assertNull(HttpLinkTarget.of("http://example.org/a\nfile:///x"));
        assertNull(HttpLinkTarget.of("http://example.org/a\r\nHost: x"));
        assertNull(HttpLinkTarget.of("http://example.org/a\u0000"));
        assertNull(HttpLinkTarget.of("http://example.org/a\t"));
        assertNull(HttpLinkTarget.of("http://example.org/a\u007f"));
    }

    @Test
    public void anOversizedLinkIsRefusedAtTheBoundary() {
        String prefix = "http://example.org/";
        StringBuilder atLimit = new StringBuilder(prefix);
        while (atLimit.length() < HttpLinkTarget.MAX_LENGTH) {
            atLimit.append('a');
        }
        assertEquals(HttpLinkTarget.MAX_LENGTH, atLimit.length());
        assertNotNull(HttpLinkTarget.of(atLimit.toString()));
        assertNull(HttpLinkTarget.of(atLimit + "a"));
    }

    @Test
    public void isFetchableMirrorsOf() {
        assertTrue(HttpLinkTarget.isFetchable("https://example.org/a"));
        assertFalse(HttpLinkTarget.isFetchable("file:///x"));
        assertFalse(HttpLinkTarget.isFetchable(null));
    }

    @Test
    public void toStringNeverShowsTheLink() {
        HttpLinkTarget target = HttpLinkTarget.of("https://example.org/secret-token-123");
        assertFalse(target.toString().contains("secret"));
        assertFalse(target.toString().contains("example.org"));
    }

    @Test
    public void twoTargetsWithTheSameLinkAreEqual() {
        assertEquals(HttpLinkTarget.of("http://a.org/x"), HttpLinkTarget.of("HTTP://a.org/x"));
        assertEquals(HttpLinkTarget.of("http://a.org/x").hashCode(),
                HttpLinkTarget.of("http://a.org/x").hashCode());
    }
}
