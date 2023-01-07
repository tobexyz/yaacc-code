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

import android.os.Build;

import org.fourthline.cling.model.ServerClientTokens;
import org.fourthline.cling.transport.spi.AbstractStreamClientConfiguration;

import java.util.concurrent.ExecutorService;

public class YaaccStreamingClientConfigurationImpl extends AbstractStreamClientConfiguration {

    public YaaccStreamingClientConfigurationImpl(ExecutorService timeoutExecutorService) {
        super(timeoutExecutorService);
    }

    public YaaccStreamingClientConfigurationImpl(ExecutorService timeoutExecutorService, int timeoutSeconds) {
        super(timeoutExecutorService, timeoutSeconds);
    }

    /**
     * @return By default <code>0</code>.
     */
    public int getRequestRetryCount() {
        return 0;
    }

    @Override
    public String getUserAgentValue(int majorVersion, int minorVersion) {
        // TODO: UPNP VIOLATION: Synology NAS requires User-Agent to contain
        // "Android" to return DLNA protocolInfo required to stream to Samsung TV
        // see: http://two-play.com/forums/viewtopic.php?f=6&t=81
        ServerClientTokens tokens = new ServerClientTokens(majorVersion, minorVersion);
        tokens.setOsName("Android");
        tokens.setOsVersion(Build.VERSION.RELEASE);
        return tokens.toString();
    }
}

