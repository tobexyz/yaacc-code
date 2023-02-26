/*
 * Copyright (C) 2019 Tobias Schoene www.yaacc.de
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

package de.yaacc.browser;

import android.os.AsyncTask;

import org.fourthline.cling.support.model.DIDLObject;

public class ContentItemPlayTask extends AsyncTask<Integer, Void, Void> {
    public final static int PLAY_CURRENT = 0;
    public final static int PLAY_ALL = 1;
    private final ContentListFragment parent;
    private final DIDLObject currentObject;


    public ContentItemPlayTask(ContentListFragment parent, DIDLObject currentObject) {
        this.parent = parent;
        this.currentObject = currentObject;
    }

    @Override
    public Void doInBackground(Integer... integers) {
        if (integers == null || integers.length != 1) {
            return null;
        }
        if (integers[0] == PLAY_CURRENT) {
            parent.playItem(currentObject);
        } else if (integers[0] == PLAY_ALL) {
            parent.playAllChildsOfParentFrom(currentObject);
        }
        return null;
    }
}
