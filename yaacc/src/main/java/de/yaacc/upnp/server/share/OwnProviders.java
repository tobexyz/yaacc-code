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
package de.yaacc.upnp.server.share;

import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;

/**
 * Whether a content authority belongs to a provider of THIS app. The app has more than one
 * (the file provider, the start-up provider of a library), and a future one would pass a check
 * of the file provider's authority alone; a URI of any of them would let another app make yaacc
 * read and serve its own data, so every authority is resolved to its package.
 */
public final class OwnProviders {

    private OwnProviders() {
    }

    /**
     * @param packageManager the package manager
     * @param ownPackage     this app's package name
     * @param authority      the authority of the shared URI
     * @return true if a provider of this app answers to the authority; also true when the question
     * cannot be answered (fail closed). False for an authority nobody (visible) provides.
     */
    public static boolean isOwnAuthority(PackageManager packageManager, String ownPackage, String authority) {
        if (packageManager == null || ownPackage == null || authority == null) {
            return true;
        }
        try {
            ProviderInfo info = packageManager.resolveContentProvider(authority, 0);
            return info != null && ownPackage.equals(info.packageName);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The authority of yaacc's file provider ({@code ${applicationId}.fileprovider} in the manifest). */
    public static String fileProviderAuthority(String ownPackage) {
        return ownPackage + ".fileprovider";
    }
}
