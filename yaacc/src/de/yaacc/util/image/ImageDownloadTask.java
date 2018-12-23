package de.yaacc.util.image;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.view.View;
import android.widget.ImageView;
import android.widget.ListView;

import de.yaacc.R;

/**
 * AsyncTask fpr retrieving icons while browsing.
 *
 * @author: Christoph Hähnel (eyeless)
 */
public class ImageDownloadTask extends AsyncTask<Uri, Integer, Bitmap> {


    private ImageView imageView;
    private IconDownloadCacheHandler cache;

    /**
     * Initialize a new download by handing over the the list where the image should be shown
     * @param imageView contains the view
     */
    public ImageDownloadTask(ImageView imageView){
        this.imageView = imageView;
        this.cache = IconDownloadCacheHandler.getInstance();
    }

    /**
     * Download image and convert it to icon
     * @param uri uri of resource
     * @return icon
     */
    @Override
    protected Bitmap doInBackground(Uri... uri) {
        if(cache.getBitmap(uri[0]) == null){
            cache.addBitmap(uri[0],new ImageDownloader().retrieveImageWithCertainSize(uri[0],imageView.getWidth(),imageView.getHeight()));
        }

        return cache.getBitmap(uri[0]);
    }

    /**
     * Replaces the icon in the list with the recently loaded icon
     * @param result downloaded icon
     */
    @Override
    protected void onPostExecute(Bitmap result) {
        imageView.setImageBitmap(result);
    }
}