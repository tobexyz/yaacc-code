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

import org.fourthline.cling.model.gena.LocalGENASubscription;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.gena.OutgoingEventRequestMessage;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import java.io.IOException;
import org.fourthline.cling.transport.impl.GENAEventProcessorImpl;

import java.io.IOException;
import java.net.URL;

import de.yaacc.upnp.protocol.SendingSync;
import de.yaacc.upnp.server.http.HttpRequestSender;

/**
 * Sending GENA event messages to remote subscribers.
 * <p>
 * Any {@link LocalGENASubscription} instantiates and executes this protocol
 * when the state of a local service changes. However, a remote subscriber might require event
 * notification messages on more than one callback URL, so this protocol potentially sends
 * many messages. What is returned is always the last response, that is, the response for the
 * message sent to the last callback URL in the list of the subscriber.
 * </p>
 *
 * @author Christian Bauer
 */
public class SendingEvent extends SendingSync<OutgoingEventRequestMessage, StreamResponseMessage> {

    final protected String subscriptionId;
    final protected OutgoingEventRequestMessage[] requestMessages;
    final protected UnsignedIntegerFourBytes currentSequence;
    private final HttpRequestSender httpRequestSender;

    public SendingEvent(HttpRequestSender httpRequestSender, LocalGENASubscription subscription) {
        super(null); // Special case, we actually need to send several messages to each callback URL
        this.httpRequestSender = httpRequestSender;
        // TODO: Ugly design! It is critical (concurrency) that we prepare the event messages here, in the constructor thread!

        subscriptionId = subscription.getSubscriptionId();

        requestMessages = new OutgoingEventRequestMessage[subscription.getCallbackURLs().size()];
        int i = 0;
        for (URL url : subscription.getCallbackURLs()) {
            requestMessages[i] = new OutgoingEventRequestMessage(subscription, url);
            new GENAEventProcessorImpl().writeBody(requestMessages[i]);
            i++;
        }

        currentSequence = subscription.getCurrentSequence();

        // Always increment sequence now, as (its value) has already been set on the headers and the
        // next event will use the incremented value
        subscription.incrementSequence();
    }

    protected StreamResponseMessage executeSync() throws IOException {

        YaaccLogger.v(getClass().getName(), "Sending event for subscription: " + subscriptionId);

        StreamResponseMessage lastResponse = null;

        for (OutgoingEventRequestMessage requestMessage : requestMessages) {

            if (currentSequence.getValue() == 0) {
                YaaccLogger.v(getClass().getName(), "Sending initial event message to callback URL: " + requestMessage.getUri());
            } else {
                YaaccLogger.v(getClass().getName(), "Sending event message '" + currentSequence + "' to callback URL: " + requestMessage.getUri());
            }


            // Send request
            try {
                lastResponse = httpRequestSender.send(requestMessage);
            } catch (IOException e) {
                throw new IOException(e);
            }
            YaaccLogger.v(getClass().getName(), "Received event callback response: " + lastResponse);

        }

        // It's not really used, so just return the last one - we have only one callback URL most of the
        // time anyway
        return lastResponse;

    }
}