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
package de.yaacc.upnp.callback.contentdirectory;

import org.fourthline.cling.support.contentdirectory.callback.Browse.Status;
import org.fourthline.cling.support.model.DIDLContent;

import de.yaacc.upnp.UpnpFailure;

/**
 * Result of a content directory browsing.
 * This object is used either in synchronous or asynchronous requests.
 * In case of asynchronous requests you have to query the status 
 * in order to know if the request completes.   
 *  
 * @author Tobias Schöne (openbit)  
 *
 */
public class ContentDirectoryBrowseResult {
	private Status status = Status.LOADING;
	private DIDLContent result  = null;
	private UpnpFailure upnpFailure;
	

	
	

	/**
	 * default constructor. 
	 *
	 *   
	 */
	public ContentDirectoryBrowseResult() {
		super();
		
	}


	/**
	 * Returns the status of browsing, i.e. LAODING, NO_CONTENT, OK.
	 * @return the status
	 */
	public Status getStatus() {
		return status;
	}


	/**
	 * Returns the browsing result.
	 * @return the result
	 */
	public DIDLContent getResult() {
		return result;
	}


	/**
	 * a failure object if anything goes wrong.
	 * @return the upnpFailure
	 */
	public UpnpFailure getUpnpFailure() {
		return upnpFailure;
	}


	/**
	 * Set the status of browsing
	 * @param status the status to set
	 */
	public void setStatus(Status status) {
		this.status = status;
	}


	/**
	 * @param result the result to set
	 */
	public void setResult(DIDLContent result) {
		this.result = result;
	}


	/**
	 * @param upnpFailure the upnpFailure to set
	 */
	public void setUpnpFailure(UpnpFailure upnpFailure) {
		this.upnpFailure = upnpFailure;
	}

}