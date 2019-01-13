package de.yaacc.browser;

import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import org.fourthline.cling.support.model.DIDLContent;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;

public class BrowseItemLoadTask extends AsyncTask<Long,Integer,ContentDirectoryBrowseResult> {
    private BrowseItemAdapter itemAdapter;
    private Long chunkSize= 0L;

    public BrowseItemLoadTask(BrowseItemAdapter itemAdapter, Long chunkSize) {
        this.itemAdapter = itemAdapter;
        this.chunkSize = chunkSize;
    }

    @Override
    protected ContentDirectoryBrowseResult doInBackground(Long... params) {
        if (params == null ||params.length < 1){
            return null;
        }
        Long from = params[0];
        return ((Yaacc)itemAdapter.getContext().getApplicationContext()).getUpnpClient().browseSync(itemAdapter.getNavigator().getCurrentPosition(), from, this.chunkSize);

    }

    @Override
    protected void onPostExecute(ContentDirectoryBrowseResult result) {
        Log.d(getClass().getName(),"Ended AsyncTask for loading:" + result);
        if (result == null)
            return ;
        itemAdapter.removeLoadMoreItem();
        int currentItemCount = itemAdapter.getCount();
        DIDLContent content = result.getResult();
        if (content != null) {
            // Add all children in two steps to get containers first
            itemAdapter.addAll(content.getContainers());
            itemAdapter.addAll(content.getItems());
            boolean allItemsFetched = chunkSize > itemAdapter.getCount() - currentItemCount;
            itemAdapter.setAllItemsFetched(allItemsFetched);
            if (!allItemsFetched){
                itemAdapter.addLoadMoreItem();
            }

        } else {
            // If result is null it may be an empty result
            // only in case of an UpnpFailure in the result it is really an
            // failure

            if (result.getUpnpFailure() != null) {
                int duration = Toast.LENGTH_SHORT;
                String text = itemAdapter.getContext().getString(R.string.error_upnp_specific) + " "
                        + result.getUpnpFailure();
                Log.e("ResolveError", text + "(" + itemAdapter.getNavigator().getCurrentPosition().getObjectId() + ")");
                Toast toast = Toast.makeText(itemAdapter.getContext(), text, duration);
                toast.show();
            } else {
                itemAdapter.clear();
            }

        }
        if (itemAdapter != null){
            itemAdapter.removeTask(this);
        }
        itemAdapter.notifyDataSetChanged();
        itemAdapter.setLoading(false);

    }
}
