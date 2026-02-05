/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package de.yaacc.upnp.callback.avtransport;

import android.util.Log;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.model.PlayMode;

import de.yaacc.upnp.callback.ActionCallback;
import de.yaacc.upnp.server.http.HttpRequestSender;

/**
 * @author Christian Bauer
 */
public abstract class SetPlayMode extends ActionCallback {


    public SetPlayMode(HttpRequestSender httpRequestSender, Service service, PlayMode playMode) {
        this(httpRequestSender, new UnsignedIntegerFourBytes(0), service, playMode);
    }

    public SetPlayMode(HttpRequestSender httpRequestSender, UnsignedIntegerFourBytes instanceId, Service service, PlayMode playMode) {
        super(new ActionInvocation(service.getAction("SetPlayMode")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
        getActionInvocation().setInput("NewPlayMode", playMode.toString());
    }

    @Override
    public void success(ActionInvocation invocation) {
        Log.v(getClass().getName(), "Execution successful");
    }
}