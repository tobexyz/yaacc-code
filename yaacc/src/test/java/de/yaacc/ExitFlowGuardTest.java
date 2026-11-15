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
package de.yaacc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source guards for the exit flow (tasks 12.1, 12.2, 12.9): the parts that need Android (the application object, the services, the
 * activities) have no JVM test, so these pin how they are wired to the pure classes ({@code ExitSequence}, {@code ExitRestartGuard}).
 * Comments are stripped before anything is looked for.
 */
public class ExitFlowGuardTest {

    private static final File SOURCES = new File("src/main/java/de/yaacc");

    private static String code(String relativePath) throws Exception {
        File file = new File(SOURCES, relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** The text of the method starting at the signature, from its opening brace to the matching closing brace. */
    private static String body(String code, String signature) {
        int start = code.indexOf(signature);
        assertTrue("method not found: " + signature, start >= 0);
        int open = code.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced: " + signature);
    }

    private static void assertInOrder(String text, String... markers) {
        int last = -1;
        for (String marker : markers) {
            int at = text.indexOf(marker);
            assertTrue("missing: " + marker, at >= 0);
            assertTrue(marker + " must come after the one before it", at > last);
            last = at;
        }
    }

    // ---- 12.2 and 12.9: Yaacc.exit ---------------------------------------------------------------------------------

    @Test
    public void exitIsBuiltFromTheExitSequenceInTheOrderPlayersServicesNotificationsEnd() throws Exception {
        String yaacc = code("Yaacc.java");
        String exit = body(yaacc, "public void exit()");
        assertTrue("exit() builds an ExitSequence", exit.contains("new ExitSequence()"));
        assertInOrder(exit, "ExitSequence.STEP_MARK", "ExitSequence.STEP_PLAYERS", "ExitSequence.STEP_SERVICES",
                "ExitSequence.STEP_NOTIFICATIONS", "ExitSequence.STEP_TASKS");
        // the end of the process is a step of its own, after the notifications once more, and the only place that ends it
        assertInOrder(yaacc, "ExitSequence.STEP_PLAYERS", "ExitSequence.STEP_SERVICES", "ExitSequence.STEP_NOTIFICATIONS",
                "ExitSequence.STEP_END");
        Matcher ends = Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exit").matcher(yaacc);
        assertTrue(ends.find());
        assertFalse("only one place ends the process", ends.find());
        assertTrue("the process end is after STEP_END", yaacc.indexOf("Runtime.getRuntime().exit") > yaacc.indexOf("ExitSequence.STEP_END"));
        assertFalse("exit() itself does not end the process", exit.contains("Runtime.getRuntime().exit"));
    }

    @Test
    public void exitMarksTheExitBeforeAnythingElseIsStopped() throws Exception {
        String exit = body(code("Yaacc.java"), "public void exit()");
        assertInOrder(exit, "ExitSequence.STEP_MARK", "ExitSequence.STEP_PLAYERS");
        assertTrue(exit.contains("markExitRequested"));
    }

    @Test
    public void exitExitsEveryActivePlayerAndLetsTheNotificationsGo() throws Exception {
        String yaacc = code("Yaacc.java");
        assertTrue("players are exited one by one, each in its own step", yaacc.contains("getCurrentPlayers()"));
        assertTrue(yaacc.contains("player::exit") && yaacc.contains("ExitSequence.STEP_PLAYERS"));
        assertTrue("every notification of the app is cancelled, not three ids", yaacc.contains("cancelAll()"));
        assertTrue("the services are stopped", yaacc.contains("stopService(new Intent(this, PlayerService.class))")
                && yaacc.contains("stopService(new Intent(this, YaaccUpnpServerService.class))"));
        assertTrue("the client lets go of its bindings, or the server service can not end", yaacc.contains("unbindServices()"));
    }

    @Test
    public void exitWaitsForTheServicesAndFeedsBeforeTheProcessEndsBoundedAndWithoutBlockingTheMainThread() throws Exception {
        String yaacc = code("Yaacc.java");
        assertTrue(yaacc.contains("ExitSequence.shouldKeepWaiting("));
        assertTrue("the wait is a posted poll, never a sleep on the main thread", yaacc.contains("postDelayed("));
        assertFalse(yaacc.contains("Thread.sleep"));
        assertTrue("an open Snapcast feed is waited for", yaacc.contains("openFeedControllers()"));
    }

    @Test
    public void exitRunsOnTheMainThreadAndOnlyOnce() throws Exception {
        String exit = body(code("Yaacc.java"), "public void exit()");
        assertTrue(exit.contains("Looper.getMainLooper()"));
        assertTrue(exit.contains("compareAndSet(false, true)"));
    }

    @Test
    public void noOtherCodeEndsTheProcess() throws Exception {
        List<String> offenders = new ArrayList<>();
        scan(SOURCES, offenders);
        assertEquals("only Yaacc ends the process: " + offenders, 0, offenders.size());
    }

    private static void scan(File dir, List<String> offenders) throws Exception {
        File[] list = dir.listFiles();
        assertTrue(list != null);
        for (File file : list) {
            if (file.isDirectory()) {
                scan(file, offenders);
            } else if (file.getName().endsWith(".java") && !file.getName().equals("Yaacc.java")) {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
                if (text.contains("Runtime.getRuntime().exit") || text.contains("System.exit(")
                        || text.contains("Process.killProcess(")) {
                    offenders.add(file.getName());
                }
            }
        }
    }

    // ---- 12.2: the start side ---------------------------------------------------------------------------------------

    @Test
    public void theServicesStopThemselvesOnAStickyRestartAfterAnExitBeforeTheyShowAnyNotification() throws Exception {
        String player = body(code("player/PlayerService.java"), "public int onStartCommand(");
        assertInOrder(player, "ExitRestartGuards", "StartReason.SYSTEM_RESTART", "stopSelf()", "createYaaccGroupNotification()");
        assertTrue(player.contains("intent == null"));
        String server = body(code("upnp/server/YaaccUpnpServerService.java"), "public int onStartCommand(");
        assertInOrder(server, "ExitRestartGuards", "StartReason.SYSTEM_RESTART", "stopSelf()", "showNotification()");
        assertTrue(server.contains("intent == null"));
    }

    @Test
    public void theUpnpServerServiceCanBeDestroyedBeforeItWasStarted() throws Exception {
        String destroy = body(code("upnp/server/YaaccUpnpServerService.java"), "public void onDestroy()");
        assertTrue("a service that stopped itself has no network listener yet", destroy.contains("networkDeviceListener != null"));
    }

    @Test
    public void theApplicationDoesNotStartTheServerRightAfterAnExitOnItsOwn() throws Exception {
        String onCreate = body(code("Yaacc.java"), "public void onCreate()");
        assertInOrder(onCreate, "StartReason.PROCESS_START", "startService(new Intent(this, YaaccUpnpServerService.class))");
    }

    @Test
    public void everyWayTheUserStartsTheAppClearsTheMark() throws Exception {
        String yaacc = code("Yaacc.java");
        assertTrue("one callback for every screen of the app, exported ones included",
                body(yaacc, "public void onCreate()").contains("registerActivityLifecycleCallbacks(new UserStartCallbacks())"));
        String created = body(yaacc, "public void onActivityCreated(");
        assertInOrder(created, "savedInstanceState == null", "userStarted()", "dropRestoredScreen()", "finish()");
        assertTrue(body(yaacc, "public void userStarted()").contains("clearOnUserStart()"));
        assertTrue(body(yaacc, "public void userStarted()").contains("bindServerService()"));
        // the browser activities do not build a screen that is left over from before the exit
        assertTrue(code("browser/BrowserActivity.java").contains("dropRestoredScreen()"));
        assertTrue(code("browser/TabBrowserActivity.java").contains("dropRestoredScreen()"));
    }

    @Test
    public void theBootReceiverIsNotBlocked() throws Exception {
        assertFalse(code("upnp/server/ServerAutostart.java").contains("ExitRestartGuard"));
    }

    // ---- 12.1: the way out of the app -------------------------------------------------------------------------------

    @Test
    public void backNeverExitsTheApp() throws Exception {
        assertFalse(code("browser/ServerListFragment.java").contains(".exit()"));
        String browserBack = body(code("browser/BrowserActivity.java"), "private void onBack()");
        assertFalse(browserBack.contains(".exit()"));
        assertTrue(browserBack.contains("leaveToBackground()"));
        assertTrue(body(code("browser/BrowserActivity.java"), "private void leaveToBackground()").contains("moveTaskToBack(true)"));
        String tabBack = body(code("browser/TabBrowserActivity.java"), "public void onBackPressed()");
        assertFalse(tabBack.contains(".exit()"));
        assertTrue(tabBack.contains("moveTaskToBack(true)"));
    }

    @Test
    public void theExplicitExitPathsStillEndTheApp() throws Exception {
        assertTrue(code("ExitBroadcastReceiver.java").contains("exit()"));
        assertTrue(code("browser/BrowserActivitySupport.java").contains("R.id.menu_exit")
                && code("browser/BrowserActivitySupport.java").contains(".exit()"));
        assertTrue(code("browser/BackgroundIdleTimer.java").contains("yaacc.exit()"));
        assertTrue("the shutdown timer exits", body(code("Yaacc.java"), "public void onFinish()").contains("exit()"));
    }
}
