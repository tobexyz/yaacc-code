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

package org.fourthline.cling.protocol.sync;

import android.util.Log;

import org.fourthline.cling.UpnpService;
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
import org.fourthline.cling.protocol.ReceivingSync;
import org.fourthline.cling.transport.RouterException;
import org.seamless.util.Exceptions;

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

    public ReceivingAction(UpnpService upnpService, StreamRequestMessage inputMessage) {
        super(upnpService, inputMessage);
    }

    protected StreamResponseMessage executeSync() throws RouterException {

        ContentTypeHeader contentTypeHeader =
                getInputMessage().getHeaders().getFirstHeader(UpnpHeader.Type.CONTENT_TYPE, ContentTypeHeader.class);

        // Special rules for action messages! UDA 1.0 says:
        // 'If the CONTENT-TYPE header specifies an unsupported value (other then "text/xml") the
        // device must return an HTTP status code "415 Unsupported Media Type".'
        if (contentTypeHeader != null && !contentTypeHeader.isUDACompliantXML()) {
            Log.w(getClass().getName(), "Received invalid Content-Type '" + contentTypeHeader + "': " + getInputMessage());
            return new StreamResponseMessage(new UpnpResponse(UpnpResponse.Status.UNSUPPORTED_MEDIA_TYPE));
        }

        if (contentTypeHeader == null) {
            Log.w(getClass().getName(), "Received without Content-Type: " + getInputMessage());
        }

        ServiceControlResource resource =
                getUpnpService().getRegistry().getResource(
                        ServiceControlResource.class,
                        getInputMessage().getUri()
                );

        if (resource == null) {
            Log.d(getClass().getName(), "No local resource found: " + getInputMessage());
            return null;
        }

        Log.d(getClass().getName(), "Found local action resource matching relative request URI: " + getInputMessage().getUri());

        RemoteActionInvocation invocation;
        OutgoingActionResponseMessage responseMessage = null;

        try {

            // Throws ActionException if the action can't be found
            IncomingActionRequestMessage requestMessage =
                    new IncomingActionRequestMessage(getInputMessage(), resource.getModel());

            Log.i(getClass().getName(), "Created incoming action request message: " + requestMessage);
            invocation = new RemoteActionInvocation(requestMessage.getAction(), getRemoteClientInfo());

            // Throws UnsupportedDataException if the body can't be read
            Log.i(getClass().getName(), "Reading body of request message:" + requestMessage.getBodyString());
            getUpnpService().getConfiguration().getSoapActionProcessor().readBody(requestMessage, invocation);

            Log.i(getClass().getName(), "Executing on local service: " + invocation);
            resource.getModel().getExecutor(invocation.getAction()).execute(invocation);

            if (invocation.getFailure() == null) {
                responseMessage =
                        new OutgoingActionResponseMessage(invocation.getAction());
            } else {

                if (invocation.getFailure() instanceof ActionCancelledException) {
                    Log.i(getClass().getName(), "Action execution was cancelled, returning 404 to client");
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
            Log.v(getClass().getName(), "Error executing local action: ", ex);

            invocation = new RemoteActionInvocation(ex, getRemoteClientInfo());
            responseMessage = new OutgoingActionResponseMessage(UpnpResponse.Status.INTERNAL_SERVER_ERROR);

        } catch (UnsupportedDataException ex) {
            Log.w(getClass().getName(), "Error reading action request XML body: " + ex.toString(), Exceptions.unwrap(ex));

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

            Log.d(getClass().getName(), "Writing body of response message");
            getUpnpService().getConfiguration().getSoapActionProcessor().writeBody(responseMessage, invocation);

            Log.d(getClass().getName(), "Returning finished response message: " + responseMessage);
            return responseMessage;

        } catch (UnsupportedDataException ex) {
            Log.w(getClass().getName(), "Failure writing body of response message, sending '500 Internal Server Error' without body");
            Log.w(getClass().getName(), "Exception root cause: ", Exceptions.unwrap(ex));
            return new StreamResponseMessage(UpnpResponse.Status.INTERNAL_SERVER_ERROR);
        }
    }

}
