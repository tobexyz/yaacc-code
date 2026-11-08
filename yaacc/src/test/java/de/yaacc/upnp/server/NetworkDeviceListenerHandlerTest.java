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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.registry.RegistryImpl;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.upnp.server.udp.MulticastReceiver;
import de.yaacc.upnp.server.udp.UdpTransiver;

/**
 * The registry sends with the handler of the CURRENT enable() of the network listener: every enable() hands it the
 * new handler (the old one owns closed sockets), disable() clears it, and local devices are announced again after a
 * re-enable. No sockets: the receivers are mocks.
 */
public class NetworkDeviceListenerHandlerTest {

    private Registry registry;
    private NetworkDeviceListener listener;
    private int receivers;

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        ConnectivityManager connectivity = mock(ConnectivityManager.class); // no active network: no wifi locks
        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivity);
        registry = mock(Registry.class);
        listener = new NetworkDeviceListener(context, registry, mock(YaaccUpnpServerService.class), mock(WifiManager.class)) {
            @Override
            MulticastReceiver newMulticastReceiver() {
                receivers++;
                return mock(MulticastReceiver.class);
            }

            @Override
            UdpTransiver newUdpTransiver() {
                return mock(UdpTransiver.class);
            }
        };
        listener.announceExecutor = Runnable::run;
    }

    @Test
    public void enableHandsTheNewHandlerToTheRegistry() {
        listener.enable();

        UpnpProtocolHandler handler = listener.getUpnpProtocolHandler();
        assertNotNull(handler);
        verify(registry).setUpnpProtocolHandler(handler);
    }

    @Test
    public void everyReenableReplacesTheHandlerInTheRegistry() {
        listener.enable();
        UpnpProtocolHandler first = listener.getUpnpProtocolHandler();
        listener.disable();
        listener.enable();
        UpnpProtocolHandler second = listener.getUpnpProtocolHandler();
        listener.disable();
        listener.enable();
        UpnpProtocolHandler third = listener.getUpnpProtocolHandler();

        assertNotSame(first, second);
        assertNotSame(second, third);
        ArgumentCaptor<UpnpProtocolHandler> set = ArgumentCaptor.forClass(UpnpProtocolHandler.class);
        verify(registry, times(5)).setUpnpProtocolHandler(set.capture());
        // enable, disable, enable, disable, enable: the registry ends with the live (third) handler
        assertSame(first, set.getAllValues().get(0));
        assertNull(set.getAllValues().get(1));
        assertSame(second, set.getAllValues().get(2));
        assertNull(set.getAllValues().get(3));
        assertSame(third, set.getAllValues().get(4));
        assertTrue("new sockets per enable", receivers >= 3);
    }

    @Test
    public void disableClearsTheRegistrysHandler() {
        listener.enable();
        listener.disable();

        assertNull(listener.getUpnpProtocolHandler());
        InOrder order = inOrder(registry);
        order.verify(registry).setUpnpProtocolHandler(org.mockito.ArgumentMatchers.notNull());
        order.verify(registry).setUpnpProtocolHandler(null);
    }

    @Test
    public void theRegistryGetsTheHandlerBeforeTheReceiversStart() {
        MulticastReceiver multicast = mock(MulticastReceiver.class);
        UdpTransiver udp = mock(UdpTransiver.class);
        Context context = mock(Context.class);
        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mock(ConnectivityManager.class));
        NetworkDeviceListener ordered = new NetworkDeviceListener(context, registry, mock(YaaccUpnpServerService.class), mock(WifiManager.class)) {
            @Override
            MulticastReceiver newMulticastReceiver() {
                return multicast;
            }

            @Override
            UdpTransiver newUdpTransiver() {
                return udp;
            }
        };
        ordered.enable();

        InOrder order = inOrder(registry, multicast, udp);
        order.verify(registry).setUpnpProtocolHandler(org.mockito.ArgumentMatchers.notNull());
        order.verify(multicast).execute();
        order.verify(udp).execute();
    }

    @Test
    public void localDevicesAreAnnouncedAgain() {
        listener.announceLocalDevices();
        verify(registry).advertiseLocalDevices();
    }

    @Test
    public void aFailingAnnouncementDoesNotEscape() {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(registry).advertiseLocalDevices();
        listener.announceLocalDevices();
        verify(registry).advertiseLocalDevices();
    }

    @Test
    public void enableAndDisableThemselvesDoNotAnnounce() {
        // the announcement belongs to "enabled for a new network", not to the start (devices announce on add)
        listener.enable();
        listener.disable();
        verify(registry, never()).advertiseLocalDevices();
    }

    @Test
    public void noHttpSenderIsCreatedPerHandler() {
        listener.enable();
        HttpRequestSender sender = listener.getHttpRequestSender();
        listener.disable();
        listener.enable();
        assertSame("the sender is kept over a re-enable", sender, listener.getHttpRequestSender());
    }

    // ---- the registry side: sending with a null handler ----

    @Test
    public void registryImplHasOneAccessorPairOverOneVolatileField() throws Exception {
        RegistryImpl impl = org.mockito.Mockito.mock(RegistryImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        UpnpProtocolHandler handler = mock(UpnpProtocolHandler.class);
        impl.setUpnpProtocolHandler(handler);
        assertSame(handler, impl.getUpnpProtocolHandler());
        impl.setUpnpProtocolHandler(null);
        assertNull(impl.getUpnpProtocolHandler());

        // the second pair (get/setProtocolHandler) is gone, and the one field is volatile: the maintainer thread, the
        // HTTP server threads and the network thread read and write it without a common lock
        for (java.lang.reflect.Method method : RegistryImpl.class.getDeclaredMethods()) {
            assertTrue(method.getName(), !method.getName().equals("getProtocolHandler") && !method.getName().equals("setProtocolHandler"));
        }
        assertTrue(java.lang.reflect.Modifier.isVolatile(RegistryImpl.class.getDeclaredField("upnpProtocolHandler").getModifiers()));
    }
}
