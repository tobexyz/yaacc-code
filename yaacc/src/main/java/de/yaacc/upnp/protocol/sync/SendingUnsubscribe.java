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

import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.gena.OutgoingUnsubscribeRequestMessage;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.upnp.protocol.SendingSync;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.util.YaaccLogger;

/**
 * Disconnecting a GENA event subscription with a remote host.
 * <p>
 * Calls the {@link RemoteGENASubscription#end(CancelReason, org.fourthline.cling.model.message.UpnpResponse)}
 * method if the subscription request was responded to correctly. No {@link CancelReason}
 * will be provided if the unsubscribe procedure completed as expected, otherwise <code>UNSUBSCRIBE_FAILED</code>
 * is used. The response might be <code>null</code> if no response was received from the remote host.
 * </p>
 *
 * @author Christian Bauer
 */
public class SendingUnsubscribe extends SendingSync<OutgoingUnsubscribeRequestMessage, StreamResponseMessage> {

    final protected RemoteGENASubscription subscription;
    private final HttpRequestSender httpRequestSender;
    private final Registry registry;
    private final ExecutorService executorService;

    public SendingUnsubscribe(Registry registry, HttpRequestSender httpRequestSender, RemoteGENASubscription subscription) {
        super(new OutgoingUnsubscribeRequestMessage(subscription, null));
        this.registry = registry;
        this.httpRequestSender = httpRequestSender;
        this.subscription = subscription;
        executorService = Executors.newFixedThreadPool(20);

    }

    protected StreamResponseMessage executeSync() throws IOException {

        YaaccLogger.v(getClass().getName(), "Sending unsubscribe request: " + getInputMessage());

        StreamResponseMessage response = null;
        try {
            response = httpRequestSender.send(getInputMessage());
            return response;
        } catch (IOException e) {
            throw new IOException(e);
        } finally {
            onUnsubscribe(response);
        }
    }

    protected void onUnsubscribe(final StreamResponseMessage response) {
        // Always remove from the registry and end the subscription properly - even if it's failed
        registry.removeRemoteSubscription(subscription);

        executorService.execute(
                new Runnable() {
                    public void run() {
                        if (response == null) {
                            YaaccLogger.v(getClass().getName(), "Unsubscribe failed, no response received");
                            subscription.end(CancelReason.UNSUBSCRIBE_FAILED, null);
                        } else if (response.getOperation().isFailed()) {
                            YaaccLogger.v(getClass().getName(), "Unsubscribe failed, response was: " + response);
                            subscription.end(CancelReason.UNSUBSCRIBE_FAILED, response.getOperation());
                        } else {
                            YaaccLogger.v(getClass().getName(), "Unsubscribe successful, response was: " + response);
                            subscription.end(null, response.getOperation());
                        }
                    }
                }
        );
    }
}