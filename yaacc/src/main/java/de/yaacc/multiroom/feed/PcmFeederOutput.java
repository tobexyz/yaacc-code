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
package de.yaacc.multiroom.feed;


/** A {@link PcmFeeder} as a {@link FeedOutput}: the real socket, the real clock, the format of the stream. */
public final class PcmFeederOutput implements FeedOutput {

    /** Opens real feeders in {@link SnapcastStreams#FORMAT}. */
    public static final FeedOutputFactory FACTORY = (address, port, onFailed) -> new PcmFeederOutput(
            PcmFeeder.connect(address, port, SnapcastStreams.FORMAT, null, SocketFeedConnection.FACTORY, FeedClock.SYSTEM,
                    failure -> onFailed.run()));

    private final PcmFeeder feeder;

    PcmFeederOutput(PcmFeeder feeder) {
        this.feeder = feeder;
    }

    @Override
    public boolean write(byte[] data, int offset, int length) {
        return feeder.write(data, offset, length);
    }

    @Override
    public void discard() {
        feeder.discard();
    }

    @Override
    public boolean isRunning() {
        return feeder.getState() == PcmFeeder.State.RUNNING;
    }

    @Override
    public int getQueuedBytes() {
        return feeder.getQueuedBytes();
    }

    @Override
    public void close() {
        feeder.close();
    }

    /** The feeder, for the statistics a device test reads. */
    PcmFeeder getFeeder() {
        return feeder;
    }

    @Override
    public String statistics() {
        return "chunks=" + feeder.getChunksWritten() + " silence=" + feeder.getSilenceChunks() + " late="
                + feeder.getLateChunks() + " reanchors=" + feeder.getReanchors() + " dropped=" + feeder.getDroppedBytes()
                + " maxLeadMs=" + feeder.getMaxLeadNanos() / 1_000_000L;
    }
}
