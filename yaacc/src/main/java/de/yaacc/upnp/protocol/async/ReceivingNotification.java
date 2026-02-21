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

package de.yaacc.upnp.protocol.async;

import org.fourthline.cling.model.ValidationError;
import org.fourthline.cling.model.ValidationException;
import org.fourthline.cling.model.message.IncomingDatagramMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.model.message.discovery.IncomingNotificationRequest;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.RemoteDeviceIdentity;
import org.fourthline.cling.model.types.UDN;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import de.yaacc.upnp.protocol.ReceivingAsync;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.util.YaaccLogger;

/**
 * Handles reception of notification messages.
 * <p>
 * First, the UDN is created from the received message.
 * </p>
 * <p>
 * If an <em>ALIVE</em> message has been received, a new background process will be started
 * running {@link RetrieveRemoteDescriptors}.
 * </p>
 * <p>
 * If a <em>BYEBYE</em> message has been received, the device will be removed from the registry
 * directly.
 * </p>
 * <p>
 * The following was added to the UDA 1.1 spec (in 1.3), clarifying the handling of messages:
 * </p>
 * <p>
 * "If a control point has received at least one 'byebye' message of a root device, embedded device, or
 * service, then the control point can assume that all are no longer available."
 * </p>
 * <p>
 * Of course, they contradict this a little later:
 * </p>
 * <p>
 * "Only when all original advertisements of a root device, embedded device, and services have
 * expired can a control point assume that they are no longer available."
 * </p>
 * <p>
 * This could mean that even if we get 'byeby'e for the root device, we still have to assume that its services
 * are available. That clearly makes no sense at all and I think it's just badly worded and relates to the
 * previous sentence wich says "if you don't get byebye's, rely on the expiration timeout". It does not
 * imply that a service or embedded device lives beyond its root device. It actually reinforces that we are
 * free to ignore anything that happens as long as the root device is not gone with 'byebye' or has expired.
 * In other words: There is no reason at all why SSDP sends dozens of messages for all embedded devices and
 * services. The composite is the root device and the composite defines the lifecycle of all.
 * </p>
 *
 * @author Christian Bauer
 */
public class ReceivingNotification extends ReceivingAsync<IncomingNotificationRequest> {

    private final ExecutorService executorService;
    private Registry registry;
    private HttpRequestSender httpRequestSender;


    public ReceivingNotification(Registry registry, HttpRequestSender httpRequestSender, IncomingDatagramMessage<UpnpRequest> inputMessage) {
        super(new IncomingNotificationRequest(inputMessage));
        this.registry = registry;
        executorService = registry.getExecutorService();
        this.httpRequestSender = httpRequestSender;
    }

    protected void execute() throws IOException {

        UDN udn = getInputMessage().getUDN();
        if (udn == null) {
            YaaccLogger.v(getClass().getName(), "Ignoring notification message without UDN: " + getInputMessage());
            return;
        }

        RemoteDeviceIdentity rdIdentity = new RemoteDeviceIdentity(getInputMessage());
        YaaccLogger.v(getClass().getName(), "Received device notification: " + rdIdentity);

        RemoteDevice rd;
        try {
            rd = new RemoteDevice(rdIdentity);
        } catch (ValidationException ex) {
            YaaccLogger.w(getClass().getName(), "Validation errors of device during discovery: " + rdIdentity);
            for (ValidationError validationError : ex.getErrors()) {
                YaaccLogger.w(getClass().getName(), validationError.toString());
            }
            return;
        }

        if (getInputMessage().isAliveMessage()) {

            YaaccLogger.v(getClass().getName(), "Received device ALIVE advertisement, descriptor location is: " + rdIdentity.getDescriptorURL());

            if (rdIdentity.getDescriptorURL() == null) {
                YaaccLogger.v(getClass().getName(), "Ignoring message without location URL header: " + getInputMessage());
                return;
            }

            if (rdIdentity.getMaxAgeSeconds() == null) {
                YaaccLogger.v(getClass().getName(), "Ignoring message without max-age header: " + getInputMessage());
                return;
            }

            if (registry.update(rdIdentity)) {
                YaaccLogger.v(getClass().getName(), "Remote device was already known: " + udn);
                return;
            }

            // Unfortunately, we always have to retrieve the descriptor because at this point we
            // have no idea if it's a root or embedded device
            executorService.execute(
                    new RetrieveRemoteDescriptors(registry, httpRequestSender, rd)
            );

        } else if (getInputMessage().isByeByeMessage()) {

            YaaccLogger.v(getClass().getName(), "Received device BYEBYE advertisement");
            boolean removed = registry.removeDevice(rd);
            if (removed) {
                YaaccLogger.v(getClass().getName(), "Removed remote device from registry: " + rd);
            }

        } else {
            YaaccLogger.v(getClass().getName(), "Ignoring unknown notification message: " + getInputMessage());
        }

    }


}
