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

import static de.yaacc.browser.PermissionRequestPlan.POST_NOTIFICATIONS;
import static de.yaacc.browser.PermissionRequestPlan.READ_EXTERNAL_STORAGE;
import static de.yaacc.browser.PermissionRequestPlan.READ_MEDIA_AUDIO;
import static de.yaacc.browser.PermissionRequestPlan.READ_MEDIA_IMAGES;
import static de.yaacc.browser.PermissionRequestPlan.READ_MEDIA_VIDEO;
import static de.yaacc.browser.PermissionRequestPlan.READ_MEDIA_VISUAL_USER_SELECTED;
import static de.yaacc.browser.PermissionRequestPlan.WRITE_EXTERNAL_STORAGE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Task 12.3: "on every start the app asks for permissions". The old list held permissions that can never be granted for the app's
 * SDK level (write storage on API 29 and up, all-files access which is a special permission, and normal permissions that are granted
 * at install), so the check "all granted" was never true and the request ran at every start; a partial photo and video grant
 * (Android 14) or a declined notification permission was asked again at every start as well. The plan lists only the runtime
 * permissions the SDK can request, only the missing ones, and nothing that was asked before.
 */
public class PermissionRequestPlanTest {

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static final Set<String> NONE = Collections.emptySet();

    // ---- what can be requested at all -------------------------------------------------------------------------------

    @Test
    public void writeStorageIsGrantableAndNeededUpToApi29() {
        // the manifest has requestLegacyExternalStorage="true" and Android 10 honours it: write storage is what lets FileDownloader write
        // to the public Downloads folder there (review W1 of Group 12A: the first version stopped asking at 29)
        assertEquals(set(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE), new HashSet<>(PermissionRequestPlan.requestable(27)));
        assertEquals(set(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE), new HashSet<>(PermissionRequestPlan.requestable(28)));
        assertEquals(set(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE), new HashSet<>(PermissionRequestPlan.requestable(29)));
    }

    @Test
    public void writeStorageHasNoEffectFromApi30() {
        assertEquals(set(READ_EXTERNAL_STORAGE), new HashSet<>(PermissionRequestPlan.requestable(30)));
        assertEquals(set(READ_EXTERNAL_STORAGE), new HashSet<>(PermissionRequestPlan.requestable(31)));
        assertEquals(set(READ_EXTERNAL_STORAGE), new HashSet<>(PermissionRequestPlan.requestable(32)));
    }

    /** The manifest: READ_EXTERNAL_STORAGE up to 32, WRITE_EXTERNAL_STORAGE up to 33 (legacy storage only to 29), media and notifications from 33. */
    @Test
    public void theWholeListPerApiLevel() {
        Object[][] table = {
                {26, set(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE)},
                {28, set(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE)},
                {29, set(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE)},
                {30, set(READ_EXTERNAL_STORAGE)},
                {32, set(READ_EXTERNAL_STORAGE)},
                {33, set(READ_MEDIA_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, POST_NOTIFICATIONS)},
                {34, set(READ_MEDIA_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, POST_NOTIFICATIONS)},
                {36, set(READ_MEDIA_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, POST_NOTIFICATIONS)},
        };
        for (Object[] row : table) {
            List<String> list = PermissionRequestPlan.requestable((Integer) row[0]);
            assertEquals("API " + row[0], row[1], new HashSet<>(list));
            assertEquals("no duplicates at API " + row[0], list.size(), new HashSet<>(list).size());
        }
    }

    @Test
    public void api33HasTheMediaPermissionsAndTheNotificationOnly() {
        assertEquals(set(READ_MEDIA_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, POST_NOTIFICATIONS),
                new HashSet<>(PermissionRequestPlan.requestable(33)));
    }

    @Test
    public void api34AddsTheSelectedPhotosPermission() {
        assertEquals(set(READ_MEDIA_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, POST_NOTIFICATIONS),
                new HashSet<>(PermissionRequestPlan.requestable(34)));
        assertEquals(PermissionRequestPlan.requestable(34), PermissionRequestPlan.requestable(36));
    }

    @Test
    public void neverAnInstallTimeOrSpecialPermission() {
        for (int sdk = 27; sdk <= 36; sdk++) {
            for (String permission : PermissionRequestPlan.requestable(sdk)) {
                assertFalse(permission, Arrays.asList("android.permission.INTERNET", "android.permission.ACCESS_WIFI_STATE",
                        "android.permission.ACCESS_NETWORK_STATE", "android.permission.CHANGE_WIFI_STATE",
                        "android.permission.CHANGE_WIFI_MULTICAST_STATE", "android.permission.RECEIVE_BOOT_COMPLETED",
                        "android.permission.WAKE_LOCK", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                        "android.permission.MANAGE_EXTERNAL_STORAGE").contains(permission));
            }
        }
    }

    // ---- at a start -------------------------------------------------------------------------------------------------

    @Test
    public void aFreshInstallAsksForWhatTheSdkCanGrantOnce() {
        List<String> asked = PermissionRequestPlan.atStart(34, NONE, NONE, false);
        assertEquals(set(READ_MEDIA_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, POST_NOTIFICATIONS),
                new HashSet<>(asked));
        assertEquals("no duplicates", asked.size(), new HashSet<>(asked).size());
    }

    @Test
    public void everythingGrantedAsksForNothing() {
        assertTrue(PermissionRequestPlan.atStart(34, new HashSet<>(PermissionRequestPlan.requestable(34)), NONE, false).isEmpty());
        assertTrue(PermissionRequestPlan.atStart(33, new HashSet<>(PermissionRequestPlan.requestable(33)), NONE, false).isEmpty());
        assertTrue(PermissionRequestPlan.atStart(30, set(READ_EXTERNAL_STORAGE), NONE, false).isEmpty());
        assertTrue(PermissionRequestPlan.atStart(29, set(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE), NONE, false).isEmpty());
    }

    @Test
    public void onApi33WriteStorageNeverCausesARequest() {
        // the old code checked WRITE_EXTERNAL_STORAGE too, which is never granted there: it asked at every start
        Set<String> granted = new HashSet<>(PermissionRequestPlan.requestable(33));
        assertTrue(PermissionRequestPlan.atStart(33, granted, NONE, false).isEmpty());
    }

    @Test
    public void onAndroid10GrantingReadDoesNotGrantWriteSoWriteIsAskedToo() {
        assertEquals(Collections.singletonList(WRITE_EXTERNAL_STORAGE),
                PermissionRequestPlan.atStart(29, set(READ_EXTERNAL_STORAGE), NONE, false));
    }

    @Test
    public void whatWasAskedBeforeIsNotAskedAgainAtAStart() {
        Set<String> everything = new HashSet<>(PermissionRequestPlan.requestable(33));
        // the notification permission was declined: it stays missing and is not asked again
        Set<String> granted = new HashSet<>(everything);
        granted.remove(POST_NOTIFICATIONS);
        assertTrue(PermissionRequestPlan.atStart(33, granted, set(POST_NOTIFICATIONS), false).isEmpty());
        // the whole first request was declined
        assertTrue(PermissionRequestPlan.atStart(33, NONE, everything, false).isEmpty());
    }

    @Test
    public void aPermissionThatCameLaterIsAskedOnceEvenIfOthersWereDeclined() {
        // an update added a permission: only that one is asked, the declined ones stay quiet
        Set<String> asked = set(READ_MEDIA_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO);
        List<String> plan = PermissionRequestPlan.atStart(33, NONE, asked, false);
        assertEquals(Collections.singletonList(POST_NOTIFICATIONS), plan);
    }

    @Test
    public void aPartialPhotoAndVideoGrantOnApi34IsEnoughAndNotAskedAgain() {
        // "select photos and videos": the selected-photos permission is granted, images and video are not
        Set<String> granted = set(READ_MEDIA_AUDIO, READ_MEDIA_VISUAL_USER_SELECTED, POST_NOTIFICATIONS);
        assertTrue(PermissionRequestPlan.atStart(34, granted, NONE, false).isEmpty());
    }

    @Test
    public void theSelectedPhotosPermissionIsNeverAskedAlone() {
        Set<String> granted = set(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO, POST_NOTIFICATIONS);
        assertTrue("granted images and video, the selected permission is not asked on its own",
                PermissionRequestPlan.atStart(34, granted, NONE, false).isEmpty());
        // images missing, the selected one comes along (the system dialog offers all or selected photos)
        List<String> plan = PermissionRequestPlan.atStart(34, set(READ_MEDIA_AUDIO, READ_MEDIA_VIDEO, POST_NOTIFICATIONS), NONE, false);
        assertTrue(plan.contains(READ_MEDIA_IMAGES));
        assertTrue(plan.contains(READ_MEDIA_VISUAL_USER_SELECTED));
        assertFalse(plan.contains(READ_MEDIA_VIDEO));
    }

    @Test
    public void atMostOnceInAProcess() {
        assertTrue(PermissionRequestPlan.atStart(34, NONE, NONE, true).isEmpty());
    }

    @Test
    public void apiBelowTheRuntimeModelAsksForNothing() {
        assertTrue(PermissionRequestPlan.atStart(22, NONE, NONE, false).isEmpty());
        assertTrue(PermissionRequestPlan.requestable(22).isEmpty());
    }

    // ---- when the user triggers it ------------------------------------------------------------------------------------

    @Test
    public void aSettingsActionAsksForEveryMissingRequestablePermissionAgain() {
        Set<String> granted = set(READ_MEDIA_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO);
        assertEquals(Collections.singletonList(POST_NOTIFICATIONS), PermissionRequestPlan.onUserAction(33, granted));
        assertTrue(PermissionRequestPlan.onUserAction(33, new HashSet<>(PermissionRequestPlan.requestable(33))).isEmpty());
    }

    @Test
    public void aPartialGrantIsNotAskedAgainEvenOnUserActionButTheOtherMissingOnesAre() {
        Set<String> granted = set(READ_MEDIA_AUDIO, READ_MEDIA_VISUAL_USER_SELECTED);
        assertEquals(Collections.singletonList(POST_NOTIFICATIONS), PermissionRequestPlan.onUserAction(34, granted));
    }

    // ---- the battery optimisation prompt --------------------------------------------------------------------------------

    @Test
    public void theBatteryPromptIsAskedOnceNotAtEveryStart() {
        assertTrue("first time, not exempt: ask", PermissionRequestPlan.shouldAskBatteryOptimisation(false, false, false));
        assertFalse("declined before: not again", PermissionRequestPlan.shouldAskBatteryOptimisation(false, true, false));
        assertFalse("already exempt: never", PermissionRequestPlan.shouldAskBatteryOptimisation(true, false, false));
        assertFalse("exempt and asked before", PermissionRequestPlan.shouldAskBatteryOptimisation(true, true, false));
        assertFalse("not twice in a process, for example after a rotation or the second activity",
                PermissionRequestPlan.shouldAskBatteryOptimisation(false, false, true));
    }
}
