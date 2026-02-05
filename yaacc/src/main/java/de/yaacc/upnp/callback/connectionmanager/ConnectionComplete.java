package de.yaacc.upnp.callback.connectionmanager;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class ConnectionComplete extends ActionCallback {

    public ConnectionComplete(Service service, HttpRequestSender httpRequestSender, int connectionID) {
        super(new ActionInvocation(service.getAction("ConnectionComplete")), httpRequestSender);
        getActionInvocation().setInput("ConnectionID", connectionID);
    }
}
