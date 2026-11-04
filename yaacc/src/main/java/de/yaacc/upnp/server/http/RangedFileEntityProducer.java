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
package de.yaacc.upnp.server.http;

import org.apache.hc.core5.http.ContentType;

import java.io.File;
import java.io.FileInputStream;

/**
 * The body of a ranged answer for a local file ({@link RangeResponsePlan#window()}; the whole file
 * for a request without a range), read in 8 KB steps so a large file is never held in memory
 * (issue #294). What is served, see {@link RangeWindow}; how, see {@link RangedStreamEntityProducer}.
 */
final class RangedFileEntityProducer extends RangedStreamEntityProducer {

    RangedFileEntityProducer(File file, ContentType contentType, RangeWindow window) {
        super(contentType, () -> new FileInputStream(file), window.start(), window.length());
    }
}
