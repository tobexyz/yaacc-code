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

import java.io.ByteArrayOutputStream;
import java.util.function.LongSupplier;

/**
 * The loop that takes the decoded audio of one chunk out of a platform codec, with every bound the engine thread needs:
 * a limit on the bytes (it stops at the <b>first</b> buffer that would exceed it, gives that buffer back without reading
 * it and does not look at the rest), a limit on the number of buffers, and a deadline for the whole chunk. A hostile
 * stream can make a decoder produce far more audio than it was given; without these bounds one chunk could keep the
 * (high priority) audio thread busy for as long as the codec keeps answering. The codec itself is behind {@link Source}, so
 * the bounds are tested with a fake; {@code MediaCodecFlacDecoder} only adapts {@code MediaCodec} to it.
 */
public final class CodecDrain {

    /** What one call of the codec gave. */
    public enum Kind {
        /** A buffer of decoded audio. */
        BUFFER,
        /** Nothing more for now. */
        TRY_AGAIN,
        /** The format of the output changed: check it. */
        FORMAT_CHANGED,
        /** Something else happened (a deprecated notice): look again; the deadline bounds the looping. */
        AGAIN
    }

    /** Why the drain ended. */
    public enum Outcome {
        OK, OVER_LIMIT, TOO_MANY_BUFFERS, DEADLINE
    }

    /** One answer of the codec. */
    public static final class Step {
        final Kind kind;
        final int size;
        final int index;
        final Object handle;

        public Step(Kind kind, int size, int index, Object handle) {
            this.kind = kind;
            this.size = size;
            this.index = index;
            this.handle = handle;
        }

        public int getIndex() {
            return index;
        }

        public int getSize() {
            return size;
        }

        public Object getHandle() {
            return handle;
        }
    }

    /** The output side of a codec. */
    public interface Source {
        /** Waits at most {@code timeoutMicros} for the next answer. */
        Step next(long timeoutMicros);

        /** Copies the audio of a BUFFER step. */
        byte[] copy(Step step);

        /** Gives a BUFFER step back to the codec (always, exactly once). */
        void release(Step step);
    }

    /** Looks at the new output format; throws when the engine can not use it. */
    public interface FormatCheck {
        void check() throws DecoderException;
    }

    /** The audio (empty unless the outcome is OK) and why the drain ended. */
    public static final class Result {
        public final Outcome outcome;
        public final byte[] pcm;

        Result(Outcome outcome, byte[] pcm) {
            this.outcome = outcome;
            this.pcm = pcm;
        }
    }

    private static final byte[] NOTHING = new byte[0];

    private final long limitBytes;
    private final int maxBuffers;
    private final long deadlineNanos;
    private final long timeoutMicros;
    private final LongSupplier nanos;

    public CodecDrain(long limitBytes, int maxBuffers, long deadlineMillis, long timeoutMicros, LongSupplier nanos) {
        this.limitBytes = limitBytes;
        this.maxBuffers = maxBuffers;
        this.deadlineNanos = deadlineMillis * 1_000_000L;
        this.timeoutMicros = timeoutMicros;
        this.nanos = nanos;
    }

    public Result run(Source source, FormatCheck formatCheck) throws DecoderException {
        long start = nanos.getAsLong();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffers = 0;
        while (true) {
            if (nanos.getAsLong() - start >= deadlineNanos) {
                return new Result(Outcome.DEADLINE, NOTHING);
            }
            Step step = source.next(timeoutMicros);
            if (step.kind == Kind.TRY_AGAIN) {
                return new Result(Outcome.OK, out.toByteArray());
            }
            if (step.kind == Kind.FORMAT_CHANGED) {
                formatCheck.check();
                continue;
            }
            if (step.kind == Kind.AGAIN) {
                continue;
            }
            try {
                if (++buffers > maxBuffers) {
                    return new Result(Outcome.TOO_MANY_BUFFERS, NOTHING);
                }
                if (step.size > 0) {
                    if (out.size() + (long) step.size > limitBytes) {
                        return new Result(Outcome.OVER_LIMIT, NOTHING); // not read, not copied; the caller resets the codec
                    }
                    byte[] piece = source.copy(step);
                    out.write(piece, 0, piece.length);
                }
            } finally {
                source.release(step);
            }
        }
    }
}
