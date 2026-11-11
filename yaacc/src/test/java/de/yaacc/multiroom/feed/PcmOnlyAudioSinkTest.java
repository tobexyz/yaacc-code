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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.AudioSink;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The sink of the Snapcast player takes raw PCM only: a compressed format would be passed through undecoded on a
 * path without the tap when the output (HDMI, USB, Bluetooth) plays it directly, and the group would hear nothing.
 */
public class PcmOnlyAudioSinkTest {

    private static Format of(String mime) {
        return new Format.Builder().setSampleMimeType(mime).build();
    }

    private final AudioSink delegate = mock(AudioSink.class);
    private final PcmOnlyAudioSink sink = new PcmOnlyAudioSink(delegate);

    @Test
    public void everyCompressedFormatIsRefusedWithoutAskingTheDeviceSink() {
        String[] mimes = {MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS,
                MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_AAC,
                MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_DTS_EXPRESS};
        for (String mime : mimes) {
            Format format = of(mime);
            when(delegate.supportsFormat(format)).thenReturn(true); // the device would pass it through
            when(delegate.getFormatSupport(format)).thenReturn(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY);

            assertFalse(mime, sink.supportsFormat(format));
            assertEquals(mime, AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(format));
            assertSame(mime, AudioOffloadSupport.DEFAULT_UNSUPPORTED, sink.getFormatOffloadSupport(format));
        }
        verify(delegate, never()).supportsFormat(any());
        verify(delegate, never()).getFormatSupport(any());
        verify(delegate, never()).getFormatOffloadSupport(any());
    }

    @Test
    public void rawPcmIsLeftToTheRealSink() {
        Format pcm = of(MimeTypes.AUDIO_RAW);
        when(delegate.supportsFormat(pcm)).thenReturn(true);
        when(delegate.getFormatSupport(pcm)).thenReturn(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY);
        AudioOffloadSupport offload = new AudioOffloadSupport.Builder().build();
        when(delegate.getFormatOffloadSupport(pcm)).thenReturn(offload);

        assertTrue(sink.supportsFormat(pcm));
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm));
        assertSame(offload, sink.getFormatOffloadSupport(pcm));
    }

    @Test
    public void aFormatWithoutATypeIsLeftToTheRealSink() {
        Format unknown = new Format.Builder().build();
        when(delegate.supportsFormat(unknown)).thenReturn(false);
        when(delegate.getFormatSupport(unknown)).thenReturn(AudioSink.SINK_FORMAT_UNSUPPORTED);

        assertFalse(sink.supportsFormat(unknown));
        verify(delegate).supportsFormat(unknown);
    }

    @Test
    public void thePlayerWrapsItsSinkInIt() throws Exception {
        String source = new String(Files.readAllBytes(new File("src/main/java/de/yaacc/player/SnapcastPlayer.java").toPath()),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("return new PcmOnlyAudioSink(new DefaultAudioSink.Builder(context).setAudioProcessors(chain).build());"));
        assertTrue("and the renderers factory uses it", source.contains("return newSink(context, chain);"));
        assertFalse("offload is never switched on", source.toLowerCase().contains("setaudiooffload")
                || source.contains("AudioOffloadPreferences") || source.contains("setAudioOffloadPreferences"));
    }
}
