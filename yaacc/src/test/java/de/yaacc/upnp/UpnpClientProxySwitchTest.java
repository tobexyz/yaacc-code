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
package de.yaacc.upnp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.preference.PreferenceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Set;

import de.yaacc.R;
import de.yaacc.share.ProxyAvailability.Decision;

/**
 * Task 12.21 (owner's decision 2026-11-17): the shared-link proxy is used only when the proxy checkbox AND the master switch
 * "UPnP Server" are on. With the proxy ticked and the master switch off no server serves the link, so
 * {@link UpnpClient#createPlayableItem(Uri, Set)} refuses it (null) before any network work. Preferences are mocks.
 */
public class UpnpClientProxySwitchTest {

    private static final String LINK = "http://media.example.com/stream/song.mp3";

    private MockedStatic<PreferenceManager> preferenceStatic;
    private SharedPreferences preferences;
    private UpnpClient client;
    private Context context;

    @Before
    public void setUp() {
        context = mock(Context.class);
        when(context.getString(R.string.settings_local_server_chkbx)).thenReturn("master");
        when(context.getString(R.string.settings_local_server_proxy_chkbx)).thenReturn("proxy");
        preferences = mock(SharedPreferences.class);
        preferenceStatic = mockStatic(PreferenceManager.class);
        preferenceStatic.when(() -> PreferenceManager.getDefaultSharedPreferences(context)).thenReturn(preferences);
        client = spy(new UpnpClient());
        doReturn(context).when(client).getContext();
    }

    @After
    public void tearDown() {
        preferenceStatic.close();
    }

    private void switches(boolean master, boolean proxy) {
        when(preferences.getBoolean("master", false)).thenReturn(master);
        when(preferences.getBoolean("proxy", false)).thenReturn(proxy);
    }

    @Test
    public void theDecisionFollowsTheTwoSwitches() {
        switches(true, true);
        assertEquals(Decision.USE_PROXY, client.linkProxyDecision());
        switches(false, true);
        assertEquals(Decision.REFUSE_NO_SERVER, client.linkProxyDecision());
        switches(true, false);
        assertEquals(Decision.DIRECT, client.linkProxyDecision());
        switches(false, false);
        assertEquals(Decision.DIRECT, client.linkProxyDecision());
    }

    @Test
    public void aLinkWithTheProxyTickedAndTheMasterSwitchOffIsRefusedBeforeAnyNetworkWork() throws Exception {
        switches(false, true);
        Uri link = mock(Uri.class);
        when(link.toString()).thenReturn(LINK);

        assertNull(client.createPlayableItem(link, Set.of("receiver-1")));

        // nothing was registered or fetched: the registry of the application is not even asked for
        verify(context, never()).getApplicationContext();
    }
}
