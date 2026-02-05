package de.yaacc.upnp.callback.renderingcontrol;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.model.types.UnsignedIntegerTwoBytes;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class SetVolume extends ActionCallback {

    public SetVolume(Service service, long newVolume, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, newVolume, httpRequestSender);
    }

    public SetVolume(UnsignedIntegerFourBytes instanceId, Service service, long newVolume, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("SetVolume")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("Channel", "Master");
        getActionInvocation().setInput("DesiredVolume", new UnsignedIntegerTwoBytes(newVolume));
    }

    @Override
    public void success(ActionInvocation invocation) {
    }

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
