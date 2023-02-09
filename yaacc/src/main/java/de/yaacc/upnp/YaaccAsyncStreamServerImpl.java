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

import android.util.Log;

import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.TimeValue;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.transport.Router;
import org.fourthline.cling.transport.spi.InitializationException;
import org.fourthline.cling.transport.spi.StreamServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class YaaccAsyncStreamServerImpl implements StreamServer<YaaccAsyncStreamServerConfigurationImpl> {


    final protected YaaccAsyncStreamServerConfigurationImpl configuration;
    private final ProtocolFactory protocolFactory;
    protected int localPort;
    private HttpAsyncServer server;

    public YaaccAsyncStreamServerImpl(ProtocolFactory protocolFactory, YaaccAsyncStreamServerConfigurationImpl configuration) {
        this.configuration = configuration;
        this.localPort = configuration.getListenPort();
        this.protocolFactory = protocolFactory;

    }

    public YaaccAsyncStreamServerConfigurationImpl getConfiguration() {
        return configuration;
    }

    synchronized public void init(InetAddress bindAddress, final Router router) throws InitializationException {
        Thread thread = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    try {

                        Log.d(getClass().getName(), "Adding connector: " + bindAddress + ":" + getConfiguration().getListenPort());

                        IOReactorConfig config = IOReactorConfig.custom()
                                .setSoTimeout(getConfiguration().getAsyncTimeoutSeconds(), TimeUnit.SECONDS)
                                .setTcpNoDelay(true)
                                .build();
                        server = H2ServerBootstrap.bootstrap()
                                .setCanonicalHostName(bindAddress.getHostAddress())
                                .setIOReactorConfig(config)
                                .register(router.getConfiguration().getNamespace().getBasePath().getPath() + "/*", new YaaccAsyncStreamServerRequestHandler(protocolFactory))
                                .create();
                        server.start();
                        server.listen(new InetSocketAddress(getConfiguration().getListenPort()), URIScheme.HTTP);
                    } catch (Exception ex) {
                        throw new InitializationException("Could not initialize " + getClass().getSimpleName() + ": " + ex, ex);
                    }
                } catch (Exception e) {
                    throw new InitializationException("Could run init thread " + getClass().getSimpleName() + ": " + e, e);
                }
            }
        });

        thread.start();

    }

    synchronized public int getPort() {
        return this.localPort;
    }

    synchronized public void stop() {

        try {
            server.awaitShutdown(TimeValue.ofSeconds(1));
        } catch (InterruptedException e) {
            Log.w(getClass().getName(), "got exception on stream server stop ", e);
        }
    }

    public void run() {
        //do nothing all stuff done in init
    }

}