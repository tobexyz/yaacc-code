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
import de.yaacc.util.Exceptions;

import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.model.message.UpnpMessage;
import org.fourthline.cling.model.message.header.UpnpHeader;

import java.io.IOException;

/**
 * Supertype for all asynchronously executing protocols, handling reception of UPnP messages.
 *
 * @param <M> The type of UPnP message handled by this protocol.
 * @author Christian Bauer
 */
public abstract class ReceivingAsync<M extends UpnpMessage> implements Runnable {


    private M inputMessage;

    protected ReceivingAsync(M inputMessage) {
        this.inputMessage = inputMessage;
    }

    public M getInputMessage() {
        return inputMessage;
    }

    public void run() {
        boolean proceed;
        try {
            proceed = waitBeforeExecution();
        } catch (InterruptedException ex) {
            YaaccLogger.v(getClass().getName(), "Protocol wait before execution interrupted (on shutdown?): " + getClass().getSimpleName());
            proceed = false;
        }

        if (proceed) {
            try {
                execute();
            } catch (Exception ex) {
                Throwable cause = Exceptions.unwrap(ex);
                if (cause instanceof InterruptedException) {
                    YaaccLogger.v(getClass().getName(), "Interrupted protocol '" + getClass().getSimpleName() + "': " + ex, cause);
                } else {
                    throw new RuntimeException(
                            "Fatal error while executing protocol '" + getClass().getSimpleName() + "': " + ex, ex
                    );
                }
            }
        }
    }

    /**
     * Provides an opportunity to pause before executing the protocol.
     *
     * @return <code>true</code> (default) if execution should continue after waiting.
     * @throws InterruptedException If waiting has been interrupted, which also stops execution.
     */
    protected boolean waitBeforeExecution() throws InterruptedException {
        // Don't wait by default
        return true;
    }

    protected abstract void execute() throws IOException;

    protected <H extends UpnpHeader> H getFirstHeader(UpnpHeader.Type headerType, Class<H> subtype) {
        return getInputMessage().getHeaders().getFirstHeader(headerType, subtype);
    }

    @Override
    public String toString() {
        return "(" + getClass().getSimpleName() + ")";
    }

}
