/*
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
package de.yaacc.upnp.callback.contentdirectory;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.SortCriterion;

import de.yaacc.upnp.UpnpFailure;
import de.yaacc.upnp.server.http.HttpRequestSender;

/** The search action of a ContentDirectory with its answer in a {@link ContentDirectoryBrowseResult}, as the browse callback has it. */
public class ContentDirectorySearchActionCallback extends Search {
    private final ContentDirectoryBrowseResult searchResult;

    public ContentDirectorySearchActionCallback(Service<?, ?> service, String containerId, String searchCriteria, String filter,
                                                long firstResult, Long maxResults, ContentDirectoryBrowseResult searchResult,
                                                HttpRequestSender httpRequestSender, SortCriterion... orderBy) {
        super(httpRequestSender, service, containerId, searchCriteria, filter, firstResult, maxResults, orderBy);
        this.searchResult = searchResult;
    }

    @Override
    public void received(ActionInvocation actionInvocation, DIDLContent didl) {
        searchResult.setResult(didl);
    }

    @Override
    public void updateStatus(Status status) {
        searchResult.setStatus(toBrowseStatus(status));
    }

    @Override
    public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
        searchResult.setUpnpFailure(new UpnpFailure(invocation, operation, defaultMsg));
    }

    private static Browse.Status toBrowseStatus(Status status) {
        switch (status) {
            case OK:
                return Browse.Status.OK;
            case NO_CONTENT:
                return Browse.Status.NO_CONTENT;
            default:
                return Browse.Status.LOADING;
        }
    }

    public Browse.Status getStatus() {
        return searchResult.getStatus();
    }

    public UpnpFailure getUpnpFailure() {
        return searchResult.getUpnpFailure();
    }
}
