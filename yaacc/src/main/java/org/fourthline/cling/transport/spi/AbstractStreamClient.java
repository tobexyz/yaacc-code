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
package org.fourthline.cling.transport.spi;

import android.util.Log;

import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.seamless.util.Exceptions;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Implements the timeout/callback processing and unifies exception handling.
 *
 * @author Christian Bauer
 */
public abstract class AbstractStreamClient<C extends StreamClientConfiguration, REQUEST> implements StreamClient<C> {


    @Override
    public StreamResponseMessage sendRequest(StreamRequestMessage requestMessage) throws InterruptedException {


        Log.i(getClass().getName(), "Preparing HTTP request: " + requestMessage);
        Log.i(getClass().getName(), "HTTP body: " + requestMessage.getBodyString());

        REQUEST request = createRequest(requestMessage);
        if (request == null)
            return null;

        Callable<StreamResponseMessage> callable = createCallable(requestMessage, request);

        // We want to track how long it takes
        long start = System.currentTimeMillis();

        // Execute the request on a new thread
        Future<StreamResponseMessage> future =
                getConfiguration().getRequestExecutorService().submit(callable);

        // Wait on the current thread for completion
        try {
            Log.i(getClass().getName(),
                    "Waiting " + getConfiguration().getTimeoutSeconds()
                            + " seconds for HTTP request to complete: " + requestMessage
            );
            StreamResponseMessage response =
                    future.get(getConfiguration().getTimeoutSeconds(), TimeUnit.SECONDS);

            // Log a warning if it took too long
            long elapsed = System.currentTimeMillis() - start;

            Log.i(getClass().getName(), "Got HTTP response in " + elapsed + "ms: " + requestMessage);
            if (getConfiguration().getLogWarningSeconds() > 0
                    && elapsed > getConfiguration().getLogWarningSeconds() * 1000) {
                Log.w(getClass().getName(), "HTTP request took a long time (" + elapsed + "ms): " + requestMessage);
            }

            return response;

        } catch (InterruptedException ex) {


            Log.d(getClass().getName(), "Interruption, aborting request: " + requestMessage);
            abort(request, "Interruption, aborting request: " + requestMessage);
            throw new InterruptedException("HTTP request interrupted and aborted");

        } catch (TimeoutException ex) {

            Log.i(getClass().getName(),
                    "Timeout of " + getConfiguration().getTimeoutSeconds()
                            + " seconds while waiting for HTTP request to complete, aborting: " + requestMessage
            );
            abort(request, "Timeout of " + getConfiguration().getTimeoutSeconds()
                    + " seconds while waiting for HTTP request to complete, aborting: " + requestMessage);

            return null;

        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (!logExecutionException(cause)) {
                Log.w(getClass().getName(), "HTTP request failed: " + requestMessage, Exceptions.unwrap(cause));
            }
            return null;
        } finally {
            onFinally(request);
        }
    }

    /**
     * Create a proprietary representation of this request, log warnings and
     * return <code>null</code> if creation fails.
     */
    abstract protected REQUEST createRequest(StreamRequestMessage requestMessage);

    /**
     * Create a callable procedure that will execute the request.
     */
    abstract protected Callable<StreamResponseMessage> createCallable(StreamRequestMessage requestMessage,
                                                                      REQUEST request);

    /**
     * Cancel and abort the request immediately, with the proprietary API.
     */
    abstract protected boolean abort(REQUEST request, String reason);

    /**
     * @return <code>true</code> if no more logging of this exception should be done.
     */
    abstract protected boolean logExecutionException(Throwable t);

    protected void onFinally(REQUEST request) {
        // Do nothing
    }

}
