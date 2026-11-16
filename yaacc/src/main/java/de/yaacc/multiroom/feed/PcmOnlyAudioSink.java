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
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

/**
 * An audio sink that never takes a compressed format, so that ExoPlayer decodes every track to PCM and the audio goes
 * through the processors of the tap ({@link FeedTapChain}).
 *
 * <p><b>Why.</b> For a compressed format (AC-3, E-AC-3, DTS, TrueHD, ...) the {@code DefaultAudioSink} asks the audio
 * capabilities of the output the phone is connected to; if HDMI, USB or Bluetooth reports that it plays the format
 * directly, the track is passed through undecoded on a path with <b>no processors at all</b>: the group would hear
 * nothing while the phone's own output plays the track. In Media3 1.11.1 these capabilities can not be fixed on the
 * {@code DefaultAudioSink.Builder}: {@code setAudioCapabilities} counts only for the deprecated builder without a
 * context; with a context ({@code javap -c} of {@code DefaultAudioSink.build}) the builder hands {@code null}
 * to the output provider, which registers an {@code AudioCapabilitiesReceiver} and uses the device's real
 * capabilities. So the sink itself answers "not supported" for anything that is not raw PCM; the renderer then
 * chooses a decoder (as it does on a plain phone speaker) and offers the decoded PCM, which is delegated.
 * Offload is refused the same way (it is a compressed path as well).
 */
@OptIn(markerClass = UnstableApi.class)
public final class PcmOnlyAudioSink extends ForwardingAudioSink {

    public PcmOnlyAudioSink(AudioSink sink) {
        super(sink);
    }

    private static boolean isCompressed(Format format) {
        return format != null && format.sampleMimeType != null && !MimeTypes.AUDIO_RAW.equals(format.sampleMimeType);
    }

    @Override
    public boolean supportsFormat(Format format) {
        return !isCompressed(format) && super.supportsFormat(format);
    }

    @Override
    public int getFormatSupport(Format format) {
        return isCompressed(format) ? SINK_FORMAT_UNSUPPORTED : super.getFormatSupport(format);
    }

    @Override
    public AudioOffloadSupport getFormatOffloadSupport(Format format) {
        return isCompressed(format) ? AudioOffloadSupport.DEFAULT_UNSUPPORTED : super.getFormatOffloadSupport(format);
    }
}
