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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source guards for the autostart on boot (tasks 12.14 and 12.18): the parts that need Android (the application object, the manifest,
 * the settings screen, the idle timer) have no JVM test, so these pin how they are wired to the pure classes
 * ({@code AutostartReceiverState}, {@code AutoCloseRule}, {@code StartedBrowserCounter}). Comments are stripped before anything is
 * looked for in Java; the XML files are read as text.
 */
public class AutostartGuardTest {

    private static final File MAIN = new File("src/main");
    private static final String AUTOSTART_KEY = "settings_local_server_autostart_chkbx";

    private static String text(String relativePath) throws Exception {
        File file = new File(MAIN, relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String code(String relativePath) throws Exception {
        return text("java/de/yaacc/" + relativePath).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /**
     * The text of the method starting at the signature, from its opening brace to the matching closing brace.
     */
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

    // ---- the setting is always selectable ---------------------------------------------------------------------------

    @Test
    public void theAutostartEntryDoesNotDependOnAnotherEntry() throws Exception {
        String xml = text("res/xml/preference.xml");
        Matcher entry = Pattern.compile("<CheckBoxPreference[^>]*?settings_autostart_chkbx[^>]*?/>", Pattern.DOTALL).matcher(xml);
        assertTrue("the autostart entry exists", entry.find());
        assertFalse("always selectable: no dependency", entry.group().contains("android:dependency"));
        assertFalse("the entry has no default of its own besides off", entry.group().contains("defaultValue=\"true\""));
        assertFalse("no second entry for the same key", entry.find());
    }

    // ---- the boot receiver is off until the setting is ticked -------------------------------------------------------

    @Test
    public void theManifestReceiverIsDisabledByDefault() throws Exception {
        String manifest = text("AndroidManifest.xml");
        Matcher receiver = Pattern.compile("<receiver[^>]*?\\.upnp\\.server\\.ServerAutostart\"[^>]*?>", Pattern.DOTALL).matcher(manifest);
        assertTrue("the receiver is declared", receiver.find());
        assertTrue("disabled by default: the setting enables it", receiver.group().contains("android:enabled=\"false\""));
        assertFalse(receiver.group().contains("android:enabled=\"true\""));
        assertTrue("still listens for BOOT_COMPLETED", manifest.contains("android.intent.action.BOOT_COMPLETED"));
    }

    @Test
    public void theReceiverComponentIsSwitchedByTheExplicitStateWithoutKillingTheApp() throws Exception {
        String receiver = code("upnp/server/ServerAutostart.java");
        String apply = body(receiver, "public static void applySetting(");
        assertTrue(apply.contains("setComponentEnabledSetting("));
        assertTrue(apply.contains("AutostartReceiverState.componentState("));
        assertTrue(apply.contains("DONT_KILL_APP"));
        assertTrue("a refused change is caught, not a crash", apply.contains("catch"));
        // the receiver itself is as before: it checks the preference as defence and starts the service
        String onReceive = body(receiver, "public void onReceive(");
        assertTrue(onReceive.contains("settings_autostart_chkbx"));
        assertTrue(onReceive.contains("startForegroundService("));
    }

    // ---- Yaacc.onCreate is the startup route ------------------------------------------------------------------------

    @Test
    public void onCreateSyncsTheReceiverStateFromTheSetting() throws Exception {
        String onCreate = body(code("Yaacc.java"), "public void onCreate()");
        assertTrue(onCreate.contains("ServerAutostart.applySetting("));
        assertTrue("reads the setting, not a constant", onCreate.contains("settings_autostart_chkbx"));
    }

    @Test
    public void onCreateStartsTheServiceInATryCatch() throws Exception {
        String onCreate = body(code("Yaacc.java"), "public void onCreate()");
        Matcher start = Pattern.compile("try\\s*\\{\\s*startService\\(new Intent\\(this, YaaccUpnpServerService\\.class\\)\\);\\s*\\}\\s*catch\\s*\\(([A-Za-z.]+)").matcher(onCreate);
        assertTrue("startService is wrapped in a try/catch", start.find());
        assertTrue("a background start refusal is an IllegalStateException (or a wider one)",
                start.group(1).equals("IllegalStateException") || start.group(1).equals("RuntimeException"));
        assertFalse("only that start is guarded here", start.find());
    }

    @Test
    public void onCreateArmsTheIdleTimerAtTheEndAlsoAfterTheDeferredBranch() throws Exception {
        String onCreate = body(code("Yaacc.java"), "public void onCreate()");
        assertEquals("armed once", 1, onCreate.split("BackgroundIdleTimer\\.processStarted\\(", -1).length - 1);
        int armed = onCreate.indexOf("BackgroundIdleTimer.processStarted(");
        // after both branches (the deferred one and the normal one), so it is the last thing the method does
        assertTrue(armed > onCreate.indexOf("startDeferredAfterExit = true"));
        assertTrue(armed > onCreate.indexOf("new UpnpClient(this)"));
        assertTrue("not inside the else branch: nothing but the closing brace follows", onCreate.substring(armed).matches("(?s)[^}]*\\}\\s*"));
    }

    // ---- the idle timer ---------------------------------------------------------------------------------------------

    @Test
    public void theIdleTimerEntryForTheProcessStartUsesTheCounter() throws Exception {
        String timer = code("browser/BackgroundIdleTimer.java");
        String entry = body(timer, "public static void processStarted(");
        assertTrue(entry.contains("onProcessStarted()"));
        assertTrue(body(timer, "public static void browserStarted(").contains("onBrowserStarted()"));
    }

    @Test
    public void theIdleCheckReadsTheAutostartSettingIntoTheFacts() throws Exception {
        String exitIfIdle = body(code("browser/BackgroundIdleTimer.java"), "private static void exitIfIdle(");
        assertTrue(exitIfIdle.contains("IdleFacts.of("));
        assertTrue(exitIfIdle.contains("settings_autostart_chkbx"));
        assertInOrder(exitIfIdle, "settings_autostart_chkbx", "AutoCloseRule.reasonToStay(facts)");
        assertInOrder(exitIfIdle, "AutoCloseRule.reasonToStay(facts)", "Idle in background, exiting", "Idle in background, staying open: ");
    }

    // ---- the settings screen ----------------------------------------------------------------------------------------

    @Test
    public void changingTheSettingSwitchesTheReceiverAtOnce() throws Exception {
        String fragment = code("settings/SettingsFragment.java");
        assertTrue(fragment.contains("settings_autostart_chkbx"));
        assertInOrder(fragment, "settings_autostart_chkbx", "ServerAutostart.applySetting(");
    }
}
