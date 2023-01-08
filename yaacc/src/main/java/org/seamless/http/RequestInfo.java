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
 package org.seamless.http;


 import java.util.logging.Logger;

 /**
  * @author Christian Bauer
  * @author Michael Pujos
  */
 public class RequestInfo {

     final private static Logger log = Logger.getLogger(RequestInfo.class.getName());


     public static boolean isPS3Request(String userAgent, String avClientInfo) {
         return ((userAgent != null && userAgent.contains("PLAYSTATION 3")) ||
                 (avClientInfo != null && avClientInfo.contains("PLAYSTATION 3")));
     }

     public static boolean isAndroidBubbleUPnPRequest(String userAgent) {
         return (userAgent != null && userAgent.contains("BubbleUPnP"));
     }


     public static boolean isJRiverRequest(String userAgent) {
         return userAgent != null && (userAgent.contains("J-River") || userAgent.contains("J. River"));
     }

     public static boolean isWMPRequest(String userAgent) {
         return userAgent != null && userAgent.contains("Windows-Media-Player") && !isJRiverRequest(userAgent);
     }

     public static boolean isXbox360Request(String userAgent, String server) {
         return (userAgent != null && (userAgent.contains("Xbox") || userAgent.contains("Xenon"))) ||
                 (server != null && server.contains("Xbox"));
     }

 }