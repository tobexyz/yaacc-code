package de.yaacc.upnp.server.udp;

import org.junit.Test;

import static org.junit.Assert.*;

public class MulticastReceiverTest {

    @Test
    public void testMulticastReceiverCreation() {
        MulticastReceiver multicastReceiver = new MulticastReceiver();
        assertNotNull(multicastReceiver);
    }

    @Test
    public void testMulticastGroupAddress() {
        assertEquals("239.255.255.250", MulticastReceiver.IPV4_UPNP_MULTICAST_GROUP);
    }

    @Test
    public void testMulticastPort() {
        assertEquals(1900, MulticastReceiver.UPNP_MULTICAST_PORT);
    }
}
