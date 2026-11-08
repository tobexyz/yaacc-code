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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/** The notice for a shared file that is not taken: when it shows and that every locale has it. Plain JVM. */
public class RejectedFileShareNoticeTest {

    @Test
    public void aSendWithAStreamOrAClipUriIsAFileShareAttempt() {
        assertTrue(LauncherForwardPolicy.isFileShareAttempt("android.intent.action.SEND", true, false));
        assertTrue(LauncherForwardPolicy.isFileShareAttempt("android.intent.action.SEND", false, true));
        assertTrue(LauncherForwardPolicy.isFileShareAttempt("android.intent.action.SEND", true, true));
    }

    @Test
    public void aTextShareOrAnotherActionIsNotOne() {
        assertFalse(LauncherForwardPolicy.isFileShareAttempt("android.intent.action.SEND", false, false));
        assertFalse(LauncherForwardPolicy.isFileShareAttempt("android.intent.action.VIEW", true, true));
        assertFalse(LauncherForwardPolicy.isFileShareAttempt(null, true, true));
    }

    @Test
    public void everyLocaleHasTheNotice() throws Exception {
        for (String dir : new String[]{"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt",
                "values-zh"}) {
            File strings = new File("src/main/res/" + dir + "/strings.xml");
            assertTrue(strings.isFile());
            assertTrue(dir, Pattern.compile("<string name=\"share_not_accepted\">[^<]+</string>")
                    .matcher(new String(Files.readAllBytes(strings.toPath()), StandardCharsets.UTF_8)).find());
        }
    }
}
