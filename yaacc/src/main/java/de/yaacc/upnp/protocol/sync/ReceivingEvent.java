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

package de.yaacc.upnp.protocol.sync;

import de.yaacc.util.YaaccLogger;

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
 * .
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
            YaaccLogger.w(getClass().getName(), "Received without or with invalid Content-Type: " + getInputMessage());
            // We continue despite the invalid UPnP message because we can still hope to convert the content
            // return new StreamResponseMessage(new UpnpResponse(UpnpResponse.Status.UNSUPPORTED_MEDIA_TYPE));
        }

        ServiceEventCallbackResource resource =
                registry.getResource(
                        ServiceEventCallbackResource.class,
                        getInputMessage().getUri()
                );

        if (resource == null) {
            YaaccLogger.v(getClass().getName(), "No local resource found: " + getInputMessage());
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.NOT_FOUND));
        }

        final IncomingEventRequestMessage requestMessage =
                new IncomingEventRequestMessage(getInputMessage(), resource.getModel());

        // Error conditions UDA 1.0 section 4.2.1
        if (requestMessage.getSubscrptionId() == null) {
            YaaccLogger.v(getClass().getName(), "Subscription ID missing in event request: " + getInputMessage());
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.PRECONDITION_FAILED));
        }

        if (!requestMessage.hasValidNotificationHeaders()) {
            YaaccLogger.v(getClass().getName(), "Missing NT and/or NTS headers in event request: " + getInputMessage());
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.BAD_REQUEST));
        }

        if (!requestMessage.hasValidNotificationHeaders()) {
            YaaccLogger.v(getClass().getName(), "Invalid NT and/or NTS headers in event request: " + getInputMessage());
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.PRECONDITION_FAILED));
        }

        if (requestMessage.getSequence() == null) {
            YaaccLogger.v(getClass().getName(), "Sequence missing in event request: " + getInputMessage());
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.PRECONDITION_FAILED));
        }

        try {

            genaEventProcessor.readBody(requestMessage);

        } catch (final UnsupportedDataException ex) {
            YaaccLogger.v(getClass().getName(), "Can't read event message request body, " + ex);

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
            YaaccLogger.v(getClass().getName(), "Invalid subscription ID, no active subscription: " + requestMessage);
            return new OutgoingEventResponseMessage(new UpnpResponse(UpnpResponse.Status.PRECONDITION_FAILED));
        }

        executorService.execute(
                new Runnable() {
                    public void run() {
                        YaaccLogger.v(getClass().getName(), "Calling active subscription with event state variable values");
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
