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
import de.yaacc.util.YaaccLogger;

import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.SortCriterion;

import java.util.function.Supplier;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;

public class BrowseItemLoadTask extends AsyncTask<Long, Integer, ContentDirectoryBrowseResult> {
    private final BrowseContentItemAdapter itemAdapter;
    private final Long chunkSize;
    private final Integer scrollToPositionId;
    private final BrowseRetryPolicy retryPolicy;


    public BrowseItemLoadTask(BrowseContentItemAdapter itemAdapter, Long chunkSize, Integer positionId) {
        this(itemAdapter, chunkSize, positionId, new BrowseRetryPolicy());
    }

    BrowseItemLoadTask(BrowseContentItemAdapter itemAdapter, Long chunkSize, Integer positionId, BrowseRetryPolicy retryPolicy) {
        this.itemAdapter = itemAdapter;
        this.chunkSize = chunkSize;
        this.scrollToPositionId = positionId;
        this.retryPolicy = retryPolicy;

    }

    @Override
    protected ContentDirectoryBrowseResult doInBackground(Long... params) {
        if (params == null || params.length < 1) {
            return null;
        }

        Long from = params[0];
        YaaccLogger.d(getClass().getName(), "loading from:" + from + " chunkSize: " + chunkSize);
        UpnpClient upnpClient = ((Yaacc) itemAdapter.getContext().getApplicationContext()).getUpnpClient();

        boolean attemptServerSort = itemAdapter.getSortMode() == BrowseContentItemAdapter.SortMode.DATE
                && !itemAdapter.isServerSortRejected();
        if (attemptServerSort) {
            SortCriterion[] orderBy = new SortCriterion[]{new SortCriterion(itemAdapter.isDateAscending(), "dc:date")};
            ContentDirectoryBrowseResult sortedResult = browseWithRetries(
                    () -> upnpClient.browseSync(itemAdapter.getNavigator().getCurrentPosition(), from, this.chunkSize, orderBy));
            if (sortedResult != null && sortedResult.getUpnpFailure() == null) {
                return sortedResult;
            }
            if (isCancelled() || BrowseRetryPolicy.isRetriable(sortedResult)) {
                // No answer of the server even after the retries (network trouble) or the load was
                // cancelled: that says nothing about the sort criteria, so do not blame them and do not
                // start another round of tries with the unsorted request.
                return sortedResult;
            }
            // Server actively rejected the sorted (orderBy-bearing) Browse
            // request -- a legitimate UPnP action failure per the
            // ContentDirectory spec, not necessarily a silently-ignored
            // SortCriteria. Remember this for the folder's remaining chunk
            // requests and fall through to the unsorted retry below rather
            // than emptying the folder.
            YaaccLogger.d(getClass().getName(), "Server rejected sorted (dc:date) Browse request; falling back to unsorted and remembering rejection for this folder load.");
            itemAdapter.markServerSortRejected();
        }
        return browseWithRetries(
                () -> upnpClient.browseSync(itemAdapter.getNavigator().getCurrentPosition(), from, this.chunkSize));
    }

    /**
     * Runs one Browse request with the {@link BrowseRetryPolicy}; on the worker thread only (it waits
     * between the tries). The results of failed tries are dropped, nothing is added to the adapter
     * before {@link #onPostExecute}, so a retry cannot duplicate items.
     */
    private ContentDirectoryBrowseResult browseWithRetries(Supplier<ContentDirectoryBrowseResult> browse) {
        int[] attemptNumber = {0};
        return retryPolicy.execute(() -> {
            attemptNumber[0]++;
            ContentDirectoryBrowseResult result = browse.get();
            if (BrowseRetryPolicy.isRetriable(result) && attemptNumber[0] < BrowseRetryPolicy.MAX_ATTEMPTS) {
                YaaccLogger.d(getClass().getName(), "Browse try " + attemptNumber[0] + " of " + BrowseRetryPolicy.MAX_ATTEMPTS
                        + " failed, trying again in " + BrowseRetryPolicy.backoffMillisBefore(attemptNumber[0] + 1) + " ms");
            }
            return result;
        }, this::isCancelled);
    }

    @Override
    protected void onPostExecute(ContentDirectoryBrowseResult result) {
        YaaccLogger.d(getClass().getName(), "Ended AsyncTask for loading:" + result);
        DIDLContent content = result == null ? null : result.getResult();
        if (content != null) {
            int previousItemCount = itemAdapter.getItemCount();
            // Add all children in two steps to get containers first
            itemAdapter.addAll(content.getContainers());
            itemAdapter.addAll(content.getItems());
            boolean allItemsFetched = chunkSize != (itemAdapter.getItemCount() - previousItemCount);
            itemAdapter.setAllItemsFetched(allItemsFetched);
            itemAdapter.setLoadFailed(false);
        } else if (BrowseRetryPolicy.isFailure(result)) {
            // Even the retries failed (network trouble, server gone). What is shown stays: an empty folder
            // would look like the server's answer. The flag lets the reload after the network is back
            // pick the folder up again.
            String reason = result == null ? "no result" : itemAdapter.getContext().getString(R.string.error_upnp_specific) + " "
                    + result.getUpnpFailure();
            YaaccLogger.e("ResolveError", reason + "(" + currentObjectId() + ")");
            itemAdapter.setLoadFailed(true);
        } else {
            // A result without content and without failure: an empty result
            itemAdapter.clear();
        }
        itemAdapter.removeTask(this);
        itemAdapter.setLoading(false);
        itemAdapter.scrollToPositionId(scrollToPositionId);

    }

    private String currentObjectId() {
        Navigator navigator = itemAdapter.getNavigator();
        Position position = navigator == null ? null : navigator.getCurrentPosition();
        return position == null ? null : position.getObjectId();
    }
}
