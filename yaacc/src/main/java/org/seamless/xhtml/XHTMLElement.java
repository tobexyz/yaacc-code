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

import org.seamless.xml.DOMElement;
import org.w3c.dom.Element;

import javax.xml.xpath.XPath;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Christian Bauer
 */
public class XHTMLElement extends DOMElement<XHTMLElement, XHTMLElement> {

    public static final String XPATH_PREFIX = "h";

    public XHTMLElement(XPath xpath, Element element) {
        super(xpath, element);
    }

    @Override
    protected Builder<XHTMLElement> createParentBuilder(DOMElement el) {
        return new Builder<XHTMLElement>(el) {
            @Override
            public XHTMLElement build(Element element) {
                return new XHTMLElement(getXpath(), element);
            }
        };
    }

    @Override
    protected ArrayBuilder<XHTMLElement> createChildBuilder(DOMElement el) {
        return new ArrayBuilder<XHTMLElement>(el) {
            @Override
            public XHTMLElement[] newChildrenArray(int length) {
                return new XHTMLElement[length];
            }

            @Override
            public XHTMLElement build(Element element) {
                return new XHTMLElement(getXpath(), element);
            }
        };
    }

    @Override
    protected String prefix(String localName) {
        return XPATH_PREFIX + ":" + localName;
    }

    public XHTML.ELEMENT getConstant() {
        return XHTML.ELEMENT.valueOf(getElementName());
    }

    public XHTMLElement[] getChildren(XHTML.ELEMENT el) {
        return super.getChildren(el.name());
    }

    public XHTMLElement getFirstChild(XHTML.ELEMENT el) {
        return super.getFirstChild(el.name());
    }

    public XHTMLElement[] findChildren(XHTML.ELEMENT el) {
        return super.findChildren(el.name());
    }

    public XHTMLElement createChild(XHTML.ELEMENT el) {
        return super.createChild(el.name(), XHTML.NAMESPACE_URI);
    }

    public String getAttribute(XHTML.ATTR attribute) {
        return getAttribute(attribute.name());
    }

    public XHTMLElement setAttribute(XHTML.ATTR attribute, String value) {
        super.setAttribute(attribute.name(), value);
        return this;
    }

    public String getId() {
        return getAttribute(XHTML.ATTR.id);
    }

    public XHTMLElement setId(String id) {
        setAttribute(XHTML.ATTR.id, id);
        return this;
    }

    public String getTitle() {
        return getAttribute(XHTML.ATTR.title);
    }

    public XHTMLElement setTitle(String title) {
        setAttribute(XHTML.ATTR.title, title);
        return this;
    }

    public XHTMLElement setClasses(String classes) {
        setAttribute(XHTML.ATTR.CLASS, classes);
        return this;
    }

    public XHTMLElement setClasses(String[] classes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < classes.length; i++) {
            sb.append(classes[i]);
            if (i != classes.length-1) sb.append(" ");
        }
        setAttribute(XHTML.ATTR.CLASS, sb.toString());
        return this;
    }

    public String[] getClasses() {
        String v = getAttribute(XHTML.ATTR.CLASS);
        if (v == null) return new String[0];
        return v.split(" ");
    }

    public Option[] getOptions() {
        return Option.fromString(getAttribute(XHTML.ATTR.style));
    }

    public Option getOption(String key) {
        for (Option option : getOptions()) {
            if (option.getKey().equals(key)) return option;
        }
        return null;
    }

    public Anchor[] findAllAnchors() {
        return findAllAnchors(null, null);
    }

    public Anchor[] findAllAnchors(String requiredScheme) {
        return findAllAnchors(requiredScheme, null);
    }

    public Anchor[] findAllAnchors(String requiredScheme, String requiredClass) {
        XHTMLElement[] elements = findChildrenWithClass(XHTML.ELEMENT.a, requiredClass);
        List<Anchor> anchors = new ArrayList(elements.length);
        for (XHTMLElement element : elements) {
            String href = element.getAttribute(XHTML.ATTR.href);
            if (requiredScheme == null || (href != null  && href.startsWith(requiredScheme))) {
                anchors.add(new Anchor(getXpath(), element.getW3CElement()));
            }
        }
        return anchors.toArray(new Anchor[anchors.size()]);
    }

    public XHTMLElement[] findChildrenWithClass(XHTML.ELEMENT el, String clazz) {
        List<XHTMLElement> list = new ArrayList();
        XHTMLElement[] children = findChildren(el);
        for (XHTMLElement child : children) {
            if (clazz == null) {
                list.add(child);
            } else {
                for (String c : child.getClasses()) {
                    if (c.matches(clazz)) {
                        list.add(child);
                        break;
                    }
                }
            }
        }
        return list.toArray(CHILD_BUILDER.newChildrenArray(list.size()));
    }

    @Override
    public XHTMLElement setContent(String content) {
        super.setContent(content);
        return this;
    }

    @Override
    public XHTMLElement setAttribute(String attribute, String value) {
        super.setAttribute(attribute, value);
        return this;
    }

}
