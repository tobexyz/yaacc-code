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
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.gena.IncomingUnsubscribeRequestMessage;
import org.fourthline.cling.model.resource.ServiceEventSubscriptionResource;
import de.yaacc.upnp.registry.Registry;
import java.io.IOException;

import de.yaacc.upnp.protocol.ReceivingSync;

/**
 * Handles reception of GENA event unsubscribe messages.
 *
 * @author Christian Bauer
 */
public class ReceivingUnsubscribe extends ReceivingSync<StreamRequestMessage, StreamResponseMessage> {


    private final Registry registry;

    public ReceivingUnsubscribe(Registry registry, StreamRequestMessage inputMessage) {
        super(inputMessage);
        this.registry = registry;
    }

    protected StreamResponseMessage executeSync() throws IOException {

        ServiceEventSubscriptionResource resource =
                registry.getResource(
                        ServiceEventSubscriptionResource.class,
                        getInputMessage().getUri()
                );

        if (resource == null) {
            YaaccLogger.v(getClass().getName(), "No local resource found: " + getInputMessage());
            return null;
        }

        YaaccLogger.v(getClass().getName(), "Found local event subscription matching relative request URI: " + getInputMessage().getUri());

        IncomingUnsubscribeRequestMessage requestMessage =
                new IncomingUnsubscribeRequestMessage(getInputMessage(), resource.getModel());

        // Error conditions UDA 1.0 section 4.1.3
        if (requestMessage.getSubscriptionId() != null &&
                (requestMessage.hasNotificationHeader() || requestMessage.hasCallbackHeader())) {
            YaaccLogger.v(getClass().getName(), "Subscription ID and NT or Callback in unsubcribe request: " + getInputMessage());
            return new StreamResponseMessage(UpnpResponse.Status.BAD_REQUEST);
        }

        LocalGENASubscription subscription =
                registry.getLocalSubscription(requestMessage.getSubscriptionId());

        if (subscription == null) {
            YaaccLogger.v(getClass().getName(), "Invalid subscription ID for unsubscribe request: " + getInputMessage());
            return new StreamResponseMessage(UpnpResponse.Status.PRECONDITION_FAILED);
        }

        YaaccLogger.v(getClass().getName(), "Unregistering subscription: " + subscription);
        if (registry.removeLocalSubscription(subscription)) {
            subscription.end(null); // No reason, just an unsubscribe
        } else {
            YaaccLogger.v(getClass().getName(), "Subscription was already removed from registry");
        }

        return new StreamResponseMessage(UpnpResponse.Status.OK);
    }
}