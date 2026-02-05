package de.yaacc.upnp.callback.renderingcontrol;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.model.Channel;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class GetMute extends ActionCallback {

    public GetMute(Service service, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, httpRequestSender);
    }

    public GetMute(UnsignedIntegerFourBytes instanceId, Service service, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("GetMute")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("Channel", Channel.Master.toString());
    }

    @Override
    public void success(ActionInvocation invocation) {
        boolean currentMute = (Boolean) invocation.getOutput("CurrentMute").getValue();
        received(invocation, currentMute);
    }

    public abstract void received(ActionInvocation actionInvocation, boolean currentMute);

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
