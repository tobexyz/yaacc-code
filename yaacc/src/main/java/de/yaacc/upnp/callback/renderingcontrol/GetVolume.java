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

import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.model.Channel;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

public abstract class GetVolume extends ActionCallback {

    public GetVolume(Service service, HttpRequestSender httpRequestSender) {
        this(new UnsignedIntegerFourBytes(0), service, httpRequestSender);
    }

    public GetVolume(UnsignedIntegerFourBytes instanceId, Service service, HttpRequestSender httpRequestSender) {
        super(new ActionInvocation(service.getAction("GetVolume")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("Channel", Channel.Master.toString());
    }

    @Override
    public void success(ActionInvocation invocation) {
        boolean ok = true;
        int currentVolume = 0;
        try {
            currentVolume = Integer.valueOf(invocation.getOutput("CurrentVolume").getValue().toString());
        } catch (Exception ex) {
            invocation.setFailure(
                    new ActionException(ErrorCode.ACTION_FAILED, "Can't parse volume response: " + ex, ex)
            );
            failure(invocation, null);
            ok = false;
        }
        if (ok) received(invocation, currentVolume);
    }

    public abstract void received(ActionInvocation actionInvocation, int currentVolume);

    @Override
    public abstract void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg);
}
