/*
 *
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
package de.yaacc.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Source guards for code that needs an Android device to run (issue 319, Group 11D, S11 and S68): the window of the
 * Snapcast servers gives back the lease of the scan (and with it the multicast lock) on every path that ends it, and the
 * copied reports are marked as sensitive. Plain JVM: the sources are read from the module directory with the comments
 * stripped, in the style of {@code SnapcastClientServiceOrderGuardTest}.
 */
public class SnapcastServersDialogLifecycleGuardTest {

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

    private static final String DIALOG = "de/yaacc/settings/SnapcastServersDialog.java";

    @Test
    public void closeScanGivesBackEverythingTheOpenWindowHolds() throws Exception {
        String closeScan = body(code(DIALOG), "private void closeScan(");

        assertTrue("the loop flag", closeScan.contains("shown = false;"));
        assertTrue("the refresh loop", closeScan.contains("main.removeCallbacks(reportLoop);"));
        assertTrue("the lease of the scan (and the multicast lock)", closeScan.contains("lease.close();"));
        assertTrue("the lease is dropped, so a second call can not close it twice", closeScan.contains("lease = null;"));
        assertTrue("the observer of the screen", closeScan.contains("removeObserver("));
        assertTrue("the worker", closeScan.contains("worker.shutdown();"));
    }

    @Test
    public void closeScanClosesTheLeaseBeforeItDropsIt() throws Exception {
        String closeScan = body(code(DIALOG), "private void closeScan(");

        assertTrue(closeScan.indexOf("lease.close();") >= 0);
        assertTrue(closeScan.indexOf("lease.close();") < closeScan.indexOf("lease = null;"));
    }

    @Test
    public void theDismissOfTheWindowEndsTheScan() throws Exception {
        String code = code(DIALOG);

        assertTrue(code.contains("dialog.setOnDismissListener(d -> closeScan());"));
    }

    @Test
    public void theStopAndTheDestroyOfTheScreenEndTheScanAndTheWindow() throws Exception {
        String observe = body(code(DIALOG), "private void observeScreen(");

        assertTrue(observe.contains("Lifecycle.Event.ON_STOP"));
        assertTrue(observe.contains("Lifecycle.Event.ON_DESTROY"));
        assertTrue("the scan ends", observe.contains("closeScan();"));
        assertTrue("the window goes", observe.contains("dialog.dismiss();"));
        assertTrue("the observer is added to the lifecycle of the screen", observe.contains(".addObserver("));
        assertTrue(observe.indexOf("closeScan();") < observe.indexOf("dialog.dismiss();"));
    }

    @Test
    public void theRefreshLoopEndsTheScanWhenTheWindowIsGoneWithoutADismiss() throws Exception {
        String code = code(DIALOG);
        int start = code.indexOf("new Runnable()");
        String loop = body(code.substring(start), "public void run(");

        assertTrue(loop.contains("isAttachedToWindow()"));
        assertTrue(loop.contains("closeScan();"));
        assertTrue("the first tick is exempt: the first traversal has not run yet", loop.contains("ticks++ > 0"));
    }

    @Test
    public void showTakesTheLeaseOnceAndTiesTheWindowToTheScreen() throws Exception {
        String show = body(code(DIALOG), "static void show(");

        assertTrue(show.contains("diagnosticsOpened()"));
        assertTrue(show.contains("observeScreen();"));
        assertEquals("one lease per window", 1, show.split("diagnosticsOpened\\(\\)", -1).length - 1);
        assertTrue("the lease is held before the loop starts", show.indexOf("diagnosticsOpened()") < show.indexOf("reportLoop.run();"));
    }

    // ---- S68: the copied reports are marked as sensitive (Android 13 and newer keep them out of the paste preview)

    @Test
    public void bothReportsAreCopiedAsSensitiveClips() throws Exception {
        for (String file : new String[]{DIALOG, "de/yaacc/settings/SnapcastClientSpikeDialog.java"}) {
            String code = code(file);
            assertTrue(file, code.contains("SensitiveClip.plainText("));
            assertFalse(file + " builds a plain clip", code.contains("ClipData.newPlainText("));
        }
    }

    @Test
    public void theSensitiveClipSetsTheFlagOnTheDescriptionOnlyWhereTheSystemKnowsIt() throws Exception {
        String clip = code("de/yaacc/settings/SensitiveClip.java");

        assertTrue(clip.contains("ClipDescription.EXTRA_IS_SENSITIVE"));
        assertTrue(clip.contains("putBoolean("));
        assertTrue(clip.contains("getDescription().setExtras("));
        assertTrue("the constant exists from API 33", clip.contains("Build.VERSION_CODES.TIRAMISU"));
    }
}
