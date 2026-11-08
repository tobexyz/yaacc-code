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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.gena.LocalGENASubscription;
import org.fourthline.cling.model.meta.LocalService;
import org.junit.Test;

import java.net.URL;
import java.util.Arrays;

/** The counts that limit the event subscribers of the device. */
public class LocalItemsSubscriptionCountTest {

    private static LocalGENASubscription subscription(String id, LocalService<?> service, String... callbacks) throws Exception {
        LocalGENASubscription subscription = mock(LocalGENASubscription.class);
        when(subscription.getSubscriptionId()).thenReturn(id);
        when(subscription.getActualDurationSeconds()).thenReturn(1800);
        when(subscription.getService()).thenReturn((LocalService) service);
        URL[] urls = new URL[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            urls[i] = new URL(callbacks[i]);
        }
        when(subscription.getCallbackURLs()).thenReturn(Arrays.asList(urls));
        return subscription;
    }

    @Test
    public void subscribersAreCountedInTotalPerServiceAndPerHost() throws Exception {
        LocalItems items = new LocalItems(mock(RegistryImpl.class));
        LocalService<?> playlist = mock(LocalService.class);
        LocalService<?> product = mock(LocalService.class);
        items.addSubscription(subscription("uuid:1", playlist, "http://192.0.2.7:1000/cb"));
        items.addSubscription(subscription("uuid:2", product, "http://192.0.2.7:1001/cb", "http://192.0.2.8:1/cb"));
        items.addSubscription(subscription("uuid:3", product, "http://192.0.2.9:1/cb"));

        assertEquals(3, items.countSubscriptions(null));
        assertEquals(1, items.countSubscriptions(playlist));
        assertEquals(2, items.countSubscriptions(product));
        assertEquals(2, items.countSubscriptionsForHost("192.0.2.7"));
        assertEquals(1, items.countSubscriptionsForHost("192.0.2.8")); // one of several callbacks
        assertEquals(0, items.countSubscriptionsForHost("192.0.2.99"));
        assertEquals(0, items.countSubscriptionsForHost(null));
    }

    @Test
    public void theHostIsComparedWithoutRegardToCase() throws Exception {
        LocalItems items = new LocalItems(mock(RegistryImpl.class));
        items.addSubscription(subscription("uuid:1", mock(LocalService.class), "http://Controller.Local:1000/cb"));

        assertEquals(1, items.countSubscriptionsForHost("controller.local"));
    }
}
