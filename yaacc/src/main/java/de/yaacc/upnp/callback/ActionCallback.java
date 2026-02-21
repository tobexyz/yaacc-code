/*
 *
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
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
