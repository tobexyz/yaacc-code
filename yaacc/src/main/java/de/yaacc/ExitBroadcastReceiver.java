/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ExitBroadcastReceiver extends BroadcastReceiver {

    public static final String ACTION_EXIT = "de.yaacc.ACTION_EXIT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_EXIT.equals(intent.getAction())) {
            ((Yaacc) context.getApplicationContext()).exit();
        }
    }
}
