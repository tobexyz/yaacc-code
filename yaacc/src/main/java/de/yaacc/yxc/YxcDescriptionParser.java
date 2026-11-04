/*
 *
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.yxc;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the UPnP device description of a device and finds its YXC service. Verified on the owner's devices
 * (2026-11-02): the YXC service is listed under {@code <X_device>} of the Yamaha namespace as a
 * {@code <X_service>} with {@code <X_specType>urn:schemas-yamaha-com:service:X_YamahaExtendedControl:1</X_specType>},
 * {@code <X_yxcControlURL>} (a path) and {@code <X_yxcVersion>}; {@code <X_URLBase>} next to the service list is the
 * {@code host:port} of the device. A device may also list an older service of another kind ({@code X_controlURL}, no
 * {@code X_yxcControlURL}); that one is not a YXC service and is ignored, the YXC one beside it is found.
 * <p>
 * Parser choice: {@link XmlPullParser}. It is the parser the app already uses for UPnP documents on Android (the
 * platform has it), and the unit tests have it on the classpath (kxml2 / xmlpull as test dependencies), whereas the
 * Android stubs of {@code android.util.Xml} return null in unit tests. {@code javax.xml.parsers} exists on both, but the
 * Android implementation does not support the hardening features (it rejects {@code setFeature} calls it does not know),
 * so its XXE safety could not be tested on the JVM and relied upon on the phone.
 * <p>
 * Safety: a document with a document type declaration is refused before any parsing, so no entity and no external
 * resource can be declared; the pull parser is also told not to process one. The text is capped
 * ({@link #MAX_DESCRIPTION_CHARS}), as are nesting depth and the length of each value; the control URL must be a plain
 * path. Names are compared by their local part, so the prefix a device chooses does not matter, and a prefix that is not
 * declared is not an error. A malformed document is "not a YXC device", never an exception.
 */
public final class YxcDescriptionParser {
    /** The longest description that is parsed; a caller that reads the document should stop reading at this size. */
    public static final int MAX_DESCRIPTION_CHARS = 256 * 1024;

    /** The service type that names the YXC service. */
    public static final String YXC_SERVICE_TYPE = "urn:schemas-yamaha-com:service:X_YamahaExtendedControl:1";

    private static final int MAX_DEPTH = 32;
    private static final int MAX_VALUE_CHARS = 256;
    private static final Pattern CONTROL_PATH = Pattern.compile("/[A-Za-z0-9_][A-Za-z0-9_.~/%+-]*|/");
    private static final Pattern HOST_PORT = Pattern.compile("([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?");
    private static final Pattern IPV6_HOST_PORT = Pattern.compile("\\[([0-9A-Fa-f:.]+)\\](?::([0-9]{1,5}))?");

    private YxcDescriptionParser() {
    }

    /**
     * @param xml the text of the description
     * @return the YXC service of the device, or {@code null} when the document is not a description of a device with a
     * YXC service (also: too large, malformed, with a document type declaration)
     */
    public static YxcDescriptionInfo parse(String xml) {
        if (xml == null || xml.length() > MAX_DESCRIPTION_CHARS) {
            return null;
        }
        String text = xml.trim();
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
            text = text.substring(1).trim();
        }
        if (text.isEmpty() || containsDeclaration(text)) {
            return null;
        }
        try {
            return read(newParser(), text);
        } catch (XmlPullParserException | IOException | RuntimeException malformed) {
            return null;
        }
    }

    private static boolean containsDeclaration(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY");
    }

    private static XmlPullParser newParser() {
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            try {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            } catch (XmlPullParserException unsupported) {
                // the default of the parsers in use is "off" already, and the declaration was refused above
            }
            return parser;
        } catch (XmlPullParserException e) {
            throw new IllegalStateException("no XML pull parser available", e);
        }
    }

    private static YxcDescriptionInfo read(XmlPullParser parser, String text) throws XmlPullParserException, IOException {
        parser.setInput(new StringReader(text));
        List<String> path = new ArrayList<>();
        Service current = null;
        Service yxc = null;
        String urlBase = null;
        String udn = null;
        String serial = null;
        String friendlyName = null;
        boolean rootDeviceSeen = false;
        boolean inRootDevice = false;
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (parser.getDepth() > MAX_DEPTH) {
                return null;
            }
            if (event == XmlPullParser.START_TAG) {
                String name = localName(parser.getName());
                String parent = path.isEmpty() ? null : path.get(path.size() - 1);
                path.add(name);
                if ("device".equals(name) && path.size() == 2 && !rootDeviceSeen) {
                    rootDeviceSeen = true;
                    inRootDevice = true;
                } else if ("X_service".equals(name) && path.contains("X_device")) {
                    current = new Service();
                } else if (isLeafOfInterest(name, parent, path, inRootDevice, current != null)) {
                    String value = clean(parser.nextText());
                    path.remove(path.size() - 1); // nextText() ends on the closing tag, which the loop does not see
                    if (current != null && "X_service".equals(parent)) {
                        current.accept(name, value);
                    } else if ("X_URLBase".equals(name) && "X_device".equals(parent)) {
                        urlBase = urlBase == null ? value : urlBase;
                    } else if (inRootDevice && path.size() == 2) {
                        // children of the root device only: an embedded device has its own UDN and name
                        if ("UDN".equals(name) && udn == null) {
                            udn = value;
                        } else if ("serialNumber".equals(name) && serial == null) {
                            serial = value;
                        } else if ("friendlyName".equals(name) && friendlyName == null) {
                            friendlyName = value;
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                String name = localName(parser.getName());
                if (!path.isEmpty()) {
                    path.remove(path.size() - 1);
                }
                if ("X_service".equals(name) && current != null) {
                    if (yxc == null && current.isYxc()) {
                        yxc = current;
                    }
                    current = null;
                } else if ("device".equals(name) && path.size() == 1) {
                    inRootDevice = false;
                }
            }
            event = parser.next();
        }
        if (yxc == null) {
            return null;
        }
        String host = null;
        int port = YxcDescriptionInfo.DEFAULT_PORT;
        Endpoint endpoint = Endpoint.parse(urlBase);
        if (endpoint != null) {
            host = endpoint.host;
            port = endpoint.port;
        }
        return new YxcDescriptionInfo(yxc.controlUrl, yxc.version, host, port, udn, serial, friendlyName);
    }

    private static boolean isLeafOfInterest(String name, String parent, List<String> path, boolean inRootDevice, boolean inService) {
        if (inService && "X_service".equals(parent)) {
            return "X_specType".equals(name) || "X_yxcControlURL".equals(name) || "X_yxcVersion".equals(name);
        }
        if ("X_URLBase".equals(name) && "X_device".equals(parent)) {
            return true;
        }
        return inRootDevice && path.size() == 3
                && ("UDN".equals(name) || "serialNumber".equals(name) || "friendlyName".equals(name));
    }

    private static String localName(String qualified) {
        int colon = qualified.lastIndexOf(':');
        return colon < 0 ? qualified : qualified.substring(colon + 1);
    }

    /** Trimmed, control characters removed, cut at {@link #MAX_VALUE_CHARS}; {@code null} when nothing is left. */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_VALUE_CHARS));
        for (int i = 0; i < raw.length() && out.length() < MAX_VALUE_CHARS; i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                out.append(c);
            }
        }
        String value = out.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /** One {@code X_service} entry. */
    private static final class Service {
        String specType;
        String controlUrl;
        String version;

        void accept(String name, String value) {
            if ("X_specType".equals(name)) {
                specType = value;
            } else if ("X_yxcControlURL".equals(name)) {
                controlUrl = value != null && isPlainPath(value) ? value : null;
            } else if ("X_yxcVersion".equals(name)) {
                version = value;
            }
        }

        /** The service is a YXC one when it names a control path in the {@code X_yxcControlURL} tag. */
        boolean isYxc() {
            return controlUrl != null;
        }

        private static boolean isPlainPath(String value) {
            return CONTROL_PATH.matcher(value).matches() && !value.contains("..") && !value.contains("//");
        }
    }

    /** {@code host[:port]} of the URL base; a scheme in front and a slash at the end are tolerated. */
    private static final class Endpoint {
        final String host;
        final int port;

        private Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static Endpoint parse(String urlBase) {
            if (urlBase == null) {
                return null;
            }
            String value = urlBase;
            if (value.regionMatches(true, 0, "http://", 0, 7)) {
                value = value.substring(7);
            }
            if (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            Matcher matcher = IPV6_HOST_PORT.matcher(value);
            if (!matcher.matches()) {
                matcher = HOST_PORT.matcher(value);
                if (!matcher.matches()) {
                    return null;
                }
            }
            int port = YxcDescriptionInfo.DEFAULT_PORT;
            if (matcher.group(2) != null) {
                port = Integer.parseInt(matcher.group(2));
                if (port < 1 || port > 65535) {
                    return null;
                }
            }
            return new Endpoint(matcher.group(1), port);
        }
    }
}
