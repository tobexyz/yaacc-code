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

package de.yaacc.upnp.registry;

import de.yaacc.util.YaaccLogger;

/**
 * Runs periodically and calls {@link de.yaacc.upnp.registry.RegistryImpl#maintain()}.
 *
 * @author Christian Bauer
 */
public class RegistryMaintainer implements Runnable {


    final private RegistryImpl registry;
    final private int sleepIntervalMillis;

    private volatile boolean stopped = false;

    public RegistryMaintainer(RegistryImpl registry, int sleepIntervalMillis) {
        this.registry = registry;
        this.sleepIntervalMillis = sleepIntervalMillis;
    }

    public void stop() {

        YaaccLogger.v(getClass().getName(), "Setting stopped status on thread");
        stopped = true;
    }

    public void run() {
        stopped = false;

        YaaccLogger.v(getClass().getName(), "Running registry maintenance loop every milliseconds: " + sleepIntervalMillis);
        while (!stopped) {

            try {
                registry.maintain();
                Thread.sleep(sleepIntervalMillis);
            } catch (InterruptedException ex) {
                stopped = true;
            }

        }
        YaaccLogger.v(getClass().getName(), "Stopped status on thread received, ending maintenance loop");
    }

}