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
package de.yaacc.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** The logcat command of the log viewer and its "clear" marker. Plain JVM. */
public class LogCommandTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    // 2026-10-27 21:30:15.123 UTC
    private static final long MARK = 1793136615123L;

    @Test
    public void withoutAMarkerTheWholeBufferIsDumped() {
        assertArrayEquals(new String[]{"logcat", "-d", "*:W"}, LogCommand.build("W", 0L, UTC));
    }

    @Test
    public void aMarkerShowsOnlyEntriesSinceThen() {
        assertArrayEquals(new String[]{"logcat", "-d", "-T", "10-27 21:30:15.123", "*:E"},
                LogCommand.build("E", MARK, UTC));
    }

    @Test
    public void theMarkerIsPrintedInTheTimeZoneOfTheDevice() {
        assertEquals("10-27 23:30:15.123",
                LogCommand.build("E", MARK, TimeZone.getTimeZone("GMT+02:00"))[3]);
    }

    @Test
    public void anUnknownLevelFallsBackToErrors() {
        for (String bad : new String[]{null, "", "X", "EE", "e", "E; rm -rf /"}) {
            assertEquals("*:E", LogCommand.build(bad, 0L, UTC)[2]);
        }
    }

    @Test
    public void everyKnownLevelIsKept() {
        for (String level : new String[]{"V", "D", "I", "W", "E", "F"}) {
            assertEquals("*:" + level, LogCommand.build(level, 0L, UTC)[2]);
        }
    }

    @Test
    public void theClearCommandEmptiesTheBuffer() {
        assertArrayEquals(new String[]{"logcat", "-c"}, LogCommand.clearBuffer());
    }

    @Test
    public void theLogViewerHasAClearActionInEveryLocale() throws Exception {
        File menu = new File("src/main/res/menu/activity_yaacc_log.xml");
        assertTrue(menu.isFile());
        assertTrue(new String(Files.readAllBytes(menu.toPath()), StandardCharsets.UTF_8)
                .contains("@+id/menu_clear_log"));
        for (String dir : new String[]{"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt",
                "values-zh"}) {
            File strings = new File("src/main/res/" + dir + "/strings.xml");
            assertTrue(strings.isFile());
            assertTrue(dir, Pattern.compile("<string name=\"clear_log\">[^<]+</string>")
                    .matcher(new String(Files.readAllBytes(strings.toPath()), StandardCharsets.UTF_8)).find());
        }
    }
}
