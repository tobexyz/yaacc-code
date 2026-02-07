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

import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.RemoteDeviceIdentity;
import org.fourthline.cling.model.resource.Resource;
import org.fourthline.cling.model.types.UDN;
import de.yaacc.upnp.registry.RegistrationException;
import de.yaacc.upnp.registry.RegistryListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Internal class, required by {@link org.fourthline.cling.registry.RegistryImpl}.
 *
 * @author Christian Bauer
 */
class RemoteItems extends RegistryItems<RemoteDevice, RemoteGENASubscription> {


    private final ExecutorService executorService;

    RemoteItems(RegistryImpl registry) {
        super(registry);
        executorService = Executors.newFixedThreadPool(20);
    }

    /**
     * Adds the given remote device to the registry, or udpates its expiration timestamp.
     * <p>
     * This method first checks if there is a remote device with the same UDN already registered. If so, it
     * updates the expiration timestamp of the remote device without notifying any registry listeners. If the
     * device is truly new, all its resources are tested for conflicts with existing resources in the registry's
     * namespace, then it is added to the registry and listeners are notified that a new fully described remote
     * device is now available.
     * </p>
     *
     * @param device The remote device to be added
     */
    void add(final RemoteDevice device) {

        if (update(device.getIdentity())) {
            YaaccLogger.v(getClass().getName(), "Ignoring addition, device already registered: " + device);
            return;
        }

        Resource[] resources = getResources(device);

        for (Resource deviceResource : resources) {
            YaaccLogger.v(getClass().getName(), "Validating remote device resource; " + deviceResource);
            if (registry.getResource(deviceResource.getPathQuery()) != null) {
                throw new RegistrationException("URI namespace conflict with already registered resource: " + deviceResource);
            }
        }

        for (Resource validatedResource : resources) {
            registry.addResource(validatedResource);
            YaaccLogger.v(getClass().getName(), "Added remote device resource: " + validatedResource);
        }

        // Override the device's maximum age if configured (systems without multicast support)
        RegistryItem item = new RegistryItem(
                device.getIdentity().getUdn(),
                device,
                device.getIdentity().getMaxAgeSeconds()
        );
        YaaccLogger.v(getClass().getName(), "Adding hydrated remote device to registry with "
                + item.getExpirationDetails().getMaxAgeSeconds() + " seconds expiration: " + device);
        getDeviceItems().add(item);


        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("-------------------------- START Registry Namespace -----------------------------------\n");
        for (Resource resource : registry.getResources()) {
            sb.append(resource).append("\n");
        }
        sb.append("-------------------------- END Registry Namespace -----------------------------------");
        YaaccLogger.v(getClass().getName(), sb.toString());


        // Only notify the listeners when the device is fully usable
        YaaccLogger.v(getClass().getName(), "Completely hydrated remote device graph available, calling listeners: " + device);
        for (final RegistryListener listener : registry.getListeners()) {
            executorService.execute(
                    new Runnable() {
                        public void run() {
                            listener.remoteDeviceAdded(registry, device);
                        }
                    }
            );
        }

    }

    boolean update(RemoteDeviceIdentity rdIdentity) {

        for (LocalDevice localDevice : registry.getLocalDevices()) {
            if (localDevice.findDevice(rdIdentity.getUdn()) != null) {
                YaaccLogger.v(getClass().getName(), "Ignoring update, a local device graph contains UDN");
                return true;
            }
        }

        RemoteDevice registeredRemoteDevice = get(rdIdentity.getUdn(), false);
        if (registeredRemoteDevice != null) {

            if (!registeredRemoteDevice.isRoot()) {
                YaaccLogger.v(getClass().getName(), "Updating root device of embedded: " + registeredRemoteDevice);
                registeredRemoteDevice = registeredRemoteDevice.getRoot();
            }

            // Override the device's maximum age if configured (systems without multicast support)
            final RegistryItem<UDN, RemoteDevice> item = new RegistryItem<>(
                    registeredRemoteDevice.getIdentity().getUdn(),
                    registeredRemoteDevice,
                    rdIdentity.getMaxAgeSeconds()
            );

            YaaccLogger.v(getClass().getName(), "Updating expiration of: " + registeredRemoteDevice);
            getDeviceItems().remove(item);
            getDeviceItems().add(item);

            YaaccLogger.v(getClass().getName(), "Remote device updated, calling listeners: " + registeredRemoteDevice);
            for (final RegistryListener listener : registry.getListeners()) {
                executorService.execute(
                        new Runnable() {
                            public void run() {
                                listener.remoteDeviceUpdated(registry, item.getItem());
                            }
                        }
                );
            }

            return true;

        }
        return false;
    }

    /**
     * Removes the given device from the registry and notifies registry listeners.
     *
     * @param remoteDevice The device to remove from the registry.
     * @return <tt>true</tt> if the given device was found and removed from the registry, false if it wasn't registered.
     */
    boolean remove(final RemoteDevice remoteDevice) {
        return remove(remoteDevice, false);
    }

    boolean remove(final RemoteDevice remoteDevice, boolean shuttingDown) throws RegistrationException {
        final RemoteDevice registeredDevice = get(remoteDevice.getIdentity().getUdn(), true);
        if (registeredDevice != null) {

            YaaccLogger.v(getClass().getName(), "Removing remote device from registry: " + remoteDevice);

            // Resources
            for (Resource deviceResource : getResources(registeredDevice)) {
                if (registry.removeResource(deviceResource)) {
                    YaaccLogger.v(getClass().getName(), "Unregistered resource: " + deviceResource);
                }
            }

            // Active subscriptions
            Iterator<RegistryItem<String, RemoteGENASubscription>> it = getSubscriptionItems().iterator();
            while (it.hasNext()) {
                final RegistryItem<String, RemoteGENASubscription> outgoingSubscription = it.next();

                UDN subscriptionForUDN =
                        outgoingSubscription.getItem().getService().getDevice().getIdentity().getUdn();

                if (subscriptionForUDN.equals(registeredDevice.getIdentity().getUdn())) {
                    YaaccLogger.v(getClass().getName(), "Removing outgoing subscription: " + outgoingSubscription.getKey());
                    it.remove();
                    if (!shuttingDown) {
                        executorService.execute(
                                new Runnable() {
                                    public void run() {
                                        outgoingSubscription.getItem().end(CancelReason.DEVICE_WAS_REMOVED, null);
                                    }
                                }
                        );
                    }
                }
            }

            // Only notify listeners if we are NOT in the process of shutting down the registry
            if (!shuttingDown) {
                for (final RegistryListener listener : registry.getListeners()) {
                    executorService.execute(
                            new Runnable() {
                                public void run() {
                                    listener.remoteDeviceRemoved(registry, registeredDevice);
                                }
                            }
                    );
                }
            }

            // Finally, remove the device from the registry
            getDeviceItems().remove(new RegistryItem(registeredDevice.getIdentity().getUdn()));

            return true;
        }

        return false;
    }

    void removeAll() {
        removeAll(false);
    }

    void removeAll(boolean shuttingDown) {
        RemoteDevice[] allDevices = get().toArray(new RemoteDevice[get().size()]);
        for (RemoteDevice device : allDevices) {
            remove(device, shuttingDown);
        }
    }

    /* ############################################################################################################ */

    void start() {
        // Noop
    }

    void maintain() {

        if (getDeviceItems().isEmpty()) return;

        // Remove expired remote devices
        Map<UDN, RemoteDevice> expiredRemoteDevices = new HashMap<>();
        // Iterate over a copy to avoid ConcurrentModificationException
        for (RegistryItem<UDN, RemoteDevice> remoteItem : new HashSet<>(getDeviceItems())) {

            YaaccLogger.v(getClass().getName(), "Device '" + remoteItem.getItem() + "' expires in seconds: "
                    + remoteItem.getExpirationDetails().getSecondsUntilExpiration());
            if (remoteItem.getExpirationDetails().hasExpired(false)) {
                expiredRemoteDevices.put(remoteItem.getKey(), remoteItem.getItem());
            }
        }
        for (RemoteDevice remoteDevice : expiredRemoteDevices.values()) {

            YaaccLogger.v(getClass().getName(), "Removing expired: " + remoteDevice);
            remove(remoteDevice);
        }

        // Renew outgoing subscriptions
        Set<RemoteGENASubscription> expiredOutgoingSubscriptions = new HashSet<>();
        // Iterate over a copy to avoid ConcurrentModificationException
        for (RegistryItem<String, RemoteGENASubscription> item : new HashSet<>(getSubscriptionItems())) {
            if (item.getExpirationDetails().hasExpired(true)) {
                expiredOutgoingSubscriptions.add(item.getItem());
            }
        }
        for (RemoteGENASubscription subscription : expiredOutgoingSubscriptions) {

            YaaccLogger.v(getClass().getName(), "Renewing outgoing subscription: " + subscription);
            renewOutgoingSubscription(subscription);
        }
    }

    public void resume() {
        YaaccLogger.v(getClass().getName(), "Updating remote device expiration timestamps on resume");
        List<RemoteDeviceIdentity> toUpdate = new ArrayList<>();
        // Iterate over a copy to avoid ConcurrentModificationException
        for (RegistryItem<UDN, RemoteDevice> remoteItem : new HashSet<>(getDeviceItems())) {
            toUpdate.add(remoteItem.getItem().getIdentity());
        }
        for (RemoteDeviceIdentity identity : toUpdate) {
            update(identity);
        }
    }

    void shutdown() {
        YaaccLogger.v(getClass().getName(), "Cancelling all outgoing subscriptions to remote devices during shutdown");
        List<RemoteGENASubscription> remoteSubscriptions = new ArrayList<>();
        // Iterate over a copy to avoid ConcurrentModificationException
        for (RegistryItem<String, RemoteGENASubscription> item : new HashSet<>(getSubscriptionItems())) {
            remoteSubscriptions.add(item.getItem());
        }
        for (RemoteGENASubscription remoteSubscription : remoteSubscriptions) {
            // This will remove the active subscription from the registry!
            registry.getProtocolHandler()
                    .createSendingUnsubscribe(remoteSubscription)
                    .run();
        }

        YaaccLogger.v(getClass().getName(), "Removing all remote devices from registry during shutdown");
        removeAll(true);
    }

    /* ############################################################################################################ */

    protected void renewOutgoingSubscription(final RemoteGENASubscription subscription) {
        registry.executeAsyncProtocol(
                registry.getProtocolHandler().createSendingRenewal(subscription)
        );
    }
}
