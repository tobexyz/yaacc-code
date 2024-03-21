/*
 *
 * Copyright (C) 2024 Tobias Schoene www.yaacc.de
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
package de.yaacc.upnp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.mock.MockProtocolFactory;
import org.fourthline.cling.mock.MockRouter;
import org.fourthline.cling.mock.MockUpnpServiceConfiguration;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.protocol.ProtocolCreationException;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.protocol.ReceivingSync;
import org.fourthline.cling.transport.spi.UpnpStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.URI;

import de.yaacc.upnp.YaaccAsyncStreamServerConfigurationImpl;
import de.yaacc.upnp.YaaccAsyncStreamServerImpl;
import de.yaacc.upnp.YaaccStreamingClientConfigurationImpl;
import de.yaacc.upnp.YaaccStreamingClientImpl;

public class YaaccSteamingServerClientTest {

    public static final String TEST_HOST = "localhost";
    public static final int TEST_PORT = 8081;
    final private UpnpServiceConfiguration configuration = new MockUpnpServiceConfiguration(false, true);
    private YaaccAsyncStreamServerImpl server;
    private YaaccStreamingClientImpl client;

    final private MockProtocolFactory protocolFactory = new MockProtocolFactory() {

        @Override
        public ReceivingSync<?, ?> createReceivingSync(StreamRequestMessage requestMessage) throws ProtocolCreationException {
            String path = requestMessage.getUri().getPath();
            if (path.endsWith(OKEmptyResponse.PATH)) {
                return new OKEmptyResponse(requestMessage);
            } else if (path.endsWith(OKBodyResponse.PATH)) {
                return new OKBodyResponse(requestMessage);
            } else if (path.endsWith(NoResponse.PATH)) {
                return new NoResponse(requestMessage);
            } else if (path.endsWith(DelayedResponse.PATH)) {
                return new DelayedResponse(requestMessage);
            } else if (path.endsWith(TooLongResponse.PATH)) {
                return new TooLongResponse(requestMessage);
            } else if (path.endsWith(CheckAliveResponse.PATH)) {
                return new CheckAliveResponse(requestMessage);
            } else if (path.endsWith(CheckAliveLongResponse.PATH)) {
                return new CheckAliveLongResponse(requestMessage);
            }
            throw new ProtocolCreationException("Invalid test path: " + path);


        }
    };
    final private MockRouter router = new MockRouter(configuration, protocolFactory) {
        @Override
        public void received(UpnpStream stream) {
            stream.run();
        }
    };

    protected ProtocolFactory getProtocolFactory() {
        return protocolFactory;
    }

    @After
    public void stop() throws Exception {
        Thread.sleep(1000);
        client.stop();
        Thread.sleep(1000);
        server.stop();
        Thread.sleep(1000);
        server = null;
        client = null;
    }

    @Before
    public void start() throws Exception {
        if (server == null) {
            server = createStreamServer(TEST_PORT);
            server.init(InetAddress.getByName(TEST_HOST), router);

            configuration.getStreamServerExecutorService().execute(server);

            client = createStreamClient(configuration);
            Thread.sleep(1000);
        }
    }


    @Test
    public void basic() throws Exception {
        StreamResponseMessage responseMessage;

        responseMessage = client.sendRequest(createRequestMessage(OKEmptyResponse.PATH));
        assertEquals(responseMessage.getOperation().getStatusCode(), 200);
        assertFalse(responseMessage.hasBody());


        responseMessage = client.sendRequest(createRequestMessage(OKBodyResponse.PATH));
        assertEquals(responseMessage.getOperation().getStatusCode(), 200);
        assertTrue(responseMessage.hasBody());
        assertEquals(responseMessage.getBodyString(), "foo");


        responseMessage = client.sendRequest(createRequestMessage(NoResponse.PATH));
        assertEquals(responseMessage.getOperation().getStatusCode(), 404);
        assertFalse(responseMessage.hasBody());

    }

    @Test
    public void cancelled() throws Exception {
        final boolean[] tests = new boolean[1];

        final Thread requestThread = new Thread(() -> {
            try {
                client.sendRequest(createRequestMessage(DelayedResponse.PATH));
            } catch (InterruptedException ex) {
                // We expect this thread to be interrupted
                tests[0] = true;
            }
        });

        requestThread.start();

        // Cancel the request after 250ms
        new Thread(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                // Ignore
            }
            requestThread.interrupt();
        }).start();

        Thread.sleep(3000);
        for (boolean test : tests) {
            assertTrue(test);
        }

    }

    @Test
    public void expired() throws Exception {
        StreamResponseMessage responseMessage = client.sendRequest(createRequestMessage(TooLongResponse.PATH));
        assertNull(responseMessage);

    }

    @Test
    public void checkAlive() throws Exception {
        StreamResponseMessage responseMessage = client.sendRequest(createRequestMessage(CheckAliveResponse.PATH));
        assertEquals(200, responseMessage.getOperation().getStatusCode());
        assertFalse(responseMessage.hasBody());

    }

    @Test
    public void checkAliveExpired() throws Exception {
        StreamResponseMessage responseMessage = client.sendRequest(createRequestMessage(CheckAliveLongResponse.PATH));
        assertNull(responseMessage);
    }

    @Test
    public void checkAliveCancelled() throws Exception {
        final boolean[] tests = new boolean[1];

        final Thread requestThread = new Thread(() -> {
            try {
                client.sendRequest(createRequestMessage(CheckAliveResponse.PATH));
            } catch (InterruptedException ex) {
                // We expect this thread to be interrupted
                tests[0] = true;
            }
        });

        requestThread.start();

        // Cancel the request after 1 second
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                // Ignore
            }
            requestThread.interrupt();
        }).start();

        Thread.sleep(3000);
        for (boolean test : tests) {
            assertTrue(test);
        }
    }

    protected StreamRequestMessage createRequestMessage(String path) {
        return new StreamRequestMessage(
                UpnpRequest.Method.GET,
                URI.create("http://" + TEST_HOST + ":" + TEST_PORT + path)
        );
    }


    public abstract static class TestProtocol extends ReceivingSync<StreamRequestMessage, StreamResponseMessage> {


        public TestProtocol(StreamRequestMessage inputMessage) {
            super(null, inputMessage);
        }
    }

    public static class OKEmptyResponse extends TestProtocol {

        public static final String PATH = "/ok";

        public OKEmptyResponse(StreamRequestMessage inputMessage) {
            super(inputMessage);
        }

        @Override
        protected StreamResponseMessage executeSync() {
            return new StreamResponseMessage(UpnpResponse.Status.OK);
        }
    }

    public static class OKBodyResponse extends TestProtocol {

        public static final String PATH = "/okbody";

        public OKBodyResponse(StreamRequestMessage inputMessage) {
            super(inputMessage);
        }

        @Override
        protected StreamResponseMessage executeSync() {
            return new StreamResponseMessage("foo");
        }
    }

    public static class NoResponse extends TestProtocol {

        public static final String PATH = "/noresponse";

        public NoResponse(StreamRequestMessage inputMessage) {
            super(inputMessage);
        }

        @Override
        protected StreamResponseMessage executeSync() {
            return null;
        }
    }

    public static class DelayedResponse extends TestProtocol {

        public static final String PATH = "/delayed";

        public DelayedResponse(StreamRequestMessage inputMessage) {
            super(inputMessage);
        }

        @Override
        protected StreamResponseMessage executeSync() {
            try {
                Log.i(getClass().getName(), "Sleeping for 2 seconds before completion...");
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            return new StreamResponseMessage(UpnpResponse.Status.OK);
        }
    }

    public static class TooLongResponse extends TestProtocol {

        public static final String PATH = "/toolong";

        public TooLongResponse(StreamRequestMessage inputMessage) {
            super(inputMessage);
        }

        @Override
        protected StreamResponseMessage executeSync() {
            try {
                Log.i(getClass().getName(), "Sleeping for 6 seconds before completion...");
                Thread.sleep(6000);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            return new StreamResponseMessage(UpnpResponse.Status.OK);
        }
    }

    public static class CheckAliveResponse extends TestProtocol {

        public static final String PATH = "/checkalive";

        public CheckAliveResponse(StreamRequestMessage inputMessage) {
            super(inputMessage);
        }

        @Override
        protected StreamResponseMessage executeSync() {
            // Return OK response after 2 seconds, check if client connection every 500ms
            int i = 0;
            while (i < 4) {
                try {
                    Log.i(getClass().getName(), "Sleeping for 500ms before checking connection...");
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }

                i++;
            }
            return new StreamResponseMessage(UpnpResponse.Status.OK);
        }
    }

    public static class CheckAliveLongResponse extends TestProtocol {

        public static final String PATH = "/checkalivelong";

        public CheckAliveLongResponse(StreamRequestMessage inputMessage) {
            super(inputMessage);
        }

        @Override
        protected StreamResponseMessage executeSync() {
            // Return OK response after 5 seconds, check if client connection every 500ms
            int i = 0;
            while (i < 10) {
                try {
                    Log.i(getClass().getName(), "Sleeping for 500ms before checking connection...");
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }

                i++;
            }
            return new StreamResponseMessage(UpnpResponse.Status.OK);
        }
    }


    public YaaccAsyncStreamServerImpl createStreamServer(int port) {
        YaaccAsyncStreamServerConfigurationImpl configuration =
                new YaaccAsyncStreamServerConfigurationImpl(port);


        return new YaaccAsyncStreamServerImpl(
                getProtocolFactory(),
                configuration
        );
    }


    public YaaccStreamingClientImpl createStreamClient(UpnpServiceConfiguration configuration) {
        return new YaaccStreamingClientImpl(
                new YaaccStreamingClientConfigurationImpl(
                        configuration.getSyncProtocolExecutorService(),
                        5
                )
        );

    }


}
