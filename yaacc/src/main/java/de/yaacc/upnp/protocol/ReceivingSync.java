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

package de.yaacc.upnp.protocol;

import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.profile.RemoteClientInfo;

import java.io.IOException;

/**
 * Supertype for all synchronously executing protocols, handling reception of UPnP messages and return a response.
 * <p>
 * The returned response will be available to the client of this protocol. The
 * client will then call either {@link #responseSent(StreamResponseMessage)}
 * or {@link #responseException(Throwable)}, depending on whether the response was successfully
 * delivered. The protocol can override these methods to decide if the whole procedure it is
 * implementing was successful or not, including not only creation but also delivery of the response.
 * </p>
 *
 * @param <IN>  The type of incoming UPnP message handled by this protocol.
 * @param <OUT> The type of response UPnP message created by this protocol.
 * @author Christian Bauer
 */
public abstract class ReceivingSync<IN extends StreamRequestMessage, OUT extends StreamResponseMessage> extends ReceivingAsync<IN> {


    final protected RemoteClientInfo remoteClientInfo;
    protected OUT outputMessage;


    protected ReceivingSync(IN inputMessage) {
        super(inputMessage);
        this.remoteClientInfo = new RemoteClientInfo(inputMessage);
    }

    public OUT getOutputMessage() {
        return outputMessage;
    }

    final protected void execute() throws IOException {
        outputMessage = executeSync();

        if (outputMessage != null && getRemoteClientInfo().getExtraResponseHeaders().size() > 0) {
            YaaccLogger.v(getClass().getName(), "Setting extra headers on response message: " + getRemoteClientInfo().getExtraResponseHeaders().size());
            outputMessage.getHeaders().putAll(getRemoteClientInfo().getExtraResponseHeaders());
        }
    }

    protected abstract OUT executeSync() throws IOException;

    /**
     * Called by the client of this protocol after the returned response has been successfully delivered.
     * <p>
     * NOOP by default.
     * </p>
     */
    public void responseSent(StreamResponseMessage responseMessage) {
    }

    /**
     * Called by the client of this protocol if the returned response was not delivered.
     * <p>
     * NOOP by default.
     * </p>
     *
     * @param t The reason why the response wasn't delivered.
     */
    public void responseException(Throwable t) {
    }

    public RemoteClientInfo getRemoteClientInfo() {
        return remoteClientInfo;
    }

    @Override
    public String toString() {
        return "(" + getClass().getSimpleName() + ")";
    }

}
