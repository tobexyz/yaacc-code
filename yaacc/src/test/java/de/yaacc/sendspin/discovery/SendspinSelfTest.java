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
package de.yaacc.sendspin.discovery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class SendspinSelfTest {

    @Test
    public void everyAddressOfAnInterfaceOfThisDeviceIsItself() throws Exception {
        int seen = 0;
        for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress address : Collections.list(face.getInetAddresses())) {
                assertTrue(address.toString(), SendspinSelf.isThisDevice(address));
                seen++;
            }
        }
        assertTrue("the machine has an address", seen > 0);
    }

    @Test
    public void loopbackAndTheWildcardAreThisDevice() throws Exception {
        assertTrue(SendspinSelf.isThisDevice(InetAddress.getByName("127.0.0.1")));
        assertTrue(SendspinSelf.isThisDevice(InetAddress.getByName("0.0.0.0")));
    }

    @Test
    public void anotherDeviceAndNothingAreNotThisDevice() throws Exception {
        assertFalse(SendspinSelf.isThisDevice(InetAddress.getByName("192.0.2.77"))); // documentation range: no interface has it
        assertFalse(SendspinSelf.isThisDevice(null));
    }
}
