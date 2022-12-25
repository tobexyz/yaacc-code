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

import org.w3c.dom.Element;
import javax.xml.xpath.XPath;

/**
 * @author Christian Bauer
 */
public class Link extends XHTMLElement {

    public Link(XPath xpath, Element element) {
        super(xpath, element);
    }

    public Href getHref() {
        return Href.fromString(getAttribute(XHTML.ATTR.href));
    }

    public String getRel() {
        return getAttribute(XHTML.ATTR.rel);
    }

    public String getRev() {
        return getAttribute(XHTML.ATTR.rev);
    }
}