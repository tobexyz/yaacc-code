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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Locale;

import androidx.preference.PreferenceManager;

import de.yaacc.R;
import de.yaacc.browser.BrowserLayoutChoice.Layout;
import de.yaacc.util.YaaccLogger;

/**
 * Single place that answers "which browser activity do we start": the launcher activity, the three
 * internal notification launches and the resume check of each browser all go through it, so they
 * cannot disagree.
 * <p>
 * The stored "Layout" preference is only ever mapped to a {@link Layout} by
 * {@link BrowserLayoutChoice#resolve(String)}; the stored string is never treated as a class name.
 */
public final class BrowserLauncher {

    private static final String TAG = BrowserLauncher.class.getName();

    /**
     * Whether the new tab-less browser exists and is usable (true since the receiver bar and the
     * mini-player landed in {@link BrowserActivity}). While false every stored choice, including
     * "new", resolves to the classic layout; keep the switch so a build without a usable new
     * layout cannot strand a stored "new" (backup/restore, adb) on a layout that is not there.
     */
    static final boolean NEW_LAYOUT_AVAILABLE = true;

    private BrowserLauncher() {
    }

    /**
     * @param storedValue the stored preference value, may be null
     * @return the layout that is actually launched
     */
    public static Layout effectiveLayout(String storedValue) {
        if (!NEW_LAYOUT_AVAILABLE) {
            return Layout.CLASSIC;
        }
        return BrowserLayoutChoice.resolve(storedValue);
    }

    /**
     * Reads the stored "Layout" preference with default {@code null}:
     * {@link BrowserLayoutChoice#resolve(String)} turns a missing value into the new layout.
     */
    public static Layout effectiveLayout(Context context) {
        String stored = null;
        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(
                    context.getApplicationContext());
            stored = preferences.getString(context.getString(R.string.settings_browser_layout_key), null);
        } catch (RuntimeException e) {
            // e.g. a value of another type; fall back to the default layout
            YaaccLogger.w(TAG, "Could not read the layout preference (" + e.getClass().getSimpleName() + ")");
        }
        return effectiveLayout(stored);
    }

    /** The browser activity class for the effective layout. */
    public static Class<? extends Activity> targetClass(Context context) {
        return targetClass(effectiveLayout(context));
    }

    /**
     * The browser activity class for an effective layout that the caller already computed, so a
     * decision and the target derived from it cannot disagree.
     */
    public static Class<? extends Activity> targetClass(Layout effective) {
        if (effective == Layout.NEW) {
            return BrowserActivity.class;
        }
        return TabBrowserActivity.class;
    }

    /**
     * The resume-time (and, for the new layout, create-time) check of both browsers: if the
     * effective layout is not the one {@code activity} renders, starts the browser of the effective
     * layout with a fresh intent and finishes {@code activity} without animation. A static guard
     * allows one relaunch per ~2 s, so a wrong decision cannot loop; when it trips the activity
     * stays where it is and logs.
     *
     * @return true if {@code activity} is finishing and must skip the rest of its callback
     */
    public static boolean relaunchIfOtherLayout(Activity activity, Layout own) {
        Layout effective = effectiveLayout(activity);
        if (!BrowserLayoutSwitch.shouldRelaunch(own, effective)) {
            return false;
        }
        if (!RelaunchGuard.shared().tryRelaunch()) {
            YaaccLogger.w(TAG, "Layout relaunch suppressed by the loop guard, staying on the "
                    + own.name().toLowerCase(Locale.ROOT) + " layout");
            return false;
        }
        activity.startActivity(new Intent(activity, targetClass(effective)));
        activity.overridePendingTransition(0, 0);
        activity.finish();
        return true;
    }
}
