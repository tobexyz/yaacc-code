/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.fourthline.cling.test.transport;

import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.mock.MockProtocolFactory;
import org.fourthline.cling.model.message.Connection;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.protocol.ProtocolCreationException;
import org.fourthline.cling.protocol.ReceivingSync;
import org.fourthline.cling.transport.spi.StreamClient;
import org.fourthline.cling.transport.spi.StreamServer;

import java.net.InetAddress;
import java.net.UnknownHostException;

import de.yaacc.upnp.YaaccAsyncStreamServerConfigurationImpl;
import de.yaacc.upnp.YaaccAsyncStreamServerImpl;
import de.yaacc.upnp.YaaccStreamingClientConfigurationImpl;
import de.yaacc.upnp.YaaccStreamingClientImpl;

/**
 * @author Christian Bauer
 */
public class JettyServerJettyClientTest extends StreamServerClientTest {
    private TestProtocol lastExecutedServerProtocol;
    private MockProtocolFactory protocolFactory = new MockProtocolFactory() {
        @Override
        public ReceivingSync createReceivingSync(StreamRequestMessage requestMessage) throws ProtocolCreationException {
            Connection conn = new Connection() {
                @Override
                public boolean isOpen() {
                    return true;
                }

                @Override
                public InetAddress getRemoteAddress() {
                    try {
                        return InetAddress.getByName("10.0.0.1");
                    } catch (UnknownHostException ex) {
                        throw new RuntimeException(ex);
                    }
                }

                @Override
                public InetAddress getLocalAddress() {
                    try {
                        return InetAddress.getByName("10.0.0.2");
                    } catch (UnknownHostException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            };
            requestMessage.setConnection(conn);
            String path = requestMessage.getUri().getPath();
            if (path.endsWith(OKEmptyResponse.PATH)) {
                lastExecutedServerProtocol = new OKEmptyResponse(requestMessage);
            } else if (path.endsWith(OKBodyResponse.PATH)) {
                lastExecutedServerProtocol = new OKBodyResponse(requestMessage);
            } else if (path.endsWith(NoResponse.PATH)) {
                lastExecutedServerProtocol = new NoResponse(requestMessage);
            } else if (path.endsWith(DelayedResponse.PATH)) {
                lastExecutedServerProtocol = new DelayedResponse(requestMessage);
            } else if (path.endsWith(TooLongResponse.PATH)) {
                lastExecutedServerProtocol = new TooLongResponse(requestMessage);
            } else if (path.endsWith(CheckAliveResponse.PATH)) {
                lastExecutedServerProtocol = new CheckAliveResponse(requestMessage);
            } else if (path.endsWith(CheckAliveLongResponse.PATH)) {
                lastExecutedServerProtocol = new CheckAliveLongResponse(requestMessage);
            } else {
                throw new ProtocolCreationException("Invalid test path: " + path);
            }
            return lastExecutedServerProtocol;
        }
    };

    @Override
    public StreamServer createStreamServer(int port) {
        YaaccAsyncStreamServerConfigurationImpl configuration =
                new YaaccAsyncStreamServerConfigurationImpl(port);


        return new YaaccAsyncStreamServerImpl(
                getProtocolFactory(),
                //new ProtocolFactoryImpl(new UpnpServiceImpl()),
                configuration
        );
    }

    @Override
    public StreamClient createStreamClient(UpnpServiceConfiguration configuration) {
        return new YaaccStreamingClientImpl(
                new YaaccStreamingClientConfigurationImpl(
                        configuration.getSyncProtocolExecutorService()
                )
        );
    }


}
