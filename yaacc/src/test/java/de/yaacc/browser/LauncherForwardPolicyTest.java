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

import de.yaacc.browser.LauncherForwardPolicy.Decision;

/**
 * Unit tests for {@link LauncherForwardPolicy}, the allowlist that decides what the exported
 * launcher activity may hand on to the browser: a plain launch, or a text/plain share with a
 * capped text. Everything else is a plain launch. Plain JVM, no Android runtime.
 */
public class LauncherForwardPolicyTest {

    private static final String MAIN = "android.intent.action.MAIN";
    private static final String VIEW = "android.intent.action.VIEW";
    private static final String SEND = "android.intent.action.SEND";
    private static final String TEXT = "https://example.org/song.mp3";

    private static void assertPlainLaunch(Decision decision) {
        assertFalse(decision.isSendText());
        assertNull(decision.getText());
    }

    @Test
    public void mainIsAPlainLaunch() {
        assertPlainLaunch(LauncherForwardPolicy.decide(MAIN, null, null));
    }

    @Test
    public void mainIgnoresTypeAndText() {
        assertPlainLaunch(LauncherForwardPolicy.decide(MAIN, "text/plain", TEXT));
    }

    @Test
    public void viewIsAPlainLaunch() {
        assertPlainLaunch(LauncherForwardPolicy.decide(VIEW, "text/plain", TEXT));
    }

    @Test
    public void nullActionIsAPlainLaunch() {
        assertPlainLaunch(LauncherForwardPolicy.decide(null, "text/plain", TEXT));
    }

    @Test
    public void unknownActionIsAPlainLaunch() {
        assertPlainLaunch(LauncherForwardPolicy.decide("com.evil.ACTION", "text/plain", TEXT));
    }

    @Test
    public void sendTextPlainWithTextForwardsTheText() {
        Decision decision = LauncherForwardPolicy.decide(SEND, "text/plain", TEXT);
        assertTrue(decision.isSendText());
        assertEquals(TEXT, decision.getText());
    }

    @Test
    public void sendTextPlainWithTextAtTheCapIsForwarded() {
        String text = repeat(ShareUriPolicy.MAX_TEXT_LENGTH);
        Decision decision = LauncherForwardPolicy.decide(SEND, "text/plain", text);
        assertTrue(decision.isSendText());
        assertEquals(text, decision.getText());
    }

    @Test
    public void sendTextPlainWithTextOverTheCapDropsTheText() {
        String text = repeat(ShareUriPolicy.MAX_TEXT_LENGTH + 1);
        assertPlainLaunch(LauncherForwardPolicy.decide(SEND, "text/plain", text));
    }

    @Test
    public void sendTextPlainWithNullTextIsAPlainLaunch() {
        assertPlainLaunch(LauncherForwardPolicy.decide(SEND, "text/plain", null));
    }

    @Test
    public void sendTextPlainWithEmptyTextIsAPlainLaunch() {
        assertPlainLaunch(LauncherForwardPolicy.decide(SEND, "text/plain", ""));
    }

    @Test
    public void sendOtherTypeIsAPlainLaunch() {
        assertPlainLaunch(LauncherForwardPolicy.decide(SEND, "image/png", TEXT));
    }

    @Test
    public void sendWithNullTypeIsAPlainLaunch() {
        assertPlainLaunch(LauncherForwardPolicy.decide(SEND, null, TEXT));
    }

    @Test
    public void sendWildcardTextTypeIsAPlainLaunch() {
        assertPlainLaunch(LauncherForwardPolicy.decide(SEND, "text/*", TEXT));
    }

    @Test
    public void typeIsMatchedExactlyNotCaseInsensitively() {
        // documented decision: the manifest filter and the share handler use lower case, so any
        // other spelling is not forwarded
        assertPlainLaunch(LauncherForwardPolicy.decide(SEND, "TEXT/PLAIN", TEXT));
    }

    @Test
    public void typeWithParametersIsAPlainLaunch() {
        assertPlainLaunch(LauncherForwardPolicy.decide(SEND, "text/plain; charset=utf-8", TEXT));
    }

    @Test
    public void actionIsMatchedExactly() {
        assertPlainLaunch(LauncherForwardPolicy.decide("android.intent.action.send", "text/plain", TEXT));
    }

    private static String repeat(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append('a');
        }
        return builder.toString();
    }
}
