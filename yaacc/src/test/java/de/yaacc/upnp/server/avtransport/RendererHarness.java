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
package de.yaacc.upnp.server.avtransport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.nio.AsyncResponseProducer;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.ResponseChannel;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.model.ServiceManager;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpHeaders;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.gena.OutgoingEventRequestMessage;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.resource.ServiceControlResource;
import org.fourthline.cling.model.resource.ServiceEventSubscriptionResource;
import org.fourthline.cling.model.gena.LocalGENASubscription;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.player.PlayMode;
import de.yaacc.player.Player;
import de.yaacc.player.PlayerListChangeNotifier;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.upnp.server.http.YaaccUpnpServerProtocolRequestHandler;

/**
 * Runs a control point's SOAP actions and GENA subscriptions against the media renderer's real AVTransport
 * service the way the app receives them: the service is bound from its annotations
 * ({@link AnnotationLocalServiceBinder}), a service manager is set on it, and a request goes through
 * {@link YaaccUpnpServerProtocolRequestHandler#process} (the method the HTTP server's I/O thread calls),
 * {@link UpnpProtocolHandler#createReceivingSync}, {@code ReceivingAction} / {@code ReceivingSubscribe},
 * Cling's incoming message parsing, the service's action executor, the manager lock and the SOAP answer.
 * Only the network and the players are replaced: a mocked {@link UpnpClient} with fake {@link Player}s that
 * keep their {@link PlayMode} and tell their property change listeners like {@code AbstractPlayer} does,
 * and a mocked {@link HttpRequestSender} that records the event messages sent to subscribers. Plain JVM.
 */
final class RendererHarness implements AutoCloseable {

    static final String SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final URI CONTROL = URI.create("/upnp/dev/uuid/svc/upnp-org/AVTransport/action");
    private static final URI EVENTS = URI.create("/upnp/dev/uuid/svc/upnp-org/AVTransport/event");

    final LocalService<YaaccAVTransportService> localService;
    final UpnpClient client = mock(UpnpClient.class);
    final HttpRequestSender sender = mock(HttpRequestSender.class);
    final Registry registry = mock(Registry.class);
    /** The players the renderer plays on (what {@code UpnpClient.getCurrentPlayers(transport)} answers). */
    final List<Player> players = new CopyOnWriteArrayList<>();
    /** Bodies of the event messages the renderer sent to subscribers, with the subscription id. */
    final List<String> sentEvents = new CopyOnWriteArrayList<>();
    /** When set, every delivery of an event fails like a connect timeout to an address that is not reachable. */
    volatile boolean deliveryFails;
    final java.util.concurrent.atomic.AtomicInteger deliveryAttempts = new java.util.concurrent.atomic.AtomicInteger();
    final List<LocalGENASubscription> subscriptions = new CopyOnWriteArrayList<>();
    /** The pool of the registry ({@code Registry.getExecutorService}) that sends events; plays the HTTP I/O threads too. */
    final ExecutorService executor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "harness-io");
        thread.setDaemon(true);
        return thread;
    });
    private final YaaccUpnpServerProtocolRequestHandler handler;

    RendererHarness(ServiceManagerFactory managerFactory) {
        AvTransport.setUpnpClient(client);
        localService = new AnnotationLocalServiceBinder().read(YaaccAVTransportService.class);
        localService.setManager(managerFactory.create(localService));
        try {
            when(sender.send(any(OutgoingEventRequestMessage.class))).thenAnswer(invocation -> {
                OutgoingEventRequestMessage message = invocation.getArgument(0);
                deliveryAttempts.incrementAndGet();
                if (deliveryFails) {
                    throw new java.io.IOException("connect timed out");
                }
                sentEvents.add(message.getBodyString());
                return new StreamResponseMessage(new UpnpResponse(UpnpResponse.Status.OK));
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        when(registry.getExecutorService()).thenReturn(executor);
        when(registry.getResource(any(), any())).thenAnswer(invocation -> {
            Class<?> type = invocation.getArgument(0);
            if (type == ServiceControlResource.class) {
                return new ServiceControlResource(CONTROL, localService);
            }
            if (type == ServiceEventSubscriptionResource.class) {
                return new ServiceEventSubscriptionResource(EVENTS, localService);
            }
            return null;
        });
        doAnswer(invocation -> subscriptions.add(invocation.getArgument(0))).when(registry).addLocalSubscription(any());
        UpnpProtocolHandler protocols = new UpnpProtocolHandler(mock(Context.class), registry, null, null, sender);
        handler = new YaaccUpnpServerProtocolRequestHandler(() -> protocols);

        when(client.getCurrentPlayers(any(AvTransport.class))).thenAnswer(invocation ->
                lookupByKindFindsNothing ? new ArrayList<Player>() : new ArrayList<>(players));
        when(client.getCurrentPlayers()).thenAnswer(invocation -> new ArrayList<>(players));
        doAnswer(invocation -> playerListListeners.addIfAbsent(invocation.getArgument(0)))
                .when(client).addPlayerListChangeListener(any());
        doAnswer(invocation -> playerListListeners.remove(invocation.<Object>getArgument(0)))
                .when(client).removePlayerListChangeListener(any());
        // like PlayerService.createPlayerForLocalDevice: the player of the same kind is shut down, a new one with the
        // mode of the settings is created and added
        when(client.initializePlayers(any(AvTransport.class))).thenAnswer(invocation -> {
            List<Player> replaced = new ArrayList<>(players);
            players.clear();
            if (removalNoticeDuringStart) {
                // the main thread delivers the "removed" notice while the new player is still being created
                replaced.forEach(this::announceRemoved);
            }
            if (!newPlayerCannotBeCreated) {
                players.add(fakePlayer(newPlayerMode));
            }
            return new ArrayList<>(players);
        });
    }

    /** Listeners of {@code UpnpClient.addPlayerListChangeListener} (the transport registers one). */
    final CopyOnWriteArrayList<PlayerListChangeNotifier.Listener> playerListListeners = new CopyOnWriteArrayList<>();
    /** The mode a new player starts with (the settings). */
    PlayMode newPlayerMode = PlayMode.NORMAL;
    boolean removalNoticeDuringStart = true;
    boolean newPlayerCannotBeCreated;
    /** {@code getCurrentPlayers(transport)} goes by the kind of player for the mime type of the track: it can find none. */
    volatile boolean lookupByKindFindsNothing;

    private void announceRemoved(Player player) {
        for (PlayerListChangeNotifier.Listener listener : playerListListeners) {
            listener.onPlayerRemoved(player);
        }
    }

    /** The user closes the player in the app (or it ends): it leaves the active players, the listeners are told. */
    void closePlayer(Player player) {
        players.remove(player);
        announceRemoved(player);
    }

    interface ServiceManagerFactory {
        ServiceManager<YaaccAVTransportService> create(LocalService<YaaccAVTransportService> service);
    }

    YaaccAVTransportService service() {
        return localService.getManager().getImplementation();
    }

    AvTransport transport() throws Exception {
        return (AvTransport) service().findStateMachine(YaaccAVTransportService.getDefaultInstanceID())
                .getCurrentState().getTransport();
    }

    // ---- control point: SOAP

    /**
     * @param action     SOAP action name
     * @param nameValues argument names and values in order, e.g. {@code "InstanceID", "0", "NewPlayMode", "SHUFFLE"}
     * @return what the control point gets back: status 200 and {@code <u:XResponse>}, or 500 and a SOAP fault
     */
    StreamResponseMessage soap(String action, String... nameValues) {
        String body = soapBody(action, nameValues);
        StreamRequestMessage request = new StreamRequestMessage(UpnpRequest.Method.POST, CONTROL, body);
        UpnpHeaders headers = new UpnpHeaders();
        headers.add("CONTENT-TYPE", "text/xml; charset=\"utf-8\"");
        headers.add("SOAPACTION", "\"" + SERVICE_TYPE + "#" + action + "\"");
        request.setHeaders(headers);
        return handler.process(request);
    }

    private static String soapBody(String action, String... nameValues) {
        StringBuilder body = new StringBuilder();
        body.append("<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
                .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:").append(action)
                .append(" xmlns:u=\"").append(SERVICE_TYPE).append("\">");
        for (int i = 0; i < nameValues.length; i += 2) {
            body.append('<').append(nameValues[i]).append('>').append(escape(nameValues[i + 1]))
                    .append("</").append(nameValues[i]).append('>');
        }
        body.append("</u:").append(action).append("></s:Body></s:Envelope>");
        return body.toString();
    }

    StreamResponseMessage setPlayMode(String mode) {
        return soap("SetPlayMode", "InstanceID", "0", "NewPlayMode", mode);
    }

    StreamResponseMessage setAvTransportUri(String uri) {
        return soap("SetAVTransportURI", "InstanceID", "0", "CurrentURI", uri, "CurrentURIMetaData", "");
    }

    StreamResponseMessage play() {
        return soap("Play", "InstanceID", "0", "Speed", "1");
    }

    StreamResponseMessage stop() {
        return soap("Stop", "InstanceID", "0");
    }

    StreamResponseMessage pause() {
        return soap("Pause", "InstanceID", "0");
    }

    static boolean succeeded(StreamResponseMessage response) {
        return response != null && response.getOperation().getStatusCode() == 200;
    }

    static String describe(StreamResponseMessage response) {
        return response == null ? "no response (404)"
                : response.getOperation().getStatusCode() + " " + response.getBodyString();
    }

    // ---- control point: GENA

    /** SUBSCRIBE with a callback, as Home Assistant does; returns the answer to the control point. */
    StreamResponseMessage subscribe() {
        StreamRequestMessage request = new StreamRequestMessage(UpnpRequest.Method.SUBSCRIBE, EVENTS);
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
    int exchange(String method, URI uri, String body, String... headerNameValues) throws Exception {
        return start(method, uri, body, headerNameValues).complete(false);
    }

    /** An exchange whose answer is computed but not yet written: {@link Exchange#complete} writes it. */
    Exchange start(String method, URI uri, String body, String... headerNameValues) throws Exception {
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

    static final class Exchange {
        private final AsyncResponseProducer producer;
        private final HttpContext context;

        Exchange(AsyncResponseProducer producer, HttpContext context) {
            this.producer = producer;
            this.context = context;
        }

        /**
         * What the server does with the answer: write it, then release the producer when the exchange is over
         * (or tell it that the write failed).
         *
         * @return the HTTP status code that was sent
         */
        int complete(boolean writeFails) throws Exception {
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

    int soapExchange(String action, String... nameValues) throws Exception {
        return exchange("POST", CONTROL, soapBody(action, nameValues),
                "Content-Type", "text/xml; charset=\"utf-8\"", "SOAPACTION", "\"" + SERVICE_TYPE + "#" + action + "\"");
    }

    int subscribeExchange() throws Exception {
        return startSubscribe().complete(false);
    }

    Exchange startSubscribe() throws Exception {
        return start("SUBSCRIBE", EVENTS, null,
                "CALLBACK", "<http://192.0.2.10:8123/api/dlna_dmr/notify>", "NT", "upnp:event", "TIMEOUT", "Second-300");
    }

    YaaccUpnpServerProtocolRequestHandler handler() {
        return handler;
    }

    // ---- players

    Player addPlayer(PlayMode mode) {
        Player player = fakePlayer(mode);
        players.add(player);
        return player;
    }

    static Player fakePlayer(PlayMode initial) {
        Player player = mock(Player.class);
        AtomicReference<PlayMode> mode = new AtomicReference<>(initial);
        PropertyChangeSupport listeners = new PropertyChangeSupport(player);
        when(player.getPlayMode()).thenAnswer(invocation -> mode.get());
        doAnswer(invocation -> {
            PlayMode wanted = invocation.getArgument(0);
            PlayMode old = mode.getAndSet(wanted);
            if (!old.equals(wanted)) {
                listeners.firePropertyChange(Player.PROPERTY_PLAY_MODE, old, wanted);
            }
            return null;
        }).when(player).setPlayMode(any());
        doAnswer(invocation -> {
            listeners.addPropertyChangeListener((PropertyChangeListener) invocation.getArgument(0));
            return null;
        }).when(player).addPropertyChangeListener(any());
        doAnswer(invocation -> {
            listeners.removePropertyChangeListener((PropertyChangeListener) invocation.getArgument(0));
            return null;
        }).when(player).removePropertyChangeListener(any());
        return player;
    }

    void settings(boolean replay, boolean shuffle) {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(client.getContext()).thenReturn(context);
        when(context.getPackageName()).thenReturn("de.yaacc");
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(context.getString(de.yaacc.R.string.settings_replay_playlist_chkbx)).thenReturn("replay");
        when(context.getString(de.yaacc.R.string.settings_music_player_shuffle_chkbx)).thenReturn("shuffle");
        when(preferences.getBoolean("replay", false)).thenReturn(replay);
        when(preferences.getBoolean("shuffle", false)).thenReturn(shuffle);
        newPlayerMode = PlayMode.fromSettings(replay, shuffle);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void close() {
        executor.shutdownNow();
        AvTransport.setUpnpClient(null);
    }
}
