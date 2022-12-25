/*
 *
 * Copyright (C) 2014 www.yaacc.de
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

import android.app.NotificationManager;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Environment;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.item.Item;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

import de.yaacc.R;
import de.yaacc.player.PlayableItem;
import de.yaacc.upnp.UpnpClient;

/**
 * @author Tobias Schoene (theopenbit)
 */
public class FileDownloader extends AsyncTask<DIDLObject, Void, Void> {

    UpnpClient upnpClient;

    public FileDownloader(UpnpClient upnpClient) {
        this.upnpClient = upnpClient;
    }

    @Override
    protected Void doInBackground(DIDLObject... didlObjects) {
        if (didlObjects == null || didlObjects.length == 0 || didlObjects.length > 1) {
            throw new IllegalStateException("to less or many didlObjects....");
        }
        if (!isExternalStorageWritable()) {
            throw new IllegalStateException("External Storage is not writeable");
        }

        try {
            File storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/yaacc");
            if (!storageDir.exists()) {
                storageDir.mkdir();
            }
            createNotification(storageDir.getAbsolutePath());
            List<Item> items = upnpClient.toItemList(didlObjects[0]);
            for (Item item : items) {
                PlayableItem playableItem = new PlayableItem(item, 0);
                String filename = playableItem.getTitle().replace(" ", "");
                filename += "." + MimeTypeMap.getSingleton().getExtensionFromMimeType(playableItem.getMimeType());
                File file = new File(storageDir, filename);
                if (file.exists()) {
                    int i = 1;
                    while (file.exists()) {
                        filename = playableItem.getTitle().replace(" ", "") + "_" + i;
                        filename += "." + MimeTypeMap.getSingleton().getExtensionFromMimeType(playableItem.getMimeType());
                        file = new File(storageDir, filename);
                        i++;
                    }
                }

                try {

                    InputStream is = new URL(playableItem.getUri().toString()).openStream();
                    FileOutputStream outputStream = new FileOutputStream(file);
                    byte[] b = new byte[1024];
                    int len = 0;
                    while ((len = is.read(b)) != -1) {
                        outputStream.write(b, 0, len);
                    }
                    is.close();
                    outputStream.close();

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            }
        } finally {
            cancleNotification();
        }
        return null;
    }


    /* Checks if external storage is available for read and write */
    private boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    /* Checks if external storage is available to at least read */
    private boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state) ||
                Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            return true;
        }
        return false;
    }

    /**
     * Cancels the notification.
     * @param outdir name of the output dir
     */
    private void createNotification(String outdir) {
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
                upnpClient.getContext()).setOngoing(true)
                .setSmallIcon(R.drawable.ic_notification_default)
                .setContentTitle("Yaacc file download")
                .setContentText("download to: " + outdir );

        NotificationManager mNotificationManager = (NotificationManager) upnpClient.getContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        mNotificationManager.notify(NotificationId.FILE_DOWNLOADER.getId(), mBuilder.build());
    }

    /**
     * Cancels the notification.
     */
    private void cancleNotification() {
        NotificationManager mNotificationManager = (NotificationManager) upnpClient.getContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        Log.d(getClass().getName(), "Cancle Notification with ID: " + NotificationId.FILE_DOWNLOADER.getId());
        mNotificationManager.cancel(NotificationId.FILE_DOWNLOADER.getId());

    }
}
