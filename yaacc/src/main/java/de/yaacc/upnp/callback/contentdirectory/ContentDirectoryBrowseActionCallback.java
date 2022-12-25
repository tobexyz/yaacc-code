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

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.support.contentdirectory.callback.Browse;
import org.fourthline.cling.support.model.BrowseFlag;
import org.fourthline.cling.support.model.BrowseResult;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.SortCriterion;

import android.util.Log;

import de.yaacc.upnp.UpnpFailure;

/**
 * ActionCallback for content directory browsing. 
 * Connect an instance of this class to a MediaServer-Service.
 * After calling run you will browse the MediaServer-Directory asynchronously 
 * @author Tobias Schöne (openbit)  
 *
 */
public class ContentDirectoryBrowseActionCallback extends Browse {	
	private ContentDirectoryBrowseResult browsingResult;
	

	
	public ContentDirectoryBrowseActionCallback(Service<?, ?> service, String objectID,
			BrowseFlag flag, String filter, long firstResult, Long maxResults, ContentDirectoryBrowseResult browsingResult,
			SortCriterion... orderBy) {
		super(service, objectID, flag, filter, firstResult, maxResults, orderBy);
		this.browsingResult = browsingResult;

	}

	
	

	/* (non-Javadoc)
	 * @see org.fourthline.cling.support.contentdirectory.callback.Browse#receivedRaw(org.fourthline.cling.model.action.ActionInvocation, org.fourthline.cling.support.model.BrowseResult)
	 */
	@Override
	public boolean receivedRaw(ActionInvocation actionInvocation,
			BrowseResult browseResult) {
		// TODO Auto-generated method stub
		Log.d(this.getClass().getName(), "RAW-Result: " + browseResult.getResult());
		return super.receivedRaw(actionInvocation, browseResult);
	}




	@Override
	public void received(ActionInvocation actionInvocation, DIDLContent didl) {		
		this.browsingResult.setResult(didl);
	}
	

	@Override
	public void updateStatus(Status status) {		
		this.browsingResult.setStatus(status);
	}

	@Override
	public void failure(ActionInvocation invocation, UpnpResponse operation,
			String defaultMsg) {
		this.browsingResult.setUpnpFailure(new UpnpFailure(invocation, operation, defaultMsg));

	}

	public Status getStatus() {
		return this.browsingResult.getStatus();
	}


	/**
	 * @return the result
	 */
	public DIDLContent getResult() {
		return this.browsingResult.getResult();
	}


	/**
	 * @return the upnpFailure
	 */
	public UpnpFailure getUpnpFailure() {
		return this.browsingResult.getUpnpFailure();
	}

}