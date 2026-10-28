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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ShareUriPolicy}: which shared texts may be turned into a playable link, and the host
 * the picker shows for it. Plain JVM.
 */
public class ShareUriPolicyTest {

    @Test
    public void httpAndHttpsAreAllowed() {
        assertEquals("http://example.com/a.mp3", ShareUriPolicy.extractUrl("http://example.com/a.mp3"));
        assertEquals("https://example.com/a.mp4?x=1#t", ShareUriPolicy.extractUrl("https://example.com/a.mp4?x=1#t"));
        assertTrue(ShareUriPolicy.isAllowed("http://example.com/a.mp3"));
    }

    @Test
    public void schemeIsMatchedIgnoringCase() {
        assertEquals("HTTP://example.com/a.mp3", ShareUriPolicy.extractUrl("HTTP://example.com/a.mp3"));
        assertEquals("HtTpS://example.com/a.mp3", ShareUriPolicy.extractUrl("HtTpS://example.com/a.mp3"));
    }

    @Test
    public void localSchemesAreRejected() {
        assertNull(ShareUriPolicy.extractUrl("file:///sdcard/Music/a.mp3"));
        assertNull(ShareUriPolicy.extractUrl("FILE:///sdcard/Music/a.mp3"));
        assertNull(ShareUriPolicy.extractUrl("content://media/external/audio/media/1"));
    }

    @Test
    public void otherSchemesAreRejected() {
        assertNull(ShareUriPolicy.extractUrl("javascript:alert(1)"));
        assertNull(ShareUriPolicy.extractUrl("ftp://example.com/a.mp3"));
        assertNull(ShareUriPolicy.extractUrl("intent://x#Intent;end"));
        assertNull(ShareUriPolicy.extractUrl("httpx://example.com"));
        assertNull(ShareUriPolicy.extractUrl("http.evil://example.com"));
    }

    @Test
    public void nullAndEmptyAreRejected() {
        assertNull(ShareUriPolicy.extractUrl(null));
        assertNull(ShareUriPolicy.extractUrl(""));
        assertFalse(ShareUriPolicy.isAllowed(null));
    }

    @Test
    public void textWithoutALinkIsRejected() {
        assertNull(ShareUriPolicy.extractUrl("/sdcard/Music/a.mp3"));
        assertNull(ShareUriPolicy.extractUrl("example.com/a.mp3"));
        assertNull(ShareUriPolicy.extractUrl("//example.com/a.mp3"));
        assertNull(ShareUriPolicy.extractUrl("/sdcard/http://x"));
        assertNull(ShareUriPolicy.extractUrl("http"));
        assertNull(ShareUriPolicy.extractUrl("https"));
        assertNull(ShareUriPolicy.extractUrl(" "));
    }

    @Test
    public void aLinkNeedsSlashesAndAHost() {
        assertNull(ShareUriPolicy.extractUrl("http:"));
        assertNull(ShareUriPolicy.extractUrl("http:example.com"));
        assertNull(ShareUriPolicy.extractUrl("http://"));
        assertNull(ShareUriPolicy.extractUrl("https:///a.mp3"));
        assertNull(ShareUriPolicy.extractUrl("http://:80/a"));
    }

    @Test
    public void theLinkIsPickedOutOfASentence() {
        assertEquals("https://example.com/a.mp3", ShareUriPolicy.extractUrl("Listen to https://example.com/a.mp3"));
        assertEquals("https://example.com/a.mp3",
                ShareUriPolicy.extractUrl("Song of the day\nhttps://example.com/a.mp3\nvia App"));
        assertEquals("https://example.com/a.mp3", ShareUriPolicy.extractUrl(" https://example.com/a.mp3"));
        assertEquals("https://example.com/a.mp3", ShareUriPolicy.extractUrl("\thttps://example.com/a.mp3"));
        assertEquals("https://example.com/a.mp3", ShareUriPolicy.extractUrl("(https://example.com/a.mp3)"));
    }

    @Test
    public void onlyTheFirstLinkIsUsedAndNothingAfterIt() {
        assertEquals("http://example.com/a.mp3",
                ShareUriPolicy.extractUrl("http://example.com/a.mp3\nfile:///sdcard/x"));
        assertEquals("http://one.example/a", ShareUriPolicy.extractUrl("http://one.example/a http://two.example/b"));
    }

    @Test
    public void aLinkInTheMiddleOfAWordIsNotALink() {
        assertNull(ShareUriPolicy.extractUrl("xhttp://example.com"));
        assertNull(ShareUriPolicy.extractUrl("Xhttps://example.com/a"));
    }

    @Test
    public void punctuationAfterTheLinkIsNotPartOfIt() {
        assertEquals("https://example.com/a.mp3", ShareUriPolicy.extractUrl("See https://example.com/a.mp3."));
        assertEquals("https://example.com/a.mp3", ShareUriPolicy.extractUrl("\"https://example.com/a.mp3\", please"));
        assertEquals("https://example.com/a.mp3", ShareUriPolicy.extractUrl("<https://example.com/a.mp3>"));
        assertEquals("https://example.com/wiki/A_(b)", ShareUriPolicy.extractUrl("https://example.com/wiki/A_(b)"));
    }

    @Test
    public void whiteSpaceEndsTheLink() {
        assertEquals("http://exa", ShareUriPolicy.extractUrl("http://exa mple.com"));
        assertEquals("http://example.com/a", ShareUriPolicy.extractUrl("http://example.com/a b"));
    }

    @Test
    public void controlCharactersEndTheLink() {
        assertEquals("http://example.com/a", ShareUriPolicy.extractUrl("http://example.com/a\u0000b"));
        assertEquals("http://example.com/a", ShareUriPolicy.extractUrl("http://example.com/a\rb"));
    }

    @Test
    public void textUpToTheCapIsAllowedAndLongerTextIsDropped() {
        String prefix = "https://example.com/";
        StringBuilder atCap = new StringBuilder(prefix);
        while (atCap.length() < ShareUriPolicy.MAX_TEXT_LENGTH) {
            atCap.append('a');
        }
        assertEquals(ShareUriPolicy.MAX_TEXT_LENGTH, atCap.length());
        assertEquals(atCap.toString(), ShareUriPolicy.extractUrl(atCap.toString()));
        assertNull(ShareUriPolicy.extractUrl(atCap + "a"));
    }

    @Test
    public void theHostIsTheRealOneAndInLowerCase() {
        assertEquals("example.com", ShareUriPolicy.hostOf("https://Example.COM/a"));
        assertEquals("example.com", ShareUriPolicy.hostOf("http://example.com:8080/a?x=1"));
        assertEquals("evil.example", ShareUriPolicy.hostOf("http://trusted.com@evil.example/a"));
        assertEquals("evil.example", ShareUriPolicy.hostOf("http://trusted.com:pw@evil.example:80"));
        assertEquals("192.168.1.5", ShareUriPolicy.hostOf("http://192.168.1.5/a"));
        assertEquals("fe80::1", ShareUriPolicy.hostOf("http://[fe80::1]:8080/a"));
        assertEquals("my_nas", ShareUriPolicy.hostOf("http://my_nas/a"));
        assertEquals("bücher.de", ShareUriPolicy.hostOf("https://Bücher.de/a"));
    }

    @Test
    public void aHostWithOddCharactersIsRejected() {
        assertNull(ShareUriPolicy.hostOf("http://exa<mple.com/a"));
        assertNull(ShareUriPolicy.hostOf("http://example.com\u202e/a"));
        assertNull(ShareUriPolicy.hostOf("http://[nonsense]/a"));
        assertNull(ShareUriPolicy.hostOf("http://[::1/a"));
        assertNull(ShareUriPolicy.hostOf("http://example.com:abc/a"));
        assertNull(ShareUriPolicy.hostOf("http://.example.com/a"));
        assertNull(ShareUriPolicy.hostOf("ftp://example.com/a"));
        assertNull(ShareUriPolicy.hostOf(null));
    }
}
