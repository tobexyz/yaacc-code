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
