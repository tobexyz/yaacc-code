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
package de.yaacc.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.R;
import de.yaacc.upnp.server.contentdirectory.MediaPathFilter;

public class SafPermissionManager {

    private static final int MAX_PERMISSIONS = 120; // Leave buffer below Android's 128 limit

    public static void validateAndCleanupPermissions(Context context) {
        Set<String> storedUris = MediaPathFilter.getSafPathes(context);
        List<UriPermission> grantedPermissions = context.getContentResolver().getPersistedUriPermissions();
        Set<String> toBeRemoved = new HashSet<>();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        YaaccLogger.d(SafPermissionManager.class.getName(), "Checking " + storedUris.size() + " stored SAF URIs, " +
                grantedPermissions.size() + " total permissions");

        // Check each stored URI to see if we still have permission
        for (String uriString : storedUris) {
            try {
                Uri uri = Uri.parse(uriString);
                boolean hasPermission = grantedPermissions.stream()
                        .anyMatch(perm -> uriString.contains(perm.getUri().toString()) && perm.isReadPermission());

                grantedPermissions.stream().forEach(it -> YaaccLogger.d(SafPermissionManager.class.getName(), "Permission: " + it.getUri() + " " + it.isReadPermission()));
                YaaccLogger.d(SafPermissionManager.class.getName(), "Checking permission for SAF URI: " + uriString + " -> " + hasPermission);
                if (!hasPermission) {
                    YaaccLogger.d(SafPermissionManager.class.getName(), "Lost permission for SAF URI removing: " + uriString);
                    toBeRemoved.add(uriString);
                } else {
                    YaaccLogger.d(SafPermissionManager.class.getName(), "Permission OK for SAF URI: " + uriString);
                }
            } catch (Exception e) {
                YaaccLogger.w(SafPermissionManager.class.getName(), "Error checking permission for URI: " + uriString, e);
            }
        }
        //release orphaned permissions from MediaPathFilter
        Set<String> storedUriSet = new HashSet<>(storedUris);
        storedUriSet.removeAll(toBeRemoved);
        MediaPathFilter.saveSafPathes(context, storedUriSet);
        Set<String> selectedUriSet = new HashSet<>(MediaPathFilter.getSelectedSafPathes(context));
        selectedUriSet.removeAll(toBeRemoved);
        MediaPathFilter.saveSelectedSafPathes(context, selectedUriSet);
        for (String uriString : toBeRemoved) {
            YaaccLogger.d(SafPermissionManager.class.getName(), "Removing duration cache entry for SAF URI: " + uriString);
            if (preferences.contains(context.getString(R.string.settings_duration_format_key) + toBeRemoved)) {
                preferences.edit().remove(context.getString(R.string.settings_duration_format_key) + toBeRemoved);
            }
        }

        // Clean up orphaned permissions if we're approaching the limit
        if (grantedPermissions.size() >= 110) {
            cleanupOrphanedPermissions(context, storedUriSet, grantedPermissions);
        }

        YaaccLogger.i(SafPermissionManager.class.getName(), "SAF permission check complete");
    }

    private static void cleanupOrphanedPermissions(Context context, Set<String> validUris, List<UriPermission> grantedPermissions) {
        Set<String> validUriSet = new HashSet<>(validUris);

        for (UriPermission permission : grantedPermissions) {
            String uriString = permission.getUri().toString();
            if (!validUriSet.contains(uriString)) {
                try {
                    context.getContentResolver().releasePersistableUriPermission(
                            permission.getUri(),
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                    YaaccLogger.i(SafPermissionManager.class.getName(), "Released orphaned permission: " + uriString);
                } catch (Exception e) {
                    YaaccLogger.w(SafPermissionManager.class.getName(), "Failed to release permission: " + uriString, e);
                }
            }
        }
    }

    public static boolean canAddMorePermissions(Context context) {
        List<UriPermission> grantedPermissions = context.getContentResolver().getPersistedUriPermissions();
        return grantedPermissions.size() < MAX_PERMISSIONS;
    }

    public static int getPermissionCount(Context context) {
        return context.getContentResolver().getPersistedUriPermissions().size();
    }
}
