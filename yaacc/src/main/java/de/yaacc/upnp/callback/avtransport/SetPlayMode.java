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

import de.yaacc.util.YaaccLogger;

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
        YaaccLogger.v(getClass().getName(), "Execution successful");
    }
}