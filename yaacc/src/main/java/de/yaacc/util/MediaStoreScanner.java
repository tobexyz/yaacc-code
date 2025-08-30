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
import android.graphics.Point;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import de.yaacc.R;

public class MediaStoreScanner {

    public MediaStoreScanner() {

    }

    public void scanMediaFiles(Activity context) {
        Toast.makeText(context,
                context.getString(R.string.media_store_scanner_scan_triggered),
                Toast.LENGTH_SHORT).show();

        Collection<File> dirsToScan = new ArrayList<>();
        dirsToScan.add(Environment.getExternalStorageDirectory());
        dirsToScan.addAll(recursiveListFiles(Environment.getExternalStorageDirectory()));
        final Point filesSize = new Point(dirsToScan.size(), dirsToScan.size());
        MediaScannerConnection.scanFile(context, dirsToScan.stream().map(it -> it.getAbsolutePath()).collect(Collectors.toList()).toArray(new String[0]), null, (String path, Uri uri) -> {
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
