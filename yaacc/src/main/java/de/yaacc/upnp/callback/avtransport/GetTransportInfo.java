package de.yaacc.upnp.callback.avtransport;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.model.TransportInfo;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class GetTransportInfo extends ActionCallback {

    public GetTransportInfo(Service service, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, httpRequestSender);
    }

    public GetTransportInfo(UnsignedIntegerFourBytes instanceId, Service service, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("GetTransportInfo")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
    }

    @Override
    public void success(ActionInvocation invocation) {
        TransportInfo transportInfo = new TransportInfo(invocation.getOutputMap());
        received(invocation, transportInfo);
    }

    public abstract void received(ActionInvocation invocation, TransportInfo transportInfo);

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
