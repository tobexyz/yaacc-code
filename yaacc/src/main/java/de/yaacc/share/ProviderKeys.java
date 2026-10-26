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
package de.yaacc.share;

import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;

/**
 * Names the content provider behind an authority for the per-provider call gate
 * ({@link de.yaacc.share.ProviderCallGate}). An app can declare many authorities, so the key is
 * the package that owns the authority where the system tells us; one hostile app then keeps one
 * place in the gate, not one per authority. If the authority cannot be resolved (not visible,
 * unknown, the question failed) the key is ONE fixed key shared by all such providers
 * ({@link #UNRESOLVED}): the manifest has no {@code <queries>}, so a provider whose package is not
 * visible could otherwise be asked under many authority spellings, and a permit lives as long as
 * its call, so each stuck one would keep a place of its own. Unresolved providers therefore
 * share one place (one such call at a time); that is the price of a bounded gate.
 */
public final class ProviderKeys {

    /** The one key of every provider whose owning package cannot be resolved. */
    public static final String UNRESOLVED = "unresolved";

    private ProviderKeys() {
    }

    /**
     * @param packageManager the package manager, may be null
     * @param authority      the authority of the shared URI, may be null
     * @return a key that is never null for a non-null authority; null for a null authority
     */
    public static String of(PackageManager packageManager, String authority) {
        if (authority == null) {
            return null;
        }
        if (packageManager != null) {
            try {
                ProviderInfo info = packageManager.resolveContentProvider(authority, 0);
                if (info != null && info.packageName != null && !info.packageName.isEmpty()) {
                    return "pkg:" + info.packageName;
                }
            } catch (RuntimeException e) {
                // fall through to the shared key
            }
        }
        return UNRESOLVED;
    }
}
