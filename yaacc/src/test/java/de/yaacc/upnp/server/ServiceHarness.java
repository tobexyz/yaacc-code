/*
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
package de.yaacc.upnp.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.nio.AsyncResponseProducer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.nio.ResponseChannel;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.model.ServiceManager;
import org.fourthline.cling.model.gena.LocalGENASubscription;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpHeaders;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.gena.OutgoingEventRequestMessage;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.resource.ServiceControlResource;
import org.fourthline.cling.model.resource.ServiceEventSubscriptionResource;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.upnp.server.http.YaaccUpnpServerProtocolRequestHandler;

/**
 * Runs a control point's SOAP actions and GENA subscriptions against one of the app's real local UPnP services
 * the way the app receives them: the service is bound from its annotations ({@link AnnotationLocalServiceBinder}),
 * a service manager is set on it, and a request goes through {@link YaaccUpnpServerProtocolRequestHandler#process}
 * (the method the HTTP server's I/O thread calls), {@link UpnpProtocolHandler#createReceivingSync},
 * {@code ReceivingAction} / {@code ReceivingSubscribe}, Cling's incoming message parsing, the service's action
 * executor, the manager lock and the SOAP answer. Only the network is replaced: a mocked
 * {@link HttpRequestSender} records the event messages sent to subscribers. Plain JVM.
 */
public class ServiceHarness<T> implements AutoCloseable {

    public interface ManagerFactory<S> {
        ServiceManager<S> create(LocalService<S> service);
    }

    public final LocalService<T> localService;
    public final HttpRequestSender sender = mock(HttpRequestSender.class);
    public final Registry registry = mock(Registry.class);
    /** Bodies of the event messages the service sent to subscribers, with the subscription id. */
    public final List<String> sentEvents = new CopyOnWriteArrayList<>();
    /** {@code System.nanoTime()} at which each entry of {@link #sentEvents} was sent (same index). */
    public final List<Long> sentEventNanos = new CopyOnWriteArrayList<>();
    /** When set, every delivery of an event fails like a connect timeout to an address that is not reachable. */
    public volatile boolean deliveryFails;
    public final AtomicInteger deliveryAttempts = new AtomicInteger();
    public final List<LocalGENASubscription> subscriptions = new CopyOnWriteArrayList<>();
    /** The pool of the registry ({@code Registry.getExecutorService}) that sends events; plays the HTTP I/O threads too. */
    public final ExecutorService executor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "harness-io");
        thread.setDaemon(true);
        return thread;
    });
    private final String serviceType;
    private final URI control;
    private final URI events;
    private final YaaccUpnpServerProtocolRequestHandler handler;

    /**
     * @param serviceClass the annotated service class to bind
     * @param serviceType  e.g. {@code urn:schemas-upnp-org:service:AVTransport:1}
     * @param serviceName  the service as it appears in the URLs, e.g. {@code AVTransport}
     */
    public ServiceHarness(Class<T> serviceClass, String serviceType, String serviceName, ManagerFactory<T> managerFactory) {
        this.serviceType = serviceType;
        this.control = URI.create("/upnp/dev/uuid/svc/upnp-org/" + serviceName + "/action");
        this.events = URI.create("/upnp/dev/uuid/svc/upnp-org/" + serviceName + "/event");
        localService = new AnnotationLocalServiceBinder().read(serviceClass);
        localService.setManager(managerFactory.create(localService));
        try {
            when(sender.send(any(OutgoingEventRequestMessage.class))).thenAnswer(invocation -> {
                OutgoingEventRequestMessage message = invocation.getArgument(0);
                deliveryAttempts.incrementAndGet();
                if (deliveryFails) {
                    throw new java.io.IOException("connect timed out");
                }
                synchronized (sentEvents) { // both lists in step, also when two threads of the pool send
                    // time first: a reader that sees the event also sees when it was sent
                    sentEventNanos.add(System.nanoTime());
                    sentEvents.add(message.getBodyString());
                }
                return new StreamResponseMessage(new UpnpResponse(UpnpResponse.Status.OK));
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        when(registry.getExecutorService()).thenReturn(executor);
        when(registry.getResource(any(), any())).thenAnswer(invocation -> {
            Class<?> type = invocation.getArgument(0);
            if (type == ServiceControlResource.class) {
                return new ServiceControlResource(control, localService);
            }
            if (type == ServiceEventSubscriptionResource.class) {
                return new ServiceEventSubscriptionResource(events, localService);
            }
            return null;
        });
        doAnswer(invocation -> subscriptions.add(invocation.getArgument(0))).when(registry).addLocalSubscription(any());
        UpnpProtocolHandler protocols = new UpnpProtocolHandler(mock(Context.class), registry, null, null, sender);
        handler = new YaaccUpnpServerProtocolRequestHandler(() -> protocols);
    }

    public T service() {
        return localService.getManager().getImplementation();
    }

    // ---- control point: SOAP

    /**
     * @param action     SOAP action name
     * @param nameValues argument names and values in order, e.g. {@code "InstanceID", "0", "NewPlayMode", "SHUFFLE"}
     * @return what the control point gets back: status 200 and {@code <u:XResponse>}, or 500 and a SOAP fault
     */
    public StreamResponseMessage soap(String action, String... nameValues) {
        String body = soapBody(action, nameValues);
        StreamRequestMessage request = new StreamRequestMessage(UpnpRequest.Method.POST, control, body);
        UpnpHeaders headers = new UpnpHeaders();
        headers.add("CONTENT-TYPE", "text/xml; charset=\"utf-8\"");
        headers.add("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
        request.setHeaders(headers);
        return handler.process(request);
    }

    private String soapBody(String action, String... nameValues) {
        StringBuilder body = new StringBuilder();
        body.append("<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
                .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:").append(action)
                .append(" xmlns:u=\"").append(serviceType).append("\">");
        for (int i = 0; i < nameValues.length; i += 2) {
            body.append('<').append(nameValues[i]).append('>').append(escape(nameValues[i + 1]))
                    .append("</").append(nameValues[i]).append('>');
        }
        body.append("</u:").append(action).append("></s:Body></s:Envelope>");
        return body.toString();
    }

    public static boolean succeeded(StreamResponseMessage response) {
        return response != null && response.getOperation().getStatusCode() == 200;
    }

    public static String describe(StreamResponseMessage response) {
        return response == null ? "no response (404)"
                : response.getOperation().getStatusCode() + " " + response.getBodyString();
    }

    // ---- control point: GENA

    /** SUBSCRIBE with a callback, as Home Assistant does; returns the answer to the control point. */
    public StreamResponseMessage subscribe() {
        StreamRequestMessage request = new StreamRequestMessage(UpnpRequest.Method.SUBSCRIBE, events);
        UpnpHeaders headers = new UpnpHeaders();
        headers.add("CALLBACK", "<http://192.0.2.10:8123/api/dlna_dmr/notify>");
        headers.add("NT", "upnp:event");
        headers.add("TIMEOUT", "Second-300");
        request.setHeaders(headers);
        return handler.process(request);
    }


    /**
     * A complete HTTP exchange the way the server's I/O thread runs it: {@code handle(...)} of the handler with
     * the request as the server parsed it, then what the server does with the answer it was handed: write it,
     * and release the response producer when the exchange is over (or tell it about a failed write).
     *
     * @return the HTTP status code that was sent
     */
    public int exchange(String method, URI uri, String body, String... headerNameValues) throws Exception {
        return start(method, uri, body, headerNameValues).complete(false);
    }

    /** An exchange whose answer is computed but not yet written: {@link Exchange#complete} writes it. */
    public Exchange start(String method, URI uri, String body, String... headerNameValues) throws Exception {
        HttpRequest request = new BasicHttpRequest(method, uri.toString());
        for (int i = 0; i < headerNameValues.length; i += 2) {
            request.addHeader(headerNameValues[i], headerNameValues[i + 1]);
        }
        byte[] bytes = body == null ? null : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AtomicReference<AsyncResponseProducer> submitted = new AtomicReference<>();
        AsyncServerRequestHandler.ResponseTrigger trigger = new AsyncServerRequestHandler.ResponseTrigger() {
            @Override
            public void sendInformation(org.apache.hc.core5.http.HttpResponse response, HttpContext context) {
            }

            @Override
            public void submitResponse(AsyncResponseProducer producer, HttpContext context) {
                submitted.set(producer);
            }

            @Override
            public void pushPromise(org.apache.hc.core5.http.HttpRequest promise, HttpContext context,
                                    org.apache.hc.core5.http.nio.AsyncPushProducer push) {
            }
        };
        HttpContext context = new BasicHttpContext();
        handler.handle(new Message<>(request, bytes), trigger, context);
        if (submitted.get() == null) {
            throw new IllegalStateException("no response was submitted");
        }
        return new Exchange(submitted.get(), context);
    }

    public static final class Exchange {
        private final AsyncResponseProducer producer;
        private final HttpContext context;

        public Exchange(AsyncResponseProducer producer, HttpContext context) {
            this.producer = producer;
            this.context = context;
        }

        /**
         * What the server does with the answer: write it, then release the producer when the exchange is over
         * (or tell it that the write failed).
         *
         * @return the HTTP status code that was sent
         */
        public int complete(boolean writeFails) throws Exception {
            int[] status = {-1};
            ResponseChannel channel = new ResponseChannel() {
                @Override
                public void sendInformation(org.apache.hc.core5.http.HttpResponse response, HttpContext ctx) {
                }

                @Override
                public void sendResponse(org.apache.hc.core5.http.HttpResponse response,
                                         org.apache.hc.core5.http.EntityDetails entity, HttpContext ctx) {
                    status[0] = response.getCode();
                }

                @Override
                public void pushPromise(org.apache.hc.core5.http.HttpRequest promise,
                                        org.apache.hc.core5.http.nio.AsyncPushProducer push, HttpContext ctx) {
                }
            };
            producer.sendResponse(channel, context);
            if (writeFails) {
                producer.failed(new java.io.IOException("connection reset"));
            } else {
                producer.releaseResources();
            }
            return status[0];
        }
    }

    public int soapExchange(String action, String... nameValues) throws Exception {
        return exchange("POST", control, soapBody(action, nameValues),
                "Content-Type", "text/xml; charset=\"utf-8\"", "SOAPACTION", "\"" + serviceType + "#" + action + "\"");
    }

    public int subscribeExchange() throws Exception {
        return startSubscribe().complete(false);
    }

    public Exchange startSubscribe() throws Exception {
        return start("SUBSCRIBE", events, null,
                "CALLBACK", "<http://192.0.2.10:8123/api/dlna_dmr/notify>", "NT", "upnp:event", "TIMEOUT", "Second-300");
    }

    public YaaccUpnpServerProtocolRequestHandler handler() {
        return handler;
    }


    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
