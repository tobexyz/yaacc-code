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
package de.yaacc.sendspin;

import android.content.Context;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.UUID;
import java.util.function.Consumer;

import de.yaacc.R;

/**
 * Scans a QR code with the camera (issue #325, K12): the scanner of the library asks for the camera permission when it opens. The
 * result comes back through the activity's registry, which is used on the tap, so that no field has to exist before the activity
 * starts. Nothing happens without an activity that can take a result.
 */
public final class QrScan {

    private QrScan() {
    }

    /** @param onText called on the main thread with the text of the code; not called if the scan was cancelled */
    public static void scan(Context context, final Consumer<String> onText) {
        if (!(context instanceof ComponentActivity)) {
            return;
        }
        @SuppressWarnings("unchecked") final ActivityResultLauncher<ScanOptions>[] launcher = new ActivityResultLauncher[1];
        launcher[0] = ((ComponentActivity) context).getActivityResultRegistry().register("sendspin-scan-" + UUID.randomUUID(),
                new ScanContract(), result -> {
                    launcher[0].unregister();
                    String text = result == null ? null : result.getContents();
                    if (text != null && !text.isEmpty()) {
                        onText.accept(text);
                    }
                });
        launcher[0].launch(new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(context.getString(R.string.sendspin_pair_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false));
    }
}
