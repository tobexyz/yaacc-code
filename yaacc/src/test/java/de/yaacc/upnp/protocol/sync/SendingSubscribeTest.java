package de.yaacc.upnp.protocol.sync;

import static org.junit.Assert.assertNotNull;

import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.header.SubscriptionIdHeader;
import org.fourthline.cling.model.message.header.TimeoutHeader;
import org.fourthline.cling.model.message.header.UpnpHeader;
import org.fourthline.cling.model.meta.RemoteService;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.ServiceType;
import org.fourthline.cling.model.types.UDAServiceId;
import org.fourthline.cling.model.types.UDAServiceType;
import org.junit.Test;
import org.mockito.Mockito;

import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.upnp.registry.Registry;
import org.fourthline.cling.model.NetworkAddress;

public class SendingSubscribeTest {

    @Test
    public void testSendingSubscribe() throws Exception {
        Registry registry = Mockito.mock(Registry.class);
        HttpRequestSender sender = Mockito.mock(HttpRequestSender.class);
        
        ServiceId serviceId = new UDAServiceId("ContentDirectory");
        ServiceType serviceType = new UDAServiceType("ContentDirectory", 1);
        RemoteService service = Mockito.mock(RemoteService.class);
        Mockito.when(service.getServiceId()).thenReturn(serviceId);
        Mockito.when(service.getServiceType()).thenReturn(serviceType);
        
        RemoteGENASubscription subscription = Mockito.mock(RemoteGENASubscription.class);
        Mockito.when(subscription.getService()).thenReturn(service);
        
        NetworkAddress address = new NetworkAddress(java.net.InetAddress.getByName("192.168.1.1"), 8080);
        
        StreamResponseMessage response = new StreamResponseMessage(new UpnpResponse(UpnpResponse.Status.OK));
        response.getHeaders().add(UpnpHeader.Type.SID, new SubscriptionIdHeader("uuid:test-123"));
        response.getHeaders().add(UpnpHeader.Type.TIMEOUT, new TimeoutHeader(1800));
        
        Mockito.when(sender.send(Mockito.any())).thenReturn(response);
        
        SendingSubscribe protocol = new SendingSubscribe(registry, sender, subscription, address);
        
        assertNotNull(protocol);
    }
}
