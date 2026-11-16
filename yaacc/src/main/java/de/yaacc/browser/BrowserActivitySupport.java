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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.MenuItem;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.util.AboutActivity;
import de.yaacc.util.YaaccLogActivity;
import de.yaacc.util.YaaccLogger;

/**
 * The startup and menu steps that every browser activity (classic and new layout) does the same
 * way: runtime permissions, the battery-optimization prompt and the common options-menu actions.
 * Moved here unchanged from {@code TabBrowserActivity}.
 */
final class BrowserActivitySupport {

    private static final int PERMISSION_REQUEST_CODE = 101;

    private BrowserActivitySupport() {
    }

    // What was asked before and whether the battery prompt was shown: a file of its own, it is no setting.
    private static final String PROMPTS_FILE = "yaacc_prompts";
    private static final String KEY_ASKED_PERMISSIONS = "asked_permissions";
    private static final String KEY_BATTERY_ASKED = "battery_optimisation_asked";
    // at most once per process: the second activity or a rotation does not ask again
    private static boolean permissionsAskedInThisProcess;
    private static boolean batteryAskedInThisProcess;

    private static SharedPreferences prompts(Context context) {
        return context.getApplicationContext().getSharedPreferences(PROMPTS_FILE, Context.MODE_PRIVATE);
    }

    private static Set<String> grantedAmong(Context context, List<String> permissions) {
        Set<String> granted = new HashSet<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                granted.add(permission);
            }
        }
        return granted;
    }

    /**
     * Asks for the runtime permissions that this Android version can grant and that are missing, once: what was asked before is not
     * asked again, a decline is final until the user asks from {@link #requestPermissionsOnUserAction}, and a start asks at most once
     * per process (task 12.3, {@link PermissionRequestPlan}). The result is not handled (as before).
     */
    static void requestMissingPermissions(Activity activity) {
        SharedPreferences memory = prompts(activity);
        int sdk = Build.VERSION.SDK_INT;
        Set<String> granted = grantedAmong(activity, PermissionRequestPlan.requestable(sdk));
        Set<String> asked = new HashSet<>(memory.getStringSet(KEY_ASKED_PERMISSIONS, Collections.<String>emptySet()));
        List<String> plan = PermissionRequestPlan.atStart(sdk, granted, asked, permissionsAskedInThisProcess);
        if (plan.isEmpty()) {
            YaaccLogger.d(activity.getClass().getName(), "No permission to ask for");
            return;
        }
        permissionsAskedInThisProcess = true;
        asked.addAll(plan);
        // written before the dialog: a missing permission that was asked is taken as declined at the next start
        memory.edit().putStringSet(KEY_ASKED_PERMISSIONS, asked).apply();
        ActivityCompat.requestPermissions(activity, plan.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    /**
     * Asks again for the missing permissions that this Android version can grant: for an action of the user (a settings entry) or a
     * feature that needs them. Earlier answers do not count; the system may still show nothing for a permission the user declined
     * twice.
     */
    static void requestPermissionsOnUserAction(Activity activity) {
        int sdk = Build.VERSION.SDK_INT;
        List<String> plan = PermissionRequestPlan.onUserAction(sdk, grantedAmong(activity, PermissionRequestPlan.requestable(sdk)));
        if (!plan.isEmpty()) {
            ActivityCompat.requestPermissions(activity, plan.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    /**
     * Opens the "ignore battery optimizations" request once: not at every start, not again after a decline, and not when the app is
     * already exempt (task 12.3, {@link PermissionRequestPlan#shouldAskBatteryOptimisation}). The old code opened it at every start
     * of every activity while the app was not exempt, and started an empty intent when it was.
     */
    // The server and the players must keep running with the screen off: the exemption is the app's core need.
    @android.annotation.SuppressLint("BatteryLife")
    static void checkBatteryOptimizationEnabled(Activity activity) {
        String packageName = activity.getPackageName();
        PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        SharedPreferences memory = prompts(activity);
        if (!PermissionRequestPlan.shouldAskBatteryOptimisation(pm.isIgnoringBatteryOptimizations(packageName),
                memory.getBoolean(KEY_BATTERY_ASKED, false), batteryAskedInThisProcess)) {
            return;
        }
        batteryAskedInThisProcess = true;
        memory.edit().putBoolean(KEY_BATTERY_ASKED, true).apply();
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            YaaccLogger.d(activity.getClass().getName(), "Ignoring exception ActivityNotFoundException during check for battery optimization");
        }
    }

    /**
     * Handles exit, settings, about and log.
     *
     * @return true if the item was one of them
     */
    static boolean handleCommonMenuItem(AppCompatActivity activity, MenuItem item) {
        if (item.getItemId() == R.id.menu_exit) {

            ((Yaacc) activity.getApplicationContext()).exit();
            return true;
        }
        if (item.getItemId() == R.id.menu_settings) {
            Intent i = new Intent(activity, SettingsActivity.class);
            activity.startActivity(i);
            return true;
        }
        if (item.getItemId() == R.id.yaacc_about) {
            AboutActivity.showAbout(activity);
            return true;
        }
        if (item.getItemId() == R.id.yaacc_log) {
            YaaccLogActivity.showLog(activity);
            return true;
        }
        return false;
    }
}
