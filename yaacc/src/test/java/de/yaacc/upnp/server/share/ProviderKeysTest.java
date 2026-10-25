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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;

import org.junit.Test;

/** {@link ProviderKeys}: one app with many authorities is one provider for the call gate. */
public class ProviderKeysTest {

    private static ProviderInfo providerOf(String packageName) {
        ProviderInfo info = new ProviderInfo();
        info.packageName = packageName;
        return info;
    }

    @Test
    public void severalAuthoritiesOfOneAppShareOneKey() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.resolveContentProvider("evil.one", 0)).thenReturn(providerOf("com.evil"));
        when(pm.resolveContentProvider("evil.two", 0)).thenReturn(providerOf("com.evil"));

        assertEquals("pkg:com.evil", ProviderKeys.of(pm, "evil.one"));
        assertEquals(ProviderKeys.of(pm, "evil.one"), ProviderKeys.of(pm, "evil.two"));
    }

    @Test
    public void anUnresolvableAuthorityFallsBackToTheLowerCaseAuthority() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.resolveContentProvider(anyString(), anyInt())).thenReturn(null);

        assertEquals("auth:media", ProviderKeys.of(pm, "Media"));
        assertEquals(ProviderKeys.of(pm, "MEDIA"), ProviderKeys.of(pm, "media"));
    }

    @Test
    public void aFailingPackageManagerFallsBackToTheAuthority() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.resolveContentProvider(anyString(), anyInt())).thenThrow(new RuntimeException("dead"));

        assertEquals("auth:media", ProviderKeys.of(pm, "media"));
    }

    @Test
    public void noPackageManagerFallsBackToTheAuthority() {
        assertEquals("auth:media", ProviderKeys.of(null, "media"));
    }

    @Test
    public void noAuthorityNoKey() {
        assertNull(ProviderKeys.of(mock(PackageManager.class), null));
    }

    @Test
    public void aPackageProviderAndAnAuthorityNeverCollide() {
        PackageManager resolving = mock(PackageManager.class);
        when(resolving.resolveContentProvider("x", 0)).thenReturn(providerOf("auth:x"));

        // "pkg:auth:x" vs "auth:x"
        assertEquals("pkg:auth:x", ProviderKeys.of(resolving, "x"));
        assertEquals("auth:x", ProviderKeys.of(null, "x"));
    }
}
