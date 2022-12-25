/*
 *
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
package de.yaacc.upnp;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;


/**
 * Value holder for upnp failure information
 * 
 * @author Tobias Schöne (openbit)
 * 
 */
public class UpnpFailure {
	private ActionInvocation invocation;
	private UpnpResponse response;
	private String defaultMsg;

	/**
	 * constructor.
	 * 
	 * @param invocation
	 *            the ActionInvocation
	 * @param response
	 *            the Upnp response
	 * @param defaultMsg
	 *            a default message
	 */
	public UpnpFailure(ActionInvocation invocation, UpnpResponse response,
			String defaultMsg) {
		super();
		this.invocation = invocation;
		this.response = response;
		this.defaultMsg = defaultMsg;
	}

	/**
	 * @return the invocation
	 */
	public ActionInvocation getInvocation() {
		return invocation;
	}

	/**
	 * @return the operation
	 */
	public UpnpResponse getOperation() {
		return response;
	}

	/**
	 * @return the defaultMsg
	 */
	public String getDefaultMsg() {
		return defaultMsg;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "UpnpFailure ["
				+ (invocation != null ? "invocation=" + invocation + ", " : "")
				+ (response != null ? "response=" + response + ", " : "")
				+ (defaultMsg != null ? "defaultMsg=" + defaultMsg : "") + "]";
	}

}
