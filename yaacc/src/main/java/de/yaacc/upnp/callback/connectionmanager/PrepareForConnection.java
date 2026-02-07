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
                (Integer) invocation.getOutput("ConnectionID").getValue(),
                (Integer) invocation.getOutput("RcsID").getValue(),
                (Integer) invocation.getOutput("AVTransportID").getValue()
        );
    }

    public abstract void received(ActionInvocation invocation, int connectionID, int rcsID, int avTransportID);
}
