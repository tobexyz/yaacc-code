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


import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;

import java.io.IOException;

/**
 * Supertype for all synchronously executing protocols, sending UPnP messages.
 *
 * @param <IN>  The type of request UPnP message send by this protocol.
 * @param <OUT> The type of response UPnP message expected by this protocol.
 * @author Christian Bauer
 */
public abstract class SendingSync<IN extends StreamRequestMessage, OUT extends StreamResponseMessage> extends SendingAsync {

    final private IN inputMessage;
    protected OUT outputMessage;

    protected SendingSync(IN inputMessage) {
        this.inputMessage = inputMessage;
    }

    public IN getInputMessage() {
        return inputMessage;
    }

    public OUT getOutputMessage() {
        return outputMessage;
    }

    final protected void execute() throws IOException {
        outputMessage = executeSync();
    }

    protected abstract OUT executeSync() throws IOException;

    @Override
    public String toString() {
        return "(" + getClass().getSimpleName() + ")";
    }

}