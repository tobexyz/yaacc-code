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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The loop that takes decoded audio out of the platform codec (review W1 of the security review of Group 8): it must end
 * whatever the codec does, and must not read on after the first buffer that is over the limit. The codec is a fake.
 */
public class CodecDrainTest {

    /** A codec output that plays back a script; each step advances a fake clock by the time the call "took". */
    private static final class FakeCodec implements CodecDrain.Source {
        final ArrayDeque<Object> script = new ArrayDeque<>();
        final List<Integer> released = new ArrayList<>();
        final AtomicLong nanos;
        final long nanosPerCall;
        int calls;
        int copies;
        boolean endless;
        int endlessSize;

        FakeCodec(AtomicLong nanos, long nanosPerCall) {
            this.nanos = nanos;
            this.nanosPerCall = nanosPerCall;
        }

        @Override
        public CodecDrain.Step next(long timeoutMicros) {
            calls++;
            nanos.addAndGet(nanosPerCall);
            if (endless) {
                return buffer(calls, endlessSize);
            }
            Object step = script.poll();
            if (step == null || step == CodecDrain.Kind.TRY_AGAIN) {
                return new CodecDrain.Step(CodecDrain.Kind.TRY_AGAIN, 0, -1, null);
            }
            if (step == CodecDrain.Kind.FORMAT_CHANGED) {
                return new CodecDrain.Step(CodecDrain.Kind.FORMAT_CHANGED, 0, -1, null);
            }
            return buffer(calls, (Integer) step);
        }

        private CodecDrain.Step buffer(int index, int size) {
            return new CodecDrain.Step(CodecDrain.Kind.BUFFER, size, index, null);
        }

        @Override
        public byte[] copy(CodecDrain.Step step) {
            copies++;
            return new byte[step.getSize()];
        }

        @Override
        public void release(CodecDrain.Step step) {
            released.add(step.getIndex());
        }
    }

    private static CodecDrain drain(AtomicLong nanos, long limitBytes, int maxBuffers, long deadlineMillis) {
        return new CodecDrain(limitBytes, maxBuffers, deadlineMillis, 5_000L, nanos::get);
    }

    @Test
    public void whatTheCodecHasIsCollectedUntilItHasNothingMore() throws Exception {
        AtomicLong nanos = new AtomicLong();
        FakeCodec codec = new FakeCodec(nanos, 100_000);
        codec.script.add(100);
        codec.script.add(200);
        CodecDrain.Result result = drain(nanos, 10_000, 64, 200).run(codec, () -> { });
        assertEquals(CodecDrain.Outcome.OK, result.outcome);
        assertEquals(300, result.pcm.length);
        assertEquals("every buffer was given back exactly once", 2, codec.released.size());
    }

    @Test
    public void theFirstBufferOverTheLimitEndsTheDrainAndNothingMoreIsReadOrCopied() throws Exception {
        AtomicLong nanos = new AtomicLong();
        FakeCodec codec = new FakeCodec(nanos, 100_000);
        codec.script.add(900);
        codec.script.add(5_000); // over the limit of 1000 together with the first
        for (int i = 0; i < 100; i++) {
            codec.script.add(5_000); // the hostile rest
        }
        CodecDrain.Result result = drain(nanos, 1000, 64, 200).run(codec, () -> { });
        assertEquals(CodecDrain.Outcome.OVER_LIMIT, result.outcome);
        assertEquals("only the first buffer was copied", 1, codec.copies);
        assertEquals("the codec was asked twice and never again", 2, codec.calls);
        assertEquals("the over-limit buffer was given back, not read", 2, codec.released.size());
        assertEquals("nothing is handed on from a chunk that was too large", 0, result.pcm.length);
    }

    @Test(timeout = 5000)
    public void aCodecThatNeverStopsProducingIsStoppedByTheNumberOfBuffers() throws Exception {
        AtomicLong nanos = new AtomicLong();
        FakeCodec codec = new FakeCodec(nanos, 1_000);
        codec.endless = true;
        codec.endlessSize = 1; // small buffers: never over the byte limit
        CodecDrain.Result result = drain(nanos, 1_000_000, 64, 10_000).run(codec, () -> { });
        assertEquals(CodecDrain.Outcome.TOO_MANY_BUFFERS, result.outcome);
        assertEquals(65, codec.calls);
        assertEquals(codec.calls, codec.released.size());
    }

    @Test(timeout = 5000)
    public void aCodecThatIsSlowIsStoppedByTheDeadlineOfTheChunk() throws Exception {
        AtomicLong nanos = new AtomicLong();
        FakeCodec codec = new FakeCodec(nanos, 60_000_000L); // every call takes 60 ms
        codec.endless = true;
        codec.endlessSize = 1;
        CodecDrain.Result result = drain(nanos, 1_000_000, 10_000, 200).run(codec, () -> { });
        assertEquals(CodecDrain.Outcome.DEADLINE, result.outcome);
        assertTrue("calls " + codec.calls, codec.calls <= 5);
    }

    @Test
    public void aChangeOfTheOutputFormatIsCheckedAndCanRefuseTheStream() throws Exception {
        AtomicLong nanos = new AtomicLong();
        FakeCodec codec = new FakeCodec(nanos, 1_000);
        codec.script.add(CodecDrain.Kind.FORMAT_CHANGED);
        codec.script.add(50);
        final int[] checks = {0};
        CodecDrain.Result ok = drain(nanos, 1000, 64, 200).run(codec, () -> checks[0]++);
        assertEquals(1, checks[0]);
        assertEquals(50, ok.pcm.length);

        FakeCodec bad = new FakeCodec(nanos, 1_000);
        bad.script.add(CodecDrain.Kind.FORMAT_CHANGED);
        bad.script.add(50);
        try {
            drain(nanos, 1000, 64, 200).run(bad, () -> {
                throw new DecoderException(DecoderException.Reason.UNSUPPORTED_OUTPUT, "format");
            });
            assertFalse("refused", true);
        } catch (DecoderException e) {
            assertEquals(DecoderException.Reason.UNSUPPORTED_OUTPUT, e.getReason());
            assertEquals("nothing was read after the refusal", 1, bad.calls);
        }
    }

    @Test
    public void emptyBuffersAreGivenBackAndNotCopied() throws Exception {
        AtomicLong nanos = new AtomicLong();
        FakeCodec codec = new FakeCodec(nanos, 1_000);
        codec.script.add(0);
        codec.script.add(10);
        CodecDrain.Result result = drain(nanos, 1000, 64, 200).run(codec, () -> { });
        assertEquals(1, codec.copies);
        assertEquals(2, codec.released.size());
        assertArrayEquals(new byte[10], result.pcm);
    }

    @Test(timeout = 5000)
    public void unknownAnswersAreLookedPastButBoundedByTheDeadline() throws Exception {
        AtomicLong nanos = new AtomicLong();
        CodecDrain.Source forever = new CodecDrain.Source() {
            int calls;

            @Override
            public CodecDrain.Step next(long timeoutMicros) {
                calls++;
                nanos.addAndGet(10_000_000L);
                return new CodecDrain.Step(CodecDrain.Kind.AGAIN, 0, -1, null);
            }

            @Override
            public byte[] copy(CodecDrain.Step step) {
                throw new AssertionError("nothing to copy");
            }

            @Override
            public void release(CodecDrain.Step step) {
                throw new AssertionError("nothing to release");
            }
        };
        assertEquals(CodecDrain.Outcome.DEADLINE, drain(nanos, 1000, 64, 100).run(forever, () -> { }).outcome);
    }
}
