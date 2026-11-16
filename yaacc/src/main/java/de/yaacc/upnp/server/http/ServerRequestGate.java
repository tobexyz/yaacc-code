/*
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
package de.yaacc.upnp.server.http;

/**
 * Which requests the HTTP server of the local service may answer, from the preferences alone (task 12.21). The HTTP server is
 * started with the service at every process creation (for discovery), also when the master switch "UPnP Server" is off, so the
 * master switch is checked here and not only where the service is started. With the master switch on nothing changes: the
 * sub-switches decide as before. Plain Java.
 */
final class ServerRequestGate {

    private ServerRequestGate() {
    }

    /** The shares of the media proxy (random key, expiry, receiver addresses) are served only with the master switch on. */
    static boolean mayServeShares(boolean masterOn) {
        return masterOn;
    }

    /** The content paths of the provider (own media, SAF, live streams). */
    static boolean contentServerOpen(boolean masterOn, boolean providerTicked) {
        return masterOn && providerTicked;
    }

    /** The content paths that the proxy checkbox opens. */
    static boolean contentProxyOpen(boolean masterOn, boolean proxyTicked) {
        return masterOn && proxyTicked;
    }
}
