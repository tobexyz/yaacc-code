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
package de.yaacc.upnp.server.configuration;

/**
 * What a row of the shared folders tree needs, as plain data that was read on a worker thread:
 * binding a row must never ask the file system or a document provider (with a {@code DocumentFile}
 * every property is one more provider query). Immutable, no Android types.
 *
 * @author Tobias Schoene (tobexyz)
 */
public final class TreeNodeData {

    private final String name;
    private final String path;
    private final boolean saf;
    private final boolean directory;

    /**
     * @param name      the text shown in the row
     * @param path      the absolute path of a file system node or the document uri of a SAF node - the
     *                  string the media path preferences store
     * @param saf       a SAF document (otherwise a {@link java.io.File})
     * @param directory a folder (shows the check box and the expand symbol)
     */
    public TreeNodeData(String name, String path, boolean saf, boolean directory) {
        this.name = name;
        this.path = path;
        this.saf = saf;
        this.directory = directory;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public boolean isSaf() {
        return saf;
    }

    public boolean isDirectory() {
        return directory;
    }

    @Override
    public String toString() {
        return path;
    }
}
