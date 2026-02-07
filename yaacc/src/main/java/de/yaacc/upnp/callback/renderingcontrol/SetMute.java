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
package de.yaacc.upnp.callback.renderingcontrol;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class SetMute extends ActionCallback {

    public SetMute(Service service, boolean desiredMute, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, desiredMute, httpRequestSender);
    }

    public SetMute(UnsignedIntegerFourBytes instanceId, Service service, boolean desiredMute, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("SetMute")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("Channel", "Master");
        getActionInvocation().setInput("DesiredMute", desiredMute);
    }

    @Override
    public void success(ActionInvocation invocation) {
    }

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
