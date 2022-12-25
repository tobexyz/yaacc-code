/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 * and since 2022 yaacc.de tobexyz
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
 *
 *
 */

package org.fourthline.cling.transport.impl.jetty;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpHeaders;
import org.fourthline.cling.model.message.UpnpMessage;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.header.ContentTypeHeader;
import org.fourthline.cling.model.message.header.UpnpHeader;
import org.fourthline.cling.transport.spi.AbstractStreamClient;
import org.fourthline.cling.transport.spi.InitializationException;
import org.fourthline.cling.transport.spi.StreamClient;
import org.seamless.util.Exceptions;
import org.seamless.util.MimeType;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation based on Jetty client API.
 * <p>
 * This implementation works on Android, dependencies are the <code>jetty-client</code>
 * Maven module.
 * </p>
 *
 * @author Christian Bauer
 */
public class StreamClientImpl extends AbstractStreamClient<StreamClientConfigurationImpl, Request> {

    final private static Logger log = Logger.getLogger(StreamClient.class.getName());

    final protected StreamClientConfigurationImpl configuration;
    final protected HttpClient client;

    public StreamClientImpl(StreamClientConfigurationImpl configuration) throws InitializationException {
        this.configuration = configuration;

        log.info("Starting Jetty HttpClient...");
        client = new HttpClient();

        // Jetty client needs threads for its internal expiration routines, which we don't need but
        // can't disable, so let's abuse the request executor service for this
        //FIXME tobexyz really needed?
        /*client.ThreadPool(
            new ExecutorThreadPool(getConfiguration().getRequestExecutorService()) {
                @Override
                protected void doStop() throws Exception {
                    // Do nothing, don't shut down the Cling ExecutorService when Jetty stops!
                }
            }
        );*/

        // These are some safety settings, we should never run into these timeouts as we
        // do our own expiration checking
        client.setConnectTimeout((configuration.getTimeoutSeconds() + 5) * 1000);
        client.setFollowRedirects(true);

        //FIXME write own retry listener client.setMaxRetries(configuration.getRequestRetryCount());

        try {
            client.start();
        } catch (Exception ex) {
            throw new InitializationException(
                    "Could not start Jetty HTTP client: " + ex, ex
            );
        }
    }

    @Override
    public StreamClientConfigurationImpl getConfiguration() {
        return configuration;
    }

    @Override
    protected Request createRequest(StreamRequestMessage requestMessage) {
        return client.newRequest(requestMessage.getUri())
                .method(requestMessage.getOperation().getHttpMethodName());
    }

    @Override
    protected Callable<StreamResponseMessage> createCallable(final StreamRequestMessage requestMessage,
                                                             final Request request) {
        return new Callable<StreamResponseMessage>() {
            public StreamResponseMessage call() throws Exception {

                if (log.isLoggable(Level.FINE))
                    log.fine("Sending HTTP request: " + requestMessage);

                applyRequestHeader(requestMessage, request);
                applyRequestBody(requestMessage, request);
                try {
                    ContentResponse response = request.send();
                    return createResponse(response);

                } catch (Throwable t) {
                    log.log(Level.WARNING, "Error reading response: " + requestMessage, Exceptions.unwrap(t));
                    return null;
                }
            }
        };
    }

    @Override
    protected boolean abort(Request request, String reason) {
        return request.abort(new Throwable(reason));
    }

    @Override
    protected boolean logExecutionException(Throwable t) {
        return false;
    }

    @Override
    public void stop() {
        try {
            client.stop();
        } catch (Exception ex) {
            log.info("Error stopping HTTP client: " + ex);
        }
    }

    private void applyRequestHeader(StreamRequestMessage requestMessage, Request request) {
        if (!requestMessage.getHeaders().containsKey(UpnpHeader.Type.USER_AGENT)) {
            String value = getConfiguration().getUserAgentValue(
                    requestMessage.getUdaMajorVersion(),
                    requestMessage.getUdaMinorVersion());
            if (log.isLoggable(Level.FINE))
                log.fine("Setting header '" + UpnpHeader.Type.USER_AGENT.getHttpName() + "': " + value);
            request.headers(httpFields -> httpFields.add(UpnpHeader.Type.USER_AGENT.getHttpName(), value));
        }
        for (Map.Entry<String, List<String>> entry : requestMessage.getHeaders().entrySet()) {
            for (String v : entry.getValue()) {
                String headerName = entry.getKey();
                if (log.isLoggable(Level.FINE))
                    log.fine("Setting header '" + headerName + "': " + v);
                request.headers(httpFields -> httpFields.add(headerName, v));
            }
        }
    }

    private void applyRequestBody(StreamRequestMessage requestMessage, Request request) {
        // Body
        if (requestMessage.hasBody()) {

            if (log.isLoggable(Level.FINE))
                log.fine("Writing textual request body: " + requestMessage);

            MimeType contentType =
                    requestMessage.getContentTypeHeader() != null
                            ? requestMessage.getContentTypeHeader().getValue()
                            : ContentTypeHeader.DEFAULT_CONTENT_TYPE_UTF8;

            String charset =
                    requestMessage.getContentTypeCharset() != null
                            ? requestMessage.getContentTypeCharset()
                            : "UTF-8";
            byte[] content = requestMessage.getBodyString().getBytes(Charset.forName(charset));
            request.headers(httpFields -> httpFields.add(HttpHeader.CONTENT_LENGTH.asString(), "" + content.length));
            request.body(new BytesRequestContent(contentType.toString(), content));


        }
    }


    protected StreamResponseMessage createResponse(ContentResponse response) {
        // Status
        UpnpResponse responseOperation =
                new UpnpResponse(
                        response.getStatus(),
                        UpnpResponse.Status.getByStatusCode(response.getStatus()).getStatusMsg()
                );

        if (log.isLoggable(Level.FINE))
            log.fine("Received response: " + responseOperation);

        StreamResponseMessage responseMessage = new StreamResponseMessage(responseOperation);

        // Headers
        UpnpHeaders headers = new UpnpHeaders();
        HttpFields responseFields = response.getHeaders();
        for (String name : responseFields.getFieldNamesCollection()) {
            for (String value : responseFields.getValuesList(name)) {
                headers.add(name, value);
            }
        }
        responseMessage.setHeaders(headers);

        // Body
        byte[] bytes = response.getContent();
        if (bytes != null && bytes.length > 0 && responseMessage.isContentTypeMissingOrText()) {

            if (log.isLoggable(Level.FINE))
                log.fine("Response contains textual entity body, converting then setting string on message");
            try {
                responseMessage.setBodyCharacters(bytes);
            } catch (UnsupportedEncodingException ex) {
                throw new RuntimeException("Unsupported character encoding: " + ex, ex);
            }

        } else if (bytes != null && bytes.length > 0) {

            if (log.isLoggable(Level.FINE))
                log.fine("Response contains binary entity body, setting bytes on message");
            responseMessage.setBody(UpnpMessage.BodyType.BYTES, bytes);

        } else {
            if (log.isLoggable(Level.FINE))
                log.fine("Response did not contain entity body");
        }

        if (log.isLoggable(Level.FINE))
            log.fine("Response message complete: " + responseMessage);
        return responseMessage;
    }

}


