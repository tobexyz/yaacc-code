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

import de.yaacc.util.Exceptions;
import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.action.ActionCancelledException;
import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.action.RemoteActionInvocation;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.control.IncomingActionRequestMessage;
import org.fourthline.cling.model.message.control.OutgoingActionResponseMessage;
import org.fourthline.cling.model.message.header.ContentTypeHeader;
import org.fourthline.cling.model.message.header.UpnpHeader;
import org.fourthline.cling.model.resource.ServiceControlResource;
import org.fourthline.cling.model.types.ErrorCode;
import de.yaacc.upnp.registry.Registry;
import java.io.IOException;
import org.fourthline.cling.transport.impl.SOAPActionProcessorImpl;

import de.yaacc.upnp.protocol.ReceivingSync;

/**
 * Handles reception of control messages, invoking actions on local services.
 * <p>
 * Actions are invoked through the {@link org.fourthline.cling.model.action.ActionExecutor} returned
 * by the registered {@link org.fourthline.cling.model.meta.LocalService#getExecutor(org.fourthline.cling.model.meta.Action)}
 * method.
 * </p>
 *
 * @author Christian Bauer
 */
public class ReceivingAction extends ReceivingSync<StreamRequestMessage, StreamResponseMessage> {

    private final Registry registry;
    SOAPActionProcessorImpl soapActionProcessor = new SOAPActionProcessorImpl();

    public ReceivingAction(Registry registry, StreamRequestMessage inputMessage) {
        super(inputMessage);
        this.registry = registry;
    }

    protected StreamResponseMessage executeSync() throws IOException {

        ContentTypeHeader contentTypeHeader =
                getInputMessage().getHeaders().getFirstHeader(UpnpHeader.Type.CONTENT_TYPE, ContentTypeHeader.class);

        // Special rules for action messages! UDA 1.0 says:
        // 'If the CONTENT-TYPE header specifies an unsupported value (other then "text/xml") the
        // device must return an HTTP status code "415 Unsupported Media Type".'
        if (contentTypeHeader != null && !contentTypeHeader.isUDACompliantXML()) {
            YaaccLogger.w(getClass().getName(), "Received invalid Content-Type '" + contentTypeHeader + "': " + getInputMessage());
            return new StreamResponseMessage(new UpnpResponse(UpnpResponse.Status.UNSUPPORTED_MEDIA_TYPE));
        }

        if (contentTypeHeader == null) {
            YaaccLogger.w(getClass().getName(), "Received without Content-Type: " + getInputMessage());
        }

        ServiceControlResource resource =
                registry.getResource(
                        ServiceControlResource.class,
                        getInputMessage().getUri()
                );

        if (resource == null) {
            YaaccLogger.v(getClass().getName(), "No local resource found: " + getInputMessage());
            return null;
        }

        YaaccLogger.v(getClass().getName(), "Found local action resource matching relative request URI: " + getInputMessage().getUri());

        RemoteActionInvocation invocation;
        OutgoingActionResponseMessage responseMessage = null;

        try {

            // Throws ActionException if the action can't be found
            IncomingActionRequestMessage requestMessage =
                    new IncomingActionRequestMessage(getInputMessage(), resource.getModel());

            YaaccLogger.v(getClass().getName(), "Created incoming action request message: " + requestMessage);
            invocation = new RemoteActionInvocation(requestMessage.getAction(), getRemoteClientInfo());

            // Throws UnsupportedDataException if the body can't be read
            YaaccLogger.v(getClass().getName(), "Reading body of request message:" + requestMessage.getBodyString());
            soapActionProcessor.readBody(requestMessage, invocation);

            YaaccLogger.v(getClass().getName(), "Executing on local service: " + invocation);
            resource.getModel().getExecutor(invocation.getAction()).execute(invocation);

            if (invocation.getFailure() == null) {
                responseMessage =
                        new OutgoingActionResponseMessage(invocation.getAction());
            } else {

                if (invocation.getFailure() instanceof ActionCancelledException) {
                    YaaccLogger.v(getClass().getName(), "Action execution was cancelled, returning 404 to client");
                    // A 404 status is appropriate for this situation: The resource is gone/not available and it's
                    // a temporary condition. Most likely the cancellation happened because the client connection
                    // has been dropped, so it doesn't really matter what we return here anyway.
                    return null;
                } else {
                    responseMessage =
                            new OutgoingActionResponseMessage(
                                    UpnpResponse.Status.INTERNAL_SERVER_ERROR,
                                    invocation.getAction()
                            );
                }
            }

        } catch (ActionException ex) {
            YaaccLogger.v(getClass().getName(), "Error executing local action: ", ex);

            invocation = new RemoteActionInvocation(ex, getRemoteClientInfo());
            responseMessage = new OutgoingActionResponseMessage(UpnpResponse.Status.INTERNAL_SERVER_ERROR);

        } catch (UnsupportedDataException ex) {
            YaaccLogger.w(getClass().getName(), "Error reading action request XML body: " + ex.toString(), Exceptions.unwrap(ex));

            invocation =
                    new RemoteActionInvocation(
                            Exceptions.unwrap(ex) instanceof ActionException
                                    ? (ActionException) Exceptions.unwrap(ex)
                                    : new ActionException(ErrorCode.ACTION_FAILED, ex.getMessage()),
                            getRemoteClientInfo()
                    );
            responseMessage = new OutgoingActionResponseMessage(UpnpResponse.Status.INTERNAL_SERVER_ERROR);

        }

        try {

            YaaccLogger.v(getClass().getName(), "Writing body of response message");
            soapActionProcessor.writeBody(responseMessage, invocation);

            YaaccLogger.v(getClass().getName(), "Returning finished response message: " + responseMessage);
            return responseMessage;

        } catch (UnsupportedDataException ex) {
            YaaccLogger.w(getClass().getName(), "Failure writing body of response message, sending '500 Internal Server Error' without body");
            YaaccLogger.w(getClass().getName(), "Exception root cause: ", Exceptions.unwrap(ex));
            return new StreamResponseMessage(UpnpResponse.Status.INTERNAL_SERVER_ERROR);
        }
    }

}
