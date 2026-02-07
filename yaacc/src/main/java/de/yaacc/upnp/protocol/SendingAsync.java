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

import java.io.IOException;

/**
 * Supertype for all synchronously executing protocols, sending UPnP messages.
 *
 * <p>
 * A {@link IOException} during execution will be wrapped in a fatal <code>RuntimeException</code>,
 * unless its cause is an <code>InterruptedException</code>, in which case an INFO message will be logged.
 * </p>
 *
 * @author Christian Bauer
 */
public abstract class SendingAsync implements Runnable {


    protected SendingAsync() {
    }


    public void run() {
        try {
            execute();
        } catch (Exception ex) {
            Throwable cause = ex;
            for (Throwable current = ex; current != null; current = current.getCause()) {
                cause = current;
            }
            if (cause instanceof InterruptedException) {
                YaaccLogger.v(getClass().getName(), "Interrupted protocol '" + getClass().getSimpleName() + "': " + ex, cause);
            } else {
                throw new RuntimeException(
                        "Fatal error while executing protocol '" + getClass().getSimpleName() + "': " + ex, ex
                );
            }
        }
    }

    protected abstract void execute() throws IOException;

    @Override
    public String toString() {
        return "(" + getClass().getSimpleName() + ")";
    }

}