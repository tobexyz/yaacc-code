/*
 *
 * Copyright (C) 2023 Tobias Schoene www.yaacc.de
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
package de.yaacc.upnp;

import android.util.Log;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.nio.entity.AsyncEntityProducers;
import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityConsumer;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.nio.support.BasicRequestConsumer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpHeaders;
import org.fourthline.cling.model.message.UpnpMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.transport.spi.UpnpStream;
import org.seamless.util.Exceptions;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

public class YaaccAsyncStreamServerRequestHandler extends UpnpStream implements AsyncServerRequestHandler<Message<HttpRequest, byte[]>> {


    protected YaaccAsyncStreamServerRequestHandler(ProtocolFactory protocolFactory) {
        super(protocolFactory);
    }

    @Override
    public AsyncRequestConsumer<Message<HttpRequest, byte[]>> prepare(
            final HttpRequest request,
            final EntityDetails entityDetails,
            final HttpContext context) throws HttpException {
        return new BasicRequestConsumer<>(entityDetails != null ? new BasicAsyncEntityConsumer() : null);
    }

    @Override
    public void handle(
            final Message<HttpRequest, byte[]> message,
            final ResponseTrigger responseTrigger,
            final HttpContext context) throws HttpException, IOException {
        final AsyncResponseBuilder responseBuilder = AsyncResponseBuilder.create(HttpStatus.SC_OK);

        try {
            StreamRequestMessage requestMessage = readRequestMessage(message);
            Log.v(getClass().getName(), "Processing new request message: " + requestMessage);

            StreamResponseMessage responseMessage = process(requestMessage);

            if (responseMessage != null) {

                Log.v(getClass().getName(), "Preparing HTTP response message: " + responseMessage);
                writeResponseMessage(responseMessage, responseBuilder);
            } else {
                // If it's null, it's 404
                Log.v(getClass().getName(), "Sending HTTP response status: " + HttpStatus.SC_NOT_FOUND);
                responseBuilder.setStatus(HttpStatus.SC_NOT_FOUND);
            }
            responseTrigger.submitResponse(responseBuilder.build(), context);

        } catch (Throwable t) {
            Log.i(getClass().getName(), "Exception occurred during UPnP stream processing: " + t);
            Log.d(getClass().getName(), "Cause: " + Exceptions.unwrap(t), Exceptions.unwrap(t));
            Log.v(getClass().getName(), "returning INTERNAL SERVER ERROR to client");
            responseBuilder.setStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR);
            responseTrigger.submitResponse(responseBuilder.build(), context);

            responseException(t);
        }
    }

    protected StreamRequestMessage readRequestMessage(Message<HttpRequest, byte[]> message) throws IOException {
        // Extract what we need from the HTTP httpRequest
        HttpRequest request = message.getHead();
        String requestMethod = request.getMethod();
        String requestURI = request.getRequestUri();

        Log.v(getClass().getName(), "Processing HTTP request: " + requestMethod + " " + requestURI);

        StreamRequestMessage requestMessage;
        try {
            requestMessage =
                    new StreamRequestMessage(
                            UpnpRequest.Method.getByHttpName(requestMethod),
                            URI.create(requestURI)
                    );

        } catch (IllegalArgumentException ex) {
            throw new RuntimeException("Invalid request URI: " + requestURI, ex);
        }

        if (requestMessage.getOperation().getMethod().equals(UpnpRequest.Method.UNKNOWN)) {
            throw new RuntimeException("Method not supported: " + requestMethod);
        }

        UpnpHeaders headers = new UpnpHeaders();
        Header[] requestHeaders = request.getHeaders();
        for (Header header : requestHeaders) {
            headers.add(header.getName(), header.getValue());
        }
        requestMessage.setHeaders(headers);

        // Body
        byte[] bodyBytes = message.getBody();
        if (bodyBytes == null) {
            bodyBytes = new byte[]{};
        }
        Log.v(getClass().getName(), "Reading request body bytes: " + bodyBytes.length);

        if (bodyBytes.length > 0 && requestMessage.isContentTypeMissingOrText()) {

            Log.v(getClass().getName(), "Request contains textual entity body, converting then setting string on message");
            requestMessage.setBodyCharacters(bodyBytes);

        } else if (bodyBytes.length > 0) {

            Log.v(getClass().getName(), "Request contains binary entity body, setting bytes on message");
            requestMessage.setBody(UpnpMessage.BodyType.BYTES, bodyBytes);

        } else {
            Log.v(getClass().getName(), "Request did not contain entity body");
        }

        return requestMessage;
    }

    protected void writeResponseMessage(StreamResponseMessage responseMessage, AsyncResponseBuilder responseBuilder) {
        Log.v(getClass().getName(), "Sending HTTP response status: " + responseMessage.getOperation().getStatusCode());

        responseBuilder.setStatus(responseMessage.getOperation().getStatusCode());

        // Headers
        for (Map.Entry<String, List<String>> entry : responseMessage.getHeaders().entrySet()) {
            for (String value : entry.getValue()) {
                responseBuilder.addHeader(entry.getKey(), value);
            }
        }
        // The Date header is recommended in UDA
        responseBuilder.addHeader("Date", "" + System.currentTimeMillis());

        // Body
        byte[] responseBodyBytes = responseMessage.hasBody() ? responseMessage.getBodyBytes() : null;
        int contentLength = responseBodyBytes != null ? responseBodyBytes.length : -1;

        if (contentLength > 0) {
            Log.v(getClass().getName(), "Response message has body, writing bytes to stream...");
            ContentType ct = ContentType.APPLICATION_XML;
            if (responseMessage.getContentTypeHeader() != null) {
                ct = ContentType.parse(responseMessage.getContentTypeHeader().getValue().toString());
            }
            responseBuilder.setEntity(AsyncEntityProducers.create(responseBodyBytes, ct));
        }
    }

    @Override
    public void run() {
        //FIXME why this has to be a runnable?
    }
}
