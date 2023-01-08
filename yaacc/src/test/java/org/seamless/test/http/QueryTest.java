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
 package org.seamless.test.http;

 import static org.junit.Assert.assertEquals;
 import static org.junit.Assert.assertTrue;

 import org.junit.Test;
 import org.seamless.http.Query;

 /**
  * @author Christian Bauer
  */
 public class QueryTest {

     @Test
     public void createQuery() {
         Query query = new Query();
         assertTrue(query.isEmpty());
         assertEquals(query.getMap().size(), 0);
         assertEquals(query.getMapWithLists().size(), 0);
         assertEquals(query.get("foo"), "");
         assertEquals(query.toString(), "");

         query = new Query("foo=bar&baz=abc&baz=def");
         assertEquals(query.getMap().size(), 2);
         assertEquals(query.getMapWithLists().size(), 2);
         assertEquals(query.get("foo"), "bar");
         assertEquals(query.get("baz"), "abc");
         assertEquals(query.getValues("baz").length, 2);
         assertEquals(query.getValues("baz")[0], "abc");
         assertEquals(query.getValues("baz")[1], "def");
         assertEquals(query.getValuesAsList("baz").size(), 2);
         assertEquals(query.getValuesAsList("baz").get(0), "abc");
         assertEquals(query.getValuesAsList("baz").get(1), "def");

         assertEquals(query.toString(), "foo=bar&baz=abc&baz=def");
     }

     @Test
     public void addParameter() {
         Query query = new Query();
         query = query.cloneAndAdd("foo", "bar", "baz");
         assertEquals(query.getMap().size(), 1);
         assertEquals(query.getValues("foo").length, 2);

         query = new Query("abc=123");
         query = query.cloneAndAdd("foo", "bar", "baz");
         assertEquals(query.getMap().size(), 2);
         assertEquals(query.getValues("abc").length, 1);
         assertEquals(query.getValues("foo").length, 2);
     }
 }
