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

package de.yaacc.upnp.protocol.sync;

import android.util.Log;

import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.gena.IncomingEventRequestMessage;
import org.fourthline.cling.model.message.gena.OutgoingEventResponseMessage;
import org.fourthline.cling.model.resource.ServiceEventCallbackResource;
import java.io.IOException;
import org.fourthline.cling.transport.impl.GENAEventProcessorImpl;

import java.util.concurrent.ExecutorService;

import de.yaacc.upnp.protocol.ReceivingSync;
import de.yaacc.upnp.registry.Registry;

/**
 * Handles incoming GENA event messages.
 * <p>
 * Attempts to find an outgoing (remote) subscription matching the callback and subscription identifier.
 * Once found, the GENA event message payload will be transformed and the
 * {@link RemoteGENASubscription#receive(org.fourthline.cling.model.types.UnsignedIntegerFourBytes,
 * java.util.Collection)} method will be called asynchronously using the executor
 * returned by {@link org.fourthline.cling.UpnpServiceConfiguration#getRegistryListenerExecutor()}.
 * </p>
 *
 * @author Christian Bauer
 */
public class ReceivingEvent extends ReceivingSync<StreamRequestMessage, OutgoingEventResponseMessage> {

    private final Registry registry;
    private final GENAEventProcessorImpl genaEventProcessor = new GENAEventProcessorImpl();
    private final ExecutorService executorService;

    public ReceivingEvent(Registry registry, StreamRequestMessage inputMessage) {
        super(inputMessage);
        this.registry = registry;
        executorService = registry.getExecutorService();
    }

    protected OutgoingEventResponseMessage executeSync() throws IOException {

        if (!getInputMessage().isContentTypeTextUDA()) {
            Log.w(getClass().getName(), "Received without or with invalid Content-Type: " + getInputMessage());
            // We continue despite the invalid UPnP message because we can still hope to convert the content
            // return new StreamResponseMessage(new UpnpResponse(UpnpResponse.Status.UNSUPPORTED_MEDIA_TYPE));
        }

        ServiceEventCallbackResource resource =
                registry.getResource(
                        ServiceEventCallbackResource.class,
                        getInputMessage().getUri()
                );

        if (resource == null) {
            Log.v(getClass().getName(), "No local resource found: " + getInputMessage());
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.NOT_FOUND));
        }

        final IncomingEventRequestMessage requestMessage =
                new IncomingEventRequestMessage(getInputMessage(), resource.getModel());

        // Error conditions UDA 1.0 section 4.2.1
        if (requestMessage.getSubscrptionId() == null) {
            Log.v(getClass().getName(), "Subscription ID missing in event request: " + getInputMessage());
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.PRECONDITION_FAILED));
        }

        if (!requestMessage.hasValidNotificationHeaders()) {
            Log.v(getClass().getName(), "Missing NT and/or NTS headers in event request: " + getInputMessage());
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.BAD_REQUEST));
        }

        if (!requestMessage.hasValidNotificationHeaders()) {
            Log.v(getClass().getName(), "Invalid NT and/or NTS headers in event request: " + getInputMessage());
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.PRECONDITION_FAILED));
        }

        if (requestMessage.getSequence() == null) {
            Log.v(getClass().getName(), "Sequence missing in event request: " + getInputMessage());
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.PRECONDITION_FAILED));
        }

        try {

            genaEventProcessor.readBody(requestMessage);

        } catch (final UnsupportedDataException ex) {
            Log.v(getClass().getName(), "Can't read event message request body, " + ex);

            // Pass the parsing failure on to any listeners, so they can take action if necessary
            final RemoteGENASubscription subscription =
                    registry.getRemoteSubscription(requestMessage.getSubscrptionId());
            if (subscription != null) {
                executorService.execute(
                        new Runnable() {
                            public void run() {
                                subscription.invalidMessage(ex);
                            }
                        }
                );
            }

            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.INTERNAL_SERVER_ERROR));
        }

        // get the remove subscription, if the subscription can't be found, wait for pending subscription
        // requests to finish
        final RemoteGENASubscription subscription =
                registry.getWaitRemoteSubscription(requestMessage.getSubscrptionId());

        if (subscription == null) {
            Log.v(getClass().getName(), "Invalid subscription ID, no active subscription: " + requestMessage);
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.PRECONDITION_FAILED));
        }

        executorService.execute(
                new Runnable() {
                    public void run() {
                        Log.v(getClass().getName(), "Calling active subscription with event state variable values");
                        subscription.receive(
                                requestMessage.getSequence(),
                                requestMessage.getStateVariableValues()
                        );
                    }
                }
        );

        return new OutgoingEventResponseMessage();

    }
}
