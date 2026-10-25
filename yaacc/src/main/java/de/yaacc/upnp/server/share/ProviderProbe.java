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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.upnp.server.share;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

/**
 * Asks the content provider of a shared file for its MIME type and its display name. Both
 * answers are untrusted text from another app: the type goes through
 * {@code SharedContentPolicy.chooseMimeType}, the name through {@code DisplayName.forShare}.
 * <p>
 * These are Binder calls into another process and can block for ever: call only on a worker
 * thread, through {@link de.yaacc.share.BoundedCall}, never on the main thread.
 */
public final class ProviderProbe {

    /** A longer name is cut before it is cleaned: nothing sensible is that long. */
    static final int MAX_NAME_CHARS = 1024;

    /** What the provider said; every field may be null. */
    public static final class Info {
        private final String providerType;
        private final String displayName;

        Info(String providerType, String displayName) {
            this.providerType = providerType;
            this.displayName = displayName;
        }

        /** @return the type from {@code ContentResolver.getType}, untrusted, or null */
        public String getProviderType() {
            return providerType;
        }

        /** @return the raw display name, at most {@value #MAX_NAME_CHARS} chars, untrusted, or null */
        public String getDisplayName() {
            return displayName;
        }
    }

    private ProviderProbe() {
    }

    /** Never throws; a failing provider is a null field. */
    public static Info query(ContentResolver resolver, Uri uri) {
        return new Info(typeOf(resolver, uri), nameOf(resolver, uri));
    }

    private static String typeOf(ContentResolver resolver, Uri uri) {
        try {
            return resolver.getType(uri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String nameOf(ContentResolver resolver, Uri uri) {
        // only the first row and the one column; a provider that ignores the projection costs nothing more
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (column < 0 || cursor.getType(column) != Cursor.FIELD_TYPE_STRING) {
                return null;
            }
            String name = cursor.getString(column);
            if (name != null && name.length() > MAX_NAME_CHARS) {
                name = name.substring(0, MAX_NAME_CHARS);
            }
            return name;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
