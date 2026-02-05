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

package de.yaacc.upnp.protocol.async;

import android.content.Context;
import android.util.Log;

import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.types.NotificationSubtype;
import java.io.IOException;

import java.util.logging.Logger;

import de.yaacc.upnp.server.udp.UdpTransiver;

/**
 * Sending <em>BYEBYE</em> notification messages for a registered local device.
 *
 * @author Christian Bauer
 */
public class SendingNotificationByebye extends SendingNotification {

    final private static Logger log = Logger.getLogger(SendingNotification.class.getName());

    public SendingNotificationByebye(Context context, UdpTransiver udpTransiver, LocalDevice device) {
        super(context, udpTransiver, device);
    }

    // The UDA 1.0 spec says "a message corresponding to /each/ of the ssd:alive messages" but
    // it's not clear if that means the "required" messages according to the tables only or if
    // it includes the triple (or whatever) repeated messages that have been sent to protect
    // against networking problems. It also says, a little later, that "each of the messages should
    // be send more than once". So we are also sending them three times - hell, why not pollute the
    // network with useless stuff, that is going to make this more reliable for sure...

    // In other words: The superclass method is fine even for byebye.

    @Override
    protected void execute() throws IOException {
        Log.v(getClass().getName(), "Sending byebye messages (" + getBulkRepeat() + " times) for: " + getDevice());
        super.execute();
    }

    protected NotificationSubtype getNotificationSubtype() {
        return NotificationSubtype.BYEBYE;
    }

}