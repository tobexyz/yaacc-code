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
package de.yaacc.imageviewer;

import android.app.Dialog;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Window;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;

import de.yaacc.R;

/**
 * Background task for retrieving network images.
 *
 * @author Tobias Schoene (openbit)
 */
public class RetrieveImageTask extends AsyncTask<Uri, Void, Void> {

	private ImageViewerActivity imageViewerActivity;
	private Dialog pd;
	public RetrieveImageTask(ImageViewerActivity imageViewerActivity) {
		this.imageViewerActivity = imageViewerActivity;
	}

	@Override
	protected Void doInBackground(Uri... imageUris) {
		if (imageUris == null || imageUris.length == 0) {
			return null;
		}
		if (imageUris.length > 1) {
			throw new IllegalStateException("more than one uri to be retrieved");
		}
		retrieveImage(imageUris[0]);
		// This async task has no result
		return null;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see android.os.AsyncTask#onPostExecute(java.lang.Object)
	 */
	@Override
	protected void onPostExecute(Void result) {
		super.onPostExecute(result);
		if (pd != null) {
			pd.dismiss();
		}
		// Start Timer after new image is loaded
		if (imageViewerActivity.isPictureShowActive()) {
			imageViewerActivity.startTimer();
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see android.os.AsyncTask#onPreExecute()
	 */
	@Override
	protected void onPreExecute() {
		super.onPreExecute();
		imageViewerActivity.runOnUiThread(new Runnable() {
			public void run() {
				pd = new Dialog(imageViewerActivity);
				pd.requestWindowFeature(Window.FEATURE_NO_TITLE);
				pd.setContentView(R.layout.yaacc_progress_dialog);
				pd.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
				pd.show();
			}
		});

	}

	/**
	 * retrieves an image an stores them in the image cache of the
	 * ImageViewerActivity.
	 *
	 * @param imageUri
	 */
	private void retrieveImage(Uri imageUri) {
		{
			Log.d(getClass().getName(), "Load imageUri: " + imageUri);
			Drawable image = null;

			try {
				if (imageUri != null) {

					int heightPixels = imageViewerActivity.getResources()
							.getDisplayMetrics().heightPixels;
					int widthPixels = imageViewerActivity.getResources()
							.getDisplayMetrics().widthPixels;
					Log.d(getClass().getName(),
							"Decode image: " + System.currentTimeMillis());
					Log.d(getClass().getName(), "Size width,height: "
							+ widthPixels + "," + heightPixels);
					Bitmap bitmap = decodeSampledBitmapFromStream(imageUri,
							widthPixels, heightPixels);
					image = new BitmapDrawable(
							imageViewerActivity.getResources(), bitmap);
					Log.d(getClass().getName(),
							"Got image: " + System.currentTimeMillis());
					Log.d(getClass().getName(), "image: " + image);
				}
			} catch (final Exception e) {
				image = Drawable.createFromPath("@drawable/ic_launcher");
				Log.d(getClass().getName(), "Error while processing image", e);
				imageViewerActivity.runOnUiThread(new Runnable() {
					public void run() {
						Toast toast = Toast.makeText(imageViewerActivity,
								"Exception:" + e.getMessage(),
								Toast.LENGTH_LONG);
						toast.show();
					}
				});

			}

			final Drawable finalImage = image;
			imageViewerActivity.runOnUiThread(new Runnable() {
				public void run() {
					Log.d(getClass().getName(),
							"Start show image: " + System.currentTimeMillis());
					imageViewerActivity.showImage(finalImage);
					Log.d(getClass().getName(),
							"End show image: " + System.currentTimeMillis());
				}
			});

		}
	}

	/**
	 * @param imageUri
	 * @return
	 * @throws FileNotFoundException
	 * @throws IOException
	 * @throws MalformedURLException
	 */
	private InputStream getUriAsStream(Uri imageUri)
			throws FileNotFoundException, IOException, MalformedURLException {
		InputStream is = null;
		Log.d(getClass().getName(), "Start load: " + System.currentTimeMillis());
		if (ContentResolver.SCHEME_CONTENT.equals(imageUri.getScheme())) {
			is = imageViewerActivity.getContentResolver().openInputStream(
					imageUri);
		} else {
			is = (InputStream) new java.net.URL(imageUri.toString())
					.getContent();
		}
		Log.d(getClass().getName(), "Stop load: " + System.currentTimeMillis());
		Log.d(getClass().getName(), "InputStream: " + is);
		return is;
	}

	private Bitmap decodeSampledBitmapFromStream(Uri imageUri, int reqWidth,
												 int reqHeight) throws IOException {
		InputStream is = getUriAsStream(imageUri);
		int tmpWidth = reqWidth;
		int tmpHeight = reqHeight;
		if (reqHeight > 2048) {
			tmpHeight = 2048;
			tmpWidth = reqHeight * (2048 / reqWidth);
		}
		if (reqWidth > 2048) {
			tmpWidth = 2048;
			tmpHeight = reqWidth * (2048 / reqHeight);
		}

		final BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = false;
		options.outHeight = tmpHeight;
		options.outWidth = tmpWidth;
		options.inPreferQualityOverSpeed = false;
		options.inDensity = DisplayMetrics.DENSITY_LOW;
		options.inTempStorage = new byte[7680016];
		Log.d(this.getClass().getName(),
				"displaying image size width, height, inSampleSize "
						+ options.outWidth + "," + options.outHeight + ","
						+ options.inSampleSize);
		Log.d(this.getClass().getName(), "free meomory before image load: "
				+ Runtime.getRuntime().freeMemory());
		Bitmap bitmap = BitmapFactory.decodeStream(new FlushedInputStream(is),
				null, options);
		Log.d(this.getClass().getName(), "free meomory after image load: "
				+ Runtime.getRuntime().freeMemory());
		return bitmap;
	}

	static class FlushedInputStream extends FilterInputStream {
		public FlushedInputStream(InputStream inputStream) {
			super(inputStream);
		}

		@Override
		public long skip(long n) throws IOException {
			long totalBytesSkipped = 0L;
			while (totalBytesSkipped < n) {
				long bytesSkipped = in.skip(n - totalBytesSkipped);
				if (bytesSkipped == 0L) {
					int byte_ = read();
					if (byte_ < 0) {
						break; // we reached EOF
					} else {
						bytesSkipped = 1; // we read one byte
					}
				}
				totalBytesSkipped += bytesSkipped;
			}
			return totalBytesSkipped;
		}
	}

}
