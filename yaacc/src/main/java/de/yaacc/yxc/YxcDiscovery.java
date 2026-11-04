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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Finds out which of the devices yaacc knows speak the protocol, once per device: the description is fetched, the
 * control interface is looked for ({@link YxcDescriptionParser}), and the answer is remembered per UDN, including
 * "this is not such a device". A device that did not answer is asked again after {@link #RETRY_AFTER_MILLIS}. A device
 * whose description URL changed (new address) is looked at again.
 * <p>
 * The address of the endpoint is the one of the description URL that yaacc's own registry gave; the host a document
 * names is used only to take over its port when it names the same host, so a device can not send yaacc elsewhere.
 */
public final class YxcDiscovery {
    /** How long a failed look is remembered before the next one. */
    static final long RETRY_AFTER_MILLIS = 2 * 60 * 1000L;
    /** The most devices remembered; yaacc sees a handful. */
    static final int MAX_ENTRIES = 256;

    private final YxcDescriptionFetcher fetcher;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new HashMap<>();

    private static final class Entry {
        final String descriptorUrl;
        final YxcEndpoint endpoint; // null: not a device of this kind, or not answering
        final long retryAt; // 0: final

        Entry(String descriptorUrl, YxcEndpoint endpoint, long retryAt) {
            this.descriptorUrl = descriptorUrl;
            this.endpoint = endpoint;
            this.retryAt = retryAt;
        }
    }

    public YxcDiscovery(YxcDescriptionFetcher fetcher) {
        this(fetcher, System::nanoTime);
    }

    YxcDiscovery(YxcDescriptionFetcher fetcher, LongSupplier nanoClock) {
        this.fetcher = fetcher;
        this.clock = nanoClock;
    }

    /**
     * @param udn           the UDN of the UPnP device, as the registry has it
     * @param descriptorUrl the URL the registry fetched the description from
     * @return the endpoint, or {@code null} when the device does not speak the protocol or did not answer
     */
    public YxcEndpoint find(String udn, String descriptorUrl) {
        if (udn == null || descriptorUrl == null) {
            return null;
        }
        long now = clock.getAsLong() / 1_000_000L;
        synchronized (this) {
            Entry known = entries.get(udn);
            if (known != null && known.descriptorUrl.equals(descriptorUrl) && (known.retryAt == 0 || now < known.retryAt)) {
                return known.endpoint;
            }
        }
        // the network call is made without the lock
        Entry looked;
        try {
            looked = look(udn, descriptorUrl);
        } catch (IOException | RuntimeException e) {
            looked = new Entry(descriptorUrl, null, now + RETRY_AFTER_MILLIS);
        }
        synchronized (this) {
            if (entries.size() >= MAX_ENTRIES && !entries.containsKey(udn)) {
                entries.clear();
            }
            entries.put(udn, looked);
        }
        return looked.endpoint;
    }

    private Entry look(String udn, String descriptorUrl) throws IOException {
        URI uri;
        try {
            uri = new URI(descriptorUrl);
        } catch (URISyntaxException e) {
            throw new IOException("not a URL");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("no host");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        YxcDescriptionInfo info = YxcDescriptionParser.parse(fetcher.fetch(descriptorUrl));
        if (info == null) {
            return new Entry(descriptorUrl, null, 0);
        }
        int port = info.getHost() != null && info.getHost().equalsIgnoreCase(host) ? info.getPort() : YxcDescriptionInfo.DEFAULT_PORT;
        return new Entry(descriptorUrl, new YxcEndpoint(udn, host, port, info.getUdnSuffix()), 0);
    }
}
