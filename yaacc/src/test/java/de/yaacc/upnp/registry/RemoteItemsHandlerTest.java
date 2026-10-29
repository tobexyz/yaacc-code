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
package de.yaacc.upnp.registry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.junit.Test;

import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.upnp.protocol.sync.SendingRenewal;

/**
 * While the network listener is disabled the registry has no protocol handler (see
 * {@code NetworkDeviceListener#disable()}): the maintainer thread must skip the renewal, not throw.
 */
public class RemoteItemsHandlerTest {

    @Test
    public void renewalWithoutHandlerIsPostponedNotAnException() {
        RegistryImpl registry = mock(RegistryImpl.class);
        when(registry.getProtocolHandler()).thenReturn(null);
        RemoteItems items = new RemoteItems(registry);

        items.renewOutgoingSubscription(mock(RemoteGENASubscription.class));

        verify(registry, never()).executeAsyncProtocol(any());
    }

    @Test
    public void renewalWithHandlerIsSentWithThatHandler() {
        RegistryImpl registry = mock(RegistryImpl.class);
        UpnpProtocolHandler handler = mock(UpnpProtocolHandler.class);
        SendingRenewal renewal = mock(SendingRenewal.class);
        RemoteGENASubscription subscription = mock(RemoteGENASubscription.class);
        when(registry.getProtocolHandler()).thenReturn(handler);
        when(handler.createSendingRenewal(subscription)).thenReturn(renewal);
        RemoteItems items = new RemoteItems(registry);

        items.renewOutgoingSubscription(subscription);

        verify(registry).executeAsyncProtocol(renewal);
    }
}
