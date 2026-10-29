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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ServiceId;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import de.yaacc.upnp.server.NetworkDeviceListener;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.upnp.server.http.HttpRequestSender;

/**
 * The calls of {@link UpnpClient} that need the HTTP sender of the server service's network listener (search, mute and
 * volume) when that chain is not there (no service bound, no listener yet, no sender because no network came up):
 * they log and give up softly instead of throwing a NullPointerException, which on a pool or async thread could end a
 * wait loop only by its watchdog. See also {@link UpnpClientBrowseGuardTest}.
 */
public class UpnpClientSenderGuardTest {

    private UpnpClient client;
    private Device<?, ?, ?> renderer;

    @Before
    public void setUp() {
        client = new UpnpClient();
        renderer = mock(Device.class);
        Service<?, ?> renderingControl = mock(Service.class);
        // every action the client asks for exists
        when(renderingControl.getAction(anyString())).thenReturn(mock(Action.class));
        when(renderer.findService(any(ServiceId.class))).thenAnswer(invocation -> renderingControl);
    }

    private void bindServerService(YaaccUpnpServerService serverService) throws ReflectiveOperationException {
        Field field = UpnpClient.class.getDeclaredField("yaaccUpnpServerService");
        field.setAccessible(true);
        field.set(client, serverService);
    }

    private void bindListener(NetworkDeviceListener listener) throws ReflectiveOperationException {
        YaaccUpnpServerService serverService = mock(YaaccUpnpServerService.class);
        when(serverService.getNetworkDeviceListener()).thenReturn(listener);
        when(serverService.isInitialized()).thenReturn(true);
        bindServerService(serverService);
    }

    // ---- the helper ----

    @Test
    public void senderIsNullWithoutServiceListenerOrSender() throws ReflectiveOperationException {
        assertNull("no server service bound", client.getHttpRequestSender());

        bindListener(null);
        assertNull("no network listener yet", client.getHttpRequestSender());

        NetworkDeviceListener listener = mock(NetworkDeviceListener.class);
        bindListener(listener);
        assertNull("listener without sender", client.getHttpRequestSender());

        HttpRequestSender sender = mock(HttpRequestSender.class);
        when(listener.getHttpRequestSender()).thenReturn(sender);
        assertSame(sender, client.getHttpRequestSender());
    }

    // ---- mute and volume: no service bound ----

    @Test
    public void getMuteWithoutServerServiceIsFalse() {
        assertFalse(client.getMute(renderer));
    }

    @Test
    public void getVolumeWithoutServerServiceIsZero() {
        assertEquals(0, client.getVolume(renderer));
    }

    @Test
    public void setMuteWithoutServerServiceDoesNothing() {
        client.setMute(renderer, true);
    }

    @Test
    public void setVolumeWithoutServerServiceDoesNothing() {
        client.setVolume(renderer, 30);
    }

    // ---- mute and volume: service bound, no listener / no sender ----

    @Test
    public void muteAndVolumeWithoutNetworkListenerGiveUpSoftly() throws ReflectiveOperationException {
        bindListener(null);
        assertFalse(client.getMute(renderer));
        assertEquals(0, client.getVolume(renderer));
        client.setMute(renderer, false);
        client.setVolume(renderer, 10);
    }

    @Test
    public void muteAndVolumeWithoutSenderGiveUpSoftly() throws ReflectiveOperationException {
        NetworkDeviceListener listener = mock(NetworkDeviceListener.class);
        when(listener.getHttpRequestSender()).thenReturn(null);
        bindListener(listener);

        assertFalse(client.getMute(renderer));
        assertEquals(0, client.getVolume(renderer));
        client.setMute(renderer, true);
        client.setVolume(renderer, 80);
        verify(listener, never()).getUdpTransiver();
    }

    // ---- search ----

    @Test
    public void searchWithoutServerServiceDoesNothing() {
        client.searchDevices();
    }

    @Test
    public void searchWithInitializedServiceButNoListenerDoesNothing() throws ReflectiveOperationException {
        bindListener(null);
        client.searchDevices();
    }

    @Test
    public void searchWithListenerWithoutTransceiverDoesNothing() throws ReflectiveOperationException {
        NetworkDeviceListener listener = mock(NetworkDeviceListener.class);
        when(listener.getUdpTransiver()).thenReturn(null);
        bindListener(listener);
        client.searchDevices();
        verify(listener).getUdpTransiver();
    }
}
