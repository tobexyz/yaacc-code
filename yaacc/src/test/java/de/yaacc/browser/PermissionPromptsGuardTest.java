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

/**
 * Task 12.3: the startup code asks through {@link PermissionRequestPlan} and nothing else. The Android glue
 * ({@code BrowserActivitySupport}) has no JVM test, so this pins that the old list (which held permissions that can never be granted,
 * so that the request came at every start) and the battery prompt at every start are gone.
 */
public class PermissionPromptsGuardTest {

    private static String code() throws Exception {
        File file = new File("src/main/java/de/yaacc/browser/BrowserActivitySupport.java");
        assertTrue(file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test
    public void theRequestComesFromThePlanAndIsRememberedBeforeTheDialog() throws Exception {
        String code = code();
        assertTrue(code.contains("PermissionRequestPlan.atStart("));
        assertTrue(code.indexOf("putStringSet(KEY_ASKED_PERMISSIONS") < code.indexOf("ActivityCompat.requestPermissions(activity, plan"));
    }

    @Test
    public void noPermissionThatCanNeverBeGrantedIsInTheStartupCode() throws Exception {
        String code = code();
        for (String never : new String[]{"MANAGE_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "INTERNET",
                "ACCESS_WIFI_STATE", "ACCESS_NETWORK_STATE", "CHANGE_WIFI", "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK",
                "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"}) {
            assertFalse(never, code.contains("permission." + never.replace("permission.", "")));
        }
    }

    @Test
    public void theBatteryPromptIsAskedThroughThePlanAndNeverWithAnEmptyIntent() throws Exception {
        String code = code();
        assertTrue(code.contains("PermissionRequestPlan.shouldAskBatteryOptimisation("));
        assertFalse("the empty intent that was started when the app was exempt is gone", code.contains("new Intent();"));
        assertTrue(code.contains("KEY_BATTERY_ASKED"));
    }
}
