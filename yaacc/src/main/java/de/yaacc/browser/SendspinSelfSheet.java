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

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

import de.yaacc.R;
import de.yaacc.sendspin.QrCodes;
import de.yaacc.sendspin.SendspinReceiverDevice;
import de.yaacc.sendspin.SendspinRowText;
import de.yaacc.sendspin.SendspinSettings;
import de.yaacc.sendspin.player.ClientCodePairing;
import de.yaacc.sendspin.player.SendspinPlayerHost;
import de.yaacc.sendspin.player.SendspinPlayerRuntime;
import de.yaacc.sendspin.player.SendspinPlayerService;

/** The sheet of the gear on the card of this phone as a player: the servers that are connected (each can be disconnected) and the switch-off. */
final class SendspinSelfSheet {

    private SendspinSelfSheet() {
    }

    static void show(final Context context, SendspinReceiverDevice device) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);
        final SendspinPlayerHost host = SendspinPlayerRuntime.host();
        final AlertDialog[] dialog = new AlertDialog[1];
        List<SendspinPlayerHost.SessionInfo> servers = host == null ? java.util.Collections.<SendspinPlayerHost.SessionInfo>emptyList()
                : host.sessionInfos();
        if (servers.isEmpty()) {
            TextView none = new TextView(context);
            none.setText(R.string.sendspin_self_sheet_none);
            root.addView(none);
        }
        for (int i = 0; i < servers.size(); i++) {
            final int index = i;
            String name = SendspinRowText.clean(servers.get(i).serverName);
            Button disconnect = new Button(context);
            disconnect.setText(context.getString(R.string.sendspin_self_disconnect, name.isEmpty() ? "?" : name));
            disconnect.setOnClickListener(v -> {
                host.disconnect(index);
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
            });
            root.addView(disconnect);
        }
        Button token = new Button(context);
        token.setText(R.string.sendspin_self_pairing);
        token.setOnClickListener(v -> {
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
            SendspinClientPairDialog.showToken(context);
        });
        root.addView(token);
        final Handler main = new Handler(Looper.getMainLooper());
        if (host != null && host.codePairing() != null) {
            addCodePairing(context, root, host, main, density);
        }
        Button off = new Button(context);
        off.setText(R.string.sendspin_self_switch_off);
        off.setOnClickListener(v -> {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            preferences.edit().putBoolean(SendspinSettings.KEY_PLAYER, false).apply();
            SendspinPlayerService.sync(context);
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
        });
        root.addView(off);
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(root);
        dialog[0] = new AlertDialog.Builder(context)
                .setTitle(device.getName())
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog[0].setOnDismissListener(d -> main.removeCallbacksAndMessages(null));
        dialog[0].show();
    }

    /**
     * Pairing with a code (task 11.1): the button that opens the pairing window on this phone, how long it stays open, the code of the
     * attempt that runs (digits and QR code) and the fixed code. The sheet follows the state once a second while it is open.
     */
    private static void addCodePairing(final Context context, LinearLayout root, final SendspinPlayerHost host, final Handler main,
                                       float density) {
        final ClientCodePairing pairing = host.codePairing();
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
        open.setOnClickListener(v -> host.openPairingWindow());
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
