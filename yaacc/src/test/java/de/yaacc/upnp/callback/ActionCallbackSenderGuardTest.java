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
package de.yaacc.upnp.callback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.RemoteService;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import de.yaacc.upnp.server.http.HttpRequestSender;

/**
 * {@link ActionCallback#run()} for a remote service: a missing HTTP sender and an unexpected exception of the send
 * both end in the callback's own failure path (so a caller waiting for the action to finish is told), never in an
 * exception on the pool thread.
 */
public class ActionCallbackSenderGuardTest {

    private final List<String> failures = new ArrayList<>();
    private int successes;
    private ActionInvocation invocation;

    @Before
    public void setUp() throws Exception {
        RemoteService service = mock(RemoteService.class);
        RemoteDevice device = mock(RemoteDevice.class);
        when(service.getDevice()).thenReturn(device);
        when(service.getControlURI()).thenReturn(URI.create("/control"));
        when(device.normalizeURI(URI.create("/control"))).thenReturn(new URL("http://192.0.2.1:80/control"));
        Action action = mock(Action.class);
        when(action.getService()).thenReturn(service);
        when(action.getName()).thenReturn("GetVolume");
        invocation = mock(ActionInvocation.class);
        when(invocation.getAction()).thenReturn(action);
    }

    private ActionCallback callback(HttpRequestSender sender) {
        return new ActionCallback(invocation, sender) {
            @Override
            public void success(ActionInvocation invocation) {
                successes++;
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                failures.add(defaultMsg);
            }
        };
    }

    @Test
    public void missingSenderIsAFailureNotAnException() {
        callback(null).run();

        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("No HTTP request sender"));
        assertEquals(0, successes);
    }

    @Test
    public void unexpectedExceptionWhileSendingIsAFailureNotAnException() {
        // the mocked invocation cannot be turned into a request: the send throws a RuntimeException
        callback(mock(HttpRequestSender.class)).run();

        assertEquals(1, failures.size());
        assertNotNull(failures.get(0));
        assertTrue(failures.get(0).startsWith("Action failed unexpectedly"));
        assertEquals(0, successes);
    }
}
