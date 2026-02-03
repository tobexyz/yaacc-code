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

import android.util.Log;

import org.fourthline.cling.model.ValidationError;
import org.fourthline.cling.model.ValidationException;
import org.fourthline.cling.model.message.IncomingDatagramMessage;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.discovery.IncomingSearchResponse;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.RemoteDeviceIdentity;
import org.fourthline.cling.model.types.UDN;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.transport.RouterException;

import java.util.concurrent.Executors;

import de.yaacc.upnp.protocol.ReceivingAsync;
import de.yaacc.upnp.protocol.RetrieveRemoteDescriptors;
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

    public ReceivingSearchResponse(Registry registry, HttpRequestSender httpRequestSender, IncomingDatagramMessage<UpnpResponse> inputMessage) {
        super(new IncomingSearchResponse(inputMessage));
        this.registry = registry;
        this.httpReqSender = httpRequestSender;
    }

    protected void execute() throws RouterException {

        if (!getInputMessage().isSearchResponseMessage()) {
            Log.v(getClass().getName(), "Ignoring invalid search response message: " + getInputMessage());
            return;
        }

        UDN udn = getInputMessage().getRootDeviceUDN();
        if (udn == null) {
            Log.v(getClass().getName(), "Ignoring search response message without UDN: " + getInputMessage());
            return;
        }

        RemoteDeviceIdentity rdIdentity = new RemoteDeviceIdentity(getInputMessage());
        Log.v(getClass().getName(), "Received device search response: " + rdIdentity);

        if (registry.update(rdIdentity)) {
            Log.v(getClass().getName(), "Remote device was already known: " + udn);
            return;
        }

        RemoteDevice rd;
        try {
            rd = new RemoteDevice(rdIdentity);
        } catch (ValidationException ex) {
            Log.w(getClass().getName(), "Validation errors of device during discovery: " + rdIdentity);
            for (ValidationError validationError : ex.getErrors()) {
                Log.w(getClass().getName(), validationError.toString());
            }
            return;
        }

        if (rdIdentity.getDescriptorURL() == null) {
            Log.v(getClass().getName(), "Ignoring message without location URL header: " + getInputMessage());
            return;
        }

        if (rdIdentity.getMaxAgeSeconds() == null) {
            Log.v(getClass().getName(), "Ignoring message without max-age header: " + getInputMessage());
            return;
        }

        // Unfortunately, we always have to retrieve the descriptor because at this point we
        // have no idea if it's a root or embedded device
        Executors.newSingleThreadExecutor().execute(
                new RetrieveRemoteDescriptors(registry, httpReqSender, rd)
        );

    }

}
