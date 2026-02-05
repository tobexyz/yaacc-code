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

import androidx.annotation.Nullable;

import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.control.IncomingActionResponseMessage;
import org.fourthline.cling.model.message.control.OutgoingActionRequestMessage;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.types.ErrorCode;
import java.io.IOException;
import org.fourthline.cling.transport.impl.SOAPActionProcessorImpl;

import java.io.IOException;
import java.net.URL;

import de.yaacc.upnp.protocol.SendingSync;
import de.yaacc.upnp.server.http.HttpRequestSender;

/**
 * Sending control message, transforming a local {@link ActionInvocation}.
 * <p>
 * Writes the outgoing message's body with the {@link org.fourthline.cling.transport.spi.SOAPActionProcessor}.
 * This protocol will return <code>null</code> if no response was received from the control target host.
 * In all other cases, even if only the processing of message content failed, this protocol will
 * return an {@link IncomingActionResponseMessage}. Any error
 * details of a failed response ({@link UpnpResponse#isFailed()}) are
 * available with
 * {@link ActionInvocation#setFailure(ActionException)}.
 * </p>
 *
 * @author Christian Bauer
 */
public class SendingAction extends SendingSync<OutgoingActionRequestMessage, IncomingActionResponseMessage> {

    final protected ActionInvocation actionInvocation;
    SOAPActionProcessorImpl soapActionProcessor = new SOAPActionProcessorImpl();
    private final HttpRequestSender httpRequestSender;

    public SendingAction(HttpRequestSender httpRequestSender, ActionInvocation actionInvocation, URL controlURL) {
        super(new OutgoingActionRequestMessage(actionInvocation, controlURL));
        this.actionInvocation = actionInvocation;
        this.httpRequestSender = httpRequestSender;

    }

    protected IncomingActionResponseMessage executeSync() throws IOException {
        return invokeRemote(getInputMessage());
    }

    protected IncomingActionResponseMessage invokeRemote(OutgoingActionRequestMessage requestMessage) throws IOException {
        Device device = actionInvocation.getAction().getService().getDevice();

        Log.v(getClass().getName(), "Sending outgoing action call '" + actionInvocation.getAction().getName() + "' to remote service of: " + device);
        IncomingActionResponseMessage responseMessage = null;
        try {

            StreamResponseMessage streamResponse = sendRemoteRequest(requestMessage);

            if (streamResponse == null) {
                Log.v(getClass().getName(), "No connection or no no response received, returning null");
                actionInvocation.setFailure(new ActionException(ErrorCode.ACTION_FAILED, "Connection error or no response received"));
                return null;
            }

            responseMessage = new IncomingActionResponseMessage(streamResponse);

            if (responseMessage.isFailedNonRecoverable()) {
                Log.v(getClass().getName(), "Response was a non-recoverable failure: " + responseMessage);
                throw new ActionException(
                        ErrorCode.ACTION_FAILED, "Non-recoverable remote execution failure: " + responseMessage.getOperation().getResponseDetails()
                );
            } else if (responseMessage.isFailedRecoverable()) {
                handleResponseFailure(responseMessage);
            } else {
                handleResponse(responseMessage);
            }

            return responseMessage;


        } catch (ActionException ex) {
            Log.v(getClass().getName(), "Remote action invocation failed, returning Internal Server Error message: " + ex.getMessage());
            actionInvocation.setFailure(ex);
            if (responseMessage == null || !responseMessage.getOperation().isFailed()) {
                return new IncomingActionResponseMessage(new UpnpResponse(UpnpResponse.Status.INTERNAL_SERVER_ERROR));
            } else {
                return responseMessage;
            }
        }
    }

    protected StreamResponseMessage sendRemoteRequest(OutgoingActionRequestMessage requestMessage)
            throws ActionException, IOException {

        try {
            Log.v(getClass().getName(), "Writing SOAP request body of: " + requestMessage);
            soapActionProcessor.writeBody(requestMessage, actionInvocation);

            Log.v(getClass().getName(), "Sending SOAP body of message as stream to remote device");
            return httpRequestSender.send(requestMessage);

        } catch (UnsupportedDataException ex) {
            Log.v(getClass().getName(), "Error writing SOAP body: " + ex);
            Log.v(getClass().getName(), "Exception root cause: ", unwrap(ex));

            throw new ActionException(ErrorCode.ACTION_FAILED, "Error writing request message. " + ex.getMessage());
        } catch (IOException ex) {
            Log.v(getClass().getName(), "Error writing SOAP body: " + ex);
            Log.v(getClass().getName(), "Exception root cause: ", unwrap(ex));

            throw new ActionException(ErrorCode.ACTION_FAILED, "Error writing request message. " + ex.getMessage());
        }
    }

    @Nullable
    private static Throwable unwrap(Exception ex) {
        Throwable cause = ex;
        for (Throwable current = ex; current != null; current = current.getCause()) {
            cause = current;
        }
        return cause;
    }

    protected void handleResponse(IncomingActionResponseMessage responseMsg) throws ActionException {

        try {
            Log.v(getClass().getName(), "Received response for outgoing call, reading SOAP response body: " + responseMsg);
            soapActionProcessor.readBody(responseMsg, actionInvocation);
        } catch (UnsupportedDataException ex) {
            Log.v(getClass().getName(), "Error reading SOAP body: " + ex);
            Log.v(getClass().getName(), "Exception root cause: ", unwrap(ex));
            throw new ActionException(
                    ErrorCode.ACTION_FAILED,
                    "Error reading SOAP response message. " + ex.getMessage(),
                    false
            );
        }
    }

    protected void handleResponseFailure(IncomingActionResponseMessage responseMsg) throws ActionException {

        try {
            Log.v(getClass().getName(), "Received response with Internal Server Error, reading SOAP failure message");

            soapActionProcessor.readBody(responseMsg, actionInvocation);
        } catch (UnsupportedDataException ex) {
            Log.v(getClass().getName(), "Error reading SOAP body: " + ex);
            Log.v(getClass().getName(), "Exception root cause: ", unwrap(ex));
            throw new ActionException(
                    ErrorCode.ACTION_FAILED,
                    "Error reading SOAP response failure message. " + ex.getMessage(),
                    false
            );
        }
    }

}

/*

- send request
   - UnsupportedDataException: Can't write body

- streamResponseMessage is null: No response received, return null to client

- streamResponseMessage >= 300 && !(405 || 500): Response was HTTP failure, set on anemic response and return

- streamResponseMessage >= 300 && 405: Try request again with different headers
   - UnsupportedDataException: Can't write body
   - (The whole streamResponse conditions apply again but this time, ignore 405)

- streamResponseMessage >= 300 && 500 && lastExecutionFailure != null: Try to read SOAP failure body
   - UnsupportedDataException: Can't read body

- streamResponseMessage < 300: Response was OK, try to read response body
   - UnsupportedDataException: Can't read body


*/