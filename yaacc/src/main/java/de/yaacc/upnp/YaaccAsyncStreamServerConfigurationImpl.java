/*
 *
 * Copyright (C) 2023 Tobias Schoene www.yaacc.de
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
package de.yaacc.upnp;

import org.fourthline.cling.transport.spi.StreamServerConfiguration;

public class YaaccAsyncStreamServerConfigurationImpl implements StreamServerConfiguration {

    protected int listenPort;
    protected int asyncTimeoutSeconds = 60;


    public YaaccAsyncStreamServerConfigurationImpl(int listenPort) {
        this.listenPort = listenPort;
    }


    /**
     * @return Defaults to <code>0</code>.
     */
    public int getListenPort() {
        return listenPort;
    }


    /**
     * The time in seconds this server wait for the {@link org.fourthline.cling.transport.Router}
     * to execute a {@link org.fourthline.cling.transport.spi.UpnpStream}.
     *
     * @return The default of 60 seconds.
     */
    public int getAsyncTimeoutSeconds() {
        return asyncTimeoutSeconds;
    }
}
