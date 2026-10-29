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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.support.model.BrowseFlag;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;
import de.yaacc.upnp.server.NetworkDeviceListener;
import de.yaacc.upnp.server.YaaccUpnpServerService;

/**
 * {@link UpnpClient#browseSync} after the network went away: a missing device, service, server
 * service, network listener or HTTP sender gives a failure result (transport failure where waiting
 * can help) instead of a NullPointerException, and a failure is told apart as transport or server error.
 */
public class UpnpClientBrowseGuardTest {

    private UpnpClient client;
    private Device<?, ?, ?> device;

    @Before
    public void setUp() {
        client = new UpnpClient();
        device = mock(Device.class);
        Service<?, ?> service = mock(Service.class);
        when(device.findService(org.mockito.ArgumentMatchers.any(ServiceId.class))).thenAnswer(invocation -> service);
    }

    private ContentDirectoryBrowseResult browse(Device<?, ?, ?> target) {
        return client.browseSync(target, "0", BrowseFlag.DIRECT_CHILDREN, "*", 0L, 50L);
    }

    private void bindServerService(YaaccUpnpServerService serverService) throws ReflectiveOperationException {
        Field field = UpnpClient.class.getDeclaredField("yaaccUpnpServerService");
        field.setAccessible(true);
        field.set(client, serverService);
    }

    @Test
    public void missingDeviceIsATransportFailureNotAnEmptyFolder() {
        ContentDirectoryBrowseResult result = browse(null);

        assertNotNull(result.getUpnpFailure());
        assertTrue(result.getUpnpFailure().isTransportFailure());
    }

    @Test
    public void deviceWithoutContentDirectoryIsAFailureWaitingDoesNotFix() {
        Device<?, ?, ?> other = mock(Device.class);

        ContentDirectoryBrowseResult result = browse(other);

        assertNotNull(result.getUpnpFailure());
        assertFalse(result.getUpnpFailure().isTransportFailure());
    }

    @Test
    public void noServerServiceBoundGivesATransportFailure() {
        ContentDirectoryBrowseResult result = browse(device);

        assertNotNull(result.getUpnpFailure());
        assertTrue(result.getUpnpFailure().isTransportFailure());
    }

    @Test
    public void noNetworkListenerYetGivesATransportFailure() throws ReflectiveOperationException {
        YaaccUpnpServerService serverService = mock(YaaccUpnpServerService.class);
        when(serverService.getNetworkDeviceListener()).thenReturn(null);
        bindServerService(serverService);

        ContentDirectoryBrowseResult result = browse(device);

        assertNotNull(result.getUpnpFailure());
        assertTrue(result.getUpnpFailure().isTransportFailure());
    }

    @Test
    public void noHttpSenderGivesATransportFailure() throws ReflectiveOperationException {
        YaaccUpnpServerService serverService = mock(YaaccUpnpServerService.class);
        NetworkDeviceListener listener = mock(NetworkDeviceListener.class);
        when(listener.getHttpRequestSender()).thenReturn(null);
        when(serverService.getNetworkDeviceListener()).thenReturn(listener);
        bindServerService(serverService);

        ContentDirectoryBrowseResult result = browse(device);

        assertNotNull(result.getUpnpFailure());
        assertTrue(result.getUpnpFailure().isTransportFailure());
    }

    @Test
    public void failureIsATransportFailureOnlyForTheTransportException() {
        ActionInvocation<?> transport = new ActionInvocation<>(new UpnpTransportException("no response"));
        ActionInvocation<?> serverFault = new ActionInvocation<>(new ActionException(ErrorCode.INVALID_ARGS, "bad sort"));
        ActionInvocation<?> actionFailed = new ActionInvocation<>(new ActionException(ErrorCode.ACTION_FAILED, "fault"));

        assertTrue(new UpnpFailure(transport, null, null).isTransportFailure());
        assertFalse(new UpnpFailure(serverFault, null, null).isTransportFailure());
        assertFalse("a fault answered by the server is no transport failure even with the same code", new UpnpFailure(actionFailed, null, null).isTransportFailure());
        assertFalse(new UpnpFailure(null, null, "x").isTransportFailure());
        assertEquals(ErrorCode.ACTION_FAILED.getCode(), new UpnpTransportException("x").getErrorCode());
    }
}
