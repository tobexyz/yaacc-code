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

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.model.MediaInfo;

import java.util.logging.Logger;

import de.yaacc.upnp.server.http.HttpRequestSender;

/**
 *
 * @author Christian Bauer
 */
public abstract class GetMediaInfo extends de.yaacc.upnp.callback.ActionCallback {

    private static Logger log = Logger.getLogger(GetMediaInfo.class.getName());

    public GetMediaInfo(HttpRequestSender httpRequestSender, Service service) {
        this(httpRequestSender, new UnsignedIntegerFourBytes(0), service);
    }

    public GetMediaInfo(HttpRequestSender httpRequestSender, UnsignedIntegerFourBytes instanceId, Service service) {
        super(new ActionInvocation(service.getAction("GetMediaInfo")), httpRequestSender);
        getActionInvocation().setInput("InstanceID", instanceId);
    }

    public void success(ActionInvocation invocation) {
        MediaInfo mediaInfo = new MediaInfo(invocation.getOutputMap());
        received(invocation, mediaInfo);
    }

    public abstract void received(ActionInvocation invocation, MediaInfo mediaInfo);

}