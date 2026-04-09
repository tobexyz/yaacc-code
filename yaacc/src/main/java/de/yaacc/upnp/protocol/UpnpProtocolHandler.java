
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

package de.yaacc.upnp.protocol;

import android.content.Context;
import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.model.Namespace;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.gena.LocalGENASubscription;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.message.IncomingDatagramMessage;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.header.UpnpHeader;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.types.InvalidValueException;
import org.fourthline.cling.model.types.NamedServiceType;
import org.fourthline.cling.model.types.NotificationSubtype;
import org.fourthline.cling.model.types.ServiceType;

import java.net.URI;
import java.net.URL;

import de.yaacc.upnp.protocol.async.ReceivingNotification;
import de.yaacc.upnp.protocol.async.ReceivingSearch;
import de.yaacc.upnp.protocol.async.ReceivingSearchResponse;
import de.yaacc.upnp.protocol.async.SendingNotificationAlive;
import de.yaacc.upnp.protocol.async.SendingNotificationByebye;
import de.yaacc.upnp.protocol.async.SendingSearch;
import de.yaacc.upnp.protocol.sync.ReceivingAction;
import de.yaacc.upnp.protocol.sync.ReceivingEvent;
import de.yaacc.upnp.protocol.sync.ReceivingRetrieval;
import de.yaacc.upnp.protocol.sync.ReceivingSubscribe;
import de.yaacc.upnp.protocol.sync.ReceivingUnsubscribe;
import de.yaacc.upnp.protocol.sync.SendingAction;
import de.yaacc.upnp.protocol.sync.SendingEvent;
import de.yaacc.upnp.protocol.sync.SendingRenewal;
import de.yaacc.upnp.protocol.sync.SendingSubscribe;
import de.yaacc.upnp.protocol.sync.SendingUnsubscribe;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.upnp.server.udp.MulticastReceiver;
import de.yaacc.upnp.server.udp.UdpTransiver;
import de.yaacc.util.InterfaceResolutionHelper;


public class UpnpProtocolHandler {

    public static final Namespace NAMESPACE = new Namespace("/upnp");
    private final Registry registry;
    private final MulticastReceiver multicastReceiver;
    private final HttpRequestSender httpRequestSender;
    private UdpTransiver udpTransiver;
    private Context context;


    public UpnpProtocolHandler(Context context, Registry registry, UdpTransiver transiver, MulticastReceiver multicastReceiver, HttpRequestSender httpRequestSender) {
        this.registry = registry;
        this.udpTransiver = transiver;
        this.multicastReceiver = multicastReceiver;
        this.httpRequestSender = httpRequestSender;
        this.context = context;

    }

    public ReceivingAsync createReceivingAsync(IncomingDatagramMessage message) throws ProtocolCreationException {

        YaaccLogger.v(getClass().getName(), "Creating protocol for incoming asynchronous: " + message);


        if (message.getOperation() instanceof UpnpRequest) {
            IncomingDatagramMessage<UpnpRequest> incomingRequest = message;

            switch (incomingRequest.getOperation().getMethod()) {
                case NOTIFY:
                    return isSsdpAlive(incomingRequest) || isByeBye(incomingRequest) || isSupportedServiceAdvertisement(incomingRequest)
                            ? createReceivingNotification(incomingRequest) : null;
                case MSEARCH:
                    return createReceivingSearch(incomingRequest);
            }

        } else if (message.getOperation() instanceof UpnpResponse) {
            IncomingDatagramMessage<UpnpResponse> incomingResponse = message;

            return isSupportedServiceAdvertisement(incomingResponse)
                    ? createReceivingSearchResponse(incomingResponse) : null;
        }

        throw new ProtocolCreationException("Protocol for incoming datagram message not found: " + message);
    }

    protected ReceivingAsync createReceivingNotification(IncomingDatagramMessage<UpnpRequest> incomingRequest) {
        return new ReceivingNotification(registry, httpRequestSender, incomingRequest);
    }

    protected ReceivingAsync createReceivingSearch(IncomingDatagramMessage<UpnpRequest> incomingRequest) {
        return new ReceivingSearch(context, registry, udpTransiver, incomingRequest);
    }

    protected ReceivingAsync createReceivingSearchResponse(IncomingDatagramMessage<UpnpResponse> incomingResponse) {
        return new ReceivingSearchResponse(registry, httpRequestSender, incomingResponse);
    }


    protected boolean isByeBye(IncomingDatagramMessage message) {
        String ntsHeader = message.getHeaders().getFirstHeader(UpnpHeader.Type.NTS.getHttpName());
        return ntsHeader != null && ntsHeader.equals(NotificationSubtype.BYEBYE.getHeaderString());
    }

    protected boolean isSsdpAlive(IncomingDatagramMessage message) {
        String ntsHeader = message.getHeaders().getFirstHeader(UpnpHeader.Type.NTS.getHttpName());
        return ntsHeader != null && ntsHeader.equals(NotificationSubtype.ALIVE.getHeaderString());
    }

    protected boolean isSupportedServiceAdvertisement(IncomingDatagramMessage message) {
        ServiceType[] exclusiveServiceTypes = YaaccUpnpServerService.EXCLUSIVE_SERVER_TYPES;
        if (exclusiveServiceTypes == null) return false; // Discovery is disabled
        if (exclusiveServiceTypes.length == 0) return true; // Any advertisement is fine

        String usnHeader = message.getHeaders().getFirstHeader(UpnpHeader.Type.USN.getHttpName());
        if (usnHeader == null) return false; // Not a service advertisement, drop it

        try {
            NamedServiceType nst = NamedServiceType.valueOf(usnHeader);
            for (ServiceType exclusiveServiceType : exclusiveServiceTypes) {
                if (nst.getServiceType().implementsVersion(exclusiveServiceType))
                    return true;
            }
        } catch (InvalidValueException ex) {
            YaaccLogger.v(getClass().getName(), "Not a named service type header value: " + usnHeader);
        }
        YaaccLogger.v(getClass().getName(), "Service advertisement not supported, dropping it: " + usnHeader);
        return false;
    }


    public Namespace getNamespace() {
        return NAMESPACE;
    }

    public ReceivingSync createReceivingSync(StreamRequestMessage message) throws ProtocolCreationException {
        YaaccLogger.v(getClass().getName(), "Creating protocol for incoming synchronous: " + message);

        if (message.getOperation().getMethod().equals(UpnpRequest.Method.GET) ||
                message.getOperation().getMethod().equals(UpnpRequest.Method.HEAD)) {

            return createReceivingRetrieval(message);

        } else if (getNamespace().isControlPath(message.getUri())) {

            if (message.getOperation().getMethod().equals(UpnpRequest.Method.POST))
                return createReceivingAction(message);

        } else if (getNamespace().isEventSubscriptionPath(message.getUri())) {

            if (message.getOperation().getMethod().equals(UpnpRequest.Method.SUBSCRIBE)) {
                return createReceivingSubscribe(message);
            } else if (message.getOperation().getMethod().equals(UpnpRequest.Method.UNSUBSCRIBE)) {
                return createReceivingUnsubscribe(message);
            }

        } else if (getNamespace().isEventCallbackPath(message.getUri())) {

            if (message.getOperation().getMethod().equals(UpnpRequest.Method.NOTIFY))
                return createReceivingEvent(message);

        } else {

            // TODO: UPNP VIOLATION: Onkyo devices send event messages with trailing garbage characters
            // /dev/9bb022aa-e922-aab9-682b-aa09e9b9e059/svc/upnp-org/RenderingControl/event/cb192%2e168%2e10%2e38
            // TODO: UPNP VIOLATION: Yamaha does the same
            // /dev/9ab0c000-f668-11de-9976-00a0de870fd4/svc/upnp-org/RenderingControl/event/cb><http://10.189.150.197:42082/dev/9ab0c000-f668-11de-9976-00a0de870fd4/svc/upnp-org/RenderingControl/event/cb
            if (message.getUri().getPath().contains(Namespace.EVENTS + Namespace.CALLBACK_FILE)) {
                YaaccLogger.w(getClass().getName(), "Fixing trailing garbage in event message path: " + message.getUri().getPath());
                String invalid = message.getUri().toString();
                message.setUri(
                        URI.create(invalid.substring(
                                0, invalid.indexOf(Namespace.CALLBACK_FILE) + Namespace.CALLBACK_FILE.length()
                        ))
                );
                if (getNamespace().isEventCallbackPath(message.getUri())
                        && message.getOperation().getMethod().equals(UpnpRequest.Method.NOTIFY))
                    return createReceivingEvent(message);
            }

        }

        throw new ProtocolCreationException("Protocol for message type not found: " + message);
    }

    public SendingNotificationAlive createSendingNotificationAlive(LocalDevice localDevice) {
        return new SendingNotificationAlive(context, udpTransiver, localDevice);
    }


    public SendingNotificationByebye createSendingNotificationByebye(LocalDevice localDevice) {
        return new SendingNotificationByebye(context, udpTransiver, localDevice);
    }


    public SendingSearch createSendingSearch(UpnpHeader searchTarget, int mxSeconds) {
        return new SendingSearch(udpTransiver, searchTarget, mxSeconds);
    }


    public SendingAction createSendingAction(ActionInvocation actionInvocation, URL controlURL) {
        return new SendingAction(httpRequestSender, actionInvocation, controlURL);
    }


    public SendingSubscribe createSendingSubscribe(RemoteGENASubscription subscription) throws ProtocolCreationException {
        return new SendingSubscribe(registry, httpRequestSender, subscription, InterfaceResolutionHelper.getNetworkAddress(context));
    }


    public SendingRenewal createSendingRenewal(RemoteGENASubscription subscription) {
        return new SendingRenewal(registry, httpRequestSender, subscription);
    }


    public SendingUnsubscribe createSendingUnsubscribe(RemoteGENASubscription subscription) {
        return new SendingUnsubscribe(registry, httpRequestSender, subscription);
    }


    public SendingEvent createSendingEvent(LocalGENASubscription subscription) {
        return new SendingEvent(httpRequestSender, subscription);
    }


    protected ReceivingRetrieval createReceivingRetrieval(StreamRequestMessage message) {
        return new ReceivingRetrieval(registry, message);
    }

    protected ReceivingAction createReceivingAction(StreamRequestMessage message) {
        return new ReceivingAction(registry, message);
    }

    protected ReceivingSubscribe createReceivingSubscribe(StreamRequestMessage message) {
        return new ReceivingSubscribe(registry, httpRequestSender, message);
    }

    protected ReceivingUnsubscribe createReceivingUnsubscribe(StreamRequestMessage message) {
        return new ReceivingUnsubscribe(registry, message);
    }

    protected ReceivingEvent createReceivingEvent(StreamRequestMessage message) {
        return new ReceivingEvent(registry, message);
    }
}
