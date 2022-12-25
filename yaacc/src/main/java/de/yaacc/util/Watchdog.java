/*
 * Copyright (C) 2013 www.yaacc.de 
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
package de.yaacc.util;

import java.util.Timer;
import java.util.TimerTask;

/**
 * A watchdog for implementing timeouts.
 * 
 * @author Tobias Schoene (openbit)
 * 
 */
public class Watchdog {

	private boolean watchdogFlag = false;
	private long timeout = 0;
	private Timer watchdogTimer;

	private Watchdog(long timeout) {
		this.timeout = timeout;
	}

	/**
	 * Create an Watchdog with the given timeout
	 * 
	 * @param timeout
	 *            the timeout
	 * @return the Watchdog
	 */
	public static  Watchdog createWatchdog(long timeout) {
		return new Watchdog(timeout);
	}

	/**
	 * starts the watchdog.
	 */
	public void start() {
		watchdogFlag = false;
		watchdogTimer = new Timer();
		watchdogTimer.schedule(new TimerTask() {

			@Override
			public void run() {							
				watchdogFlag = true;
			}
		}, timeout);	
	}

	public boolean hasTimeout(){
		return watchdogFlag;
	}

    public  void cancel(){
        watchdogTimer.cancel();
    }
}
