package de.yaacc.upnp.server.udp;

import org.junit.Test;

import static org.junit.Assert.*;

public class UdpTransiverTest {

    @Test
    public void testUdpTransiverCreation() {
        UdpTransiver udpTransiver = new UdpTransiver();
        assertNotNull(udpTransiver);
    }

    @Test
    public void testInitialization() {
        UdpTransiver udpTransiver = new UdpTransiver();
        assertNotNull(udpTransiver);
    }
}
