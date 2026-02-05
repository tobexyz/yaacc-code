package de.yaacc.upnp.callback.renderingcontrol;

import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.model.Channel;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class GetVolume extends ActionCallback {

    public GetVolume(Service service, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, httpRequestSender);
    }

    public GetVolume(UnsignedIntegerFourBytes instanceId, Service service, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("GetVolume")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("Channel", Channel.Master.toString());
    }

    @Override
    public void success(ActionInvocation invocation) {
        boolean ok = true;
        int currentVolume = 0;
        try {
            currentVolume = Integer.valueOf(invocation.getOutput("CurrentVolume").getValue().toString());
        } catch (Exception ex) {
            invocation.setFailure(
                    new ActionException(ErrorCode.ACTION_FAILED, "Can't parse volume response: " + ex, ex)
            );
            failure(invocation, null);
            ok = false;
        }
        if (ok) received(invocation, currentVolume);
    }

    public abstract void received(ActionInvocation actionInvocation, int currentVolume);

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
