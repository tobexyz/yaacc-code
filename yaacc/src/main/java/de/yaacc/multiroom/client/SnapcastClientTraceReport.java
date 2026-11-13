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
package de.yaacc.multiroom.client;

import java.util.Locale;

/**
 * The text the "Copy trace" button puts on the clipboard: a one-line summary (device, stream, output), the status text and
 * the event trace. The event trace has numbers and fixed words only; the status text also has the cleaned `server says`
 * lines (the start of the group id, the stream names and states). Pure.
 */
public final class SnapcastClientTraceReport {

    private SnapcastClientTraceReport() {
    }

    /**
     * The one-line summary: device model and API level, codec and sample format, then what the output says about itself
     * (track buffer frames, start threshold, underruns, whether getTimestamp ever worked, clock base age).
     *
     * @param device e.g. "Pixel 7 / API 34"; the caller's text, not the server's
     */
    public static String summary(EngineStatus status, String device) {
        if (status == null) {
            return "device " + device + " | no engine yet";
        }
        return String.format(Locale.ROOT, "device %s | codec %s, %d Hz, %d ch, %d bit | %s | flushes %d (hard resync %d), underruns %d",
                device, status.codec.isEmpty() ? "-" : status.codec, status.sampleRate, status.channels, status.sourceBits,
                status.outputSummary == null || status.outputSummary.isEmpty() ? "no output yet" : status.outputSummary,
                status.flushes, status.flushesHardResync, status.underruns);
    }

    /** Summary, status text and trace for the clipboard. */
    public static String of(SnapcastClientRuntime.View view, String device, long nowMillis) {
        EngineStatus status = view.engine;
        StringBuilder out = new StringBuilder(8192);
        out.append(summary(status, device)).append("\n\n");
        out.append(SnapcastClientStatusText.of(view, nowMillis)).append("\n");
        out.append("event trace (oldest first, ms since the engine started):\n");
        if (status == null || status.trace == null || status.trace.size() == 0) {
            out.append("(empty)\n");
        } else {
            out.append(status.trace.text());
        }
        return out.toString();
    }
}
