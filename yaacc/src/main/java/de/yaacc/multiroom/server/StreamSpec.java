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
package de.yaacc.multiroom.server;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A tcp stream source as the control port accepts it (issue #330, rule S4): {@code tcp://<host>:<port>?name=<name>} with the options of
 * snapserver that make sense here. Everything else is refused with -32602: other kinds of source (pipe, file, process, alsa), a path, a
 * command or a script in a parameter, a port outside the feeder ports, client mode, a sample format the server cannot cut into chunks.
 */
public final class StreamSpec {

    public static final int FIRST_PORT = 4953;
    public static final int LAST_PORT = 4962;
    public static final int MAX_NAME_LENGTH = 64;

    /** The options that are accepted (the others of snapserver start programs or read files and are not offered). */
    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList("name", "mode", "port", "sampleformat", "codec", "chunk_ms",
            "idle_threshold", "buffer_ms", "send_to_muted", "silence_threshold_percent"));

    private final String raw;
    private final String name;
    private final int port;
    private final int sampleRate;
    private final int bitsPerSample;
    private final int channels;
    private final String codec;

    private StreamSpec(String raw, String name, int port, int sampleRate, int bitsPerSample, int channels, String codec) {
        this.raw = raw;
        this.name = name;
        this.port = port;
        this.sampleRate = sampleRate;
        this.bitsPerSample = bitsPerSample;
        this.channels = channels;
        this.codec = codec;
    }

    public String raw() {
        return raw;
    }

    public String name() {
        return name;
    }

    public int port() {
        return port;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int bitsPerSample() {
        return bitsPerSample;
    }

    public int channels() {
        return channels;
    }

    public String codec() {
        return codec;
    }

    public static StreamSpec parse(String uri) {
        if (uri == null || uri.length() > 1024) {
            throw invalid("no stream uri");
        }
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd <= 0 || !"tcp".equalsIgnoreCase(uri.substring(0, schemeEnd))) {
            throw invalid("Only tcp stream sources are supported");
        }
        String rest = uri.substring(schemeEnd + 3);
        String queryText = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            queryText = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        if (rest.indexOf('/') >= 0) {
            throw invalid("a stream source has no path");
        }
        int portFromUri = -1;
        int colon = rest.lastIndexOf(':');
        if (colon >= 0) {
            portFromUri = port(rest.substring(colon + 1));
        }
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : queryText.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? decode(pair.substring(eq + 1)) : "";
            if (!KNOWN.contains(key)) {
                throw invalid("parameter '" + key + "' is not supported");
            }
            query.put(key, value);
        }
        String name = query.get("name");
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw invalid("a stream needs a name of 1 to " + MAX_NAME_LENGTH + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw invalid("the name has a control character");
            }
        }
        String mode = query.get("mode");
        if (mode != null && !"server".equals(mode)) {
            throw invalid("only mode=server is supported");
        }
        int port = query.containsKey("port") ? port(query.get("port")) : (portFromUri > 0 ? portFromUri : FIRST_PORT);
        if (port < FIRST_PORT || port > LAST_PORT) {
            throw invalid("the port has to be " + FIRST_PORT + " to " + LAST_PORT);
        }
        int rate = 48000;
        int bits = 16;
        int channels = 2;
        String format = query.get("sampleformat");
        if (format != null) {
            String[] parts = format.split(":");
            if (parts.length != 3) {
                throw invalid("sampleformat is rate:bits:channels");
            }
            rate = number(parts[0], "sample rate");
            bits = number(parts[1], "bits");
            channels = number(parts[2], "channels");
        }
        if (rate < 8000 || rate > 192000 || (bits != 16 && bits != 24 && bits != 32) || channels < 1 || channels > 2) {
            throw invalid("sample format not supported");
        }
        String codec = query.containsKey("codec") ? query.get("codec") : "pcm";
        if (!"pcm".equals(codec) && !"flac".equals(codec)) {
            throw invalid("codec '" + codec + "' is not supported");
        }
        return new StreamSpec(uri, name, port, rate, bits, channels, codec);
    }

    private static int port(String text) {
        int p = number(text, "port");
        if (p < 1 || p > 65535) {
            throw invalid("port out of range");
        }
        return p;
    }

    private static int number(String text, String what) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw invalid(what + " is not a number");
        }
    }

    private static String decode(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '%') {
                if (i + 2 >= text.length()) {
                    throw invalid("bad percent encoding");
                }
                try {
                    bytes.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException | StringIndexOutOfBoundsException bad) {
                    throw invalid("bad percent encoding");
                }
            }
            byte[] raw = String.valueOf(ch).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            bytes.write(raw, 0, raw.length);
        }
        return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ServerState.StateException invalid(String data) {
        return new ServerState.StateException(-32602, data);
    }
}
