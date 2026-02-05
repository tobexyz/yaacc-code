package de.yaacc.upnp.callback;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.RemoteService;
import org.fourthline.cling.model.meta.Service;

import java.net.URL;

import de.yaacc.upnp.protocol.sync.SendingAction;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class ActionCallback implements Runnable {

    protected final ActionInvocation actionInvocation;
    protected final HttpRequestSender httpRequestSender;

    protected ActionCallback(ActionInvocation actionInvocation, HttpRequestSender httpRequestSender) {
        this.actionInvocation = actionInvocation;
        this.httpRequestSender = httpRequestSender;
    }

    public ActionInvocation getActionInvocation() {
        return actionInvocation;
    }

    public void run() {
        Service service = actionInvocation.getAction().getService();

        if (service instanceof LocalService) {
            LocalService localService = (LocalService) service;
            localService.getExecutor(actionInvocation.getAction()).execute(actionInvocation);

            if (actionInvocation.getFailure() != null) {
                failure(actionInvocation, null);
            } else {
                success(actionInvocation);
            }

        } else if (service instanceof RemoteService) {
            RemoteService remoteService = (RemoteService) service;

            URL controlURL;
            try {
                controlURL = remoteService.getDevice().normalizeURI(remoteService.getControlURI());
            } catch (IllegalArgumentException e) {
                failure(actionInvocation, null, "bad control URL: " + remoteService.getControlURI());
                return;
            }

            SendingAction protocol = new SendingAction(httpRequestSender, actionInvocation, controlURL);
            protocol.run();

            if (actionInvocation.getFailure() != null) {
                failure(actionInvocation, null);
            } else {
                success(actionInvocation);
            }
        }
    }

    public abstract void success(ActionInvocation invocation);

    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);

    protected void failure(ActionInvocation invocation, UpnpResponse operation) {
        failure(invocation, operation, null);
    }
}
