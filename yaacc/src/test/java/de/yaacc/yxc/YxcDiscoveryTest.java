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
package de.yaacc.yxc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Discovery with the captured description of a device and invented ones that are not. */
public class YxcDiscoveryTest {
    private static final String URL = "http://192.0.2.7:49154/MediaRenderer/desc.xml";
    private static final String UDN = "uuid:9ab0c000-f668-11de-9976-00000000000c";

    private static final class Fetcher implements YxcDescriptionFetcher {
        final List<String> fetched = new ArrayList<>();
        String answer = YxcFixtures.read("captured/c_upnpDescription.xml");
        IOException failure;

        @Override
        public String fetch(String descriptorUrl) throws IOException {
            fetched.add(descriptorUrl);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private long nanos;

    private YxcDiscovery discovery(Fetcher fetcher) {
        return new YxcDiscovery(fetcher, () -> nanos);
    }

    private void advanceMillis(long millis) {
        nanos += millis * 1_000_000L;
    }

    @Test
    public void aDeviceWithTheControlUrlIsFoundAtTheAddressOfTheDescription() {
        Fetcher fetcher = new Fetcher();

        YxcEndpoint endpoint = discovery(fetcher).find(UDN, URL);

        assertNotNull(endpoint);
        assertEquals("192.0.2.7", endpoint.getHost());
        assertEquals("the document names no usable host, so the default port", 80, endpoint.getPort());
        assertEquals("00000000000c", endpoint.getDeviceIdSuffix());
        assertEquals(UDN, endpoint.getUdn());
    }

    @Test
    public void theAnswerIsRememberedPerDevice() {
        Fetcher fetcher = new Fetcher();
        YxcDiscovery discovery = discovery(fetcher);

        discovery.find(UDN, URL);
        discovery.find(UDN, URL);

        assertEquals(1, fetcher.fetched.size());
    }

    @Test
    public void aDeviceWithoutTheControlUrlIsRememberedAsNotOne() {
        Fetcher fetcher = new Fetcher();
        fetcher.answer = "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><friendlyName>x</friendlyName></device></root>";
        YxcDiscovery discovery = discovery(fetcher);

        assertNull(discovery.find("uuid:other", "http://192.0.2.9/desc.xml"));
        advanceMillis(10 * 60 * 1000L);
        assertNull(discovery.find("uuid:other", "http://192.0.2.9/desc.xml"));

        assertEquals("a verdict is final, no new fetch even after a long time", 1, fetcher.fetched.size());
    }

    @Test
    public void aDeviceThatDoesNotAnswerIsAskedAgainAfterAWhile() {
        Fetcher fetcher = new Fetcher();
        fetcher.failure = new IOException("timeout");
        YxcDiscovery discovery = discovery(fetcher);

        assertNull(discovery.find(UDN, URL));
        advanceMillis(YxcDiscovery.RETRY_AFTER_MILLIS - 1);
        assertNull(discovery.find(UDN, URL));
        assertEquals(1, fetcher.fetched.size());

        fetcher.failure = null;
        advanceMillis(2);
        assertNotNull(discovery.find(UDN, URL));
        assertEquals(2, fetcher.fetched.size());
    }

    @Test
    public void aNewDescriptionUrlIsLookedAtAgain() {
        Fetcher fetcher = new Fetcher();
        YxcDiscovery discovery = discovery(fetcher);
        discovery.find(UDN, URL);

        YxcEndpoint moved = discovery.find(UDN, "http://192.0.2.8:49154/MediaRenderer/desc.xml");

        assertEquals("192.0.2.8", moved.getHost());
        assertEquals(2, fetcher.fetched.size());
    }

    @Test
    public void aHostNamedInTheDocumentIsNotFollowed() {
        Fetcher fetcher = new Fetcher();
        fetcher.answer = fetcher.answer.replace("<yamaha:X_URLBase>scrubbed</yamaha:X_URLBase>",
                "<yamaha:X_URLBase>http://198.51.100.99:8080</yamaha:X_URLBase>");

        YxcEndpoint endpoint = discovery(fetcher).find(UDN, URL);

        assertEquals("192.0.2.7", endpoint.getHost());
        assertEquals(80, endpoint.getPort());
    }

    @Test
    public void theDocumentsPortIsTakenWhenItNamesTheSameHost() {
        Fetcher fetcher = new Fetcher();
        fetcher.answer = fetcher.answer.replace("<yamaha:X_URLBase>scrubbed</yamaha:X_URLBase>",
                "<yamaha:X_URLBase>http://192.0.2.7:8080</yamaha:X_URLBase>");

        YxcEndpoint endpoint = discovery(fetcher).find(UDN, URL);

        assertEquals(8080, endpoint.getPort());
    }

    @Test
    public void missingInputIsNull() {
        YxcDiscovery discovery = discovery(new Fetcher());

        assertNull(discovery.find(null, URL));
        assertNull(discovery.find(UDN, null));
        assertNull(discovery.find(UDN, "not a url"));
    }

    @Test
    public void onlyPlainHttpUrlsAreFetched() throws Exception {
        for (String bad : new String[]{"https://192.0.2.7/d.xml", "file:///etc/passwd", "http://user@192.0.2.7/d.xml", "ftp://192.0.2.7/d"}) {
            try {
                YxcHttpDescriptionFetcher.checkedUrl(bad);
                throw new AssertionError("accepted: " + bad);
            } catch (IOException expected) {
                // ok
            }
        }
        assertNotNull(YxcHttpDescriptionFetcher.checkedUrl(URL));
    }

    @Test
    public void aScopedIpv6HostIsNotUsedAndNothingIsFetched() {
        Fetcher fetcher = new Fetcher();

        YxcEndpoint endpoint = discovery(fetcher).find(UDN, "http://[fe80::1%25eth0]:49154/desc.xml");

        assertNull(endpoint);
        assertTrue(fetcher.fetched.isEmpty());
    }
}
