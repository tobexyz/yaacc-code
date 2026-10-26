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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;

import org.junit.Test;

/** {@link OwnProviders}: any provider of this app counts as our own authority. */
public class OwnProvidersTest {

    private static ProviderInfo providerOf(String packageName) {
        ProviderInfo info = new ProviderInfo();
        info.packageName = packageName;
        return info;
    }

    @Test
    public void anAuthorityResolvedToOurPackageIsOurs() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.resolveContentProvider("de.yaacc.androidx-startup", 0)).thenReturn(providerOf("de.yaacc"));

        assertTrue(OwnProviders.isOwnAuthority(pm, "de.yaacc", "de.yaacc.androidx-startup"));
    }

    @Test
    public void anAuthorityOfAnotherPackageIsNot() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.resolveContentProvider("media", 0)).thenReturn(providerOf("com.android.providers.media.module"));

        assertFalse(OwnProviders.isOwnAuthority(pm, "de.yaacc", "media"));
    }

    @Test
    public void anAuthorityNobodyProvidesIsNot() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.resolveContentProvider(anyString(), anyInt())).thenReturn(null);

        assertFalse(OwnProviders.isOwnAuthority(pm, "de.yaacc", "com.example.unknown"));
    }

    @Test
    public void aFailingPackageManagerFailsClosed() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.resolveContentProvider(anyString(), anyInt())).thenThrow(new IllegalStateException("dead"));

        assertTrue(OwnProviders.isOwnAuthority(pm, "de.yaacc", "media"));
    }

    @Test
    public void missingInputsFailClosed() {
        PackageManager pm = mock(PackageManager.class);

        assertTrue(OwnProviders.isOwnAuthority(null, "de.yaacc", "media"));
        assertTrue(OwnProviders.isOwnAuthority(pm, null, "media"));
        assertTrue(OwnProviders.isOwnAuthority(pm, "de.yaacc", null));
    }

    @Test
    public void theFileProviderAuthorityIsThePackageNamePlusFileprovider() {
        assertEquals("de.yaacc.fileprovider", OwnProviders.fileProviderAuthority("de.yaacc"));
    }
}
