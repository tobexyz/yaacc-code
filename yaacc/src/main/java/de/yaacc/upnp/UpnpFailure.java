/*
 *
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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

import androidx.annotation.NonNull;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;


/**
 * Value holder for upnp failure information
 *
 * @author Tobias Schöne (openbit)
 */
public class UpnpFailure {
	private final ActionInvocation<?> invocation;
	private final UpnpResponse response;
	private final String defaultMsg;
	private final boolean transportFailure;

	/**
	 * constructor. The failure counts as a transport failure when the invocation failed with an
	 * {@link UpnpTransportException}.
	 *
	 * @param invocation the ActionInvocation
	 * @param response   the Upnp response
	 * @param defaultMsg a default message
	 */
	public UpnpFailure(ActionInvocation<?> invocation, UpnpResponse response,
					   String defaultMsg) {
		this(invocation, response, defaultMsg,
				invocation != null && invocation.getFailure() instanceof UpnpTransportException);
	}

	/**
	 * constructor.
	 *
	 * @param invocation       the ActionInvocation
	 * @param response         the Upnp response
	 * @param defaultMsg       a default message
	 * @param transportFailure true if the server could not be reached or did not answer
	 */
	public UpnpFailure(ActionInvocation<?> invocation, UpnpResponse response,
					   String defaultMsg, boolean transportFailure) {
		super();
		this.invocation = invocation;
		this.response = response;
		this.defaultMsg = defaultMsg;
		this.transportFailure = transportFailure;
	}

	/**
	 * @return true if the request did not reach the server or no answer came back, so repeating it
	 * can help; false for an answer of the server (a UPnP error) or a local problem
	 */
	public boolean isTransportFailure() {
		return transportFailure;
	}

	/**
	 * @return the invocation
	 */
	public ActionInvocation<?> getInvocation() {
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


	@NonNull
	@Override
	public String toString() {
		return "UpnpFailure ["
				+ (invocation != null ? "invocation=" + invocation + ", " : "")
				+ (response != null ? "response=" + response + ", " : "")
				+ (defaultMsg != null ? "defaultMsg=" + defaultMsg : "") + "]";
	}

}
