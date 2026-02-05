package de.yaacc.upnp.callback.connectionmanager;

import org.fourthline.cling.model.action.ActionArgumentValue;
import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.support.model.ProtocolInfos;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class GetProtocolInfo extends ActionCallback {

    public GetProtocolInfo(Service service, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("GetProtocolInfo")), httpRequestSender);
    }

    @Override
    public void success(ActionInvocation invocation) {
        try {
            ActionArgumentValue sink = invocation.getOutput("Sink");
            ActionArgumentValue source = invocation.getOutput("Source");

            received(
                    invocation,
                    sink != null ? new ProtocolInfos(sink.toString()) : null,
                    source != null ? new ProtocolInfos(source.toString()) : null
            );

        } catch (Exception ex) {
            invocation.setFailure(
                    new ActionException(ErrorCode.ACTION_FAILED, "Can't parse ProtocolInfo response: " + ex, ex)
            );
            failure(invocation, null);
        }
    }

    public abstract void received(ActionInvocation actionInvocation, ProtocolInfos sinkProtocolInfos, ProtocolInfos sourceProtocolInfos);
}
