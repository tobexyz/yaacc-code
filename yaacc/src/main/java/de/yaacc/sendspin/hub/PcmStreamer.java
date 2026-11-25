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
package de.yaacc.sendspin.hub;

import de.yaacc.multiroom.feed.PcmSink;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * Turns the PCM that the player of YAACC produces (16 bit stereo, little endian, at the rate the Sendspin player listed) into the
 * timestamped chunks of a Sendspin stream and sends them with a fixed lead: a chunk goes out when it is {@code lead} ahead of the
 * moment it must play, so the player holds about {@code lead} of audio. That is the pacing the server owes the player: ExoPlayer
 * decodes faster than real time, and {@link #write} therefore waits (back pressure) until the chunk is due.
 * <p>
 * The timeline is continuous across tracks. It restarts at {@code now + lead} when a stream starts, after a {@link #discard()} (a
 * seek or a skip: {@code stream/clear}) and when the producer fell behind and the next chunk would be late (a gap in the sound, no
 * stream message). {@link #end()} sends {@code stream/end} (pause, stop) and refuses audio until {@link #begin()}; the stream starts
 * again with the next write. The audio a player may hold is bounded by its {@code buffer_capacity}: the bytes of the chunks that
 * have not finished playing, as the specification counts them (13 bytes of header and the payload of each chunk).
 * <p>
 * {@code write} is for one thread (the audio thread); {@code discard}, {@code end} and {@code begin} may come from another and
 * interrupt a wait in progress.
 * <p>
 * <b>A group</b> (linking, {@code groups.md}) is the same streamer with several members: one timeline (the same timestamps for every
 * member, which is what keeps them in sync), one chunk cut once, and for every member its own output and its own buffer accounting. The
 * lead of the group is the largest lead of its members, so that no member gets a chunk later than it asks for; a member that comes
 * in while the group plays is started by the audio thread with the next chunk and plays in sync from there (the timestamps are
 * absolute); a member whose output fails leaves the group and the others go on. With one member this is exactly the stream of one player.
 */
public final class PcmStreamer implements PcmSink {

    /** What the streamer sends; the connection implements it. */
    public interface Output {
        void streamStart(long serverTransmittedMicros, int sampleRate) throws IOException;

        void streamClear(long serverTransmittedMicros) throws IOException;

        void streamEnd() throws IOException;

        void audio(long timestampMicros, long sendAheadMicros, byte[] pcm, int offset, int length) throws IOException;

        /**
         * Breaks a write that does not end: the streamer calls it when a write has been stuck for a while (the player is gone and the socket
         * can not take more), so that {@code end}, {@code discard} and {@code removeMember} are not held up behind it. Any thread; the stuck
         * write then fails with an {@link IOException}.
         */
        default void abort() {
        }
    }

    /** What the player asked for, read at every chunk (it may change while a stream runs). */
    public interface Timing {
        /** How far ahead of its timestamp a chunk is sent, output delay included. */
        long leadMicros();

        /** The delay beyond the audio port that the player reported; chunks count as in the player's buffer until it has passed. */
        long outputDelayMicros();

        /** The player's {@code buffer_capacity} in bytes. */
        long capacityBytes();
    }

    /** Numbers for a log; no content. */
    public static final class Statistics {
        public long chunks;
        public long reanchors;
        public long clears;
        public long capacityWaits;
    }

    public static final int CHUNK_MILLIS = 20;
    private static final int FRAME_BYTES = 4;
    private static final int CHUNK_HEADER_BYTES = 13;
    /** The longest a single sleep of the wait lasts, so that a discard or a lead change is noticed quickly. */
    private static final long SLICE_MICROS = 20_000L;
    /** A chunk that would play less than this ahead of now is late: the timeline restarts. */
    private static final long LATE_MICROS = 30_000L;

    /** One player of the group: its output, its timing and the buffer it holds. Handed out by {@link #addMember}. */
    public static final class Member {
        private final Output out;
        private final Timing timing;
        private final ArrayDeque<long[]> inFlight = new ArrayDeque<>(); // {completion time, bytes}
        private long inFlightBytes;
        private boolean started;
        private boolean failed;
        private boolean removed;

        private Member(Output out, Timing timing) {
            this.out = out;
            this.timing = timing;
        }

        private boolean active() {
            return !failed && !removed;
        }

        private void resetBuffer() {
            inFlight.clear();
            inFlightBytes = 0;
        }
    }

    private final HubClock clock;
    private final int sampleRate;
    private final int chunkFrames;
    private final int chunkBytes;
    private final long chunkMicros;
    private final byte[] pending;
    private final Object lock = new Object();
    private final java.util.concurrent.CopyOnWriteArrayList<Member> members = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** When the audio thread began the write it is in now (nanoTime), 0 if it is in none: a write that is stuck is aborted. */
    private volatile long writingSinceNanos;
    private volatile long stuckNanos = 1_000_000_000L;
    private final Statistics statistics = new Statistics();

    private int pendingLength;
    private boolean streaming;
    private boolean accepting = true;
    private boolean failed;
    private long nextTimestamp;
    private long generation;

    /** The stream to one player. */
    public PcmStreamer(Output out, HubClock clock, Timing timing, int sampleRate) {
        this(clock, sampleRate);
        addMember(out, timing);
    }

    /** A group without members; {@link #addMember} adds the players. */
    public PcmStreamer(HubClock clock, int sampleRate) {
        if (sampleRate < 8000 || sampleRate > 384000 || sampleRate % 50 != 0) {
            throw new IllegalArgumentException("sample rate");
        }
        this.clock = clock;
        this.sampleRate = sampleRate;
        this.chunkFrames = sampleRate * CHUNK_MILLIS / 1000; // exact: the rate is a multiple of 50
        this.chunkBytes = chunkFrames * FRAME_BYTES;
        this.chunkMicros = CHUNK_MILLIS * 1000L;
        this.pending = new byte[chunkBytes];
    }

    public Statistics statistics() {
        return statistics;
    }

    /** For tests: how long a write may last before it counts as stuck. */
    void setStuckMillis(long millis) {
        stuckNanos = millis * 1_000_000L;
    }

    /** If the audio thread is stuck in a write, the outputs are told to break it: the lock is then free for the caller. */
    private void abortIfStuck() {
        long since = writingSinceNanos;
        if (since != 0 && System.nanoTime() - since > stuckNanos) {
            for (Member m : members) {
                m.out.abort();
            }
        }
    }

    /**
     * Adds a player to the group. It never writes to the network itself: while a stream runs, the audio thread starts the member with
     * the next chunk (so this may be called from any thread).
     */
    public Member addMember(Output out, Timing timing) {
        Member member = new Member(out, timing);
        synchronized (lock) {
            members.add(member);
            failed = false;
        }
        return member;
    }

    /**
     * Takes a player out of the group and sends it {@code stream/end} if its stream was started. This writes to the network: call it
     * from a thread that may.
     */
    public void removeMember(Member member) {
        abortIfStuck();
        synchronized (lock) {
            if (member.removed) {
                return;
            }
            if (member.started && member.active()) {
                try {
                    member.out.streamEnd();
                } catch (IOException e) {
                    // it leaves anyway
                }
            }
            member.removed = true;
            member.started = false;
            member.resetBuffer();
            members.remove(member);
            if (activeCount() == 0) {
                streaming = false;
            }
        }
    }

    /** True if this member's output failed (it has left the group by itself). */
    public boolean hasFailed(Member member) {
        synchronized (lock) {
            return member.failed;
        }
    }

    /** True while a stream is open at the players. */
    public boolean isStreaming() {
        synchronized (lock) {
            return streaming;
        }
    }

    private int activeCount() {
        int n = 0;
        for (Member m : members) {
            if (m.active()) {
                n++;
            }
        }
        return n;
    }

    /** The largest lead of the members: no member gets a chunk later than it asks for. */
    private long groupLead() {
        long lead = 0;
        for (Member m : members) {
            if (m.active()) {
                lead = Math.max(lead, m.timing.leadMicros());
            }
        }
        return lead;
    }

    // ------------------------------------------------------------------ PcmSink

    @Override
    public boolean write(byte[] data, int offset, int length) {
        int off = offset;
        int remaining = length;
        while (remaining > 0) {
            synchronized (lock) {
                if (failed) {
                    return false;
                }
                if (!accepting) {
                    return true; // the audio of a paused or stopped player: dropped
                }
                int n = Math.min(remaining, chunkBytes - pendingLength);
                System.arraycopy(data, off, pending, pendingLength, n);
                pendingLength += n;
                off += n;
                remaining -= n;
                if (pendingLength < chunkBytes) {
                    return true;
                }
            }
            if (!sendPendingChunk()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void discard() {
        abortIfStuck();
        synchronized (lock) {
            generation++;
            pendingLength = 0;
            if (streaming && !failed) {
                long now = clock.nowMicros();
                for (Member m : members) {
                    if (m.active() && m.started) {
                        try {
                            m.out.streamClear(now);
                        } catch (IOException e) {
                            m.failed = true;
                        }
                        m.resetBuffer();
                    }
                }
                statistics.clears++;
                if (activeCount() == 0) {
                    failed = true;
                    return;
                }
                nextTimestamp = clock.nowMicros() + groupLead();
            }
        }
    }

    /** Pause or stop: {@code stream/end} to every member; audio is refused until {@link #begin()}. */
    public void end() {
        abortIfStuck();
        synchronized (lock) {
            generation++;
            pendingLength = 0;
            accepting = false;
            if (streaming && !failed) {
                for (Member m : members) {
                    if (m.active() && m.started) {
                        try {
                            m.out.streamEnd();
                        } catch (IOException e) {
                            m.failed = true;
                        }
                    }
                }
                if (activeCount() == 0) {
                    failed = true;
                }
            }
            streaming = false;
            for (Member m : members) {
                m.started = false;
                m.resetBuffer();
            }
        }
    }

    /** Play or resume: audio is accepted again; the stream starts with the next write. */
    public void begin() {
        synchronized (lock) {
            accepting = true;
        }
    }

    /** True when no member is left that can be sent to (with one member: its output failed). */
    public boolean hasFailed() {
        synchronized (lock) {
            return failed;
        }
    }

    // ------------------------------------------------------------------ one chunk

    private boolean sendPendingChunk() {
        final long myGeneration;
        synchronized (lock) {
            myGeneration = generation;
        }
        while (true) {
            long wait;
            synchronized (lock) {
                if (failed) {
                    return false;
                }
                if (myGeneration != generation || !accepting) {
                    return true; // a discard or an end overtook this chunk: it is stale
                }
                if (activeCount() == 0) {
                    failed = true;
                    return false;
                }
                long now = clock.nowMicros();
                long lead = groupLead();
                boolean wasStreaming = streaming;
                boolean joined = false;
                // a member that is not started yet (the first start of the stream, or a player that came in while it runs)
                writingSinceNanos = System.nanoTime();
                for (Member m : members) {
                    if (m.active() && !m.started) {
                        try {
                            m.out.streamStart(now, sampleRate);
                            m.started = true;
                            m.resetBuffer();
                            joined = joined || wasStreaming;
                        } catch (IOException e) {
                            m.failed = true;
                        }
                    }
                }
                writingSinceNanos = 0;
                if (activeCount() == 0) {
                    failed = true;
                    return false;
                }
                if (!wasStreaming) {
                    streaming = true;
                    nextTimestamp = now + lead;
                } else if (nextTimestamp < now + LATE_MICROS) {
                    nextTimestamp = now + lead; // the producer fell behind: a gap, and the timeline starts again
                    statistics.reanchors++;
                } else if (joined && nextTimestamp < now + lead - LATE_MICROS / 3) {
                    nextTimestamp = now + lead; // a member that needs more lead than the group had: the timeline moves for everybody
                    statistics.reanchors++;
                }
                // what the players have finished with is no longer in their buffers
                for (Member m : members) {
                    while (m.active() && !m.inFlight.isEmpty() && m.inFlight.peekFirst()[0] <= now) {
                        m.inFlightBytes -= m.inFlight.pollFirst()[1];
                    }
                }
                long sendAt = nextTimestamp - lead;
                wait = sendAt - now;
                long size = CHUNK_HEADER_BYTES + chunkBytes;
                if (wait <= 0) {
                    long capacityWait = 0;
                    for (Member m : members) {
                        if (m.active() && m.inFlightBytes + size > m.timing.capacityBytes() && !m.inFlight.isEmpty()) {
                            capacityWait = Math.max(capacityWait, Math.max(1000L, m.inFlight.peekFirst()[0] - now));
                        }
                    }
                    if (capacityWait > 0) {
                        wait = capacityWait;
                        statistics.capacityWaits++;
                    }
                }
                if (wait <= 0) {
                    writingSinceNanos = System.nanoTime();
                    for (Member m : members) {
                        if (!m.active()) {
                            continue;
                        }
                        try {
                            m.out.audio(nextTimestamp, nextTimestamp - now, pending, 0, chunkBytes);
                            m.inFlight.addLast(new long[]{nextTimestamp + chunkMicros - m.timing.outputDelayMicros(), size});
                            m.inFlightBytes += size;
                        } catch (IOException e) {
                            m.failed = true; // it leaves the group; the others go on
                        }
                    }
                    writingSinceNanos = 0;
                    statistics.chunks++;
                    nextTimestamp += chunkMicros;
                    pendingLength = 0;
                    if (activeCount() == 0) {
                        failed = true;
                        return false;
                    }
                    return true;
                }
            }
            try {
                clock.sleepMicros(Math.min(wait, SLICE_MICROS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
