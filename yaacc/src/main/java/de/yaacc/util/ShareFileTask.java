/*
 *
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
package de.yaacc.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;

import de.yaacc.R;

/**
 * Downloads a single browsed item's resource to the app's cache (not the
 * user-visible Downloads folder - this copy only exists to be handed to
 * another app, not for the user to keep) and hands it off to the system
 * share sheet as a {@code content://} URI via {@link FileProvider}, rather
 * than just sharing the item's resource URL as text - the URL is often
 * only reachable on the local network (this device's own embedded UPnP
 * server, or another server on the same LAN), so the receiving app/person
 * frequently can't actually resolve it.
 *
 * @see de.yaacc.browser.BrowseContentItemAdapter
 */
public class ShareFileTask extends AsyncTask<Void, Void, File> {

    private final Context context;
    private final String resourceUrl;
    private final String title;
    private final String mimeType;
    private Exception error;

    public ShareFileTask(Context context, String resourceUrl, String title, String mimeType) {
        this.context = context.getApplicationContext();
        this.resourceUrl = resourceUrl;
        this.title = title;
        this.mimeType = mimeType;
    }

    @Override
    protected File doInBackground(Void... voids) {
        try {
            File shareDir = new File(context.getCacheDir(), "shared");
            if (!shareDir.exists() && !shareDir.mkdirs()) {
                throw new IllegalStateException("Can't create share cache directory: " + shareDir);
            }
            String filename = (title == null || title.isEmpty()) ? "shared_item" : title.replace(" ", "_");
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (extension != null) {
                filename += "." + extension;
            }
            File file = new File(shareDir, filename);
            try (InputStream in = new URL(resourceUrl).openStream();
                 FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
            return file;
        } catch (Exception e) {
            error = e;
            return null;
        }
    }

    @Override
    protected void onPostExecute(File file) {
        if (file == null) {
            Toast.makeText(context, context.getString(R.string.browse_share_failed,
                    error != null ? error.getMessage() : "unknown error"), Toast.LENGTH_SHORT).show();
            return;
        }
        Uri contentUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(mimeType != null ? mimeType : "*/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent chooser = Intent.createChooser(shareIntent, context.getString(R.string.browse_context_share));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
    }
}
