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

import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.gena.IncomingSubscribeResponseMessage;
import org.fourthline.cling.model.message.gena.OutgoingRenewalRequestMessage;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.transport.RouterException;

import java.io.IOException;
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

    public SendingRenewal(Registry registry, HttpRequestSender httpRequestSender, RemoteGENASubscription subscription) {
        super(new OutgoingRenewalRequestMessage(subscription, null));
        this.subscription = subscription;
        this.registry = registry;
        this.httpRequestSender = httpRequestSender;

    }

    protected IncomingSubscribeResponseMessage executeSync() throws RouterException {
        Log.v(getClass().getName(), "Sending subscription renewal request: " + getInputMessage());

        StreamResponseMessage response;
        try {
            response = httpRequestSender.send(getInputMessage());
        } catch (IOException ex) {
            onRenewalFailure();
            throw new RouterException(ex);
        }

        if (response == null) {
            onRenewalFailure();
            return null;
        }

        final IncomingSubscribeResponseMessage responseMessage = new IncomingSubscribeResponseMessage(response);

        if (response.getOperation().isFailed()) {
            Log.v(getClass().getName(), "Subscription renewal failed, response was: " + response);
            registry.removeRemoteSubscription(subscription);
            Executors.newSingleThreadExecutor().execute(
                    new Runnable() {
                        public void run() {
                            subscription.end(CancelReason.RENEWAL_FAILED, responseMessage.getOperation());
                        }
                    }
            );
        } else if (!responseMessage.isValidHeaders()) {
            Log.v(getClass().getName(), "Subscription renewal failed, invalid or missing (SID, Timeout) response headers");
            Executors.newSingleThreadExecutor().execute(
                    new Runnable() {
                        public void run() {
                            subscription.end(CancelReason.RENEWAL_FAILED, responseMessage.getOperation());
                        }
                    }
            );
        } else {
            Log.v(getClass().getName(), "Subscription renewed, updating in registry, response was: " + response);
            subscription.setActualSubscriptionDurationSeconds(responseMessage.getSubscriptionDurationSeconds());
            registry.updateRemoteSubscription(subscription);
        }

        return responseMessage;
    }

    protected void onRenewalFailure() {
        Log.v(getClass().getName(), "Subscription renewal failed, removing subscription from registry");
        registry.removeRemoteSubscription(subscription);
        Executors.newSingleThreadExecutor().execute(
                new Runnable() {
                    public void run() {
                        subscription.end(CancelReason.RENEWAL_FAILED, null);
                    }
                }
        );
    }
}