/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
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

package org.fourthline.cling.support.model.dlna.message;

import android.util.Log;

import org.fourthline.cling.model.message.UpnpHeaders;
import org.fourthline.cling.model.message.header.UpnpHeader;
import org.fourthline.cling.support.model.dlna.message.header.DLNAHeader;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Provides UPnP header API in addition to plain multi-map HTTP header access.
 *
 * @author Mario Franco
 * @author Christian Bauer
 */
public class DLNAHeaders extends UpnpHeaders {


    protected Map<DLNAHeader.Type, List<UpnpHeader>> parsedDLNAHeaders;

    public DLNAHeaders() {
    }

    public DLNAHeaders(Map<String, List<String>> headers) {
        super(headers);
    }

    public DLNAHeaders(ByteArrayInputStream inputStream) {
        super(inputStream);
    }

    @Override
    protected void parseHeaders() {
        if (parsedHeaders == null) super.parseHeaders();

        // This runs as late as possible and only when necessary (getter called and map is dirty)
        parsedDLNAHeaders = new LinkedHashMap<>();
        Log.v(getClass().getName(), String.format("Parsing all HTTP headers for known UPnP headers: {0}", size()));
        for (Entry<String, List<String>> entry : entrySet()) {

            if (entry.getKey() == null) continue; // Oh yes, the JDK has 'null' HTTP headers

            DLNAHeader.Type type = DLNAHeader.Type.getByHttpName(entry.getKey());
            if (type == null) {
                Log.v(getClass().getName(), String.format("Ignoring non-UPNP HTTP header: {0}", entry.getKey()));
                continue;
            }

            for (String value : entry.getValue()) {
                UpnpHeader upnpHeader = DLNAHeader.newInstance(type, value);
                if (upnpHeader == null || upnpHeader.getValue() == null) {
                    Log.v(getClass().getName(), String.format("Ignoring known but non-parsable header (value violates the UDA specification?) '{0}': {1}", new Object[]{type.getHttpName(), value}));
                } else {
                    addParsedValue(type, upnpHeader);
                }
            }
        }
    }

    protected void addParsedValue(DLNAHeader.Type type, UpnpHeader value) {
        Log.v(getClass().getName(), String.format("Adding parsed header: {0}", value));
        List<UpnpHeader> list = parsedDLNAHeaders.get(type);
        if (list == null) {
            list = new LinkedList<>();
            parsedDLNAHeaders.put(type, list);
        }
        list.add(value);
    }

    @Override
    public List<String> put(String key, List<String> values) {
        parsedDLNAHeaders = null;
        return super.put(key, values);
    }

    @Override
    public void add(String key, String value) {
        parsedDLNAHeaders = null;
        super.add(key, value);
    }

    @Override
    public List<String> remove(Object key) {
        parsedDLNAHeaders = null;
        return super.remove(key);
    }

    @Override
    public void clear() {
        parsedDLNAHeaders = null;
        super.clear();
    }

    public boolean containsKey(DLNAHeader.Type type) {
        if (parsedDLNAHeaders == null) parseHeaders();
        return parsedDLNAHeaders.containsKey(type);
    }

    public List<UpnpHeader> get(DLNAHeader.Type type) {
        if (parsedDLNAHeaders == null) parseHeaders();
        return parsedDLNAHeaders.get(type);
    }

    public void add(DLNAHeader.Type type, UpnpHeader value) {
        super.add(type.getHttpName(), value.getString());
        if (parsedDLNAHeaders != null)
            addParsedValue(type, value);
    }

    public void remove(DLNAHeader.Type type) {
        super.remove(type.getHttpName());
        if (parsedDLNAHeaders != null)
            parsedDLNAHeaders.remove(type);
    }

    public UpnpHeader[] getAsArray(DLNAHeader.Type type) {
        if (parsedDLNAHeaders == null) parseHeaders();
        return parsedDLNAHeaders.get(type) != null
                ? parsedDLNAHeaders.get(type).toArray(new UpnpHeader[parsedDLNAHeaders.get(type).size()])
                : new UpnpHeader[0];
    }

    public UpnpHeader getFirstHeader(DLNAHeader.Type type) {
        return getAsArray(type).length > 0
                ? getAsArray(type)[0]
                : null;
    }

    public <H extends UpnpHeader> H getFirstHeader(DLNAHeader.Type type, Class<H> subtype) {
        UpnpHeader[] headers = getAsArray(type);
        if (headers.length == 0) return null;

        for (UpnpHeader header : headers) {
            if (subtype.isAssignableFrom(header.getClass())) {
                return (H) header;
            }
        }
        return null;
    }

    @Override
    public void log() {

        super.log();
        if (parsedDLNAHeaders != null && parsedDLNAHeaders.size() > 0) {
            Log.v(getClass().getName(), "########################## PARSED DLNA HEADERS ##########################");
            for (Map.Entry<DLNAHeader.Type, List<UpnpHeader>> entry : parsedDLNAHeaders.entrySet()) {
                Log.v(getClass().getName(), String.format("=== TYPE: {0}", entry.getKey()));
                for (UpnpHeader upnpHeader : entry.getValue()) {
                    Log.v(getClass().getName(), String.format("HEADER: {0}", upnpHeader));
                }
            }
        }
        Log.v(getClass().getName(), "####################################################################");

    }

}
