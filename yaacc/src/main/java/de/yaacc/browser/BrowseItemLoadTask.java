/*
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.browser;

import android.os.AsyncTask;
import android.util.Log;

import org.fourthline.cling.support.model.DIDLContent;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;

public class BrowseItemLoadTask extends AsyncTask<Long, Integer, ContentDirectoryBrowseResult> {
    private final BrowseContentItemAdapter itemAdapter;
    private final Long chunkSize;


    public BrowseItemLoadTask(BrowseContentItemAdapter itemAdapter, Long chunkSize) {
        this.itemAdapter = itemAdapter;
        this.chunkSize = chunkSize;
    }

    @Override
    protected ContentDirectoryBrowseResult doInBackground(Long... params) {
        if (params == null || params.length < 1) {
            return null;
        }

        Long from = params[0];
        Log.d(getClass().getName(), "loading from:" + from + " chunkSize: " + chunkSize);
        return ((Yaacc) itemAdapter.getContext().getApplicationContext()).getUpnpClient().browseSync(itemAdapter.getNavigator().getCurrentPosition(), from, this.chunkSize);

    }

    @Override
    protected void onPostExecute(ContentDirectoryBrowseResult result) {
        Log.d(getClass().getName(), "Ended AsyncTask for loading:" + result);
        if (result == null)
            return;
        itemAdapter.removeLoadMoreItem();
        int previousItemCount = itemAdapter.getCount();
        DIDLContent content = result.getResult();
        if (content != null) {
            // Add all children in two steps to get containers first
            itemAdapter.addAll(content.getContainers());
            itemAdapter.addAll(content.getItems());
            boolean allItemsFetched = chunkSize != (itemAdapter.getCount() - previousItemCount);
            itemAdapter.setAllItemsFetched(allItemsFetched);
            if (!allItemsFetched) {
                itemAdapter.addLoadMoreItem();
            }

        } else {
            // If result is null it may be an empty result
            // only in case of an UpnpFailure in the result it is really an
            // failure

            if (result.getUpnpFailure() != null) {
                String text = itemAdapter.getContext().getString(R.string.error_upnp_specific) + " "
                        + result.getUpnpFailure();
                Log.e("ResolveError", text + "(" + itemAdapter.getNavigator().getCurrentPosition().getObjectId() + ")");
            } else {
            }
            itemAdapter.clear();

        }
        itemAdapter.removeTask(this);
        itemAdapter.setLoading(false);

    }
}
