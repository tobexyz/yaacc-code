package de.yaacc.upnp.callback.avtransport;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class SetAVTransportURI extends ActionCallback {

    public SetAVTransportURI(Service service, String uri, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, uri, null, httpRequestSender);
    }

    public SetAVTransportURI(Service service, String uri, String metadata, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, uri, metadata, httpRequestSender);
    }

    public SetAVTransportURI(UnsignedIntegerFourBytes instanceId, Service service, String uri, HttpRequestSender httpRequestSender) {
        this(instanceId, service, uri, null, httpRequestSender);
    }

    public SetAVTransportURI(UnsignedIntegerFourBytes instanceId, Service service, String uri, String metadata, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("SetAVTransportURI")), httpRequestSender);

        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("CurrentURI", uri);
        getActionInvocation().setInput("CurrentURIMetaData", metadata);
    }

    @Override
    public void success(ActionInvocation invocation) {
    }

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
