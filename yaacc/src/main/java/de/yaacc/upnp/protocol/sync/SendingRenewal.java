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

import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.gena.IncomingSubscribeResponseMessage;
import org.fourthline.cling.model.message.gena.OutgoingRenewalRequestMessage;
import de.yaacc.upnp.registry.Registry;
import java.io.IOException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.upnp.protocol.SendingSync;
import de.yaacc.upnp.server.http.HttpRequestSender;

/**
 * Renewing a GENA event subscription with a remote host.
 * <p>
 * This protocol is executed periodically by the local registry, for any established GENA
 * subscription to a remote service. If renewal failed, the subscription will be removed
 * from the registry and the
 * {@link RemoteGENASubscription#end(CancelReason, org.fourthline.cling.model.message.UpnpResponse)}
 * method will be called. The <code>RENEWAL_FAILED</code> reason will be used, however,
 * the response might be <code>null</code> if no response was received from the remote host.
 * </p>
 *
 * @author Christian Bauer
 */
public class SendingRenewal extends SendingSync<OutgoingRenewalRequestMessage, IncomingSubscribeResponseMessage> {


    final protected RemoteGENASubscription subscription;
    private final HttpRequestSender httpRequestSender;
    private final Registry registry;
    private final ExecutorService executorService;

    public SendingRenewal(Registry registry, HttpRequestSender httpRequestSender, RemoteGENASubscription subscription) {
        super(new OutgoingRenewalRequestMessage(subscription, null));
        this.subscription = subscription;
        this.registry = registry;
        executorService = Executors.newFixedThreadPool(20);
        this.httpRequestSender = httpRequestSender;

    }

    protected IncomingSubscribeResponseMessage executeSync() throws IOException {
        YaaccLogger.v(getClass().getName(), "Sending subscription renewal request: " + getInputMessage());

        StreamResponseMessage response;
        try {
            response = httpRequestSender.send(getInputMessage());
        } catch (IOException ex) {
            onRenewalFailure();
            throw new IOException(ex);
        }

        if (response == null) {
            onRenewalFailure();
            return null;
        }

        final IncomingSubscribeResponseMessage responseMessage = new IncomingSubscribeResponseMessage(response);

        if (response.getOperation().isFailed()) {
            YaaccLogger.v(getClass().getName(), "Subscription renewal failed, response was: " + response);
            registry.removeRemoteSubscription(subscription);
            executorService.execute(
                    new Runnable() {
                        public void run() {
                            subscription.end(CancelReason.RENEWAL_FAILED, responseMessage.getOperation());
                        }
                    }
            );
        } else if (!responseMessage.isValidHeaders()) {
            YaaccLogger.v(getClass().getName(), "Subscription renewal failed, invalid or missing (SID, Timeout) response headers");
            executorService.execute(
                    new Runnable() {
                        public void run() {
                            subscription.end(CancelReason.RENEWAL_FAILED, responseMessage.getOperation());
                        }
                    }
            );
        } else {
            YaaccLogger.v(getClass().getName(), "Subscription renewed, updating in registry, response was: " + response);
            subscription.setActualSubscriptionDurationSeconds(responseMessage.getSubscriptionDurationSeconds());
            registry.updateRemoteSubscription(subscription);
        }

        return responseMessage;
    }

    protected void onRenewalFailure() {
        YaaccLogger.v(getClass().getName(), "Subscription renewal failed, removing subscription from registry");
        registry.removeRemoteSubscription(subscription);
        executorService.execute(
                new Runnable() {
                    public void run() {
                        subscription.end(CancelReason.RENEWAL_FAILED, null);
                    }
                }
        );
    }
}