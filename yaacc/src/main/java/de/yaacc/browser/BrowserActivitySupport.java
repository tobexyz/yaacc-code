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

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.MenuItem;

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

    //https://developer.android.com/about/versions/14/changes/partial-photo-video-access
    private static String[] getPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.RECEIVE_BOOT_COMPLETED,
                    Manifest.permission.WAKE_LOCK,
                    Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                    Manifest.permission.POST_NOTIFICATIONS

            };
        } else if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return new String[]{
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.RECEIVE_BOOT_COMPLETED,
                    Manifest.permission.WAKE_LOCK,
                    Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Manifest.permission.MANAGE_EXTERNAL_STORAGE
            };
        }
        return new String[]{
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.RECEIVE_BOOT_COMPLETED,
                Manifest.permission.WAKE_LOCK,
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS

        };

    }

    private static boolean checkIfAlreadyhavePermission(Activity activity) {
        for (String permission : getPermissions()) {
            int permissionState = ContextCompat.checkSelfPermission(activity, permission);
            if (permissionState == PackageManager.PERMISSION_DENIED) {
                return false;
            }
        }
        return true;
    }

    /** Asks for the runtime permissions that are missing; the result is not handled (as before). */
    static void requestMissingPermissions(Activity activity) {
        if (!checkIfAlreadyhavePermission(activity)) {
            ActivityCompat.requestPermissions(activity, getPermissions(), PERMISSION_REQUEST_CODE);
        } else {
            YaaccLogger.d(activity.getClass().getName(), "All permissions granted");
        }
    }

    /**
     * Opens the "ignore battery optimizations" request on every call; if the app is already
     * exempt, an empty intent is started and the resulting exception is swallowed (as before).
     */
    static void checkBatteryOptimizationEnabled(Activity activity) {
        Intent intent = new Intent();
        String packageName = activity.getPackageName();
        PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + packageName));
        }
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
