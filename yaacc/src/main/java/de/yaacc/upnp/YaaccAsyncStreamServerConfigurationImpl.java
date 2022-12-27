package de.yaacc.upnp;

import org.fourthline.cling.transport.spi.StreamServerConfiguration;

public class YaaccAsyncStreamServerConfigurationImpl implements StreamServerConfiguration {

    protected int listenPort = 0;
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

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
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

    public void setAsyncTimeoutSeconds(int asyncTimeoutSeconds) {
        this.asyncTimeoutSeconds = asyncTimeoutSeconds;
    }


}
