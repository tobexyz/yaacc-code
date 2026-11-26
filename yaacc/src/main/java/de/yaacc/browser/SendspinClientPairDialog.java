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
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import de.yaacc.R;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.SendspinReceiverDevice;
import de.yaacc.sendspin.SendspinRowText;
import de.yaacc.sendspin.client.ControllerSession;
import de.yaacc.settings.SensitiveClip;

/**
 * Pairing with a Sendspin server (this app as a client): the token this app gives to the server (the operator pastes it into the
 * server), and the buttons to copy it, to make a new one, to connect again once the server has it and to forget a pairing. The token
 * is a secret and the clip that carries it is marked sensitive where the system knows the mark.
 */
final class SendspinClientPairDialog {

    private SendspinClientPairDialog() {
    }

    /** The token of this app for a server that dials the phone (the phone as a player): shown and copied; nothing to connect to here. */
    static void showToken(final Context context) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        final SendspinHub hub = SendspinHub.getShared(context);
        final String token = hub.clientPairingToken();
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);
        TextView message = new TextView(context);
        message.setText(R.string.sendspin_selfpair_message);
        message.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        root.addView(message);
        TextView idLine = new TextView(context);
        idLine.setText(context.getString(R.string.sendspin_clientpair_id, SendspinPairDialog.shortId(hub.clientKeys().id())));
        idLine.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        root.addView(idLine);
        TextView shown = new TextView(context);
        shown.setText(token);
        shown.setTypeface(Typeface.MONOSPACE);
        shown.setTextIsSelectable(true);
        shown.setPadding(0, (int) (12 * density), 0, (int) (8 * density));
        root.addView(shown, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.sendspin_self_pair_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.sendspin_clientpair_copy, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(SensitiveClip.plainText("Sendspin pairing token", token));
                Toast.makeText(context, R.string.sendspin_clientpair_copied, Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.show();
    }

    static void show(final Context context, final SendspinReceiverDevice device) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        final SendspinHub hub = SendspinHub.getShared(context);
        final ControllerSession session = hub.controllers().session(device.getEndpoint());
        final String serverId = session == null ? null : session.serverId();
        final String token = hub.clientPairingToken();
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);
        TextView message = new TextView(context);
        message.setText(R.string.sendspin_clientpair_message);
        message.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        root.addView(message);
        TextView shown = new TextView(context);
        shown.setText(token);
        shown.setTypeface(Typeface.MONOSPACE);
        shown.setTextIsSelectable(true);
        shown.setPadding(0, (int) (12 * density), 0, (int) (8 * density));
        root.addView(shown, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (serverId != null && hub.isPairedWithServer(serverId)) {
            TextView paired = new TextView(context);
            paired.setText(R.string.sendspin_clientpair_paired);
            root.addView(paired);
        }
        final AlertDialog[] holder = new AlertDialog[1];
        android.widget.Button fresh = new android.widget.Button(context);
        fresh.setText(R.string.sendspin_clientpair_new);
        fresh.setOnClickListener(v -> {
            hub.withdrawClientToken(); // the token that was shown no longer works
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            show(context, device);
        });
        root.addView(fresh);
        if (serverId != null && hub.isPairedWithServer(serverId)) {
            android.widget.Button forget = new android.widget.Button(context);
            forget.setText(R.string.sendspin_clientpair_forget);
            forget.setOnClickListener(v -> {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                hub.unpairServer(serverId);
            });
            root.addView(forget);
        }
        String name = session == null ? "" : SendspinRowText.clean(session.serverName());
        final AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.sendspin_clientpair_title, name.isEmpty() ? device.getName() : name))
                .setView(root)
                .setPositiveButton(R.string.sendspin_clientpair_connect, (d, w) -> hub.controllers().acquire(device.getEndpoint(), true))
                .setNeutralButton(R.string.sendspin_clientpair_copy, null)
                .setNegativeButton(android.R.string.cancel, null);
        final AlertDialog dialog = builder.create();
        holder[0] = dialog;
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(SensitiveClip.plainText("Sendspin pairing token", token));
                Toast.makeText(context, R.string.sendspin_clientpair_copied, Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.show();
    }
}
