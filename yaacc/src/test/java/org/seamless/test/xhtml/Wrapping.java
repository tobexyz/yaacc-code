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
 package org.seamless.test.xhtml;

 import static org.junit.Assert.assertEquals;

 import org.junit.Test;
 import org.seamless.xhtml.XHTML;
 import org.seamless.xhtml.XHTMLElement;
 import org.seamless.xhtml.XHTMLParser;

 import javax.xml.xpath.XPath;

 /**
  * @author Christian Bauer
  */
 public class Wrapping {

     protected XHTMLParser createParser() {
         return new XHTMLParser();
     }

     @Test
     public void workFragment() throws Exception {
         XHTMLParser parser = createParser();
         XPath xpath = parser.createXPath();

         XHTML xhtml = parser.createDocument();

         XHTMLElement root =
                 xhtml.createRoot(xpath, XHTML.ELEMENT.div)
                         .setAttribute(XHTML.ATTR.CLASS, "foo")
                         .setAttribute(XHTML.ATTR.id, "div1");

         root.createChild(XHTML.ELEMENT.a)
                 .setAttribute(XHTML.ATTR.CLASS, "test1 bar")
                 .setAttribute(XHTML.ATTR.href, "http://test1.foo.bar");

         root.createChild(XHTML.ELEMENT.a)
                 .setAttribute(XHTML.ATTR.CLASS, "test2 bar")
                 .setAttribute(XHTML.ATTR.href, "http://test2.foo.bar");

         assertEquals(xhtml.getRoot(xpath).findAllAnchors(null, "bar").length, 2);
     }

     @Test
     public void wrapParse() throws Exception {
         String frag =
                 "<a href=\"http://test1.foo.bar\">test1</a>" +
                         "<a href=\"http://test2.foo.bar\">test2</a>";

         XHTMLParser parser = createParser();
         XPath xpath = parser.createXPath();

         // Create wrapper document with XHTML default namespace, XPath query works on XHTML namespace context
         XHTML xhtml = parser.parse(XHTMLParser.wrap("div", XHTML.NAMESPACE_URI, frag), false);
         assertEquals(xhtml.getRoot(xpath).findAllAnchors(null, null).length, 2);

         // No namespace, no query hits
         xhtml = parser.parse(XHTMLParser.wrap("div", frag), false);
         assertEquals(xhtml.getRoot(xpath).findAllAnchors(null, null).length, 0);
     }

 }
