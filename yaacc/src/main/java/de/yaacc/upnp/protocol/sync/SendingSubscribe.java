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

import org.fourthline.cling.model.NetworkAddress;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.gena.IncomingSubscribeResponseMessage;
import org.fourthline.cling.model.message.gena.OutgoingSubscribeRequestMessage;
import de.yaacc.upnp.registry.Registry;
import java.io.IOException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.upnp.protocol.SendingSync;
import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.upnp.server.http.HttpRequestSender;

/**
 * Establishing a GENA event subscription with a remote host.
 * <p>
 * Calls the {@link RemoteGENASubscription#establish()} method
 * if the subscription request was responded to correctly.
 * </p>
 * <p>
 * The {@link RemoteGENASubscription#fail(org.fourthline.cling.model.message.UpnpResponse)}
 * method will be called if the request failed. No response from the remote host is indicated with
 * a <code>null</code> argument value. Note that this is also the response if the subscription has
 * to be aborted early, when no local stream server for callback URL creation is available. This is
 * the case when the local network transport layer is switched off, subscriptions will fail
 * immediately with no response.
 * </p>
 *
 * @author Christian Bauer
 */
public class SendingSubscribe extends SendingSync<OutgoingSubscribeRequestMessage, IncomingSubscribeResponseMessage> {

    final protected RemoteGENASubscription subscription;
    private final HttpRequestSender httpRequestSender;
    private final Registry registry;
    private final ExecutorService executorService;

    public SendingSubscribe(Registry registry, HttpRequestSender httpRequestSender,
                            RemoteGENASubscription subscription,
                            NetworkAddress activeStreamServers) {
        super(new OutgoingSubscribeRequestMessage(
                        subscription,
                        subscription.getEventCallbackURLs(
                                List.of(activeStreamServers),
                                UpnpProtocolHandler.NAMESPACE
                        ),
                        null
                )
        );

        this.subscription = subscription;
        this.httpRequestSender = httpRequestSender;
        this.registry = registry;
        executorService = Executors.newFixedThreadPool(20);

    }

    protected IncomingSubscribeResponseMessage executeSync() throws IOException {

        if (!getInputMessage().hasCallbackURLs()) {
            Log.v(getClass().getName(), "Subscription failed, no active local callback URLs available (network disabled?)");
            executorService.execute(
                    new Runnable() {
                        public void run() {
                            subscription.fail(null);
                        }
                    }
            );
            return null;
        }

        Log.v(getClass().getName(), "Sending subscription request: " + getInputMessage());

        try {
            // register this pending Subscription to bloc if the notification is received before the
            // registration result.
            registry.registerPendingRemoteSubscription(subscription);

            StreamResponseMessage response = null;
            try {
                response = httpRequestSender.send(getInputMessage());
            } catch (IOException ex) {
                onSubscriptionFailure();
                return null;
            }

            if (response == null) {
                onSubscriptionFailure();
                return null;
            }

            final IncomingSubscribeResponseMessage responseMessage = new IncomingSubscribeResponseMessage(response);

            if (response.getOperation().isFailed()) {
                Log.v(getClass().getName(), "Subscription failed, response was: " + responseMessage);
                executorService.execute(
                        new Runnable() {
                            public void run() {
                                subscription.fail(responseMessage.getOperation());
                            }
                        }
                );
            } else if (!responseMessage.isValidHeaders()) {
                Log.v(getClass().getName(), "Subscription failed, invalid or missing (SID, Timeout) response headers");
                executorService.execute(
                        new Runnable() {
                            public void run() {
                                subscription.fail(responseMessage.getOperation());
                            }
                        }
                );
            } else {

                Log.v(getClass().getName(), "Subscription established, adding to registry, response was: " + response);
                subscription.setSubscriptionId(responseMessage.getSubscriptionId());
                subscription.setActualSubscriptionDurationSeconds(responseMessage.getSubscriptionDurationSeconds());

                registry.addRemoteSubscription(subscription);

                executorService.execute(
                        new Runnable() {
                            public void run() {
                                subscription.establish();
                            }
                        }
                );

            }
            return responseMessage;
        } finally {
            registry.unregisterPendingRemoteSubscription(subscription);
        }
    }

    protected void onSubscriptionFailure() {
        Log.v(getClass().getName(), "Subscription failed");
        executorService.execute(
                new Runnable() {
                    public void run() {
                        subscription.fail(null);
                    }
                }
        );
    }
}
