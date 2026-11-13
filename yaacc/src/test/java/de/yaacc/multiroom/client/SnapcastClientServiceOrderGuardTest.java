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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Order guards for code that needs an Android device to run (issue 319, S25 and task 11.5): the service gives its locks
 * back <b>before</b> it writes the ending (the preference write, the trace line and the Binder notification can block or
 * throw, and a lock held meanwhile keeps the phone awake for nothing), and the background idle timer asks the pure rule
 * with the Snapcast facts. Plain JVM: the sources are read from the module directory with the comments stripped.
 */
public class SnapcastClientServiceOrderGuardTest {

    private static String code(String relative) throws Exception {
        File file = new File("src/main/java/" + relative);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** The text of the method that starts at {@code signature}, up to the closing brace at the same depth. */
    private static String body(String code, String signature) {
        int start = code.indexOf(signature);
        assertTrue("missing " + signature, start >= 0);
        int open = code.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced " + signature);
    }

    @Test
    public void endSessionReleasesTheLocksBeforeItRecordsTheEnding() throws Exception {
        String endSession = body(code("de/yaacc/multiroom/client/SnapcastClientService.java"),
                "private void endSession(");
        int release = endSession.indexOf("releaseLocks();");
        int record = endSession.indexOf("recordEnding(ending);");
        assertTrue("endSession releases the locks", release >= 0);
        assertTrue("endSession records the ending", record >= 0);
        assertTrue("locks first, then the record", release < record);
    }

    @Test
    public void onDestroyReleasesTheLocksBeforeItRecordsTheEnding() throws Exception {
        String onDestroy = body(code("de/yaacc/multiroom/client/SnapcastClientService.java"), "public void onDestroy(");
        int release = onDestroy.indexOf("releaseLocks();");
        int record = onDestroy.indexOf("recordEnding(");
        assertTrue("onDestroy releases the locks", release >= 0);
        assertTrue("onDestroy records the ending", record >= 0);
        assertTrue("locks first, then the record", release < record);
    }

    @Test
    public void theBackgroundIdleTimerAsksTheRuleWithTheSnapcastFacts() throws Exception {
        String timer = code("de/yaacc/browser/BackgroundIdleTimer.java");
        assertTrue(timer.contains("AutoCloseRule.reasonToStay("));
        assertTrue(timer.contains("SnapcastClientRuntime.isActive()"));
        assertTrue(timer.contains("SnapcastPlayer"));
    }

    @Test
    public void theAppWritesTheEndingOfTheClientBeforeItEnds() throws Exception {
        String exit = body(code("de/yaacc/Yaacc.java"), "public void exit(");
        int record = exit.indexOf("recordSnapcastClientEnding();");
        int end = exit.indexOf("Runtime.getRuntime().exit(0)");
        assertTrue(record >= 0 && end > record);
    }
}
