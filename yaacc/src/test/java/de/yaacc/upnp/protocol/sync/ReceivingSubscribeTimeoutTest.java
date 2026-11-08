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
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.gena.IncomingSubscribeRequestMessage;
import org.fourthline.cling.model.meta.LocalService;
import org.junit.Test;

import java.net.URI;
import java.net.URL;
import java.util.Collections;

import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.server.http.HttpRequestSender;

/** A subscription that never ends can not be removed and fills the limited number of subscribers for good. */
public class ReceivingSubscribeTimeoutTest {

    @Test
    public void theTimeOfASubscriptionIsBetweenOneAndThirtyMinutes() {
        assertEquals(Integer.valueOf(1800), ReceivingSubscribe.clampTimeout(null));
        assertEquals(Integer.valueOf(1800), ReceivingSubscribe.clampTimeout(0));          // "Second-0"
        assertEquals(Integer.valueOf(1800), ReceivingSubscribe.clampTimeout(-5));
        assertEquals(Integer.valueOf(1800), ReceivingSubscribe.clampTimeout(Integer.MAX_VALUE)); // "Second-infinite"
        assertEquals(Integer.valueOf(1800), ReceivingSubscribe.clampTimeout(86400));
        assertEquals(Integer.valueOf(60), ReceivingSubscribe.clampTimeout(1));
        assertEquals(Integer.valueOf(300), ReceivingSubscribe.clampTimeout(300));
    }

    @Test
    public void oneHostCanNotTakeAllTheSubscriptions() throws Exception {
        Registry registry = mock(Registry.class);
        when(registry.getLocalSubscriptionCountForHost("192.0.2.7")).thenReturn(ReceivingSubscribe.MAX_SUBSCRIPTIONS_PER_HOST);
        ReceivingSubscribe protocol = new ReceivingSubscribe(registry, mock(HttpRequestSender.class),
                new StreamRequestMessage(UpnpRequest.Method.SUBSCRIBE, URI.create("/event")));
        IncomingSubscribeRequestMessage request = mock(IncomingSubscribeRequestMessage.class);
        when(request.getCallbackURLs()).thenReturn(Collections.singletonList(new URL("http://192.0.2.7:4321/cb")));
        when(request.hasNotificationHeader()).thenReturn(true);

        assertEquals(UpnpResponse.Status.SERVICE_UNAVAILABLE.getStatusCode(),
                protocol.processNewSubscription(mock(LocalService.class), request).getOperation().getStatusCode());
        verify(registry, never()).addLocalSubscription(any());
    }

    private ReceivingSubscribe protocol(Registry registry) {
        return new ReceivingSubscribe(registry, mock(HttpRequestSender.class),
                new StreamRequestMessage(UpnpRequest.Method.SUBSCRIBE, URI.create("/event")));
    }

    @Test
    public void aRenewalForAnEndlessTimeIsGivenTheLongestRealTime() {
        Registry registry = mock(Registry.class);
        org.fourthline.cling.model.gena.LocalGENASubscription subscription = mock(org.fourthline.cling.model.gena.LocalGENASubscription.class);
        when(subscription.getSubscriptionId()).thenReturn("uuid:renew");
        when(subscription.getActualDurationSeconds()).thenReturn(1800);
        when(registry.getLocalSubscription(any())).thenReturn(subscription);
        when(registry.updateLocalSubscription(subscription)).thenReturn(true);
        IncomingSubscribeRequestMessage request = mock(IncomingSubscribeRequestMessage.class);
        when(request.getRequestedTimeoutSeconds()).thenReturn(Integer.MAX_VALUE); // "Second-infinite"

        protocol(registry).processRenewal(mock(LocalService.class), request);

        verify(subscription).setSubscriptionDuration(1800);
    }

    @Test
    public void aNewSubscriptionForAnEndlessTimeLastsAtMostThirtyMinutes() throws Exception {
        Registry registry = mock(Registry.class);
        LocalService service = mock(LocalService.class);
        org.fourthline.cling.model.meta.LocalDevice device = mock(org.fourthline.cling.model.meta.LocalDevice.class);
        org.fourthline.cling.model.types.ServiceId serviceId = new org.fourthline.cling.model.types.UDAServiceId("Test");
        when(service.getServiceId()).thenReturn(serviceId);
        when(service.getDevice()).thenReturn(device);
        org.fourthline.cling.model.ServiceManager serviceManager = mock(org.fourthline.cling.model.ServiceManager.class);
        when(service.getManager()).thenReturn(serviceManager);
        when(serviceManager.getCurrentState()).thenReturn(Collections.emptyList());
        IncomingSubscribeRequestMessage request = mock(IncomingSubscribeRequestMessage.class);
        when(request.getCallbackURLs()).thenReturn(Collections.singletonList(new URL("http://192.0.2.7:4321/cb")));
        when(request.hasNotificationHeader()).thenReturn(true);
        when(request.getRequestedTimeoutSeconds()).thenReturn(0); // "Second-0" is no time limit for Cling
        org.mockito.ArgumentCaptor<org.fourthline.cling.model.gena.LocalGENASubscription> added =
                org.mockito.ArgumentCaptor.forClass(org.fourthline.cling.model.gena.LocalGENASubscription.class);

        protocol(registry).processNewSubscription(service, request);

        verify(registry).addLocalSubscription(added.capture());
        assertNotNull(added.getValue());
        assertEquals(1800, added.getValue().getActualDurationSeconds());
    }
}
