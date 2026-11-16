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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which runtime permissions the app asks for at a start, and when (task 12.3). Plain Java: the SDK level, what is granted and
 * what was asked before come in as values.
 * <p>
 * What it leaves out on purpose, because asking can never end in "granted" (so an "all granted" check is never true and the
 * request shows at every start): the permissions that are granted at install (internet, Wi-Fi, wake lock, boot, battery exemption
 * request), the all-files access (a special permission, the user grants it in the system settings, the request dialog never shows
 * it), and write storage from API 30 on (scoped storage is enforced, it has no effect; on Android 10 the legacy storage flag of the manifest still makes it the permission for the file API). What is asked is a missing one, once: after the first request the
 * ask is remembered and a permission that is still missing is taken as declined and left alone; the user asks again from a settings
 * action ({@link #onUserAction}) or a feature that needs it. Android 14's partial photo and video access (the selected-photos
 * permission granted, images and video not) counts as an answer.
 */
final class PermissionRequestPlan {

    static final int API_M = 23;
    static final int API_R = 30;
    static final int API_TIRAMISU = 33;
    static final int API_UPSIDE_DOWN_CAKE = 34;

    static final String READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE";
    static final String WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE";
    static final String READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO";
    static final String READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES";
    static final String READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO";
    static final String READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";
    static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    private PermissionRequestPlan() {
    }

    /** The runtime permissions of this app that a request on this SDK level can end up granting. */
    static List<String> requestable(int sdk) {
        List<String> list = new ArrayList<>();
        if (sdk < API_M) {
            return list;
        }
        if (sdk < API_R) {
            // Android 10 honours requestLegacyExternalStorage (manifest): write storage is what FileDownloader needs for Downloads
            list.add(READ_EXTERNAL_STORAGE);
            list.add(WRITE_EXTERNAL_STORAGE);
        } else if (sdk < API_TIRAMISU) {
            list.add(READ_EXTERNAL_STORAGE);
        } else {
            list.add(READ_MEDIA_AUDIO);
            list.add(READ_MEDIA_IMAGES);
            list.add(READ_MEDIA_VIDEO);
            if (sdk >= API_UPSIDE_DOWN_CAKE) {
                list.add(READ_MEDIA_VISUAL_USER_SELECTED);
            }
            list.add(POST_NOTIFICATIONS);
        }
        return list;
    }

    /**
     * What to request at a start of the app.
     *
     * @param granted              the permissions that are granted now
     * @param alreadyAsked         the permissions that were requested before (a missing one of them was declined)
     * @param askedInThisProcess   something was requested since this process started
     */
    static List<String> atStart(int sdk, Set<String> granted, Set<String> alreadyAsked, boolean askedInThisProcess) {
        if (askedInThisProcess) {
            return new ArrayList<>();
        }
        return missing(sdk, granted, alreadyAsked);
    }

    /** What to request when the user asks for it (a settings action, a feature that needs it): earlier answers do not count. */
    static List<String> onUserAction(int sdk, Set<String> granted) {
        return missing(sdk, granted, Collections.<String>emptySet());
    }

    /**
     * Whether to open the system's "do not optimise the battery of this app" request. The app needs it (the server and the players run
     * with the screen off), so it asks, but once: not at every start, not again after a decline, never when the app is exempt.
     */
    static boolean shouldAskBatteryOptimisation(boolean ignoringBatteryOptimisations, boolean askedBefore,
                                                boolean askedInThisProcess) {
        return !ignoringBatteryOptimisations && !askedBefore && !askedInThisProcess;
    }

    private static List<String> missing(int sdk, Set<String> granted, Set<String> alreadyAsked) {
        Set<String> wanted = new LinkedHashSet<>();
        boolean partialMediaAccess = granted.contains(READ_MEDIA_VISUAL_USER_SELECTED);
        for (String permission : requestable(sdk)) {
            if (READ_MEDIA_VISUAL_USER_SELECTED.equals(permission)) {
                continue; // only ever asked together with images or video, below
            }
            if (granted.contains(permission) || alreadyAsked.contains(permission)) {
                continue;
            }
            if (partialMediaAccess && (READ_MEDIA_IMAGES.equals(permission) || READ_MEDIA_VIDEO.equals(permission))) {
                continue; // "selected photos and videos" is the user's answer
            }
            wanted.add(permission);
        }
        if (sdk >= API_UPSIDE_DOWN_CAKE && !partialMediaAccess
                && (wanted.contains(READ_MEDIA_IMAGES) || wanted.contains(READ_MEDIA_VIDEO))) {
            wanted.add(READ_MEDIA_VISUAL_USER_SELECTED); // the system dialog then offers "select photos" too
        }
        return new ArrayList<>(wanted);
    }
}
