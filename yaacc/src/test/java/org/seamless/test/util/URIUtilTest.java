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
 package org.seamless.test.util;

 import static org.junit.Assert.assertEquals;

 import org.junit.Test;
 import org.seamless.util.URIUtil;

 import java.net.URI;
 import java.net.URL;

 /**
  * @author Christian Bauer
  */
 public class URIUtilTest {

     @Test
     public void uriCreation() {

         URI base = URI.create("http://foo.bar/baz/");
         assertEquals(URIUtil.createAbsoluteURI(base, "/asdf.txt").toString(),
                 "http://foo.bar/asdf.txt"
         );

         base = URI.create("http://foo.bar/baz");
         assertEquals(URIUtil.createAbsoluteURI(base, "/asdf.txt").toString(),
                 "http://foo.bar/asdf.txt"
         );

         base = URI.create("http://foo.bar/baz/");
         assertEquals(URIUtil.createAbsoluteURI(base, "asdf.txt").toString(),
                 "http://foo.bar/baz/asdf.txt"
         );

         base = URI.create("http://foo.bar/baz");
         assertEquals(URIUtil.createAbsoluteURI(base, "asdf.txt").toString(),
                 "http://foo.bar/asdf.txt"
         );
     }

     @Test
     public void urlCreation() throws Exception {

         URL baseURL = new URL("http://foo.bar:0");
         assertEquals(
                 URIUtil.createAbsoluteURL(baseURL, "asdf.txt").toString(),
                 "http://foo.bar:0/asdf.txt"
         );

         baseURL = new URL("http://foo.bar:0/");
         assertEquals(
                 URIUtil.createAbsoluteURL(baseURL, "asdf.txt").toString(),
                 "http://foo.bar:0/asdf.txt"
         );

         baseURL = new URL("http://foo.bar");
         assertEquals(
                 URIUtil.createAbsoluteURL(baseURL, "asdf.txt").toString(),
                 "http://foo.bar/asdf.txt"
         );

         baseURL = new URL("http://foo.bar");
         assertEquals(
                 URIUtil.createAbsoluteURL(baseURL, "/asdf.txt").toString(),
                 "http://foo.bar/asdf.txt"
         );

         baseURL = new URL("http://foo.bar:0");
         assertEquals(
                 URIUtil.createAbsoluteURL(baseURL, "asdf.txt").toString(),
                 "http://foo.bar:0/asdf.txt"
         );

         baseURL = new URL("http://foo.bar:0/");
         assertEquals(
                 URIUtil.createAbsoluteURL(baseURL, "asdf.txt").toString(),
                 "http://foo.bar:0/asdf.txt"
         );

         baseURL = new URL("http://foo.bar");
         assertEquals(
                 URIUtil.createAbsoluteURL(baseURL, "asdf.txt").toString(),
                 "http://foo.bar/asdf.txt"
         );

         baseURL = new URL("http://foo.bar");
         assertEquals(
                 URIUtil.createAbsoluteURL(baseURL, "/asdf.txt").toString(),
                 "http://foo.bar/asdf.txt"
         );

         baseURL = new URL("http://foo.bar/baz");
         assertEquals(
                 URIUtil.createAbsoluteURL(baseURL, "/asdf.txt").toString(),
                 "http://foo.bar/asdf.txt"
         );

         baseURL = new URL("http://foo.bar/baz");
         assertEquals(
                 URIUtil.createAbsoluteURL(baseURL, "asdf.txt").toString(),
                 "http://foo.bar/asdf.txt"
         );

         baseURL = new URL("http://foo.bar/baz/");
         assertEquals(
                 URIUtil.createAbsoluteURL(baseURL, "asdf.txt").toString(),
                 "http://foo.bar/baz/asdf.txt"
         );
     }

     @Test
     public void encoding() {
         assertEquals(URIUtil.encodePathParamName("azAZ09-._~!$&'()*+,;=:@é/?#[]"), "azAZ09-._~!$&'()*+,%3B%3D:@%C3%A9%2F%3F%23%5B%5D");
         assertEquals(URIUtil.encodePathParamValue("azAZ09-._~!$&'()*+,;=:@é/?#[]"), "azAZ09-._~!$&'()*+,%3B=:@%C3%A9%2F%3F%23%5B%5D");
         assertEquals(URIUtil.encodePathSegment("azAZ09-._~!$&'()*+,;=:@é/?#[]"), "azAZ09-._~!$&'()*+,%3B=:@%C3%A9%2F%3F%23%5B%5D");
         assertEquals(URIUtil.encodeQueryNameOrValue("azAZ09-._~!$&'()*+,;=:@é/?#[]"), "azAZ09-._~!$%26'()*%2B,;%3D:@%C3%A9/?%23%5B%5D");
     }
 }
