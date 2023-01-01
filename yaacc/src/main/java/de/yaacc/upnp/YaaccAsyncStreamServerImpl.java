package de.yaacc.upnp;

import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.ListenerEndpoint;
import org.apache.hc.core5.util.TimeValue;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.transport.Router;
import org.fourthline.cling.transport.spi.InitializationException;
import org.fourthline.cling.transport.spi.StreamServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class YaaccAsyncStreamServerImpl implements StreamServer<YaaccAsyncStreamServerConfigurationImpl> {

    final private static Logger log = Logger.getLogger(YaaccAsyncStreamServerImpl.class.getName());

    final protected YaaccAsyncStreamServerConfigurationImpl configuration;
    protected int localPort;
    private ExecutorService executorService;
    private HttpAsyncServer server;
    private Future<ListenerEndpoint> listenerEndpointFuture;
    private ProtocolFactory protocolFactory;

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

                        log.info("Setting executor service on stream server");

                        executorService = router.getConfiguration().getStreamServerExecutorService();


                        log.info("Adding connector: " + bindAddress + ":" + getConfiguration().getListenPort());

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
                        listenerEndpointFuture = server.listen(new InetSocketAddress(getConfiguration().getListenPort()), URIScheme.HTTP);
                    } catch (Exception ex) {
                        throw new InitializationException("Could not initialize " + getClass().getSimpleName() + ": " + ex.toString(), ex);
                    }
                } catch (Exception e) {
                    throw new InitializationException("Could run init thread " + getClass().getSimpleName() + ": " + e.toString(), e);
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
            log.log(Level.INFO, "got exception on stream server stop ", e);
        }
    }

    public void run() {
        //do nothing all stuff done in init
    }

}