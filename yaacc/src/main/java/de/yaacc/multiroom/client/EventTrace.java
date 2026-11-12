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

    public static final int CAPACITY = 300;

    private final long startMicros;
    private final long[] atMicros = new long[CAPACITY];
    private final TraceKind[] kinds = new TraceKind[CAPACITY];
    private final long[] a = new long[CAPACITY];
    private final long[] b = new long[CAPACITY];
    private final long[] c = new long[CAPACITY];
    private int next;
    private long total;

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
            TraceKind kind = kinds[s];
            out.append(String.format(Locale.ROOT, "%8d ms  %s", Math.floorDiv(atMicros[s] - startMicros, 1000L), kind.label()));
            long[] values = {a[s], b[s], c[s]};
            for (int f = 0; f < 3; f++) {
                String name = kind.field(f);
                if (!name.isEmpty()) {
                    out.append(String.format(Locale.ROOT, "  %s=%s", name, value(kind, f, values[f])));
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String value(TraceKind kind, int field, long v) {
        if (kind == TraceKind.FLUSH && field == 0) {
            FlushReason[] reasons = FlushReason.values();
            return v >= 0 && v < reasons.length ? reasons[(int) v].name().toLowerCase(Locale.ROOT) : "?";
        }
        return Long.toString(v);
    }

    private int slot(int index) {
        int n = size();
        int first = total > CAPACITY ? next : 0;
        return (first + Math.max(0, Math.min(index, n - 1))) % CAPACITY;
    }
}
