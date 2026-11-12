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

/**
 * What the event trace records. Every event has up to three numbers; the labels say what they mean. No text of the server
 * ever goes in (only numbers and these fixed words).
 */
public enum TraceKind {
    HEADER_ACCEPTED("header accepted", "rate", "channels", "bits"),
    HEADER_IGNORED("header ignored (same stream)", "", "", ""),
    CHUNK_DROPPED("chunk dropped", "why", "drops total", ""),
    ALIGN_START("align start", "drift us", "written frames", "queued ms"),
    ALIGN_STEP("align step", "pad frames", "skip frames", "drift us"),
    ALIGNED("aligned", "drift us", "padded total", "skipped total"),
    FIRST_WRITE("first write", "frames", "start threshold", "track buffer"),
    PLAY("play()", "written frames", "start threshold", ""),
    PRIME("no position yet, queueing more silence", "written frames", "start threshold", ""),
    POSITION("position", "frames", "age ms", "queued frames"),
    POSITION_REFUSED("position refused", "frames", "written frames", "age ms"),
    POSITION_HELD("position held back (odd)", "frames", "age ms", "off by us"),
    DRIFT("drift", "drift us", "aligned", "output ahead ms"),
    HARD_LATE("late over the hard limit", "drift us", "reports in a row", ""),
    FLUSH("flush", "reason", "written frames", "drift us"),
    UNDERRUN("underrun", "new", "total", "aligned"),
    UNDERRUN_JUDGED("underrun counter judged", "verdict", "spurious in a row", "output ahead ms"),
    HOLE_MARKED("hole marked", "holes", "", ""),
    HOLE_RECOVERED("hole recovered", "holes", "", ""),
    DECODER_RESET("decoder reset", "why", "", ""),
    TIMESTAMP_ODD("timestamp on another clock base (first one)", "age ms", "", ""),
    HEAD_FALLBACK("position from head position now", "failed polls", "implausible", ""),
    CONNECTION_LOST("connection lost", "why", "own limit", ""),
    STREAM_RESUMED("stream resumed", "gap ms", "action (1 re-anchor, 2 gap filled, 3 too late to skip)", ""),
    FIRST_AUDIO("first audio written after the resume", "after ms", "", ""),
    LOOP("engine loop, last second", "ticks", "max gap ms", "longest write us"),
    CALLS("longest calls, last second", "tick us", "one chunk us", "position poll us"),
    WRITES("writes, last second", "writes", "short", "worst short (frames missing)"),
    CORRECTIONS("corrections, last second", "dropped frames", "repeated frames", "largest in one block"),
    STEP_PLAYING("pad or skip while playing", "pad frames", "skip frames", "drift us"),
    CLOCK("clock offset", "offset us", "change us", "jitter us"),
    WRITE_FAILED("write failed", "reason", "", ""),
    POLLS("position reports", "taken", "refused", "held back"),
    QUEUE("queues", "waiting ms", "output ahead ms", "chunks");

    private final String label;
    private final String[] fields;

    TraceKind(String label, String a, String b, String c) {
        this.label = label;
        this.fields = new String[]{a, b, c};
    }

    String label() {
        return label;
    }

    String field(int index) {
        return fields[index];
    }
}
