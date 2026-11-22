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
package de.yaacc.multiroom.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.protocol.SnapcastConnection;

/**
 * The client ids that builds before task 12.11 gave the servers (task 12.19), computed again from the secret of this install so that
 * the entries they left on the servers are recognised as this phone's. The old Hello id was {@code yaacc-} and the first 16
 * hexadecimal characters of SHA-256(secret, a zero byte, the canonical text of the endpoint the client connected to); the endpoint
 * was the one the client checked from the typed address ({@code SnapcastClient.check}: {@code host[:port]}, the stream port 1704
 * when there was no port), in the canonical form of {@link SnapcastEndpoint#toString()} (host names in lower case, IPv4 dotted, IPv6 as
 * eight groups in brackets). That text is not stored anywhere, so the candidates are the forms the old client could have had for a
 * known server: its host with the stream port 1704 (the choice of the server list), with the control port 1705 and with the port the
 * list has for it, plus the address the listen test saved last (typed text, read like the old client read it). A host name the user
 * typed for a server that the list knows by its number (or the other way round) can not be reproduced and stays unrecognised.
 *
 * <p>Pure Java. The secret is only used here and never leaves; the result is a bounded list ({@value #MAX_IDS}), without duplicates,
 * the listen test address first (it is the most likely one), then the servers in the order given.
 */
public final class SnapcastLegacyClientIds {

    /**
     * The most ids returned.
     */
    public static final int MAX_IDS = 64;

    private static final String PREFIX = "yaacc-";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SnapcastLegacyClientIds() {
    }

    /**
     * The id of an old build: exactly the old two-argument derivation.
     *
     * @param secret       the secret of this install ({@link SnapcastClientIdentity#isSecret})
     * @param endpointText the canonical endpoint text, such as {@code 10.0.0.9:1704}
     */
    public static String legacyId(String secret, String endpointText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(secret.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(endpointText.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            char[] out = new char[16];
            for (int i = 0; i < 8; i++) {
                out[2 * i] = HEX[(hash[i] >> 4) & 0xf];
                out[2 * i + 1] = HEX[hash[i] & 0xf];
            }
            return PREFIX + new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /**
     * @param secret       the install secret; anything that is not a secret gives no id
     * @param servers      the known servers (their endpoints, any port); null and null entries are skipped
     * @param lastHostText the address the listen test saved last, as typed; null, empty or damaged is skipped
     * @return the legacy ids this install may have on its servers, at most {@value #MAX_IDS}
     */
    public static List<String> of(String secret, List<SnapcastEndpoint> servers, String lastHostText) {
        if (!SnapcastClientIdentity.isSecret(secret)) {
            return Collections.emptyList();
        }
        Set<String> texts = new LinkedHashSet<>();
        String typed = lastHostText == null ? "" : lastHostText.trim();
        if (!typed.isEmpty()) {
            addForms(texts, SnapcastEndpoint.parse(typed, SnapcastConnection.DEFAULT_PORT));
        }
        if (servers != null) {
            for (SnapcastEndpoint server : servers) {
                addForms(texts, server);
            }
        }
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String text : texts) {
            String id = legacyId(secret, text);
            if (seen.add(id)) {
                ids.add(id);
                if (ids.size() >= MAX_IDS) {
                    break;
                }
            }
        }
        return ids;
    }

    /**
     * The stream port first (what a typed host gets), then the control port, then the port of the entry itself.
     */
    private static void addForms(Set<String> texts, SnapcastEndpoint endpoint) {
        if (endpoint == null) {
            return;
        }
        String host = endpoint.getHost();
        String shown = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        texts.add(shown + ":" + SnapcastConnection.DEFAULT_PORT);
        texts.add(shown + ":" + SnapcastEndpoint.DEFAULT_CONTROL_PORT);
        texts.add(shown + ":" + endpoint.getPort());
    }
}
