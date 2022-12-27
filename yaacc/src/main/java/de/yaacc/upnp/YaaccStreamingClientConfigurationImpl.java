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

