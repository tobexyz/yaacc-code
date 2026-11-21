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
package de.yaacc.fcast.discovery;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.yaacc.fcast.protocol.FCastText;
import de.yaacc.multiroom.discovery.SnapcastAddressFilter;

/**
 * The connection URL of an FCast receiver (task 5.5): {@code fcast://r/<base64url of a JSON>}, the text of the QR code that both
 * FUTO receivers show. The JSON is {@code {name, addresses[], services[{port, type}], txt?}}; service type 0 is the FCast TCP
 * service. Pure.
 *
 * <p><b>Everything in the link is untrusted</b>: any app or web page can hand the app a link, a QR code can be printed by anyone.
 * So: the exact prefix; at most {@value #MAX_URL} characters, checked before anything is decoded; strict base64url (with or without
 * padding), strict UTF-8, a JSON of limited depth and no single quotes, comments or trailing text; only <i>IP literals of the local
 * network</i> as addresses (private, link-local IPv4, unique-local IPv6; no host names, no ports, no loopback, no public address, no
 * IPv6 link-local address, which needs a zone the link can not give); only the first valid TCP service gives the port; at most
 * {@value #MAX_ADDRESSES} addresses; the name is cleaned and cut. The hints ({@code v}, {@code fp}) are kept for protocol v4 and
 * are not authenticated. Nothing here connects, stores or logs.
 */
public final class FCastConnectionUrl {

    public static final String PREFIX = "fcast://r/";
    public static final int MAX_URL = 4096;
    public static final int MAX_ADDRESSES = 16;
    private static final int SERVICE_TCP = 0;

    /** Why a link was not accepted. */
    public enum Problem {
        NONE,
        /** It does not start with {@value #PREFIX}. */
        NOT_A_LINK,
        TOO_LONG,
        BAD_ENCODING,
        BAD_JSON,
        /** No address of the local network is left. */
        NO_ADDRESS,
        /** No usable FCast TCP service. */
        NO_SERVICE
    }

    /** What a good link says. */
    public static final class Parsed {
        private final String name;
        private final List<FCastEndpoint> endpoints;
        private final FCastServiceTxt hints;

        private Parsed(String name, List<FCastEndpoint> endpoints, FCastServiceTxt hints) {
            this.name = name;
            this.endpoints = endpoints;
            this.hints = hints;
        }

        /** The name the receiver gave itself, cleaned; empty if none. */
        public String getName() {
            return name;
        }

        /** The addresses to try, in the order of the link, never empty. */
        public List<FCastEndpoint> getEndpoints() {
            return endpoints;
        }

        /** The hints of the TXT part, {@link FCastServiceTxt#EMPTY} if none. */
        public FCastServiceTxt getHints() {
            return hints;
        }
    }

    /** The parsed link or the problem. */
    public static final class Result {
        private final Parsed parsed;
        private final Problem problem;

        private Result(Parsed parsed, Problem problem) {
            this.parsed = parsed;
            this.problem = problem;
        }

        /** @return the link's content, null if there was a problem */
        public Parsed getParsed() {
            return parsed;
        }

        public Problem getProblem() {
            return problem;
        }
    }

    private FCastConnectionUrl() {
    }

    /** True if the text (trimmed) starts with the prefix of the connection URL; says nothing about the rest. */
    public static boolean looksLikeLink(String text) {
        return text != null && text.trim().startsWith(PREFIX);
    }

    public static Result parse(String text) {
        if (text == null || !looksLikeLink(text)) {
            return fail(Problem.NOT_A_LINK);
        }
        String link = text.trim();
        if (link.length() > MAX_URL) {
            return fail(Problem.TOO_LONG);
        }
        String payload = link.substring(PREFIX.length());
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            return fail(Problem.BAD_ENCODING);
        }
        if (bytes.length == 0) {
            return fail(Problem.BAD_ENCODING);
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return fail(Problem.BAD_JSON);
        }
        if (!FCastText.isAcceptableJson(json)) {
            return fail(Problem.BAD_JSON);
        }
        JSONObject object;
        try {
            org.json.JSONTokener tokener = new org.json.JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) { // nothing may follow the object
                return fail(Problem.BAD_JSON);
            }
            object = (JSONObject) value;
        } catch (JSONException | RuntimeException | StackOverflowError e) {
            return fail(Problem.BAD_JSON);
        }
        int port = firstTcpPort(object.opt("services"));
        List<String> addresses = addressTexts(object.opt("addresses"));
        if (port < 0 && addresses.isEmpty()) {
            // report the first thing that is missing in the order of the JSON's meaning: addresses first
            return fail(Problem.NO_ADDRESS);
        }
        List<FCastEndpoint> endpoints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String address : addresses) {
            if (endpoints.size() >= MAX_ADDRESSES) {
                break;
            }
            FCastEndpoint endpoint = endpointOf(address, port < 0 ? FCastEndpoint.DEFAULT_PORT : port);
            if (endpoint != null && seen.add(endpoint.toString())) {
                endpoints.add(endpoint);
            }
        }
        if (endpoints.isEmpty()) {
            return fail(Problem.NO_ADDRESS);
        }
        if (port < 0) {
            return fail(Problem.NO_SERVICE);
        }
        Object name = object.opt("name");
        String cleanName = name instanceof String ? FCastText.clean((String) name, FCastText.MAX_NAME) : "";
        return new Result(new Parsed(cleanName == null ? "" : cleanName, endpoints, hintsOf(object.opt("txt"))), Problem.NONE);
    }

    private static Result fail(Problem problem) {
        return new Result(null, problem);
    }

    /** @return the port of the first TCP service that has a valid port, -1 if there is none */
    private static int firstTcpPort(Object services) {
        if (!(services instanceof JSONArray)) {
            return -1;
        }
        JSONArray array = (JSONArray) services;
        for (int i = 0; i < array.length() && i < 32; i++) {
            Object item = array.opt(i);
            if (!(item instanceof JSONObject)) {
                continue;
            }
            Object type = ((JSONObject) item).opt("type");
            Object port = ((JSONObject) item).opt("port");
            if (isWhole(type) && ((Number) type).intValue() == SERVICE_TCP && isWhole(port)) {
                int value = ((Number) port).intValue();
                if (value >= 1 && value <= 65535) {
                    return value;
                }
            }
        }
        return -1;
    }

    private static boolean isWhole(Object value) {
        if (!(value instanceof Integer || value instanceof Long)) {
            return false;
        }
        long number = ((Number) value).longValue();
        return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE;
    }

    private static List<String> addressTexts(Object addresses) {
        List<String> texts = new ArrayList<>();
        if (!(addresses instanceof JSONArray)) {
            return texts;
        }
        JSONArray array = (JSONArray) addresses;
        for (int i = 0; i < array.length() && i < 256; i++) {
            Object item = array.opt(i);
            if (item instanceof String) {
                texts.add(((String) item).trim());
            }
        }
        return texts;
    }

    /** An IP literal of the local network, as an endpoint with the port, or null. Nothing is looked up. */
    private static FCastEndpoint endpointOf(String address, int port) {
        if (address.isEmpty() || address.length() > 45 || address.indexOf('%') >= 0 || address.indexOf('/') >= 0
                || address.indexOf('[') >= 0 || address.indexOf(']') >= 0) {
            return null;
        }
        boolean v6 = address.indexOf(':') >= 0;
        if (!v6 && !address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            return null; // a host name or something else: not an IP literal
        }
        if (v6 && !address.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        FCastEndpoint endpoint = FCastEndpoint.parse((v6 ? "[" + address + "]" : address) + ":" + port);
        if (endpoint == null) {
            return null;
        }
        InetAddress inet = endpoint.getAddress();
        if (inet == null || !SnapcastAddressFilter.isAcceptedForDiscovery(inet)) {
            return null;
        }
        if (inet instanceof Inet6Address && inet.isLinkLocalAddress()) {
            return null;
        }
        return endpoint;
    }

    private static FCastServiceTxt hintsOf(Object txt) {
        if (!(txt instanceof JSONObject)) {
            return FCastServiceTxt.EMPTY;
        }
        JSONObject object = (JSONObject) txt;
        Map<String, byte[]> attributes = new HashMap<>();
        java.util.Iterator<String> keys = object.keys();
        int count = 0;
        while (keys.hasNext() && count++ < 32) {
            String key = keys.next();
            Object value = object.opt(key);
            if (value instanceof String) {
                attributes.put(key, ((String) value).getBytes(StandardCharsets.UTF_8));
            }
        }
        return FCastServiceTxt.parse(attributes);
    }
}
