package de.yaacc.upnp.callback.connectionmanager;

import org.fourthline.cling.model.ServiceReference;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.support.model.ConnectionInfo;
import org.fourthline.cling.support.model.ProtocolInfo;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class PrepareForConnection extends ActionCallback {

    public PrepareForConnection(Service service, HttpRequestSender httpRequestSender,
                                ProtocolInfo remoteProtocolInfo, ServiceReference peerConnectionManager,
                                int peerConnectionID, ConnectionInfo.Direction direction) {
        super(new ActionInvocation(service.getAction("PrepareForConnection")), httpRequestSender);

        getActionInvocation().setInput("RemoteProtocolInfo", remoteProtocolInfo.toString());
        getActionInvocation().setInput("PeerConnectionManager", peerConnectionManager.toString());
        getActionInvocation().setInput("PeerConnectionID", peerConnectionID);
        getActionInvocation().setInput("Direction", direction.toString());
    }

    @Override
    public void success(ActionInvocation invocation) {
        received(
                invocation,
                (Integer)invocation.getOutput("ConnectionID").getValue(),
                (Integer)invocation.getOutput("RcsID").getValue(),
                (Integer)invocation.getOutput("AVTransportID").getValue()
        );
    }

    public abstract void received(ActionInvocation invocation, int connectionID, int rcsID, int avTransportID);
}
