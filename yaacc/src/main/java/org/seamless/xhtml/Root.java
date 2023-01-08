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
public class Root extends XHTMLElement {

    public Root(XPath xpath, Element element) {
        super(xpath, element);
    }

    public Head getHead() {
        return new Builder<Head>(this) {
            @Override
            public Head build(Element element) {
                return new Head(getXpath(), element);
            }
        }.firstChildOrNull(XHTML.ELEMENT.head.name());
    }

    public Body getBody() {
        return new Builder<Body>(this) {
            @Override
            public Body build(Element element) {
                return new Body(getXpath(), element);
            }
        }.firstChildOrNull(XHTML.ELEMENT.body.name());
    }

}
