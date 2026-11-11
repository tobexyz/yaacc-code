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

import androidx.annotation.OptIn;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.ToInt16PcmAudioProcessor;
import androidx.media3.common.util.UnstableApi;

/**
 * DRAFT of task 4.1: the Media3 processors that turn what ExoPlayer decodes into the format of the Snapcast stream and
 * hand it to a {@link PcmSink}. Not wired into the player: task 4.4 waits for the owner's device spike.
 *
 * <p>The order matters:
 * <ol>
 *   <li>{@code ToInt16PcmAudioProcessor} (Media3): 8, 24, 32 bit and float to 16 bit. It is inactive for 16 bit.</li>
 *   <li>{@code SonicAudioProcessor} (Media3) with {@code setOutputSampleRateHz}: the sample rate, which is not written by
 *       hand. It accepts 16 bit and float only, hence the step before it, and is inactive when the rate matches.</li>
 *   <li>{@link PcmTapAudioProcessor}: folds the channels to stereo and hands the PCM over.</li>
 * </ol>
 * The Media3 {@code ChannelMixingAudioProcessor} is not used for the channels: its constant-power matrices exist for 1
 * to 6 channels only and it keeps a {@code SparseArray}, so it can not be tested on the JVM; {@link PcmFormatConverter}
 * handles 1 to 8 channels with a documented, tested fold-down.
 *
 * <p>Use: {@code new DefaultAudioSink.Builder(context).setAudioProcessors(FeedTapChain.create(...)).build()} from the
 * {@code buildAudioSink} of a {@code DefaultRenderersFactory}. Where in ExoPlayer's own pipeline these processors sit
 * (before or after its channel mapping and trimming) is one of the questions of the device spike, see "Snapcast feed
 * tap (draft)" in {@code docs/tech.md}.
 */
@OptIn(markerClass = UnstableApi.class)
public final class FeedTapChain {

    private FeedTapChain() {
    }

    /**
     * A new chain (the processors keep state: one chain per player).
     *
     * @param target      the format of the stream, rate included
     * @param sink        receives the PCM
     * @param playLocally false to mute the phone's own speaker
     */
    public static AudioProcessor[] create(PcmFormat target, PcmSink sink, boolean playLocally) {
        SonicAudioProcessor rate = new SonicAudioProcessor();
        rate.setOutputSampleRateHz(target.getSampleRate());
        return new AudioProcessor[]{new ToInt16PcmAudioProcessor(), rate, new PcmTapAudioProcessor(target, sink, playLocally)};
    }
}
