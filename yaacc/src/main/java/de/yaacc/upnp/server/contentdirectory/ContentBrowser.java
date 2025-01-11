/*
 *
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
package de.yaacc.upnp.server.contentdirectory;

import android.content.Context;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;
import org.seamless.util.MimeType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import de.yaacc.upnp.server.YaaccUpnpServerService;


/**
 * Super class for all contentent directory browsers.
 *
 * @author openbit (Tobias Schoene)
 */
public abstract class ContentBrowser {

    Context context;

    protected ContentBrowser(Context context) {
        this.context = context;
    }

    public Context getContext() {
        return context;
    }


    public abstract Integer getSize(YaaccContentDirectory contentDirectory, String myId);

    public abstract DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby);

    public abstract List<Container> browseContainer(
            YaaccContentDirectory content, String myId, long firstResult, long maxResults, SortCriterion[] orderby);

    public abstract List<? extends Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby);

    public List<DIDLObject> browseChildren(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<DIDLObject> result = new ArrayList<>();
        result.addAll(browseContainer(contentDirectory, myId, firstResult, maxResults, orderby));
        result.addAll(browseItem(contentDirectory, myId, firstResult, maxResults, orderby));
        return result;
    }

    public String getUriString(YaaccContentDirectory contentDirectory, String id, MimeType mimeType) {
        String fileExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.toString());
        if (fileExtension == null) {
            Log.d(getClass().getName(), "Can't lookup file extension from mimetype: " + mimeType);
            //try subtype
            fileExtension = mimeType.getSubtype();

        }
        return "http://" + contentDirectory.getIpAddress() + ":"
                + YaaccUpnpServerService.PORT + "/res/" + id + "/file." + fileExtension;
    }

    public String getDLNAAttributes(MimeType mimetype) {
        String result = "DLNA.ORG_PN=";

        if ("audio".equals(mimetype.getType())) {
            if ("mpeg".equals(mimetype.getSubtype())) {
                result = result + "MP3";
            } else if ("L16".equals(mimetype.getSubtype())) {
                result = result + "LPCM";
            } else if ("x-ms-wma".equals(mimetype.getSubtype())) {
                result = result + "WMABASE";
            } else if ("vnd.dlna.adts".equals(mimetype.getSubtype())) {
                result = result + "ADTS";
            } else if ("mp4".equals(mimetype.getSubtype())) {
                result = result + "AAC_ISO";
            } else if ("vnd.dolby.dd-raw".equals(mimetype.getSubtype())) {
                result = result + "AC3";
            } else if ("3gpp".equals(mimetype.getSubtype())) {
                result = result + "AMR_3GPP";
            } else if ("x-sony-oma".equals(mimetype.getSubtype())) {
                result = result + "ATRAC3plus";
            } else {
                result = result + "*";
            }
        } else if ("image".equals(mimetype.getType())) {
            if ("jpeg".equals(mimetype.getSubtype())) {
                result = result + "JPEG_LRG";
            } else if ("png".equals(mimetype.getSubtype())) {
                result = result + "PNG_LRG";
            } else {
                result = result + "*";
            }
        } else if ("video".equals(mimetype.getType())) {
            if ("x-ms-wmv".equals(mimetype.getSubtype())) {
                result = result + "WMVMED_BASE";
            } else if ("avi".equals(mimetype.getSubtype())) {
                result = result + "AVI";
            } else if ("divx".equals(mimetype.getSubtype())) {
                result = result + "AVI";
            } else if ("mpeg".equals(mimetype.getSubtype())) {
                result = result + "MPEG1";
            } else if ("vnd.dlna.mpeg-tts".equals(mimetype.getSubtype())) {
                result = result + "MPEG_TS_MP_LL_AAC";
            } else if ("mp4".equals(mimetype.getSubtype())) {
                result = result + "MPEG4_P2_MP4_SP_AAC";
            } else if ("3gpp".equals(mimetype.getSubtype())) {
                result = result + "MPEG4_H263_MP4_P0_L10_AAC";
            } else if ("x-matroska".equals(mimetype.getSubtype())) {
                result = result + "MATROSKA";
            } else if ("mkv".equals(mimetype.getSubtype())) {
                result = result + "MATROSKA";
            } else if ("x-ms-asf".equals(mimetype.getSubtype())) {
                result = result + "VC1_ASF_AP_L2_WMA";
            } else if ("x-ms-mwv".equals(mimetype.getSubtype())) {
                result = result + "VC1_ASF_AP_L2_WMA";
            } else {
                result = result + "*";
            }
        }
        result = result + ";DLNA.ORG_OP=01";
        return result;
    }


    protected String makeLikeClause(String column, int len) {
        StringBuilder sb = new StringBuilder();
        sb.append(column);
        sb.append(" like ?");
        for (int i = 1; i < len; i++) {
            sb.append(" or ");
            sb.append(column);
            sb.append(" like ? ");
        }
        return sb.toString();
    }

    protected List<String> getMediaPathesForLikeClause() {
        return getMediaPathes().stream().map(it -> "%" + it + "%").collect(Collectors.toList());
    }

    protected List<String> getMediaPathes() {
        List<String> result = new ArrayList<>();
        result.add("DCIM/CAMERA");
        result.add("DOWNLOADS");
        result.add("MUSIC");
        //result.add("PICTURES");
        return result;

    }


}
