package de.yaacc.upnp.callback.connectionmanager;

import org.fourthline.cling.model.ServiceReference;
import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.support.model.ConnectionInfo;
import org.fourthline.cling.support.model.ProtocolInfo;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class GetCurrentConnectionInfo extends ActionCallback {

    public GetCurrentConnectionInfo(Service service, HttpRequestSender httpRequestSender, int connectionID) {
        super(new ActionInvocation(service.getAction("GetCurrentConnectionInfo")), httpRequestSender);
        getActionInvocation().setInput("ConnectionID", connectionID);
    }

    @Override
    public void success(ActionInvocation invocation) {
        try {
            ConnectionInfo info = new ConnectionInfo(
                    (Integer)invocation.getInput("ConnectionID").getValue(),
                    (Integer)invocation.getOutput("RcsID").getValue(),
                    (Integer)invocation.getOutput("AVTransportID").getValue(),
                    new ProtocolInfo(invocation.getOutput("ProtocolInfo").toString()),
                    new ServiceReference(invocation.getOutput("PeerConnectionManager").toString()),
                    (Integer)invocation.getOutput("PeerConnectionID").getValue(),
                    ConnectionInfo.Direction.valueOf(invocation.getOutput("Direction").toString()),
                    ConnectionInfo.Status.valueOf(invocation.getOutput("Status").toString())
            );

            received(invocation, info);

        } catch (Exception ex) {
            invocation.setFailure(
                    new ActionException(ErrorCode.ACTION_FAILED, "Can't parse ConnectionInfo response: " + ex, ex)
            );
            failure(invocation, null);
        }
    }

    public abstract void received(ActionInvocation invocation, ConnectionInfo connectionInfo);
}
