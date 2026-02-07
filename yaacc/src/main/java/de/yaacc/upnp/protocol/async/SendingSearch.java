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

package de.yaacc.upnp.protocol.async;

import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.model.message.discovery.OutgoingSearchRequest;
import org.fourthline.cling.model.message.header.MXHeader;
import org.fourthline.cling.model.message.header.STAllHeader;
import org.fourthline.cling.model.message.header.UpnpHeader;
import java.io.IOException;

import de.yaacc.upnp.protocol.SendingAsync;
import de.yaacc.upnp.server.udp.UdpTransiver;

/**
 * Sending search request messages using the supplied search type.
 * <p>
 * Sends all search messages 5 times, waits 0 to 500
 * milliseconds between each sending procedure.
 * </p>
 *
 * @author Christian Bauer
 */
public class SendingSearch extends SendingAsync {


    private final int mxSeconds;
    private final UpnpHeader searchTarget;
    private final UdpTransiver udpTransiver;

    /**
     * Defaults to {@link STAllHeader} and an MX of 3 seconds.
     */
    public SendingSearch(UdpTransiver udpTransiver) {
        this(udpTransiver, new STAllHeader());
    }

    /**
     * Defaults to an MX value of 3 seconds.
     */
    public SendingSearch(UdpTransiver udpTransiver, UpnpHeader searchTarget) {
        this(udpTransiver, searchTarget, MXHeader.DEFAULT_VALUE);
    }

    /**
     * @param mxSeconds The time in seconds a host should wait before responding.
     */
    public SendingSearch(UdpTransiver udpTransiver, UpnpHeader searchTarget, int mxSeconds) {
        super();
        this.udpTransiver = udpTransiver;

        if (!UpnpHeader.Type.ST.isValidHeaderType(searchTarget.getClass())) {
            throw new IllegalArgumentException(
                    "Given search target instance is not a valid header class for type ST: " + searchTarget.getClass()
            );
        }
        this.searchTarget = searchTarget;
        this.mxSeconds = mxSeconds;
    }

    public UpnpHeader getSearchTarget() {
        return searchTarget;
    }

    public int getMxSeconds() {
        return mxSeconds;
    }

    protected void execute() throws IOException {

        YaaccLogger.v(getClass().getName(), "Executing search for target: " + searchTarget.getString() + " with MX seconds: " + getMxSeconds());

        OutgoingSearchRequest msg = new OutgoingSearchRequest(searchTarget, getMxSeconds());
        prepareOutgoingSearchRequest(msg);

        for (int i = 0; i < getBulkRepeat(); i++) {
            try {

                udpTransiver.send(msg);

                // UDA 1.0 is silent about this but UDA 1.1 recommends "a few hundred milliseconds"
                YaaccLogger.v(getClass().getName(), "Sleeping " + getBulkIntervalMilliseconds() + " milliseconds");
                Thread.sleep(getBulkIntervalMilliseconds());

            } catch (InterruptedException ex) {
                // Interruption means we stop sending search messages, e.g. on shutdown of thread pool
                YaaccLogger.v(getClass().getName(), "got exception on search", ex);
                break;
            }
        }
    }

    public int getBulkRepeat() {
        return 5; // UDA 1.0 says "repeat more than once"
    }

    public int getBulkIntervalMilliseconds() {
        return 500; // That should be plenty on an ethernet LAN
    }

    /**
     * Override this to edit the outgoing message, e.g. by adding headers.
     */
    protected void prepareOutgoingSearchRequest(OutgoingSearchRequest message) {
    }

}
