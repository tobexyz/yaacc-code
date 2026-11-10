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
package de.yaacc.multiroom.discovery;

/**
 * The stable identity of a snapserver for YAACC: its control endpoint, in the canonical text {@code host:port} (an
 * IPv6 literal in brackets). A server has no id of its own that can be trusted (the Snapcast status carries a host
 * name and a MAC address that may be empty or invented), so the endpoint the user or the network gave us is the
 * identity: one endpoint found twice, by mDNS and by hand, is one server. Immutable.
 *
 * <p>The text is used inside receiver ids ({@code snapcast:<key>:<group id>}); {@link #parse} accepts only the
 * canonical form, so an id that was written by someone else (or edited) is not a key. It carries an address of the
 * local network: show it, never log it.
 */
public final class SnapcastServerKey {

    private final SnapcastEndpoint endpoint;
    private final String text;

    private SnapcastServerKey(SnapcastEndpoint endpoint) {
        this.endpoint = endpoint;
        this.text = endpoint.toString();
    }

    public static SnapcastServerKey of(SnapcastEndpoint endpoint) {
        return new SnapcastServerKey(endpoint);
    }

    /** @return the key for exactly this canonical text, or null (also for a valid endpoint written in another form) */
    public static SnapcastServerKey parse(String text) {
        SnapcastEndpoint endpoint = SnapcastEndpoint.parse(text);
        if (endpoint == null || !endpoint.toString().equals(text)) {
            return null;
        }
        return new SnapcastServerKey(endpoint);
    }

    /** The canonical text. */
    public String asString() {
        return text;
    }

    public SnapcastEndpoint getEndpoint() {
        return endpoint;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SnapcastServerKey && text.equals(((SnapcastServerKey) other).text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
