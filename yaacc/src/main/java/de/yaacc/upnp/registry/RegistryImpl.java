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

import android.util.Log;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.model.DiscoveryOptions;
import org.fourthline.cling.model.ExpirationDetails;
import org.fourthline.cling.model.ServiceReference;
import org.fourthline.cling.model.gena.LocalGENASubscription;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.RemoteDeviceIdentity;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.resource.Resource;
import org.fourthline.cling.model.types.DeviceType;
import org.fourthline.cling.model.types.ServiceType;
import org.fourthline.cling.model.types.UDN;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.registry.RegistryListener;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;


/**
 * Default implementation of {@link Registry}.
 *
 * @author Christian Bauer
 */
public class RegistryImpl implements Registry {

    public static final int SLEEP_INTERVAL_MILLIS = 7000;
    protected final Set<RemoteGENASubscription> pendingSubscriptionsLock = new CopyOnWriteArraySet<>();
    protected final Set<RegistryListener> registryListeners = new CopyOnWriteArraySet<>();
    protected final Set<RegistryItem<URI, Resource>> resourceItems = new CopyOnWriteArraySet<>();
    protected final List<Runnable> pendingExecutions = new CopyOnWriteArrayList<>();
    protected final RemoteItems remoteItems = new RemoteItems(this);
    protected final LocalItems localItems = new LocalItems(this);
    protected UpnpService upnpService;
    protected RegistryMaintainer registryMaintainer;


    // #################################################################################################

    /**
     * Starts background maintenance immediately.
     */

    public RegistryImpl() {
        Log.v(getClass().getName(), "Creating Registry: " + getClass().getName());

        Log.v(getClass().getName(), "Starting registry background maintenance...");
        registryMaintainer = createRegistryMaintainer();
        if (registryMaintainer != null) {
            Executors.newSingleThreadExecutor().execute(registryMaintainer);
        }
    }

    protected RegistryMaintainer createRegistryMaintainer() {
        return new RegistryMaintainer(
                this,
                SLEEP_INTERVAL_MILLIS // Preserve battery on Android, only run every 7 seconds
        );
    }

    // #################################################################################################

    public void addListener(RegistryListener listener) {
        registryListeners.add(listener);
    }

    public void removeListener(RegistryListener listener) {
        registryListeners.remove(listener);
    }

    public Collection<RegistryListener> getListeners() {
        return Collections.unmodifiableCollection(registryListeners);
    }

    public boolean notifyDiscoveryStart(final RemoteDevice device) {
        // Exit if we have it already, this is atomic inside this method, finally
        if (getRemoteDevice(device.getIdentity().getUdn(), true) != null) {
            Log.v(getClass().getName(), "Not notifying listeners, already registered: " + device);
            return false;
        }
        for (final RegistryListener listener : getListeners()) {
            Executors.newSingleThreadExecutor().execute(
                    new Runnable() {
                        public void run() {
                            listener.remoteDeviceDiscoveryStarted(RegistryImpl.this, device);
                        }
                    }
            );
        }
        return true;
    }

    public void notifyDiscoveryFailure(final RemoteDevice device, final Exception ex) {
        for (final RegistryListener listener : getListeners()) {
            Executors.newSingleThreadExecutor().execute(
                    new Runnable() {
                        public void run() {
                            listener.remoteDeviceDiscoveryFailed(RegistryImpl.this, device, ex);
                        }
                    }
            );
        }
    }

    // #################################################################################################

    public void addDevice(LocalDevice localDevice) {
        localItems.add(localDevice);
    }

    public void addDevice(LocalDevice localDevice, DiscoveryOptions options) {
        localItems.add(localDevice, options);
    }

    public void setDiscoveryOptions(UDN udn, DiscoveryOptions options) {
        localItems.setDiscoveryOptions(udn, options);
    }

    public DiscoveryOptions getDiscoveryOptions(UDN udn) {
        return localItems.getDiscoveryOptions(udn);
    }

    public void addDevice(RemoteDevice remoteDevice) {
        remoteItems.add(remoteDevice);
    }

    public boolean update(RemoteDeviceIdentity rdIdentity) {
        return remoteItems.update(rdIdentity);
    }

    public boolean removeDevice(LocalDevice localDevice) {
        return localItems.remove(localDevice);
    }

    public boolean removeDevice(RemoteDevice remoteDevice) {
        return remoteItems.remove(remoteDevice);
    }

    public void removeAllLocalDevices() {
        localItems.removeAll();
    }

    public void removeAllRemoteDevices() {
        remoteItems.removeAll();
    }

    public boolean removeDevice(UDN udn) {
        Device device = getDevice(udn, true);
        if (device != null && device instanceof LocalDevice)
            return removeDevice((LocalDevice) device);
        if (device != null && device instanceof RemoteDevice)
            return removeDevice((RemoteDevice) device);
        return false;
    }

    public Device getDevice(UDN udn, boolean rootOnly) {
        Device device;
        if ((device = localItems.get(udn, rootOnly)) != null) return device;
        if ((device = remoteItems.get(udn, rootOnly)) != null) return device;
        return null;
    }

    public LocalDevice getLocalDevice(UDN udn, boolean rootOnly) {
        return localItems.get(udn, rootOnly);
    }

    public RemoteDevice getRemoteDevice(UDN udn, boolean rootOnly) {
        return remoteItems.get(udn, rootOnly);
    }

    public Collection<LocalDevice> getLocalDevices() {
        return Collections.unmodifiableCollection(localItems.get());
    }

    public Collection<RemoteDevice> getRemoteDevices() {
        return Collections.unmodifiableCollection(remoteItems.get());
    }

    public Collection<Device<?, ?, ?>> getDevices() {
        Set all = new HashSet<>();
        all.addAll(localItems.get());
        all.addAll(remoteItems.get());
        return Collections.unmodifiableCollection(all);
    }

    public Collection<Device<?, ?, ?>> getDevices(DeviceType deviceType) {
        Collection<Device<?, ?, ?>> devices = new HashSet<>();

        devices.addAll(localItems.get(deviceType));
        devices.addAll(remoteItems.get(deviceType));

        return Collections.unmodifiableCollection(devices);
    }

    public Collection<Device<?, ?, ?>> getDevices(ServiceType serviceType) {
        Collection<Device<?, ?, ?>> devices = new HashSet<>();

        devices.addAll(localItems.get(serviceType));
        devices.addAll(remoteItems.get(serviceType));

        return Collections.unmodifiableCollection(devices);
    }

    public Service getService(ServiceReference serviceReference) {
        Device device;
        if ((device = getDevice(serviceReference.getUdn(), false)) != null) {
            return device.findService(serviceReference.getServiceId());
        }
        return null;
    }

    // #################################################################################################

    public Resource getResource(URI pathQuery) throws IllegalArgumentException {
        if (pathQuery.isAbsolute()) {
            throw new IllegalArgumentException("Resource URI can not be absolute, only path and query:" + pathQuery);
        }

        // Note: Uses field access on resourceItems for performance reasons

        for (RegistryItem<URI, Resource> resourceItem : resourceItems) {
            Resource resource = resourceItem.getItem();
            if (resource.matches(pathQuery)) {
                return resource;
            }
        }

        // TODO: UPNP VIOLATION: Fuppes on my ReadyNAS thinks it's a cool idea to add a slash at the end of the callback URI...
        // It also cuts off any query parameters in the callback URL - nice!
        if (pathQuery.getPath().endsWith("/")) {
            URI pathQueryWithoutSlash = URI.create(pathQuery.toString().substring(0, pathQuery.toString().length() - 1));

            for (RegistryItem<URI, Resource> resourceItem : resourceItems) {
                Resource resource = resourceItem.getItem();
                if (resource.matches(pathQueryWithoutSlash)) {
                    return resource;
                }
            }
        }

        return null;
    }

    public <T extends Resource> T getResource(Class<T> resourceType, URI pathQuery) throws IllegalArgumentException {
        Resource resource = getResource(pathQuery);
        if (resource != null && resourceType.isAssignableFrom(resource.getClass())) {
            return (T) resource;
        }
        return null;
    }

    public Collection<Resource> getResources() {
        Collection<Resource> s = new HashSet<>();
        for (RegistryItem<URI, Resource> resourceItem : resourceItems) {
            s.add(resourceItem.getItem());
        }
        return s;
    }

    public <T extends Resource> Collection<T> getResources(Class<T> resourceType) {
        Collection<T> s = new HashSet<>();
        for (RegistryItem<URI, Resource> resourceItem : resourceItems) {
            if (resourceType.isAssignableFrom(resourceItem.getItem().getClass()))
                s.add((T) resourceItem.getItem());
        }
        return s;
    }

    public void addResource(Resource resource) {
        addResource(resource, ExpirationDetails.UNLIMITED_AGE);
    }

    public void addResource(Resource resource, int maxAgeSeconds) {
        RegistryItem resourceItem = new RegistryItem(resource.getPathQuery(), resource, maxAgeSeconds);
        resourceItems.remove(resourceItem);
        resourceItems.add(resourceItem);
    }

    public boolean removeResource(Resource resource) {
        return resourceItems.remove(new RegistryItem(resource.getPathQuery()));
    }

    // #################################################################################################

    public void addLocalSubscription(LocalGENASubscription subscription) {
        localItems.addSubscription(subscription);
    }

    public LocalGENASubscription getLocalSubscription(String subscriptionId) {
        return localItems.getSubscription(subscriptionId);
    }

    public boolean updateLocalSubscription(LocalGENASubscription subscription) {
        return localItems.updateSubscription(subscription);
    }

    public boolean removeLocalSubscription(LocalGENASubscription subscription) {
        return localItems.removeSubscription(subscription);
    }

    public void addRemoteSubscription(RemoteGENASubscription subscription) {
        remoteItems.addSubscription(subscription);
    }

    public RemoteGENASubscription getRemoteSubscription(String subscriptionId) {
        return remoteItems.getSubscription(subscriptionId);
    }

    public void updateRemoteSubscription(RemoteGENASubscription subscription) {
        remoteItems.updateSubscription(subscription);
    }

    public void removeRemoteSubscription(RemoteGENASubscription subscription) {
        remoteItems.removeSubscription(subscription);
    }

    /* ############################################################################################################ */

    public void advertiseLocalDevices() {
        localItems.advertiseLocalDevices();
    }

    /* ############################################################################################################ */

    @Override
    public UpnpService getUpnpService() {
        return null;
    }

    @Override
    public UpnpServiceConfiguration getConfiguration() {
        return null;
    }

    @Override
    public ProtocolFactory getProtocolFactory() {
        return null;
    }

    // When you call this, make sure you have the Router lock before this lock is obtained!
    public void shutdown() {
        Log.v(getClass().getName(), "Shutting down registry...");

        if (registryMaintainer != null)
            registryMaintainer.stop();

        // Final cleanup run to flush out pending executions which might
        // not have been caught by the maintainer before it stopped
        Log.v(getClass().getName(), "Executing final pending operations on shutdown: " + pendingExecutions.size());
        runPendingExecutions(false);

        for (RegistryListener listener : registryListeners) {
            listener.beforeShutdown(this);
        }

        RegistryItem<URI, Resource>[] resources = resourceItems.toArray(new RegistryItem[resourceItems.size()]);
        for (RegistryItem<URI, Resource> resourceItem : resources) {
            resourceItem.getItem().shutdown();
        }

        remoteItems.shutdown();
        localItems.shutdown();

        for (RegistryListener listener : registryListeners) {
            listener.afterShutdown();
        }
    }

    public void pause() {
        if (registryMaintainer != null) {
            Log.v(getClass().getName(), "Pausing registry maintenance");
            runPendingExecutions(true);
            registryMaintainer.stop();
            registryMaintainer = null;
        }
    }

    public void resume() {
        if (registryMaintainer == null) {
            Log.v(getClass().getName(), "Resuming registry maintenance");
            remoteItems.resume();
            registryMaintainer = createRegistryMaintainer();
            if (registryMaintainer != null) {
                Executors.newSingleThreadExecutor().execute(registryMaintainer);
            }
        }
    }

    public boolean isPaused() {
        return registryMaintainer == null;
    }

    /* ############################################################################################################ */

    void maintain() {


        Log.v(getClass().getName(), "Maintaining registry...");

        // Remove expired resources
        Iterator<RegistryItem<URI, Resource>> it = resourceItems.iterator();
        while (it.hasNext()) {
            RegistryItem<URI, Resource> item = it.next();
            if (item.getExpirationDetails().hasExpired()) {

                Log.v(getClass().getName(), "Removing expired resource: " + item);
                it.remove();
            }
        }

        // Let each resource do its own maintenance
        for (RegistryItem<URI, Resource> resourceItem : resourceItems) {
            resourceItem.getItem().maintain(
                    pendingExecutions,
                    resourceItem.getExpirationDetails()
            );
        }

        // These add all their operations to the pendingExecutions queue
        remoteItems.maintain();
        localItems.maintain();

        // We now run the queue asynchronously so the maintenance thread can continue its loop undisturbed
        runPendingExecutions(true);
    }

    void executeAsyncProtocol(Runnable runnable) {
        pendingExecutions.add(runnable);
    }

    void runPendingExecutions(boolean async) {

        Log.v(getClass().getName(), "Executing pending operations: " + pendingExecutions.size());
        for (Runnable pendingExecution : pendingExecutions) {
            if (async)
                Executors.newSingleThreadExecutor().execute(pendingExecution);
            else
                pendingExecution.run();
        }
        if (!pendingExecutions.isEmpty()) {
            pendingExecutions.clear();
        }
    }

    /* ############################################################################################################ */

    public void printDebugLog() {
        {
            Log.v(getClass().getName(), "====================================    REMOTE   ================================================");

            for (RemoteDevice remoteDevice : remoteItems.get()) {
                Log.v(getClass().getName(), remoteDevice.toString());
            }

            Log.v(getClass().getName(), "====================================    LOCAL    ================================================");

            for (LocalDevice localDevice : localItems.get()) {
                Log.v(getClass().getName(), localDevice.toString());
            }

            Log.v(getClass().getName(), "====================================  RESOURCES  ================================================");

            for (RegistryItem<URI, Resource> resourceItem : resourceItems) {
                Log.v(getClass().getName(), resourceItem.toString());
            }

            Log.v(getClass().getName(), "=================================================================================================");

        }

    }

    @Override
    public void registerPendingRemoteSubscription(RemoteGENASubscription subscription) {
        synchronized (pendingSubscriptionsLock) {
            pendingSubscriptionsLock.add(subscription);
        }
    }

    @Override
    public void unregisterPendingRemoteSubscription(RemoteGENASubscription subscription) {
        synchronized (pendingSubscriptionsLock) {
            if (pendingSubscriptionsLock.remove(subscription)) {
                pendingSubscriptionsLock.notifyAll();
            }
        }
    }

    @Override
    public RemoteGENASubscription getWaitRemoteSubscription(String subscriptionId) {
        synchronized (pendingSubscriptionsLock) {
            RemoteGENASubscription subscription = getRemoteSubscription(subscriptionId);
            while (subscription == null && !pendingSubscriptionsLock.isEmpty()) {
                try {
                    Log.v(getClass().getName(), "Subscription not found, waiting for pending subscription procedure to terminate.");
                    pendingSubscriptionsLock.wait();
                } catch (InterruptedException e) {
                }
                subscription = getRemoteSubscription(subscriptionId);
            }
            return subscription;
        }
    }

}
