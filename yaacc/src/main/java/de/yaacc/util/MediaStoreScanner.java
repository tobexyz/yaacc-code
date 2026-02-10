/*
 *
 * Copyright (C) 2023 Tobias Schoene www.yaacc.de
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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.yaacc.R;

public class MediaStoreScanner {

    public MediaStoreScanner() {

    }

    public void scanMediaFiles(Activity context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                checkPermissions(context);
                return;
            }
        }
        
        context.runOnUiThread(() -> Toast.makeText(context,
                context.getString(R.string.media_store_scanner_scan_triggered),
                Toast.LENGTH_LONG).show());

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            scanVolumesModern(context);
        } else {
            scanFilesLegacy(context);
        }
    }

    @androidx.annotation.RequiresApi(api = android.os.Build.VERSION_CODES.R)
    private void scanVolumesModern(Activity context) {
        StorageManager storageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> volumes = storageManager.getStorageVolumes();
        
        YaaccLogger.i(getClass().getName(), "Found " + volumes.size() + " storage volumes");
        
        Collection<String> pathsToScan = new ArrayList<>();
        
        for (StorageVolume volume : volumes) {
            String volumeName = volume.getMediaStoreVolumeName();
            YaaccLogger.i(getClass().getName(), "Volume: " + volumeName + 
                    " (" + volume.getDescription(context) + ")" +
                    " removable=" + volume.isRemovable() +
                    " state=" + volume.getState());
            
            if (volumeName != null && "mounted".equals(volume.getState())) {
                File volumeDir = volume.getDirectory();
                if (volumeDir != null && volumeDir.exists() && volumeDir.canRead()) {
                    YaaccLogger.i(getClass().getName(), "Scanning volume directory: " + volumeDir.getAbsolutePath());
                    pathsToScan.add(volumeDir.getAbsolutePath());
                    for (File file : recursiveListFiles(volumeDir)) {
                        pathsToScan.add(file.getAbsolutePath());
                    }
                } else {
                    YaaccLogger.w(getClass().getName(), "Cannot access volume directory");
                }
            }
        }
        
        YaaccLogger.i(getClass().getName(), "Total files to scan: " + pathsToScan.size());
        
        final Point filesSize = new Point(pathsToScan.size(), pathsToScan.size());
        MediaScannerConnection.scanFile(context, pathsToScan.toArray(new String[0]), null, (String path, Uri uri) -> {
            filesSize.x--;
            if (filesSize.x <= 0) {
                context.runOnUiThread(() -> {
                    Toast.makeText(context,
                            context.getResources().getString(R.string.media_store_scanner_scan_finished),
                            Toast.LENGTH_LONG).show();
                });
                
                // Debug: Query MediaStore for all volumes
                queryMediaStoreDebug(context);
            }
        });
    }
    
    @androidx.annotation.RequiresApi(api = android.os.Build.VERSION_CODES.R)
    private void queryMediaStoreDebug(Activity context) {
        StorageManager storageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> volumes = storageManager.getStorageVolumes();
        
        for (StorageVolume volume : volumes) {
            String volumeName = volume.getMediaStoreVolumeName();
            if (volumeName != null) {
                Uri uri = MediaStore.Audio.Media.getContentUri(volumeName);
                try (android.database.Cursor cursor = context.getContentResolver().query(
                        uri, 
                        new String[]{MediaStore.Audio.Media._ID}, 
                        null, null, null)) {
                    int count = cursor != null ? cursor.getCount() : 0;
                    YaaccLogger.i(getClass().getName(), "MediaStore volume " + volumeName + 
                            " (" + volume.getDescription(context) + "): " + count + " audio files");
                } catch (Exception e) {
                    YaaccLogger.e(getClass().getName(), "Error querying volume " + volumeName, e);
                }
            }
        }
    }

    private void scanFilesLegacy(Activity context) {
        Collection<File> dirsToScan = new ArrayList<>();
        
        File storageDir = new File("/storage");
        File[] storageMounts = storageDir.listFiles();
        
        YaaccLogger.i(getClass().getName(), "Scanning /storage directory");
        
        if (storageMounts != null) {
            YaaccLogger.i(getClass().getName(), "Found " + storageMounts.length + " mounts");
            for (File mount : storageMounts) {
                if (mount.isDirectory() && mount.canRead() && 
                    !mount.getName().equals("self") && !mount.getName().equals("emulated")) {
                    YaaccLogger.i(getClass().getName(), "Scanning: " + mount.getAbsolutePath());
                    dirsToScan.add(mount);
                    dirsToScan.addAll(recursiveListFiles(mount));
                }
            }
        }
        
        File primaryStorage = new File("/storage/emulated/0");
        if (primaryStorage.exists() && primaryStorage.canRead()) {
            YaaccLogger.i(getClass().getName(), "Scanning primary storage");
            dirsToScan.add(primaryStorage);
            dirsToScan.addAll(recursiveListFiles(primaryStorage));
        }

        YaaccLogger.i(getClass().getName(), "Total files to scan: " + dirsToScan.size());

        final Point filesSize = new Point(dirsToScan.size(), dirsToScan.size());
        MediaScannerConnection.scanFile(context, dirsToScan.stream().map(File::getAbsolutePath).toArray(String[]::new), null, (String path, Uri uri) -> {
            filesSize.x--;
            if (filesSize.x <= 0) {
                context.runOnUiThread(() -> {
                    Toast.makeText(context,
                            context.getResources().getString(R.string.media_store_scanner_scan_finished),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void checkPermissions(Activity context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                context.runOnUiThread(() -> {
                    Toast.makeText(context, "All files access required for USB scanning. Please grant in settings.", Toast.LENGTH_LONG).show();
                });
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                context.startActivity(intent);
            }
        }
    }


    public List<File> recursiveListFiles(File directory) {
        File[] files = directory.listFiles();
        List<File> result = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    result.add(file);
                } else if (file.isDirectory()) {
                    result.addAll(recursiveListFiles(file));
                }
            }
        }
        return result;
    }


}
