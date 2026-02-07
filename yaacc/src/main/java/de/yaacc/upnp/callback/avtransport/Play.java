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
package de.yaacc.upnp.callback.avtransport;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class Play extends ActionCallback {

    public Play(Service service, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, "1", httpRequestSender);
    }

    public Play(UnsignedIntegerFourBytes instanceId, Service service, HttpRequestSender httpRequestSender) {
        this(instanceId, service, "1", httpRequestSender);
    }

    public Play(UnsignedIntegerFourBytes instanceId, Service service, String speed, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("Play")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("Speed", speed);
    }

    @Override
    public void success(ActionInvocation invocation) {
    }

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
