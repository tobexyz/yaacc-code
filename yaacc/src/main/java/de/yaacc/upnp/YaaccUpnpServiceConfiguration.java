package de.yaacc.upnp;

import org.fourthline.cling.DefaultUpnpServiceConfiguration;
import org.fourthline.cling.UpnpService;
import org.fourthline.cling.android.AndroidNetworkAddressFactory;
import org.fourthline.cling.binding.xml.DeviceDescriptorBinder;
import org.fourthline.cling.binding.xml.RecoveringUDA10DeviceDescriptorBinderImpl;
import org.fourthline.cling.binding.xml.ServiceDescriptorBinder;
import org.fourthline.cling.binding.xml.UDA10ServiceDescriptorBinderSAXImpl;
import org.fourthline.cling.model.Namespace;
import org.fourthline.cling.model.types.ServiceType;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.protocol.ProtocolFactoryImpl;
import org.fourthline.cling.transport.impl.RecoveringGENAEventProcessorImpl;
import org.fourthline.cling.transport.impl.RecoveringSOAPActionProcessorImpl;
import org.fourthline.cling.transport.spi.GENAEventProcessor;
import org.fourthline.cling.transport.spi.NetworkAddressFactory;
import org.fourthline.cling.transport.spi.SOAPActionProcessor;
import org.fourthline.cling.transport.spi.StreamClient;
import org.fourthline.cling.transport.spi.StreamServer;

public class YaaccUpnpServiceConfiguration extends DefaultUpnpServiceConfiguration {

    private final UpnpService upnpService;

    public YaaccUpnpServiceConfiguration(UpnpService upnpService) {
        this(upnpService, 0); // Ephemeral port
    }

    public YaaccUpnpServiceConfiguration(UpnpService upnpService, int streamListenPort) {
        super(streamListenPort, false);
        this.upnpService = upnpService;
        // This should be the default on Android 2.1 but it's not set by default
        //FIXME really needed? System.setProperty("org.xml.sax.driver", "org.xmlpull.v1.sax2.Driver");
    }

    @Override
    protected NetworkAddressFactory createNetworkAddressFactory(int streamListenPort) {
        return new AndroidNetworkAddressFactory(streamListenPort);
    }

    @Override
    protected Namespace createNamespace() {
        // Http context path
        return new Namespace("/upnp");
    }


    @Override
    public ServiceType[] getExclusiveServiceTypes() {

        return new ServiceType[]{new UDAServiceType("AVTransport"), new UDAServiceType("ContentDirectory"), new UDAServiceType("ConnectionManager"), new UDAServiceType("RenderingControl"), new UDAServiceType("X_MS_MediaReceiverRegistrar")};
    }

    @Override
    public StreamClient createStreamClient() {
        return new YaaccStreamingClientImpl(
                new YaaccStreamingClientConfigurationImpl(
                        getSyncProtocolExecutorService()
                )
        );
    }

    @Override
    public StreamServer createStreamServer(NetworkAddressFactory networkAddressFactory) {

        return new YaaccAsyncStreamServerImpl(new ProtocolFactoryImpl(upnpService),
                new YaaccAsyncStreamServerConfigurationImpl(networkAddressFactory.getStreamListenPort())
        );
    }

    @Override
    protected DeviceDescriptorBinder createDeviceDescriptorBinderUDA10() {
        return new RecoveringUDA10DeviceDescriptorBinderImpl();
    }

    @Override
    protected ServiceDescriptorBinder createServiceDescriptorBinderUDA10() {
        return new UDA10ServiceDescriptorBinderSAXImpl();
    }

    @Override
    protected SOAPActionProcessor createSOAPActionProcessor() {
        return new RecoveringSOAPActionProcessorImpl();
    }

    @Override
    protected GENAEventProcessor createGENAEventProcessor() {
        return new RecoveringGENAEventProcessorImpl();
    }

    @Override
    public int getRegistryMaintenanceIntervalMillis() {
        return 7000; // Preserve battery on Android, only run every 7 seconds
    }

}
