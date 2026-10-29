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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link LauncherForwardPolicy#isStreamType}: which path (file or text) a declared type selects. */
public class LauncherForwardPolicyStreamTypeTest {

    @Test
    public void audioVideoAndImageTypesSelectTheFilePath() {
        assertTrue(LauncherForwardPolicy.isStreamType("audio/mpeg"));
        assertTrue(LauncherForwardPolicy.isStreamType("video/mp4"));
        assertTrue(LauncherForwardPolicy.isStreamType("image/png"));
        assertTrue(LauncherForwardPolicy.isStreamType("audio/*"));
        assertTrue(LauncherForwardPolicy.isStreamType("image/*"));
    }

    @Test
    public void textAndEverythingElseDoNot() {
        assertFalse(LauncherForwardPolicy.isStreamType("text/plain"));
        assertFalse(LauncherForwardPolicy.isStreamType("*/*"));
        assertFalse(LauncherForwardPolicy.isStreamType("application/octet-stream"));
        assertFalse(LauncherForwardPolicy.isStreamType("Audio/MP3"));
        assertFalse(LauncherForwardPolicy.isStreamType("audio/mpeg; x=y"));
        assertFalse(LauncherForwardPolicy.isStreamType(""));
        assertFalse(LauncherForwardPolicy.isStreamType(null));
    }

    @Test
    public void theTextAndTheFilePathNeverBothClaimAType() {
        for (String type : new String[]{"text/plain", "audio/mpeg", "video/mp4", "image/png", null, "*/*"}) {
            boolean text = "text/plain".equals(type);
            assertFalse(type, text && LauncherForwardPolicy.isStreamType(type));
        }
    }
}
