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
package de.yaacc.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.yaacc.R;
import de.yaacc.sendspin.QrCodes;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.player.ClientCodePairing;

/**
 * The part of a sheet or dialog that lets this phone be paired with a code, as a player or as a client of a server (issue #325, task
 * 11.1): the button that opens the pairing window, how long it stays open, the code of the attempt that runs and the fixed code.
 */
final class SendspinCodeSection {

    private SendspinCodeSection() {
    }

    /**
     * Pairing with a code (task 11.1): the button that opens the pairing window on this phone, how long it stays open, the code of the
     * attempt that runs (digits and QR code) and the fixed code. The sheet follows the state once a second while it is open.
     */
    static void add(final Context context, LinearLayout root, final Handler main, float density) {
        final SendspinHub hub = SendspinHub.getShared(context);
        final ClientCodePairing pairing = hub.clientCodePairing();
        TextView title = new TextView(context);
        title.setText(R.string.sendspin_self_code_title);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        title.setPadding(0, (int) (12 * density), 0, 0);
        root.addView(title);
        TextView explain = new TextView(context);
        explain.setText(R.string.sendspin_self_code_explain);
        root.addView(explain);
        Button open = new Button(context);
        open.setText(R.string.sendspin_self_code_open);
        open.setOnClickListener(v -> hub.openPairingWindow());
        root.addView(open);
        final TextView state = new TextView(context);
        root.addView(state);
        final TextView shown = new TextView(context);
        shown.setTextAppearance(android.R.style.TextAppearance_Material_Large);
        root.addView(shown);
        final ImageView qr = new ImageView(context);
        qr.setVisibility(android.view.View.GONE);
        root.addView(qr);
        final TextView fixed = new TextView(context);
        String staticCode = pairing.offer().staticCode();
        fixed.setText(staticCode == null ? "" : context.getString(R.string.sendspin_self_code_static,
                staticCode.substring(0, 4) + "-" + staticCode.substring(4)));
        root.addView(fixed);
        final int side240 = (int) (240 * density);
        final String[] drawn = new String[1];
        Runnable refresh = new Runnable() {
            @Override
            public void run() {
                boolean isOpen = pairing.window().isOpen();
                long left = pairing.window().remainingMillis();
                state.setText(isOpen ? context.getString(R.string.sendspin_self_code_open_status,
                        String.format(java.util.Locale.ROOT, "%d:%02d", left / 60000, (left / 1000) % 60))
                        : context.getString(R.string.sendspin_self_code_closed_status));
                ClientCodePairing.Shown code = pairing.shown();
                if (code == null) {
                    shown.setText(isOpen ? context.getString(R.string.sendspin_self_code_waiting) : "");
                    qr.setVisibility(android.view.View.GONE);
                    drawn[0] = null;
                } else {
                    shown.setText(code.digits == null ? context.getString(R.string.sendspin_self_code_shown_qr)
                            : context.getString(R.string.sendspin_self_code_shown, code.digits));
                    if (code.qrToken != null) {
                        if (!code.qrToken.equals(drawn[0])) {
                            boolean[][] modules = QrCodes.modules(code.qrToken);
                            int scale = Math.max(1, side240 / QrCodes.sizeOf(modules.length, 1));
                            int side = QrCodes.sizeOf(modules.length, scale);
                            qr.setImageBitmap(Bitmap.createBitmap(QrCodes.pixels(modules, scale), side, side, Bitmap.Config.ARGB_8888));
                            drawn[0] = code.qrToken;
                        }
                        qr.setVisibility(android.view.View.VISIBLE);
                    } else {
                        qr.setVisibility(android.view.View.GONE);
                    }
                }
                main.postDelayed(this, 1000L);
            }
        };
        main.post(refresh);
    }
}
