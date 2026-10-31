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
package de.yaacc.upnp.server.http;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.AsyncResponseProducer;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.ResponseChannel;
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
import org.fourthline.cling.model.message.UpnpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import de.yaacc.upnp.protocol.ProtocolCreationException;
import de.yaacc.upnp.protocol.ReceivingSync;
import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.util.YaaccLogger;

public class YaaccUpnpServerProtocolRequestHandler implements AsyncServerRequestHandler<Message<HttpRequest, byte[]>> {

    private final Supplier<UpnpProtocolHandler> protocolHandlerSupplier;

    /**
     * @param protocolHandlerSupplier resolves the live protocol handler for every request: the handler is replaced on
     *                                every network change and is null while there is no network. A handler captured
     *                                once at the start of the server would stay null (or become stale).
     */
    public YaaccUpnpServerProtocolRequestHandler(Supplier<UpnpProtocolHandler> protocolHandlerSupplier) {
        this.protocolHandlerSupplier = protocolHandlerSupplier;
    }

    /**
     * @return the current protocol handler, null if there is none (no network) or the supplier fails
     */
    private UpnpProtocolHandler resolveProtocolHandler() {
        try {
            return protocolHandlerSupplier == null ? null : protocolHandlerSupplier.get();
        } catch (RuntimeException ex) {
            YaaccLogger.w(getClass().getName(), "Resolving the protocol handler failed - " + ex);
            return null;
        }
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
        // The protocol of THIS request: several requests are handled at the same time (one I/O thread each), so
        // it must not be kept in a field of the shared handler (a subscriber's "response sent" went to whichever
        // protocol ran last).
        ReceivingSync protocol = null;
        StreamResponseMessage responseMessage = null;

        try {
            StreamRequestMessage requestMessage = readRequestMessage(message);
            YaaccLogger.v(getClass().getName(), "Processing new request message: " + requestMessage + " body: " + requestMessage.getBodyString());

            Processed processed = processRequest(requestMessage);
            protocol = processed.protocol;
            responseMessage = processed.response;

            if (responseMessage != null) {

                YaaccLogger.v(getClass().getName(), "Preparing HTTP response message: " + responseMessage + " body: " + responseMessage.getBodyString());
                writeResponseMessage(responseMessage, responseBuilder);
            } else {
                // If it's null, it's 404
                YaaccLogger.v(getClass().getName(), "Sending HTTP response status: " + HttpStatus.SC_NOT_FOUND);
                responseBuilder.setStatus(HttpStatus.SC_NOT_FOUND);
            }
            responseTrigger.submitResponse(
                    new DeliveryReportingResponseProducer(responseBuilder.build(), protocol, responseMessage), context);

        } catch (Throwable t) {
            YaaccLogger.i(getClass().getName(), "Exception occurred during UPnP stream processing: " + t);
            YaaccLogger.d(getClass().getName(), "Cause: " + unwrap(t), unwrap(t));
            YaaccLogger.v(getClass().getName(), "returning INTERNAL SERVER ERROR to client");
            responseBuilder.setStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR);
            responseTrigger.submitResponse(responseBuilder.build(), context);

            if (protocol != null) {
                protocol.responseException(t);
            }
        }
    }

    protected StreamRequestMessage readRequestMessage(Message<HttpRequest, byte[]> message) throws IOException {
        // Extract what we need from the HTTP httpRequest
        HttpRequest request = message.getHead();
        String requestMethod = request.getMethod();
        String requestURI = request.getRequestUri();

        YaaccLogger.v(getClass().getName(), "Processing HTTP request: " + requestMethod + " " + requestURI);

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
        YaaccLogger.v(getClass().getName(), "Reading request body bytes: " + bodyBytes.length);

        if (bodyBytes.length > 0 && requestMessage.isContentTypeMissingOrText()) {

            YaaccLogger.v(getClass().getName(), "Request contains textual entity body, converting then setting string on message");
            requestMessage.setBodyCharacters(bodyBytes);

        } else if (bodyBytes.length > 0) {

            YaaccLogger.v(getClass().getName(), "Request contains binary entity body, setting bytes on message");
            requestMessage.setBody(UpnpMessage.BodyType.BYTES, bodyBytes);

        } else {
            YaaccLogger.v(getClass().getName(), "Request did not contain entity body");
        }

        return requestMessage;
    }

    protected void writeResponseMessage(StreamResponseMessage responseMessage, AsyncResponseBuilder responseBuilder) {
        YaaccLogger.v(getClass().getName(), "Sending HTTP response status: " + responseMessage.getOperation().getStatusCode());

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
            YaaccLogger.v(getClass().getName(), "Response message has body, writing bytes to stream...");
            ContentType ct = ContentType.APPLICATION_XML;
            if (responseMessage.getContentTypeHeader() != null) {
                ct = ContentType.parse(responseMessage.getContentTypeHeader().getValue().toString());
            }
            responseBuilder.setEntity(AsyncEntityProducers.create(responseBodyBytes, ct));
        }
    }


    /**
     * Selects a UPnP protocol, runs it within the calling thread, returns the response.
     * <p>
     * This method will return <code>null</code> if the UPnP protocol returned <code>null</code>.
     * The HTTP response in this case is always <em>404 NOT FOUND</em>. Any other (HTTP) error
     * condition will be encapsulated in the returned response message and has to be
     * passed to the HTTP client as it is.
     * </p>
     *
     * @param requestMsg The TCP (HTTP) stream request message.
     * @return The TCP (HTTP) stream response message, or <code>null</code> if a 404 should be send to the client.
     */
    public StreamResponseMessage process(StreamRequestMessage requestMsg) {
        return processRequest(requestMsg).response;
    }

    /** The answer to a request and the protocol that made it (null when there was none). */
    private static final class Processed {
        final StreamResponseMessage response;
        final ReceivingSync protocol;

        Processed(StreamResponseMessage response, ReceivingSync protocol) {
            this.response = response;
            this.protocol = protocol;
        }
    }

    private Processed processRequest(StreamRequestMessage requestMsg) {
        YaaccLogger.v(getClass().getName(), "Processing stream request message: " + requestMsg);

        UpnpProtocolHandler upnpProtocolHandler = resolveProtocolHandler();
        if (upnpProtocolHandler == null) {
            // no network (or the listener is being restarted): answer cleanly, the client may try again later
            YaaccLogger.w(getClass().getName(), "No protocol handler available, answering 503 to " + requestMsg);
            return new Processed(new StreamResponseMessage(UpnpResponse.Status.SERVICE_UNAVAILABLE), null);
        }

        ReceivingSync protocol;
        try {
            // Try to get a protocol implementation that matches the request message
            protocol = upnpProtocolHandler.createReceivingSync(requestMsg);
        } catch (ProtocolCreationException ex) {
            YaaccLogger.w(getClass().getName(), "Processing stream request failed - " + unwrap(ex).toString());
            return new Processed(new StreamResponseMessage(UpnpResponse.Status.NOT_IMPLEMENTED), null);
        }
        if (protocol == null) {
            // the handler has no protocol for this method/path combination
            YaaccLogger.w(getClass().getName(), "No protocol for stream request " + requestMsg);
            return new Processed(new StreamResponseMessage(UpnpResponse.Status.NOT_IMPLEMENTED), null);
        }

        // Run it
        YaaccLogger.v(getClass().getName(), "Running protocol for synchronous message processing: " + protocol);
        protocol.run();

        // ... then grab the response
        StreamResponseMessage responseMsg = protocol.getOutputMessage();

        if (responseMsg == null) {
            // That's ok, the caller is supposed to handle this properly (e.g. convert it to HTTP 404)
            YaaccLogger.v(getClass().getName(), "Protocol did not return any response message");
            return new Processed(null, protocol);
        }
        YaaccLogger.v(getClass().getName(), "Protocol returned response: " + responseMsg);
        return new Processed(responseMsg, protocol);
    }

    /**
     * Tells the protocol of a request whether its answer reached the client, as UDA requires and Cling's own
     * servers do: {@link ReceivingSync#responseSent} (a SUBSCRIBE registers its subscription at the service and
     * sends the initial event only now) or {@link ReceivingSync#responseException}. Nothing called these in this
     * server, so a control point that subscribed (Home Assistant does for AVTransport) was answered with a
     * subscription id but never got an event, neither the initial one nor a later one.
     * <p>
     * The producer is released when the exchange is over, after the response was written (or failed).
     */
    static final class DeliveryReportingResponseProducer implements AsyncResponseProducer {
        private final AsyncResponseProducer delegate;
        private final ReceivingSync protocol;
        private final StreamResponseMessage response;
        private final AtomicBoolean reported = new AtomicBoolean();
        private volatile Throwable failure;

        DeliveryReportingResponseProducer(AsyncResponseProducer delegate, ReceivingSync protocol, StreamResponseMessage response) {
            this.delegate = delegate;
            this.protocol = protocol;
            this.response = response;
        }

        @Override
        public void sendResponse(ResponseChannel channel, HttpContext context) throws HttpException, IOException {
            try {
                delegate.sendResponse(channel, context);
            } catch (HttpException | IOException | RuntimeException e) {
                failure = e;
                throw e;
            }
        }

        @Override
        public int available() {
            return delegate.available();
        }

        @Override
        public void produce(DataStreamChannel channel) throws IOException {
            try {
                delegate.produce(channel);
            } catch (IOException | RuntimeException e) {
                failure = e;
                throw e;
            }
        }

        @Override
        public void failed(Exception cause) {
            failure = cause;
            try {
                delegate.failed(cause);
            } finally {
                report();
            }
        }

        @Override
        public void releaseResources() {
            try {
                delegate.releaseResources();
            } finally {
                report();
            }
        }

        private void report() {
            if (protocol == null || !reported.compareAndSet(false, true)) {
                return;
            }
            try {
                Throwable cause = failure;
                if (cause != null) {
                    protocol.responseException(cause);
                } else {
                    protocol.responseSent(response);
                }
            } catch (RuntimeException e) {
                YaaccLogger.w(getClass().getName(), "Reporting the delivery of a response failed: " + e, e);
            }
        }
    }

    @Override
    public String toString() {
        return "(" + getClass().getSimpleName() + ")";
    }

    public static Throwable unwrap(Throwable throwable) throws IllegalArgumentException {
        if (throwable == null) {
            throw new IllegalArgumentException("Cannot unwrap null throwable");
        }
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            throwable = current;
        }
        return throwable;
    }
}
