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
        assertInOrder(exit, "ExitSequence.STEP_MARK", "ExitSequence.STEP_SHARES", "ExitSequence.STEP_SNAPCAST_NOTE",
                "exitPlayersThenContinue()");
        // the whole order, as the steps are built: mark, shares, the Snapcast ending, players, services, notifications, tasks, end
        assertInOrder(yaacc, "ExitSequence.STEP_MARK", "ExitSequence.STEP_SHARES", "ExitSequence.STEP_SNAPCAST_NOTE",
                "ExitSequence.STEP_PLAYERS", "ExitSequence.STEP_SERVICES", "ExitSequence.STEP_NOTIFICATIONS",
                "ExitSequence.STEP_TASKS", "ExitSequence.STEP_END");
        // the end of the process is a step of its own, after the notifications once more, and the only place that ends it
        Matcher ends = Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exit").matcher(yaacc);
        assertTrue(ends.find());
        assertFalse("only one place ends the process", ends.find());
        assertTrue("the process end is after STEP_END", yaacc.indexOf("Runtime.getRuntime().exit") > yaacc.indexOf("ExitSequence.STEP_END"));
        assertFalse("exit() itself does not end the process", exit.contains("Runtime.getRuntime().exit"));
        String rest = body(yaacc, "private void continueExit(");
        assertInOrder(rest, "ExitSequence.STEP_SERVICES", "ExitSequence.STEP_NOTIFICATIONS", "ExitSequence.STEP_TASKS");
    }

    @Test
    public void theSnapcastEndingIsRecordedBeforeTheServicesAndTheProcessEnd() throws Exception {
        String yaacc = code("Yaacc.java");
        assertTrue(body(yaacc, "public void exit()").contains("this::recordSnapcastClientEnding"));
        assertTrue(yaacc.indexOf("this::recordSnapcastClientEnding") < yaacc.indexOf("ExitSequence.STEP_SERVICES"));
        assertTrue(yaacc.indexOf("this::recordSnapcastClientEnding") < yaacc.indexOf("Runtime.getRuntime().exit"));
    }

    /**
     * Review W2: ending a remote player sleeps and polls (up to 30 s) on the thread that does it. No step of the exit may sleep or wait
     * on the main thread, and the players end on threads of their own.
     */
    @Test
    public void noStepOfTheExitSleepsOrWaitsOnTheMainThread() throws Exception {
        for (String file : new String[]{"Yaacc.java", "util/ExitSequence.java", "util/ExitHandoff.java"}) {
            String code = code(file);
            for (String blocking : new String[]{"Thread.sleep(", ".join(", ".await(", ".wait(", "awaitTermination(", "SystemClock.sleep("}) {
                assertFalse(file + " must not use " + blocking, code.contains(blocking));
            }
        }
        String yaacc = code("Yaacc.java");
        assertFalse("the players are ended by the hand-over only", yaacc.contains("playerService.shutdown("));
        assertFalse("client.shutdown() ends the players on the calling (main) thread", yaacc.contains("client.shutdown()"));
        assertFalse(yaacc.contains(".onDestroy()"));
        String starter = body(yaacc, "private void exitPlayersThenContinue()");
        assertTrue(starter.contains("ExitHandoff.run("));
        assertTrue("the player's exit is a step handed to the hand-over", starter.contains("player::exit"));
        assertFalse("not called directly", starter.contains("player.exit()"));
        assertTrue(code("util/ExitHandoff.java").contains("workers.execute("));
        // the player service is stopped only when every player ended: its onDestroy would end the rest on the main thread
        String stop = body(yaacc, "private void stopServices(boolean playersEnded)");
        assertTrue(stop.indexOf("if (playersEnded)") < stop.indexOf("stopService(new Intent(this, PlayerService.class))"));
    }

    @Test
    public void aUserStartWhileTheExitRunsIsIgnored() throws Exception {
        String started = body(code("Yaacc.java"), "public void userStarted()");
        assertInOrder(started, "exiting.get()", "clearOnUserStart()");
    }

    @Test
    public void theGuardIsOneObjectPerProcess() throws Exception {
        String guards = code("util/ExitRestartGuards.java");
        assertTrue(guards.contains("private static ExitRestartGuard shared"));
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
        assertInOrder(player, "ExitRestartGuards", "StartReason.SYSTEM_RESTART", "stopRestartedService(", "createYaaccGroupNotification()");
        assertTrue(player.contains("intent == null"));
        String server = body(code("upnp/server/YaaccUpnpServerService.java"), "public int onStartCommand(");
        assertInOrder(server, "ExitRestartGuards", "StartReason.SYSTEM_RESTART", "stopRestartedService(", "showNotification()");
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
        String tabBack = body(code("browser/TabBrowserActivity.java"), "private void onBack()");
        assertFalse(tabBack.contains(".exit()"));
        assertTrue(tabBack.contains("moveTaskToBack(true)"));
    }

    /** With predictive back (the default from target SDK 36) an override of onBackPressed() is not called, a dispatcher callback is. */
    @Test
    public void bothBrowserActivitiesHandleBackThroughTheDispatcher() throws Exception {
        for (String file : new String[]{"browser/BrowserActivity.java", "browser/TabBrowserActivity.java"}) {
            String code = code(file);
            assertTrue(file, code.contains("getOnBackPressedDispatcher().addCallback(this, backPressedCallback)"));
            assertFalse(file + " has no onBackPressed override that predictive back would skip", code.contains("public void onBackPressed()"));
        }
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
