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
package de.yaacc.upnp.protocol.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.control.OutgoingActionRequestMessage;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.ActionArgument;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.model.types.UDAServiceType;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;

import de.yaacc.upnp.UpnpTransportException;
import de.yaacc.upnp.server.http.HttpRequestSender;

/**
 * The failures that mean "no connection / no answer" are told apart from an answer of the server, so
 * a browse can be repeated after a network drop ({@code BrowseRetryPolicy}).
 */
public class SendingActionTransportFailureTest {

    private ActionInvocation<?> run(HttpRequestSender sender) throws Exception {
        Service service = mock(Service.class);
        Device device = mock(Device.class);
        Action action = mock(Action.class);
        when(action.getName()).thenReturn("Browse");
        when(action.getService()).thenReturn(service);
        when(action.getInputArguments()).thenReturn(new ActionArgument[0]);
        when(action.getOutputArguments()).thenReturn(new ActionArgument[0]);
        when(service.getDevice()).thenReturn(device);
        when(service.getServiceType()).thenReturn(new UDAServiceType("ContentDirectory"));
        ActionInvocation invocation = new ActionInvocation(action);
        SendingAction sendingAction = new SendingAction(sender, invocation, new URL("http://127.0.0.1:1/control"));
        sendingAction.run();
        return invocation;
    }

    @Test
    public void noResponseIsATransportFailure() throws Exception {
        HttpRequestSender sender = mock(HttpRequestSender.class);
        when(sender.send(any(StreamRequestMessage.class))).thenReturn(null);

        ActionInvocation<?> invocation = run(sender);

        assertTrue(invocation.getFailure() instanceof UpnpTransportException);
    }

    @Test
    public void ioErrorIsATransportFailure() throws Exception {
        HttpRequestSender sender = mock(HttpRequestSender.class);
        when(sender.send(any(StreamRequestMessage.class))).thenThrow(new IOException("connection reset"));

        ActionInvocation<?> invocation = run(sender);

        assertTrue(invocation.getFailure() instanceof UpnpTransportException);
        assertEquals(ErrorCode.ACTION_FAILED.getCode(), invocation.getFailure().getErrorCode());
    }
}
