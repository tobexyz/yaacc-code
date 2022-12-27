package de.yaacc.upnp.server;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.Executor;

public class AndroidServerConnector extends ServerConnector {

    public AndroidServerConnector(Server server) {
        super(server);
    }

    public AndroidServerConnector(Server server, int acceptors, int selectors) {
        super(server, acceptors, selectors);
    }

    public AndroidServerConnector(Server server, int acceptors, int selectors, ConnectionFactory... factories) {
        super(server, acceptors, selectors, factories);
    }

    public AndroidServerConnector(Server server, ConnectionFactory... factories) {
        super(server, factories);
    }

    public AndroidServerConnector(Server server, SslContextFactory.Server sslContextFactory) {
        super(server, sslContextFactory);
    }

    public AndroidServerConnector(Server server, int acceptors, int selectors, SslContextFactory.Server sslContextFactory) {
        super(server, acceptors, selectors, sslContextFactory);
    }

    public AndroidServerConnector(Server server, SslContextFactory.Server sslContextFactory, ConnectionFactory... factories) {
        super(server, sslContextFactory, factories);
    }

    public AndroidServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, int acceptors, int selectors, ConnectionFactory... factories) {
        super(server, executor, scheduler, bufferPool, acceptors, selectors, factories);
    }


    protected ServerSocketChannel openAcceptChannel() throws IOException {
        ServerSocketChannel serverChannel = null;
        if (isInheritChannel()) {
            Channel channel = System.inheritedChannel();
            if (channel instanceof ServerSocketChannel)
                serverChannel = (ServerSocketChannel) channel;
            else
                LOG.warn("Unable to use System.inheritedChannel() [{}]. Trying a new ServerSocketChannel at {}:{}", channel, getHost(), getPort());
        }

        if (serverChannel == null) {
            InetSocketAddress bindAddress = getHost() == null ? new InetSocketAddress(getPort()) : new InetSocketAddress(getHost(), getPort());
            serverChannel = ServerSocketChannel.open();
            setSocketOption(serverChannel, StandardSocketOptions.SO_REUSEADDR, getReuseAddress());
            //ignore until sdk level 33 setSocketOption(serverChannel, StandardSocketOptions.SO_REUSEPORT, isReusePort());
            try {
                serverChannel.bind(bindAddress, getAcceptQueueSize());
            } catch (Throwable e) {
                IO.close(serverChannel);
                throw new IOException("Failed to bind to " + bindAddress, e);
            }
        }

        return serverChannel;
    }

    private <T> void setSocketOption(ServerSocketChannel channel, SocketOption<T> option, T value) {
        try {
            channel.setOption(option, value);
        } catch (Throwable x) {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not configure {} to {} on {}", option, value, channel, x);
        }
    }

}
