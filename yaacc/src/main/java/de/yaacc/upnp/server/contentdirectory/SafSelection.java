/*
 *
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
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
import android.net.Uri;
import android.provider.DocumentsContract;

import de.yaacc.util.YaaccLogger;

/** What of the SAF content the server settings select: the check of {@link SafDocumentUris} with the answer of the document provider. */
public final class SafSelection {
    private SafSelection() {
    }

    /** @return true if the document is in a folder the user selected for the server (see {@link SafDocumentUris#isInSelectedTrees}) */
    public static boolean contains(Context context, String documentUri) {
        return SafDocumentUris.isInSelectedTrees(documentUri, MediaPathFilter.getSelectedSafPathes(context),
                (parent, child) -> {
                    try {
                        return DocumentsContract.isChildDocument(context.getContentResolver(), Uri.parse(parent), Uri.parse(child));
                    } catch (Exception e) {
                        YaaccLogger.d(SafSelection.class.getName(), "Provider could not tell whether a document is in a folder: " + e.getClass().getSimpleName());
                        return false;
                    }
                });
    }
}
