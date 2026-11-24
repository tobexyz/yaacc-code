/*
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
package de.yaacc.upnp.callback.contentdirectory;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;

import de.yaacc.upnp.UpnpFailure;
import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

/** {@code GetSearchCapabilities} of a ContentDirectory: the properties the server can search, as the server lists them. */
public class SearchCapabilitiesActionCallback extends ActionCallback {
    private volatile boolean done;
    private volatile String capabilities;
    private volatile UpnpFailure failure;

    public SearchCapabilitiesActionCallback(HttpRequestSender httpRequestSender, Service<?, ?> service) {
        super(new ActionInvocation(service.getAction("GetSearchCapabilities")), httpRequestSender);
    }

    @Override
    public void success(ActionInvocation invocation) {
        try {
            Object value = invocation.getOutput("SearchCaps") == null ? null : invocation.getOutput("SearchCaps").getValue();
            capabilities = value == null ? "" : value.toString();
        } finally {
            done = true;
        }
    }

    @Override
    public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
        failure = new UpnpFailure(invocation, operation, defaultMsg);
        done = true;
    }

    public boolean isDone() {
        return done;
    }

    /** @return the comma separated list, empty if the server lists none; null while nothing arrived or after a failure */
    public String getCapabilities() {
        return capabilities;
    }

    public UpnpFailure getUpnpFailure() {
        return failure;
    }
}
