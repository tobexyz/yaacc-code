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
public class Head extends XHTMLElement {

    public Head(XPath xpath, Element element) {
        super(xpath, element);
    }

    public XHTMLElement getHeadTitle() {
        return CHILD_BUILDER.firstChildOrNull(XHTML.ELEMENT.title.name());
    }

    public Link[] getLinks() {
        return new ArrayBuilder<Link>(this) {
            @Override
            public Link build(Element element) {
                return new Link(getXpath(), element);
            }

            @Override
            public Link[] newChildrenArray(int length) {
                return new Link[length];
            }
        }.getChildElements(XHTML.ELEMENT.link.name());
    }

    public Meta[] getMetas() {
        return new ArrayBuilder<Meta>(this) {
            @Override
            public Meta build(Element element) {
                return new Meta(getXpath(), element);
            }

            @Override
            public Meta[] newChildrenArray(int length) {
                return new Meta[length];
            }
        }.getChildElements(XHTML.ELEMENT.meta.name());
    }

    public XHTMLElement[] getDocumentStyles() {
        return CHILD_BUILDER.getChildElements(XHTML.ELEMENT.style.name());
    }

    public XHTMLElement[] getScripts() {
        return CHILD_BUILDER.getChildElements(XHTML.ELEMENT.script.name());
    }

}