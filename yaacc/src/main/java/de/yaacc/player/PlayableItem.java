/*
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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
package de.yaacc.player;

import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.item.Item;

/**
 * representation of an item which is to be played
 *
 * @author Tobias Schoene (openbit)
 */
public class PlayableItem {

    private final Item item;
    private String mimeType;
    private String title;
    private Uri uri;
    private long duration;


    public PlayableItem(Item item, int defaultDuration) {
        this.item = item;
        setTitle(item.getTitle());
        Res resource = item.getFirstResource();
        if (resource != null) {
            setUri(Uri.parse(resource.getValue()));
            String mimeType = resource.getProtocolInfo().getContentFormat();
            if (mimeType == null || mimeType.equals("")) {
                String fileExtension = MimeTypeMap.getFileExtensionFromUrl(getUri().toString());
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension);
            }
            setMimeType(mimeType);

            // calculate duration

            long millis = defaultDuration;
            Log.d(getClass().getName(), "resource.getDuration(): " + resource.getDuration());
            if (resource.getDuration() != null) {
                try {
                    String[] tokens = resource.getDuration().split(":");
                    if (tokens.length > 0) {
                        millis = Long.parseLong(tokens[0]) * 3600;
                    }
                    if (tokens.length > 1) {
                        millis += Long.parseLong(tokens[1]) * 60;
                    }
                    if (tokens.length > 2) {
                        String seconds = tokens[2];
                        if (tokens[2].contains(".")) {
                            Log.d(getClass().getName(), "tokens[2]: " + tokens[2] + "spli: " + tokens[2].split("\\.").length);
                            seconds = tokens[2].split("\\.")[0];
                        }
                        millis += Long.parseLong(seconds);
                    }
                    millis = millis * 1000;
                    Log.d(getClass().getName(), "resource.getDuration(): " + resource.getDuration() + " millis: " + millis);

                } catch (Exception e) {
                    Log.d(getClass().getName(), "bad duration format", e);
                }
            }
            setDuration(millis);
        }
    }

    /**
     *
     */
    public PlayableItem() {
        mimeType = "";
        title = "";
        uri = null;
        duration = 0;
        item = null;
    }


    /**
     * @return the mimeType
     */
    public String getMimeType() {
        return mimeType;
    }


    /**
     * @param mimeType the mimeType to set
     */
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }


    /**
     * @return the title
     */
    public String getTitle() {
        return title;
    }


    /**
     * @param title the title to set
     */
    public void setTitle(String title) {
        this.title = title;
    }


    /**
     * @return the uri
     */
    public Uri getUri() {
        return uri;
    }


    /**
     * @param uri the uri to set
     */
    public void setUri(Uri uri) {
        this.uri = uri;
    }


    /**
     * Duration in milliseconds.
     *
     * @return the duration
     */
    public long getDuration() {
        return duration;
    }


    /**
     * Duration in milliseconds.
     *
     * @param duration the duration to set
     */
    public void setDuration(long duration) {
        this.duration = duration;
    }


    public Item getItem() {
        return item;

    }
}
