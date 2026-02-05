package de.yaacc.upnp.callback.avtransport;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.model.SeekMode;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class Seek extends ActionCallback {

    public Seek(Service service, String relativeTimeTarget, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, SeekMode.REL_TIME, relativeTimeTarget, httpRequestSender);
    }

    public Seek(UnsignedIntegerFourBytes instanceId, Service service, String relativeTimeTarget, HttpRequestSender httpRequestSender) {
        this(instanceId, service, SeekMode.REL_TIME, relativeTimeTarget, httpRequestSender);
    }

    public Seek(Service service, SeekMode mode, String target, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, mode, target, httpRequestSender);
    }

    public Seek(UnsignedIntegerFourBytes instanceId, Service service, SeekMode mode, String target, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("Seek")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("Unit", mode.name());
        getActionInvocation().setInput("Target", target);
    }

    @Override
    public void success(ActionInvocation invocation) {
    }

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
