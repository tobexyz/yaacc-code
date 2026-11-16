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

import de.yaacc.share.ShareRegistry;

/**
 * Which requests the HTTP server of the local service may answer, from the preferences alone (task 12.21). The HTTP server is
 * started with the service at every process creation (for discovery), also when the master switch "UPnP Server" is off, so the
 * master switch is checked here and not only where the service is started, for the paths that need it (proxied links and the content paths of the media server). With the master switch on nothing changes: the
 * sub-switches decide as before. Plain Java.
 */
final class ServerRequestGate {

    private ServerRequestGate() {
    }

    /**
     * Whether the entry of the share registry that a request names may be served. Normal sharing (a shared file, anything that
     * is not a proxied link) works without the master switch: the short-lived address is protected by its random key, its expiry
     * and the receiver addresses. A proxied link (the SSL offload, made by {@code createHttpLinkShare}) needs the app's server
     * to be switched on, as the owner decided (2026-11-17). An entry without a kind is never served.
     */
    static boolean maySendShare(boolean masterOn, ShareRegistry.Kind kind) {
        if (kind == null) {
            return false;
        }
        return kind != ShareRegistry.Kind.HTTP_LINK || masterOn;
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
