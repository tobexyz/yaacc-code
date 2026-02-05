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

import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;

public interface RegistryListener {

    void remoteDeviceDiscoveryStarted(Registry registry, RemoteDevice device);

    void remoteDeviceDiscoveryFailed(Registry registry, RemoteDevice device, Exception ex);

    void remoteDeviceAdded(Registry registry, RemoteDevice device);

    void remoteDeviceUpdated(Registry registry, RemoteDevice device);

    void remoteDeviceRemoved(Registry registry, RemoteDevice device);

    void localDeviceAdded(Registry registry, LocalDevice device);

    void localDeviceRemoved(Registry registry, LocalDevice device);

    void beforeShutdown(Registry registry);

    void afterShutdown();
}
