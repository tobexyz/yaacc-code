/*
 *
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
/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package de.yaacc.upnp.registry;

import org.fourthline.cling.model.DiscoveryOptions;
import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.gena.LocalGENASubscription;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.resource.Resource;
import org.fourthline.cling.model.types.UDN;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.upnp.protocol.SendingAsync;
import de.yaacc.util.YaaccLogger;

/**
 * Internal class, required by {@link de.yaacc.upnp.registry.RegistryImpl}.
 *
 * @author Christian Bauer
 */
class LocalItems extends RegistryItems<LocalDevice, LocalGENASubscription> {

    private final ExecutorService executorService;
    int ALIVE_INTERVAL_MILLIS = 0; //Defaults to zero, disabling ALIVE flooding.
    protected Map<UDN, DiscoveryOptions> discoveryOptions = new HashMap<>();
    protected long lastAliveIntervalTimestamp = 0;
    protected Random randomGenerator = new Random();

    LocalItems(RegistryImpl registry) {
        super(registry);
        executorService = Executors.newFixedThreadPool(20);
    }

    protected void setDiscoveryOptions(UDN udn, DiscoveryOptions options) {
        if (options != null)
            this.discoveryOptions.put(udn, options);
        else
            this.discoveryOptions.remove(udn);
    }

    protected DiscoveryOptions getDiscoveryOptions(UDN udn) {
        return this.discoveryOptions.get(udn);
    }

    protected boolean isAdvertised(UDN udn) {
        // Defaults to true
        return getDiscoveryOptions(udn) == null || getDiscoveryOptions(udn).isAdvertised();
    }

    protected boolean isByeByeBeforeFirstAlive(UDN udn) {
        // Defaults to false
        return getDiscoveryOptions(udn) != null && getDiscoveryOptions(udn).isByeByeBeforeFirstAlive();
    }

    void add(LocalDevice localDevice) throws RegistrationException {
        add(localDevice, null);
    }

    void add(final LocalDevice localDevice, DiscoveryOptions options) throws RegistrationException {

        // Always set/override the options, even if we don't end up adding the device
        setDiscoveryOptions(localDevice.getIdentity().getUdn(), options);

        if (registry.getDevice(localDevice.getIdentity().getUdn(), false) != null) {
            YaaccLogger.v(getClass().getName(), "Ignoring addition, device already registered: " + localDevice);
            return;
        }

        YaaccLogger.v(getClass().getName(), "Adding local device to registry: " + localDevice);

        for (Resource deviceResource : getResources(localDevice)) {

            if (registry.getResource(deviceResource.getPathQuery()) != null) {
                throw new RegistrationException("URI namespace conflict with already registered resource: " + deviceResource);
            }

            registry.addResource(deviceResource);
            YaaccLogger.v(getClass().getName(), "Registered resource: " + deviceResource);

        }

        YaaccLogger.v(getClass().getName(), "Adding item to registry with expiration in seconds: " + localDevice.getIdentity().getMaxAgeSeconds());

        RegistryItem<UDN, LocalDevice> localItem = new RegistryItem<>(
                localDevice.getIdentity().getUdn(),
                localDevice,
                localDevice.getIdentity().getMaxAgeSeconds()
        );

        getDeviceItems().add(localItem);
        YaaccLogger.v(getClass().getName(), "Registered local device: " + localItem);

        if (isByeByeBeforeFirstAlive(localItem.getKey()))
            advertiseByebye(localDevice, true);

        if (isAdvertised(localItem.getKey()))
            advertiseAlive(localDevice);

        for (final RegistryListener listener : registry.getListeners()) {
            executorService.execute(
                    new Runnable() {
                        public void run() {
                            listener.localDeviceAdded(registry, localDevice);
                        }
                    }
            );
        }

    }

    Collection<LocalDevice> get() {
        Set<LocalDevice> c = new HashSet<>();
        for (RegistryItem<UDN, LocalDevice> item : getDeviceItems()) {
            c.add(item.getItem());
        }
        return Collections.unmodifiableCollection(c);
    }

    boolean remove(final LocalDevice localDevice) throws RegistrationException {
        return remove(localDevice, false);
    }

    boolean remove(final LocalDevice localDevice, boolean shuttingDown) throws RegistrationException {

        LocalDevice registeredDevice = get(localDevice.getIdentity().getUdn(), true);
        if (registeredDevice != null) {

            YaaccLogger.v(getClass().getName(), "Removing local device from registry: " + localDevice);

            setDiscoveryOptions(localDevice.getIdentity().getUdn(), null);
            getDeviceItems().remove(new RegistryItem(localDevice.getIdentity().getUdn()));

            for (Resource deviceResource : getResources(localDevice)) {
                if (registry.removeResource(deviceResource)) {
                    YaaccLogger.v(getClass().getName(), "Unregistered resource: " + deviceResource);
                }
            }

            // Active subscriptions
            Iterator<RegistryItem<String, LocalGENASubscription>> it = getSubscriptionItems().iterator();
            while (it.hasNext()) {
                final RegistryItem<String, LocalGENASubscription> incomingSubscription = it.next();

                UDN subscriptionForUDN =
                        incomingSubscription.getItem().getService().getDevice().getIdentity().getUdn();

                if (subscriptionForUDN.equals(registeredDevice.getIdentity().getUdn())) {
                    YaaccLogger.v(getClass().getName(), "Removing incoming subscription: " + incomingSubscription.getKey());
                    it.remove();
                    if (!shuttingDown) {
                        executorService.execute(
                                new Runnable() {
                                    public void run() {
                                        incomingSubscription.getItem().end(CancelReason.DEVICE_WAS_REMOVED);
                                    }
                                }
                        );
                    }
                }
            }

            if (isAdvertised(localDevice.getIdentity().getUdn()))
                advertiseByebye(localDevice, !shuttingDown);

            if (!shuttingDown) {
                for (final RegistryListener listener : registry.getListeners()) {
                    executorService.execute(
                            new Runnable() {
                                public void run() {
                                    listener.localDeviceRemoved(registry, localDevice);
                                }
                            }
                    );
                }
            }

            return true;
        }

        return false;
    }

    void removeAll() {
        removeAll(false);
    }

    /* ############################################################################################################ */

    void removeAll(boolean shuttingDown) {
        LocalDevice[] allDevices = get().toArray(new LocalDevice[get().size()]);
        for (LocalDevice device : allDevices) {
            remove(device, shuttingDown);
        }
    }

    /* ############################################################################################################ */

    public void advertiseLocalDevices() {
        for (RegistryItem<UDN, LocalDevice> localItem : deviceItems) {
            if (isAdvertised(localItem.getKey()))
                advertiseAlive(localItem.getItem());
        }
    }

    void maintain() {

        if (getDeviceItems().isEmpty()) return;

        Set<RegistryItem<UDN, LocalDevice>> expiredLocalItems = new HashSet<>();

        // "Flooding" is enabled, check if we need to send advertisements for all devices
        int aliveIntervalMillis = ALIVE_INTERVAL_MILLIS;
        if (aliveIntervalMillis > 0) {
            long now = System.currentTimeMillis();
            if (now - lastAliveIntervalTimestamp > aliveIntervalMillis) {
                lastAliveIntervalTimestamp = now;
                for (RegistryItem<UDN, LocalDevice> localItem : getDeviceItems()) {
                    if (isAdvertised(localItem.getKey())) {
                        YaaccLogger.v(getClass().getName(), "Flooding advertisement of local item: " + localItem);
                        expiredLocalItems.add(localItem);
                    }
                }
            }
        } else {
            // Reset, the configuration might dynamically switch the alive interval
            lastAliveIntervalTimestamp = 0;

            // Alive interval is not enabled, regular expiration check of all devices
            for (RegistryItem<UDN, LocalDevice> localItem : getDeviceItems()) {
                if (isAdvertised(localItem.getKey()) && localItem.getExpirationDetails().hasExpired(true)) {
                    YaaccLogger.v(getClass().getName(), "Local item has expired: " + localItem);
                    expiredLocalItems.add(localItem);
                }
            }
        }

        // Now execute the advertisements
        for (RegistryItem<UDN, LocalDevice> expiredLocalItem : expiredLocalItems) {
            YaaccLogger.v(getClass().getName(), "Refreshing local device advertisement: " + expiredLocalItem.getItem());
            advertiseAlive(expiredLocalItem.getItem());
            expiredLocalItem.getExpirationDetails().stampLastRefresh();
        }

        // Expire incoming subscriptions
        Set<RegistryItem<String, LocalGENASubscription>> expiredIncomingSubscriptions = new HashSet<>();
        for (RegistryItem<String, LocalGENASubscription> item : getSubscriptionItems()) {
            if (item.getExpirationDetails().hasExpired(false)) {
                expiredIncomingSubscriptions.add(item);
            }
        }
        for (RegistryItem<String, LocalGENASubscription> subscription : expiredIncomingSubscriptions) {
            YaaccLogger.v(getClass().getName(), "Removing expired: " + subscription);
            removeSubscription(subscription.getItem());
            subscription.getItem().end(CancelReason.EXPIRED);
        }

    }

    /* ############################################################################################################ */

    void shutdown() {
        YaaccLogger.v(getClass().getName(), "Clearing all registered subscriptions to local devices during shutdown");
        getSubscriptionItems().clear();

        YaaccLogger.v(getClass().getName(), "Removing all local devices from registry during shutdown");
        removeAll(true);
    }

    protected void advertiseAlive(final LocalDevice localDevice) {
        registry.executeAsyncProtocol(new Runnable() {
            public void run() {
                try {
                    YaaccLogger.v(getClass().getName(), "Sleeping some milliseconds to avoid flooding the network with ALIVE msgs");
                    Thread.sleep(randomGenerator.nextInt(100));
                } catch (InterruptedException ex) {
                    YaaccLogger.e(getClass().getName(), "Background execution interrupted: " + ex.getMessage());
                }
                registry.getUpnpProtocolHandler().createSendingNotificationAlive(localDevice).run();
            }
        });
    }

    protected void advertiseByebye(final LocalDevice localDevice, boolean asynchronous) {
        final SendingAsync prot = registry.getUpnpProtocolHandler().createSendingNotificationByebye(localDevice);
        if (asynchronous) {
            registry.executeAsyncProtocol(prot);
        } else {
            prot.run();
        }
    }

}
