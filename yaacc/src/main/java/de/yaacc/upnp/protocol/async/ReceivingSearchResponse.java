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

import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.model.ValidationError;
import org.fourthline.cling.model.ValidationException;
import org.fourthline.cling.model.message.IncomingDatagramMessage;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.discovery.IncomingSearchResponse;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.RemoteDeviceIdentity;
import org.fourthline.cling.model.types.UDN;
import java.io.IOException;

import java.util.concurrent.ExecutorService;

import de.yaacc.upnp.protocol.ReceivingAsync;
import de.yaacc.upnp.protocol.RetrieveRemoteDescriptors;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.server.http.HttpRequestSender;

/**
 * Handles reception of search response messages.
 * <p>
 * This protocol implementation is basically the same as
 * the {@link ReceivingNotification} protocol for
 * an <em>ALIVE</em> message.
 * </p>
 *
 * @author Christian Bauer
 */
public class ReceivingSearchResponse extends ReceivingAsync<IncomingSearchResponse> {


    private final Registry registry;
    private final HttpRequestSender httpReqSender;

    public ExecutorService getExecutorService() {
        return executorService;
    }

    private final ExecutorService executorService;

    public ReceivingSearchResponse(Registry registry, HttpRequestSender httpRequestSender, IncomingDatagramMessage<UpnpResponse> inputMessage) {
        super(new IncomingSearchResponse(inputMessage));
        this.registry = registry;
        executorService = registry.getExecutorService();
        this.httpReqSender = httpRequestSender;
    }

    protected void execute() throws IOException {

        if (!getInputMessage().isSearchResponseMessage()) {
            YaaccLogger.v(getClass().getName(), "Ignoring invalid search response message: " + getInputMessage());
            return;
        }

        UDN udn = getInputMessage().getRootDeviceUDN();
        if (udn == null) {
            YaaccLogger.v(getClass().getName(), "Ignoring search response message without UDN: " + getInputMessage());
            return;
        }

        RemoteDeviceIdentity rdIdentity = new RemoteDeviceIdentity(getInputMessage());
        YaaccLogger.v(getClass().getName(), "Received device search response: " + rdIdentity);

        if (registry.update(rdIdentity)) {
            YaaccLogger.v(getClass().getName(), "Remote device was already known: " + udn);
            return;
        }

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

        if (rdIdentity.getDescriptorURL() == null) {
            YaaccLogger.v(getClass().getName(), "Ignoring message without location URL header: " + getInputMessage());
            return;
        }

        if (rdIdentity.getMaxAgeSeconds() == null) {
            YaaccLogger.v(getClass().getName(), "Ignoring message without max-age header: " + getInputMessage());
            return;
        }

        // Unfortunately, we always have to retrieve the descriptor because at this point we
        // have no idea if it's a root or embedded device
        executorService.execute(
                new RetrieveRemoteDescriptors(registry, httpReqSender, rd)
        );

    }

}
