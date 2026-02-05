package de.yaacc.upnp.callback.avtransport;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class Play extends ActionCallback {

    public Play(Service service, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, "1", httpRequestSender);
    }

    public Play(UnsignedIntegerFourBytes instanceId, Service service, HttpRequestSender httpRequestSender) {
        this(instanceId, service, "1", httpRequestSender);
    }

    public Play(UnsignedIntegerFourBytes instanceId, Service service, String speed, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("Play")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("Speed", speed);
    }

    @Override
    public void success(ActionInvocation invocation) {
    }

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
