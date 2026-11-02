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
package de.yaacc.upnp.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import org.junit.Test;

import de.yaacc.upnp.registry.Registry;

/**
 * The kind of the active network is read at the moment the network is lost (onLost): the active network can be
 * gone between two calls. That crashed the app (NullPointerException in isWifi on the ConnectivityThread).
 */
public class NetworkTransportTest {

    private static NetworkDeviceListener listenerWith(ConnectivityManager connectivity) {
        Context context = mock(Context.class);
        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivity);
        return new NetworkDeviceListener(context, mock(Registry.class), mock(YaaccUpnpServerService.class),
                mock(WifiManager.class));
    }

    @Test
    public void noActiveNetworkIsNoTransport() {
        ConnectivityManager connectivity = mock(ConnectivityManager.class);
        NetworkDeviceListener listener = listenerWith(connectivity);

        assertFalse(listener.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
    }

    @Test
    public void anActiveNetworkWithoutCapabilitiesIsNoTransport() {
        ConnectivityManager connectivity = mock(ConnectivityManager.class);
        when(connectivity.getActiveNetwork()).thenReturn(mock(Network.class));
        NetworkDeviceListener listener = listenerWith(connectivity);

        assertFalse(listener.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
    }

    @Test
    public void theCapabilitiesAreAskedForOnceSoANetworkThatGoesAwayCannotCrash() {
        ConnectivityManager connectivity = mock(ConnectivityManager.class);
        Network network = mock(Network.class);
        NetworkCapabilities capabilities = mock(NetworkCapabilities.class);
        when(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true);
        when(connectivity.getActiveNetwork()).thenReturn(network);
        // the first answer has them, any further one would not (the network was lost in between)
        when(connectivity.getNetworkCapabilities(network)).thenReturn(capabilities).thenReturn(null);
        NetworkDeviceListener listener = listenerWith(connectivity);

        assertTrue(listener.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
        verify(connectivity, times(1)).getNetworkCapabilities(any(Network.class));
    }
}
