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
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.ui.SnapcastClientCandidates;
import de.yaacc.multiroom.ui.SnapcastControls;
import de.yaacc.multiroom.ui.SnapcastMemberRows;
import de.yaacc.multiroom.ui.SnapcastRowText;
import de.yaacc.multiroom.ui.SnapcastToastGate;

/**
 * What the Snapcast rows and the group sheet share: the one {@link SnapcastControls} of the app (two daemon threads, one
 * for volume gestures and one for everything else), the failure toasts (at most one every few seconds), and the words of
 * the presentation classes backed by string resources. Thin Android glue; the decisions are in the classes of
 * {@code de.yaacc.multiroom.ui}.
 */
final class SnapcastUi {

    private static final long TOAST_GAP_MILLIS = 3_000L;

    private static SnapcastControls controls;

    private SnapcastUi() {
    }

    /** The controls of the app; they use the connections that {@link SnapcastHub} holds. */
    static synchronized SnapcastControls controls(Context context) {
        if (controls == null) {
            final Context app = context.getApplicationContext();
            final Handler main = new Handler(Looper.getMainLooper());
            final SnapcastToastGate gate = new SnapcastToastGate(MonotonicClock.SYSTEM, TOAST_GAP_MILLIS);
            controls = new SnapcastControls(SnapcastHub.getShared(app).getReceivers()::getLink,
                    daemonExecutor("snapcast-actions"), daemonExecutor("snapcast-volume"), MonotonicClock.SYSTEM,
                    (action, failure) -> {
                        if (gate.allow()) {
                            final String message = SnapcastFailureTexts.message(failure, id -> app.getString(id));
                            main.post(() -> Toast.makeText(app, message, Toast.LENGTH_LONG).show());
                        }
                    });
        }
        return controls;
    }

    private static Executor daemonExecutor(final String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** The words of a group row. */
    static SnapcastRowText.Words rowWords(final Context context) {
        return new SnapcastRowText.Words() {
            @Override
            public String stream(String name) {
                return context.getString(R.string.snapcast_row_stream, name);
            }

            @Override
            public String noStream() {
                return context.getString(R.string.snapcast_row_no_stream);
            }

            @Override
            public String clientsConnected(int connected, int total) {
                return context.getString(R.string.snapcast_row_clients_connected, connected, total);
            }

            @Override
            public String noClients() {
                return context.getString(R.string.snapcast_row_no_clients);
            }

            @Override
            public String volume(int percent) {
                return context.getString(R.string.snapcast_row_volume, percent);
            }

            @Override
            public String muted() {
                return context.getString(R.string.snapcast_row_muted);
            }

            @Override
            public String serverUnreachable() {
                return context.getString(R.string.snapcast_row_server_unreachable);
            }

            @Override
            public String streamUnavailable() {
                return context.getString(R.string.snapcast_row_stream_unavailable);
            }

            @Override
            public String server(String endpoint) {
                return context.getString(R.string.snapcast_row_server, endpoint);
            }

            @Override
            public String groupGone() {
                return context.getString(R.string.snapcast_row_group_gone);
            }
        };
    }

    /** The label of a member that has neither a name nor a host name. */
    static SnapcastMemberRows.Words memberWords(final Context context) {
        return () -> context.getString(R.string.snapcast_member_unnamed);
    }

    /** The labels of nameless clients and groups in the "add client" list. */
    static SnapcastClientCandidates.Words candidateWords(final Context context) {
        return new SnapcastClientCandidates.Words() {
            @Override
            public String unnamedClient() {
                return context.getString(R.string.snapcast_member_unnamed);
            }

            @Override
            public String unnamedGroup() {
                return context.getString(R.string.snapcast_group);
            }
        };
    }
}
