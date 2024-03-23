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

 import java.util.Base64;

 /**
  * @author Christian Bauer
  */
 public class Base64Test {

     @Test
     public void encodeDecode() {
         assertEquals("Zm9vIGJhciBiYXo=", Base64.getEncoder().encodeToString("foo bar baz".getBytes()));
         assertEquals("foo bar baz", new String(Base64.getDecoder().decode("Zm9vIGJhciBiYXo=")));
         assertEquals("foo bar baz", new String(Base64.getDecoder().decode("Zm9v\nIGJh\rciBiYXo=".replace("\n", "").replace("\r", ""))));
     }

 }
