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
 * A bounded record of what the engine and the output did, for finding out why a stream does not play right: the last
 * {@link #CAPACITY} events with the time since the trace began and up to three numbers each. The oldest are overwritten.
 * Thread safe (the engine thread writes, the screen reads). Pure.
 */
public final class EventTrace {

    public static final int CAPACITY = 600;
    /** The separate ring of rare, important events (they scroll out of the main ring in minutes when a stream is busy). */
    public static final int IMPORTANT_CAPACITY = 100;

    private final long startMicros;
    private final long[] atMicros = new long[CAPACITY];
    private final TraceKind[] kinds = new TraceKind[CAPACITY];
    private final long[] a = new long[CAPACITY];
    private final long[] b = new long[CAPACITY];
    private final long[] c = new long[CAPACITY];
    private int next;
    private long total;
    private final long[] iAt = new long[IMPORTANT_CAPACITY];
    private final TraceKind[] iKinds = new TraceKind[IMPORTANT_CAPACITY];
    private final long[] iA = new long[IMPORTANT_CAPACITY];
    private final long[] iB = new long[IMPORTANT_CAPACITY];
    private final long[] iC = new long[IMPORTANT_CAPACITY];
    private int iNext;
    private long iTotal;

    /** @param startMicros the engine clock at the start; the events show the time since then */
    public EventTrace(long startMicros) {
        this.startMicros = startMicros;
    }

    public synchronized void add(long nowMicros, TraceKind kind, long first, long second, long third) {
        atMicros[next] = nowMicros;
        kinds[next] = kind;
        a[next] = first;
        b[next] = second;
        c[next] = third;
        next = (next + 1) % CAPACITY;
        total++;
        if (isImportant(kind, first, second, third)) {
            iAt[iNext] = nowMicros;
            iKinds[iNext] = kind;
            iA[iNext] = first;
            iB[iNext] = second;
            iC[iNext] = third;
            iNext = (iNext + 1) % IMPORTANT_CAPACITY;
            iTotal++;
        }
    }

    /** What is kept for the whole session: the events after which the sound may have moved, and the session's own turning points. */
    static boolean isImportant(TraceKind kind, long first, long second, long third) {
        switch (kind) {
            case FLAC_SHIFT:
            case FLAC_FILL_DETAIL:
            case FLAC_REANCHOR:
            case FLAC_POSITIONS:
            case STREAM_RESUMED:
            case FLUSH:
            case HARD_LATE:
            case HOLE_MARKED:
            case HOLE_RECOVERED:
            case HEADER_ACCEPTED:
            case HEADER_IGNORED:
            case DECODER_RESET:
            case EPOCH_CHANGE:
            case TIMESTAMP_ODD:
            case WRITE_AHEAD_RAISED:
            case CONNECTION_LOST:
            case SESSION_END:
            case HEAD_FALLBACK:
                return true;
            case STEP_PLAYING:
                return Math.abs(third) > 2_000L; // a pad or skip while playing, with the sound more than 2 ms off
            default:
                return false;
        }
    }

    /** Important events kept (at most {@link #IMPORTANT_CAPACITY}). */
    public synchronized int importantSize() {
        return (int) Math.min(iTotal, IMPORTANT_CAPACITY);
    }

    /** The important events, oldest first, in the format of {@link #text()}. */
    public synchronized String importantText() {
        StringBuilder out = new StringBuilder(importantSize() * 64);
        if (iTotal > IMPORTANT_CAPACITY) {
            out.append(String.format(Locale.ROOT, "(%d older important events left out)%n", iTotal - IMPORTANT_CAPACITY));
        }
        int n = importantSize();
        int first = iTotal > IMPORTANT_CAPACITY ? iNext : 0;
        for (int i = 0; i < n; i++) {
            int s = (first + i) % IMPORTANT_CAPACITY;
            line(out, iAt[s], iKinds[s], iA[s], iB[s], iC[s]);
        }
        return out.toString();
    }

    public void add(long nowMicros, TraceKind kind) {
        add(nowMicros, kind, 0, 0, 0);
    }

    public void add(long nowMicros, TraceKind kind, long first) {
        add(nowMicros, kind, first, 0, 0);
    }

    public void add(long nowMicros, TraceKind kind, long first, long second) {
        add(nowMicros, kind, first, second, 0);
    }

    /** Events kept (at most {@link #CAPACITY}). */
    public synchronized int size() {
        return (int) Math.min(total, CAPACITY);
    }

    /** Events ever added. */
    public synchronized long total() {
        return total;
    }

    /** How many of the oldest events were overwritten. */
    public synchronized long overwritten() {
        return Math.max(0, total - CAPACITY);
    }

    /** The kind of the event with this index, 0 being the oldest kept one. */
    public synchronized TraceKind kindAt(int index) {
        return kinds[slot(index)];
    }

    /** Oldest first, one event per line: "  1234 ms  flush  reason=hard resync  written frames=19200". */
    public synchronized String text() {
        StringBuilder out = new StringBuilder(size() * 64);
        if (overwritten() > 0) {
            out.append(String.format(Locale.ROOT, "(%d older events left out)%n", overwritten()));
        }
        int n = size();
        for (int i = 0; i < n; i++) {
            int s = slot(i);
            line(out, atMicros[s], kinds[s], a[s], b[s], c[s]);
        }
        return out.toString();
    }

    private void line(StringBuilder out, long at, TraceKind kind, long first, long second, long third) {
        out.append(String.format(Locale.ROOT, "%8d ms  %s", Math.floorDiv(at - startMicros, 1000L), kind.label()));
        long[] values = {first, second, third};
        for (int f = 0; f < 3; f++) {
            String name = kind.field(f);
            if (!name.isEmpty()) {
                out.append(String.format(Locale.ROOT, "  %s=%s", name, value(kind, f, values[f])));
            }
        }
        out.append('\n');
    }

    private static String value(TraceKind kind, int field, long v) {
        if (kind == TraceKind.FLUSH && field == 0) {
            FlushReason[] reasons = FlushReason.values();
            return v >= 0 && v < reasons.length ? reasons[(int) v].name().toLowerCase(Locale.ROOT) : "?";
        }
        if (kind == TraceKind.UNDERRUN_JUDGED && field == 0) {
            return v == 1 ? "output dry (position stalled or empty): flush" : v == 2 ? "thin cushion (position fine): write-ahead raised" : "?";
        }
        if (kind == TraceKind.SESSION_END) {
            SnapcastClientRuntime.Ending[] endings = SnapcastClientRuntime.Ending.values();
            return v >= 0 && v < endings.length ? endings[(int) v].name().toLowerCase(Locale.ROOT) : "?";
        }
        if (kind == TraceKind.CONNECTION_LOST) {
            if (field == 0) {
                de.yaacc.multiroom.protocol.SnapcastConnection.LossReason[] reasons =
                        de.yaacc.multiroom.protocol.SnapcastConnection.LossReason.values();
                return v >= 0 && v < reasons.length ? reasons[(int) v].name().toLowerCase(Locale.ROOT) : "?";
            }
            switch ((int) v) {
                case 1:
                    return "idle limit";
                case 2:
                    return "handshake limit";
                case 3:
                    return "writer queue full";
                case 4:
                    return "message too slow or read timeout";
                default:
                    return "no (server or network)";
            }
        }
        return Long.toString(v);
    }

    private int slot(int index) {
        int n = size();
        int first = total > CAPACITY ? next : 0;
        return (first + Math.max(0, Math.min(index, n - 1))) % CAPACITY;
    }
}
