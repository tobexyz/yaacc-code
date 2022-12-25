package de.yaacc.upnp.callback.avtransport;/*
* Copyright (C) 2014 www.yaacc.de
*
* This program is free software; you can redistribute it and/or
* modify it under the terms of the GNU General Public License
* as published by the Free Software Foundation; either version 3
* of the License, or (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;

import java.util.logging.Logger;

/**
 * @author Tobias Schoene (TheOpenBit)
 */
public abstract class SyncPause extends ActionCallback {

    private static Logger log = Logger.getLogger(SyncPause.class.getName());


    public SyncPause(UnsignedIntegerFourBytes instanceId, Service service, String pauseTime, String referenceClockId) {
        super(new ActionInvocation(service.getAction("SyncPause")));
        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("PauseTime", pauseTime);
        getActionInvocation().setInput("ReferenceClockId", referenceClockId);

    }

    @Override
    public void success(ActionInvocation invocation) {
        log.fine("Execution successful");
    }

}
