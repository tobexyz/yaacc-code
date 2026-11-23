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
package de.yaacc.fcast.discovery;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import de.yaacc.fcast.protocol.FCastText;

/**
 * What a receiver announces in the TXT record of its mDNS service, as <b>hints</b> (nothing here is authenticated). The desktop
 * receiver sets {@code v} (the highest protocol version) and {@code fp} (base64 SHA-256 of the certificate's public key, for the
 * v4 pin); the Android receiver sets {@code version}, {@code appName} and {@code appVersion} (docs/tech.md). Keys are case
 * insensitive, every value is cut and cleaned, a value of the wrong shape is ignored, at most 32 attributes are read. Immutable.
 */
public final class FCastServiceTxt {
    /** No hints. */
    public static final FCastServiceTxt EMPTY = new FCastServiceTxt(null, null, null, null);

    private static final int MAX_ATTRIBUTES = 32;
    private static final int MAX_VALUE_BYTES = 256;
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,4}");
    /** Standard base64 of 32 bytes: 43 characters and one {@code =}. */
    private static final Pattern FINGERPRINT = Pattern.compile("[A-Za-z0-9+/]{43}=");

    private final Integer protocolVersion;
    private final String fingerprint;
    private final String appName;
    private final String appVersion;

    private FCastServiceTxt(Integer protocolVersion, String fingerprint, String appName, String appVersion) {
        this.protocolVersion = protocolVersion;
        this.fingerprint = fingerprint;
        this.appName = appName;
        this.appVersion = appVersion;
    }

    /** @param attributes the TXT attributes as the platform gives them ({@code NsdServiceInfo.getAttributes()}); may be null; a value may be null (a flag) */
    public static FCastServiceTxt parse(Map<String, byte[]> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return EMPTY;
        }
        Integer version = null;
        String fingerprint = null;
        String appName = null;
        String appVersion = null;
        int seen = 0;
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            if (++seen > MAX_ATTRIBUTES) {
                break;
            }
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            String value = text(entry.getValue());
            if (value == null) {
                continue;
            }
            switch (key) {
                case "v":
                case "version":
                    if (DIGITS.matcher(value).matches()) {
                        int number = Integer.parseInt(value);
                        if (number >= 1 && number <= 1000 && (version == null || key.equals("v"))) {
                            version = number;
                        }
                    }
                    break;
                case "fp":
                    if (FINGERPRINT.matcher(value).matches()) {
                        fingerprint = value;
                    }
                    break;
                case "appname":
                    appName = FCastText.clean(value, FCastText.MAX_NAME);
                    break;
                case "appversion":
                    appVersion = FCastText.clean(value, FCastText.MAX_NAME);
                    break;
                default:
                    break;
            }
        }
        if (version == null && fingerprint == null && appName == null && appVersion == null) {
            return EMPTY;
        }
        return new FCastServiceTxt(version, fingerprint, appName, appVersion);
    }

    private static String text(byte[] value) {
        if (value == null || value.length > MAX_VALUE_BYTES) {
            return null;
        }
        return new String(value, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FCastServiceTxt)) {
            return false;
        }
        FCastServiceTxt o = (FCastServiceTxt) other;
        return java.util.Objects.equals(protocolVersion, o.protocolVersion) && java.util.Objects.equals(fingerprint, o.fingerprint)
                && java.util.Objects.equals(appName, o.appName) && java.util.Objects.equals(appVersion, o.appVersion);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(protocolVersion, fingerprint, appName, appVersion);
    }

    /** The highest protocol version the receiver says it speaks ({@code v} or {@code version}), or null. A hint only. */
    public Integer getProtocolVersion() {
        return protocolVersion;
    }

    /** The announced fingerprint of the receiver's certificate (base64 of 32 bytes), or null. Unauthenticated: the pin of v4 needs a trusted channel. */
    public String getFingerprint() {
        return fingerprint;
    }

    public String getAppName() {
        return appName;
    }

    public String getAppVersion() {
        return appVersion;
    }
}
