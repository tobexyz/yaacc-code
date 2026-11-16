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
package de.yaacc.share;

/**
 * Decides how a shared link reaches the receivers (task 12.21, owner's decision 2026-11-17): the media proxy serves it only when the
 * proxy checkbox AND the master switch "UPnP Server" are on, because otherwise no server of the app serves the link. Plain Java.
 * <ul>
 * <li>proxy ticked, master on: {@link Decision#USE_PROXY};</li>
 * <li>proxy ticked, master off: {@link Decision#REFUSE_NO_SERVER}, the link is refused (it is never handed to the receivers
 * directly, the user chose the proxy);</li>
 * <li>proxy not ticked: {@link Decision#DIRECT}, the master switch does not matter.</li>
 * </ul>
 */
public final class ProxyAvailability {

    /** What to do with a shared link. */
    public enum Decision {
        USE_PROXY,
        DIRECT,
        REFUSE_NO_SERVER
    }

    private ProxyAvailability() {
    }

    /**
     * @param masterOn    the master switch "UPnP Server" ({@code settings_local_server_chkbx})
     * @param proxyTicked the proxy checkbox ({@code settings_local_server_proxy_chkbx})
     */
    public static Decision decide(boolean masterOn, boolean proxyTicked) {
        if (!proxyTicked) {
            return Decision.DIRECT;
        }
        return masterOn ? Decision.USE_PROXY : Decision.REFUSE_NO_SERVER;
    }
}
