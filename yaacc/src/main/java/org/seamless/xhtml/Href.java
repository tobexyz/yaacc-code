 /*
 * Copyright (C) 2012 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.seamless.xhtml;

import java.net.URI;

/**
 * @author Christian Bauer
 */
public class Href {

    private URI uri;

    public Href(URI uri) {
        this.uri = uri;
    }

    public URI getURI() {
        return uri;
    }

    public static Href fromString(String string) {
        if (string == null) return null;
        string = string.replaceAll(" ", "%20");
        return new Href(URI.create(string));
    }

    @Override
    public String toString() {
        return getURI().toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Href href = (Href) o;

        if (!uri.equals(href.uri)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return uri.hashCode();
    }
}
