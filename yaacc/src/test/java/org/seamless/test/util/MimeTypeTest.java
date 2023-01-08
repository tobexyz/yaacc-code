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

 import static org.junit.Assert.*;

 import org.junit.Test;
 import org.seamless.util.MimeType;

 /**
  * @author Christian Bauer
  */
 public class MimeTypeTest {

     @Test
     public void caseInsensitiveEquality() {
         assertEquals(new MimeType("foo", "bar"), new MimeType("Foo", "BAR"));
         assertNotEquals(new MimeType("foo", "bar"), new MimeType("Foo", "BAZ"));
         assertEquals(new MimeType("Foo", "Bar").toString(), "Foo/Bar");
     }

     @Test
     public void parseMimeTypeWithParameters() {
         // Note the missing quotes on the channels="1" value
         String s = "audio/L16;rate=44100;id=\"ABC@host.com\";channels=1";
         MimeType mt = MimeType.valueOf(s);
         assertEquals(mt.getType(), "audio");
         assertEquals(mt.getSubtype(), "L16");
         assertEquals(mt.getParameters().size(), 3);

         assertEquals(mt.getParameters().get("rate"), "44100");
         assertEquals(mt.getParameters().get("id"), "ABC@host.com");
         assertEquals(mt.getParameters().get("channels"), "1");
         assertEquals(mt.toString(), "audio/L16;channels=\"1\";id=\"ABC@host.com\";rate=\"44100\"");
     }

     @Test
     public void parseMimeTypeIllegalWhitespace() {
         MimeType mt = MimeType.valueOf("foo/bar ;charset=\"utf-8\"");
         assertEquals(mt.getType(), "foo");
         assertEquals(mt.getSubtype(), "bar");
         assertEquals(mt.getParameters().size(), 1);

         mt = MimeType.valueOf("foo/bar; charset=\"utf-8\"");
         assertEquals(mt.getType(), "foo");
         assertEquals(mt.getSubtype(), "bar");
         assertEquals(mt.getParameters().size(), 1);
     }

     @Test
     public void escapeBackslash() {
         MimeType mt = MimeType.valueOf("foo/bar ;baz=\"\\\"abc\\\"");
         assertEquals(mt.getType(), "foo");
         assertEquals(mt.getSubtype(), "bar");
         assertEquals(mt.getParameters().size(), 1);
         assertEquals(mt.getParameters().get("baz"), "\"abc\"");
     }

 }
