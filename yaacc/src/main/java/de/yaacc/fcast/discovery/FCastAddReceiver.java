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

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.yaacc.fcast.protocol.FCastText;
import de.yaacc.multiroom.discovery.SnapcastAddressFilter;

/**
 * What the "Add receiver" input does (tasks 5.4, 5.5, 5.7). One text goes in; the order is:
 * <ol>
 *   <li>a <b>connection URL</b> ({@code fcast://r/...}, {@link FCastConnectionUrl}): the addresses are tried in order, the first that
 *   answers is added with the name of the link;</li>
 *   <li>an <b>IP address</b> ({@code host:port} with an IP literal, port optional): added at once, without testing it (a receiver that is
 *   switched off can be added);</li>
 *   <li>a <b>name</b>: (a) a receiver that was found already (by its announced name, or the computer's host name that the default name
 *   {@code FCast-<host>} contains; whole name, upper or lower case, {@code .local} ignored; two receivers with that name give
 *   {@link Outcome#AMBIGUOUS}); (b) the system's name lookup for something that can be a host name (the address must be of the local
 *   network); (c) a search of a few seconds for a receiver of that name, only while discovery runs. The failure says what was tried.</li>
 * </ol>
 * The stored entry keeps the resolved address and the name that was typed, so a changed address is found again by the name (D6).
 * Pure: the network and the clock are behind {@link Environment}.
 */
public final class FCastAddReceiver {

    /** The search of step (c): rounds and the wait of each (about 4 seconds). */
    public static final int SEARCH_ROUNDS = 4;
    public static final long SEARCH_ROUND_MILLIS = 1_000L;
    static final int MAX_TEXT = 300;
    private static final String DEFAULT_NAME_PREFIX = "fcast-";

    /** The world of the input: the receivers found, the network tests, the store, the clock. */
    public interface Environment {
        /** The receivers known now (found, added by hand, remembered). */
        List<FCastDirectory.Entry> receivers();

        /** Whether something answers a TCP connection at this address (a few seconds at most). */
        boolean answers(FCastEndpoint endpoint);

        /** The system's name lookup; throws if the name is unknown or the lookup is too slow. */
        InetAddress lookup(String host) throws IOException;

        /** Stores a receiver added by hand. */
        FCastStoredReceivers.AddResult store(FCastEndpoint endpoint, String name);

        /** True while the search for receivers by mDNS runs (the switch is on and the dialog counts as a page). */
        boolean isDiscoveryRunning();

        /** Waits; the search of a receiver by name uses it between its rounds. */
        void pause(long millis);
    }

    public enum Outcome {
        ADDED, EMPTY, INVALID, ALREADY_LISTED, FULL,
        /** A connection URL whose addresses all did not answer. */
        UNREACHABLE,
        /** A connection URL that is not valid; see {@link Result#getLinkProblem()}. */
        BAD_LINK,
        /** A name that was not found; see what was tried in the result. */
        NAME_NOT_FOUND,
        /** An IPv6 link-local address (fe80::/10): it needs a zone that the entry can not keep; use an IPv4 or a global address. */
        LINK_LOCAL,
        /** More than one receiver has that name; {@link Result#getCandidates()} lists their addresses. */
        AMBIGUOUS
    }

    /** What happened. */
    public static final class Result {
        private final Outcome outcome;
        private FCastEndpoint endpoint;
        private String name = "";
        private FCastConnectionUrl.Problem linkProblem = FCastConnectionUrl.Problem.NONE;
        private int triedAddresses;
        private boolean triedSystemLookup;
        private boolean triedSearch;
        private List<String> candidates = Collections.emptyList();

        private Result(Outcome outcome) {
            this.outcome = outcome;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        /** The receiver that was added, null if none. */
        public FCastEndpoint getEndpoint() {
            return endpoint;
        }

        /** The name that was stored with it (may be empty). */
        public String getName() {
            return name;
        }

        public FCastConnectionUrl.Problem getLinkProblem() {
            return linkProblem;
        }

        /** How many addresses of a connection URL were tried. */
        public int getTriedAddresses() {
            return triedAddresses;
        }

        /** Whether the failure of a name includes a system lookup (the text can be a host name). */
        public boolean isTriedSystemLookup() {
            return triedSystemLookup;
        }

        /** Whether the failure of a name includes the wait for an announcement (discovery was on). */
        public boolean isTriedSearch() {
            return triedSearch;
        }

        public List<String> getCandidates() {
            return candidates;
        }
    }

    private final Environment environment;

    public FCastAddReceiver(Environment environment) {
        this.environment = environment;
    }

    public Result add(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return new Result(Outcome.EMPTY);
        }
        if (FCastConnectionUrl.looksLikeLink(text)) {
            return addLink(text);
        }
        if (text.length() > MAX_TEXT || hasControlCharacters(text)) {
            return new Result(Outcome.INVALID);
        }
        FCastEndpoint typed = FCastEndpoint.parse(text);
        if (typed != null && typed.getAddress() != null) {
            if (isLinkLocalV6(typed.getAddress())) {
                return new Result(Outcome.LINK_LOCAL);
            }
            return store(typed, "");
        }
        if (typed == null && (text.contains("://") || text.contains("/") || text.indexOf(':') >= 0 && text.indexOf(' ') < 0)) {
            return new Result(Outcome.INVALID); // an address that is not valid (a URL, a bad port), not a name with spaces
        }
        return addByName(text, typed);
    }

    // ------------------------------------------------------------------ a connection URL

    private Result addLink(String text) {
        FCastConnectionUrl.Result parsed = FCastConnectionUrl.parse(text);
        if (parsed.getParsed() == null) {
            Result result = new Result(Outcome.BAD_LINK);
            result.linkProblem = parsed.getProblem();
            return result;
        }
        int tried = 0;
        for (FCastEndpoint endpoint : parsed.getParsed().getEndpoints()) {
            tried++;
            if (environment.answers(endpoint)) {
                return store(endpoint, parsed.getParsed().getName());
            }
        }
        Result result = new Result(Outcome.UNREACHABLE);
        result.triedAddresses = tried;
        return result;
    }

    // ------------------------------------------------------------------ a name

    private Result addByName(String text, FCastEndpoint typedHost) {
        Result found = matchFound(text);
        if (found != null) {
            return found;
        }
        boolean systemLookup = typedHost != null;
        if (systemLookup) {
            try {
                InetAddress address = environment.lookup(typedHost.getHost());
                FCastEndpoint endpoint = SnapcastAddressFilter.isAcceptedForDiscovery(address) && !isLinkLocalV6(address)
                        ? FCastEndpoint.fromAddress(address, typedHost.getPort()) : null;
                if (endpoint != null) {
                    return store(endpoint, typedHost.getHost());
                }
            } catch (IOException | RuntimeException notFound) {
                // fall through to the search; the failure text says that the lookup was tried
            }
        }
        boolean search = environment.isDiscoveryRunning();
        if (search) {
            for (int round = 0; round < SEARCH_ROUNDS; round++) {
                environment.pause(SEARCH_ROUND_MILLIS);
                Result late = matchFound(text);
                if (late != null) {
                    return late;
                }
            }
        }
        Result result = new Result(Outcome.NAME_NOT_FOUND);
        result.triedSystemLookup = systemLookup;
        result.triedSearch = search;
        return result;
    }

    /** @return the result of a match among the receivers found (added, ambiguous, listed already), null if none matches */
    private Result matchFound(String text) {
        String wanted = normalise(text);
        if (wanted.isEmpty()) {
            return null;
        }
        Map<String, String> matches = new LinkedHashMap<>(); // endpoint text -> name
        Map<String, FCastEndpoint> endpoints = new LinkedHashMap<>();
        for (FCastDirectory.Entry entry : environment.receivers()) {
            String name = normalise(entry.getDisplayName());
            if (name.equals(wanted) || name.equals(DEFAULT_NAME_PREFIX + wanted)) {
                matches.put(entry.getEndpoint().toString(), entry.getDisplayName());
                endpoints.put(entry.getEndpoint().toString(), entry.getEndpoint());
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            Result result = new Result(Outcome.AMBIGUOUS);
            result.candidates = new ArrayList<>(matches.keySet());
            return result;
        }
        String key = matches.keySet().iterator().next();
        return store(endpoints.get(key), matches.get(key));
    }

    /** Lower case, trimmed, without a trailing dot and a {@code .local} (the host name of a computer as the network writes it). */
    static String normalise(String name) {
        if (name == null) {
            return "";
        }
        String text = name.trim().toLowerCase(Locale.ROOT);
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".local")) {
            text = text.substring(0, text.length() - ".local".length());
        }
        return text;
    }

    private static boolean isLinkLocalV6(InetAddress address) {
        return address instanceof java.net.Inet6Address && address.isLinkLocalAddress();
    }

    private static boolean hasControlCharacters(String text) {
        return !FCastText.clean(text, text.length()).equals(text);
    }

    private Result store(FCastEndpoint endpoint, String name) {
        FCastStoredReceivers.AddResult stored = environment.store(endpoint, name);
        Result result;
        switch (stored) {
            case ADDED:
                result = new Result(Outcome.ADDED);
                result.endpoint = endpoint;
                result.name = name == null ? "" : name;
                return result;
            case DUPLICATE:
                return new Result(Outcome.ALREADY_LISTED);
            case FULL:
                return new Result(Outcome.FULL);
            case INVALID:
            default:
                return new Result(Outcome.INVALID);
        }
    }
}
