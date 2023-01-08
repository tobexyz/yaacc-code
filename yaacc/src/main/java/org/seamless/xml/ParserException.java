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
package org.seamless.xml;

import org.xml.sax.SAXParseException;

/**
 * Unified exception thrown by the <tt>DOMParser</tt> and <tt>SAXParser</tt>.
 *
 * @author Christian Bauer
 */
public class ParserException extends Exception {

    public ParserException() {
    }

    public ParserException(String s) {
        super(s);
    }

    public ParserException(String s, Throwable throwable) {
        super(s, throwable);
    }

    public ParserException(Throwable throwable) {
        super(throwable);
    }

    public ParserException(SAXParseException ex) {
        super("(Line/Column: " + ex.getLineNumber() + ":" + ex.getColumnNumber() + ") " + ex.getMessage());
    }
}
