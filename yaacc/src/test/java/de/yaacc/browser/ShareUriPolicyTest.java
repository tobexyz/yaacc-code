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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ShareUriPolicy}: which shared texts may be turned into a playable URI.
 * Plain JVM.
 */
public class ShareUriPolicyTest {

    @Test
    public void httpAndHttpsAreAllowed() {
        assertTrue(ShareUriPolicy.isAllowed("http://example.com/a.mp3"));
        assertTrue(ShareUriPolicy.isAllowed("https://example.com/a.mp4?x=1#t"));
    }

    @Test
    public void schemeIsMatchedIgnoringCase() {
        assertTrue(ShareUriPolicy.isAllowed("HTTP://example.com/a.mp3"));
        assertTrue(ShareUriPolicy.isAllowed("HtTpS://example.com/a.mp3"));
    }

    @Test
    public void localSchemesAreRejected() {
        assertFalse(ShareUriPolicy.isAllowed("file:///sdcard/Music/a.mp3"));
        assertFalse(ShareUriPolicy.isAllowed("FILE:///sdcard/Music/a.mp3"));
        assertFalse(ShareUriPolicy.isAllowed("content://media/external/audio/media/1"));
    }

    @Test
    public void scriptAndOtherSchemesAreRejected() {
        assertFalse(ShareUriPolicy.isAllowed("javascript:alert(1)"));
        assertFalse(ShareUriPolicy.isAllowed("ftp://example.com/a.mp3"));
        assertFalse(ShareUriPolicy.isAllowed("intent://x#Intent;end"));
        assertFalse(ShareUriPolicy.isAllowed("httpx://example.com"));
        assertFalse(ShareUriPolicy.isAllowed("http.evil://example.com"));
    }

    @Test
    public void nullAndEmptyAreRejected() {
        assertFalse(ShareUriPolicy.isAllowed(null));
        assertFalse(ShareUriPolicy.isAllowed(""));
    }

    @Test
    public void bareAndRelativePathsAreRejected() {
        assertFalse(ShareUriPolicy.isAllowed("/sdcard/Music/a.mp3"));
        assertFalse(ShareUriPolicy.isAllowed("Music/a.mp3"));
        assertFalse(ShareUriPolicy.isAllowed("example.com/a.mp3"));
        assertFalse(ShareUriPolicy.isAllowed("//example.com/a.mp3"));
        // a colon after a path segment must not make "/sdcard/http" look like a scheme
        assertFalse(ShareUriPolicy.isAllowed("/sdcard/http://x"));
    }

    @Test
    public void whitespaceAroundTheSchemeIsRejected() {
        assertFalse(ShareUriPolicy.isAllowed(" "));
        assertFalse(ShareUriPolicy.isAllowed(" https://example.com/a.mp3"));
        assertFalse(ShareUriPolicy.isAllowed("\thttps://example.com/a.mp3"));
        assertFalse(ShareUriPolicy.isAllowed("\nhttps://example.com/a.mp3"));
        assertFalse(ShareUriPolicy.isAllowed("https ://example.com/a.mp3"));
        assertFalse(ShareUriPolicy.isAllowed("Listen to https://example.com/a.mp3"));
    }

    @Test
    public void schemeWithoutSeparatorIsRejected() {
        assertFalse(ShareUriPolicy.isAllowed("http"));
        assertFalse(ShareUriPolicy.isAllowed("https"));
    }

    @Test
    public void textUpToTheCapIsAllowedAndLongerTextIsDropped() {
        String prefix = "https://example.com/";
        StringBuilder atCap = new StringBuilder(prefix);
        while (atCap.length() < ShareUriPolicy.MAX_TEXT_LENGTH) {
            atCap.append('a');
        }
        assertEquals(ShareUriPolicy.MAX_TEXT_LENGTH, atCap.length());
        assertTrue(ShareUriPolicy.isAllowed(atCap.toString()));
        assertFalse(ShareUriPolicy.isAllowed(atCap + "a"));
    }
}
