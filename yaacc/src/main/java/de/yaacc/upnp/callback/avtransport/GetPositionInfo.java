package de.yaacc.upnp.callback.avtransport;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.model.PositionInfo;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class GetPositionInfo extends ActionCallback {

    public GetPositionInfo(Service service, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, httpRequestSender);
    }

    public GetPositionInfo(UnsignedIntegerFourBytes instanceId, Service service, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("GetPositionInfo")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
    }

    @Override
    public void success(ActionInvocation invocation) {
        PositionInfo positionInfo = new PositionInfo(invocation.getOutputMap());
        received(invocation, positionInfo);
    }

    public abstract void received(ActionInvocation invocation, PositionInfo positionInfo);

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
