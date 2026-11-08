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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.gena.IncomingSubscribeRequestMessage;
import org.fourthline.cling.model.message.gena.OutgoingSubscribeResponseMessage;
import org.fourthline.cling.model.meta.LocalService;
import org.junit.Test;

import java.net.URI;
import java.net.URL;
import java.util.Collections;

import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.server.http.HttpRequestSender;

/** Every host of the network may subscribe for events: the number of subscribers is limited. */
public class ReceivingSubscribeLimitTest {

    private final Registry registry = mock(Registry.class);
    private final LocalService<?> service = mock(LocalService.class);

    private OutgoingSubscribeResponseMessage subscribe() throws Exception {
        ReceivingSubscribe protocol = new ReceivingSubscribe(registry, mock(HttpRequestSender.class),
                new StreamRequestMessage(UpnpRequest.Method.SUBSCRIBE, URI.create("/event")));
        IncomingSubscribeRequestMessage request = mock(IncomingSubscribeRequestMessage.class);
        when(request.getCallbackURLs()).thenReturn(Collections.singletonList(new URL("http://192.0.2.7:1234/cb")));
        when(request.hasNotificationHeader()).thenReturn(true);
        return protocol.processNewSubscription(service, request);
    }

    @Test
    public void tooManySubscribersInTotalAreRefused() throws Exception {
        when(registry.getLocalSubscriptionCount(null)).thenReturn(ReceivingSubscribe.MAX_SUBSCRIPTIONS);

        assertEquals(UpnpResponse.Status.SERVICE_UNAVAILABLE.getStatusCode(), subscribe().getOperation().getStatusCode());
        verify(registry, never()).addLocalSubscription(any());
    }

    @Test
    public void tooManySubscribersOfOneServiceAreRefused() throws Exception {
        when(registry.getLocalSubscriptionCount(service)).thenReturn(ReceivingSubscribe.MAX_SUBSCRIPTIONS_PER_SERVICE);

        assertEquals(UpnpResponse.Status.SERVICE_UNAVAILABLE.getStatusCode(), subscribe().getOperation().getStatusCode());
        verify(registry, never()).addLocalSubscription(any());
    }
}
