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

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * @author Christian Bauer
 * @author Michael Pujos
 */
public class RequestInfo {

    final private static Logger log = Logger.getLogger(RequestInfo.class.getName());

    public static void reportRequest(StringBuilder builder, HttpServletRequest req) {
        builder.append("Request: ");
        builder.append(req.getMethod());
        builder.append(' ');
        builder.append(req.getRequestURL());
        String queryString = req.getQueryString();
        if (queryString != null) {
            builder.append('?');
            builder.append(queryString);
        }

        builder.append(" - ");

        String sessionId = req.getRequestedSessionId();
        if (sessionId != null)
            builder.append("\nSession ID: ");
        if (sessionId == null) {
            builder.append("No Session");
        } else if (req.isRequestedSessionIdValid()) {
            builder.append(sessionId);
            builder.append(" (from ");
            if (req.isRequestedSessionIdFromCookie()) {
                builder.append("cookie)\n");
            } else if (req.isRequestedSessionIdFromURL()) {
                builder.append("url)\n");
            } else {
                builder.append("unknown)\n");
            }
        } else {
            builder.append("Invalid Session ID\n");
        }
    }

    public static void reportParameters(StringBuilder builder, HttpServletRequest req) {
        Enumeration names = req.getParameterNames();
        if (names == null) return;

        if (names.hasMoreElements()) {
            builder.append("Parameters:\n");
            while (names.hasMoreElements()) {
                String name = (String) names.nextElement();
                String[] values = req.getParameterValues(name);
                if (values != null) {
                    for (String value : values) {
                        builder.append("    ").append(name).append(" = ").append(value).append('\n');
                    }
                }
            }
        }
    }

    public static void reportHeaders(StringBuilder builder, HttpServletRequest req) {
        Enumeration names = req.getHeaderNames();
        if (names == null) return;
        if (names.hasMoreElements()) {
            builder.append("Headers:\n");
            while (names.hasMoreElements()) {
                String name = (String) names.nextElement();
                String value = req.getHeader(name);
                builder.append("    ").append(name).append(": ").append(value).append('\n');
            }
        }
    }

    public static void reportCookies(StringBuilder builder, HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return;
        int l = cookies.length;
        if (l > 0) {
            builder.append("Cookies:\n");
            for (int i = 0; i < l; ++i) {
                Cookie cookie = cookies[i];
                builder.append("    ").append(cookie.getName()).append(" = ").append(cookie.getValue()).append('\n');
            }
        }
    }

    public static void reportClient(StringBuilder builder, HttpServletRequest req) {
        builder.append("Remote Address: ").append(req.getRemoteAddr()).append("\n");
        if (!req.getRemoteAddr().equals(req.getRemoteHost()))
            builder.append("Remote Host: ").append(req.getRemoteHost()).append("\n");
        builder.append("Remote Port: ").append(req.getRemotePort()).append("\n");
        if (req.getRemoteUser() != null)
            builder.append("Remote User: ").append(req.getRemoteUser()).append("\n");
    }

    public static boolean isPS3Request(String userAgent, String avClientInfo) {
        return ((userAgent != null && userAgent.contains("PLAYSTATION 3")) ||
            (avClientInfo != null && avClientInfo.contains("PLAYSTATION 3")));
    }

    public static boolean isAndroidBubbleUPnPRequest(String userAgent) {
        return (userAgent != null && userAgent.contains("BubbleUPnP"));
    }

    public static boolean isPS3Request(HttpServletRequest request) {
        return isPS3Request(request.getHeader("User-Agent"), request.getHeader("X-AV-Client-Info"));
    }

    public static boolean isJRiverRequest(HttpServletRequest request) {
        return isJRiverRequest(request.getHeader("User-Agent"));
    }

    public static boolean isJRiverRequest(String userAgent) {
        return userAgent != null && (userAgent.contains("J-River") || userAgent.contains("J. River"));
    }

    public static boolean isWMPRequest(String userAgent) {
        return userAgent != null && userAgent.contains("Windows-Media-Player") && !isJRiverRequest(userAgent);
    }

    public static boolean isXbox360Request(HttpServletRequest request) {
        return isXbox360Request(request.getHeader("User-Agent"), request.getHeader("Server"));
    }

    public static boolean isXbox360Request(String userAgent, String server) {
        return (userAgent != null && (userAgent.contains("Xbox") || userAgent.contains("Xenon"))) ||
            (server != null && server.contains("Xbox"));
    }

    public static boolean isXbox360AlbumArtRequest(HttpServletRequest request) {
        return "true".equals(request.getParameter("albumArt")) && isXbox360Request(request);
    }

    public static void dumpRequestHeaders(long timestamp, HttpServletRequest request) {
        dumpRequestHeaders(timestamp, "REQUEST HEADERS", request);
    }

    public static void dumpRequestString(long timestamp, HttpServletRequest request) {
        log.info(getRequestInfoString(timestamp, request));
    }

    public static void dumpRequestHeaders(long timestamp, String text, HttpServletRequest request) {
        log.info(text);
        dumpRequestString(timestamp, request);
        Enumeration headers = request.getHeaderNames();
        if (headers != null) {
            while (headers.hasMoreElements()) {
                String headerName = (String) headers.nextElement();
                log.info(String.format("%s: %s", headerName, request.getHeader(headerName)));
            }
        }
        log.info("----------------------------------------");
    }

    public static String getRequestInfoString(long timestamp, HttpServletRequest request) {
        return String.format("%s %s %s %s %s %d",
            request.getMethod(),
            request.getRequestURI(),
            request.getProtocol(),
            request.getParameterMap(),
            request.getRemoteAddr(),
            timestamp);
    }

    public static String getRequestFullURL(HttpServletRequest req) {

        String scheme = req.getScheme();             // http
        String serverName = req.getServerName();     // hostname.com
        int serverPort = req.getServerPort();        // 80
        String contextPath = req.getContextPath();   // /mywebapp
        String servletPath = req.getServletPath();   // /servlet/MyServlet
        String pathInfo = req.getPathInfo();         // /a/b;c=123
        String queryString = req.getQueryString();   // d=789

        // Reconstruct original requesting URL
        StringBuffer url = new StringBuffer();
        url.append(scheme).append("://").append(serverName);

        if ((serverPort != 80) && (serverPort != 443)) {
            url.append(":").append(serverPort);
        }

        url.append(contextPath).append(servletPath);

        if (pathInfo != null) {
            url.append(pathInfo);
        }
        if (queryString != null) {
            url.append("?").append(queryString);
        }
        return url.toString();
    }

}