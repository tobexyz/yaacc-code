package de.yaacc.upnp.callback.renderingcontrol;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class SetMute extends ActionCallback {

    public SetMute(Service service, boolean desiredMute, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, desiredMute, httpRequestSender);
    }

    public SetMute(UnsignedIntegerFourBytes instanceId, Service service, boolean desiredMute, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("SetMute")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("Channel", "Master");
        getActionInvocation().setInput("DesiredMute", desiredMute);
    }

    @Override
    public void success(ActionInvocation invocation) {
    }

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
