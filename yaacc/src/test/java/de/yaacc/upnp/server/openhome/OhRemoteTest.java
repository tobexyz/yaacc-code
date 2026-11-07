/*
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
package de.yaacc.upnp.server.openhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.yaacc.openhome.OhControlException;
import de.yaacc.openhome.OhError;
import de.yaacc.openhome.OhQueue;
import de.yaacc.openhome.OhRemote;
import de.yaacc.openhome.OhSources;
import de.yaacc.openhome.OhTransportState;

/**
 * The controller ({@link OhRemote}) against the OpenHome services of YAACC's own renderer, called through the real Cling services
 * in the same process. Plain JVM.
 */
public class OhRemoteTest {

    private static final class Recorder implements OhPlayback {
        final List<String> calls = new ArrayList<>();

        @Override public void play() { calls.add("play"); }
        @Override public void pause() { calls.add("pause"); }
        @Override public void stop() { calls.add("stop"); }
        @Override public void next() { calls.add("next"); }
        @Override public void previous() { calls.add("previous"); }
        @Override public void setRepeat(boolean repeat) { calls.add("repeat " + repeat); }
        @Override public void setShuffle(boolean shuffle) { calls.add("shuffle " + shuffle); }
        @Override public void seekSecondAbsolute(long second) { calls.add("abs " + second); }
        @Override public void seekSecondRelative(int seconds) { calls.add("rel " + seconds); }
        @Override public void seekToTrack(long id) { calls.add("seek " + id); }
        @Override public void trackInserted(int index, OhQueue.Entry entry) { calls.add("inserted " + index); }
        @Override public void trackDeleted(int index, long id) { calls.add("deleted " + id); }
        @Override public void queueCleared() { calls.add("cleared"); }
        @Override public void setVolume(int percent) { calls.add("volume " + percent); }
        @Override public void setMute(boolean mute) { calls.add("mute " + mute); }
    }

    private Recorder device;
    private OhRendererCore core;
    private CoreControl control;
    private OhRemote remote;

    @Before
    public void setUp() throws Exception {
        device = new Recorder();
        core = new OhRendererCore(device, (runnable, delay) -> runnable.run(),
                new OhRendererCore.Details("m", "https://example.org", "model", "room", "name"), "http-get:*:audio/mpeg:*");
        control = new CoreControl(core);
        remote = new OhRemote(control, millis -> {
        });
    }

    private static OhRemote.Track track(String name) {
        return new OhRemote.Track("http://h/" + name + ".mp3", "<DIDL-Lite><item><dc:title>" + name + " &amp; more</dc:title></item></DIDL-Lite>");
    }

    private static List<String> uris(List<OhQueue.Entry> entries) {
        List<String> uris = new ArrayList<>();
        for (OhQueue.Entry entry : entries) {
            uris.add(entry.getUri());
        }
        return uris;
    }

    @Test
    public void prepareFindsThePlaylistSourceOfTheDevice() throws Exception {
        remote.prepare();

        assertEquals(1, OhSources.parse(core.stateValue("product", "SourceXml")).size());
        assertEquals("0", core.stateValue("product", "SourceIndex"));
    }

    @Test
    public void prepareFailsForADeviceWithoutPlaylistSource() throws Exception {
        OhRemote other = new OhRemote((service, action, args) -> {
            if (action.equals("SourceXml")) {
                return java.util.Collections.singletonMap("Value",
                        "<SourceList><Source><Name>Radio</Name><Type>Radio</Type><Visible>true</Visible></Source></SourceList>");
            }
            return java.util.Collections.singletonMap("Value", "0");
        }, millis -> {
        });

        try {
            other.prepare();
            fail();
        } catch (OhControlException e) {
            assertTrue(e.getMessage().contains("playlist"));
        }
    }

    @Test
    public void prepareWakesADeviceFromStandbyAndWaitsForIt() throws Exception {
        List<String> log = new ArrayList<>();
        int[] standbyReads = {0};
        OhRemote sleepy = new OhRemote((service, action, args) -> {
            log.add(action);
            switch (action) {
                case "Standby":
                    return java.util.Collections.singletonMap("Value", ++standbyReads[0] < 4 ? "1" : "0");
                case "SourceXml":
                    return java.util.Collections.singletonMap("Value", "<SourceList><Source><Name>Playlist</Name><Type>Playlist</Type>"
                            + "<Visible>true</Visible><SystemName>Playlist</SystemName></Source></SourceList>");
                default:
                    return java.util.Collections.singletonMap("Value", "5");
            }
        }, millis -> log.add("sleep"));

        sleepy.prepare();

        assertTrue(log.contains("SetStandby"));
        assertTrue(log.indexOf("sleep") > log.indexOf("SetStandby"));
        assertTrue(log.indexOf("SourceXml") > log.lastIndexOf("sleep"));
        assertTrue(log.contains("SetSourceIndex")); // the current source (5) is not the playlist (0)
    }

    @Test
    public void replacePutsTheTracksInOrderAndTheDeviceStartsFromThem() throws Exception {
        List<Long> ids = remote.replace(Arrays.asList(track("a"), track("b"), track("c")));

        assertEquals(3, ids.size());
        assertEquals(ids, remote.idArray());
        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3", "http://h/c.mp3"), uris(remote.queue()));
        assertEquals("<DIDL-Lite><item><dc:title>b &amp; more</dc:title></item></DIDL-Lite>", remote.queue().get(1).getMetadata());
    }

    @Test
    public void replaceDropsWhatWasInTheQueueBefore() throws Exception {
        remote.replace(Arrays.asList(track("old1"), track("old2")));

        remote.replace(Arrays.asList(track("new")));

        assertEquals(Arrays.asList("http://h/new.mp3"), uris(remote.queue()));
    }

    @Test
    public void appendGoesBehindTheLastTrack() throws Exception {
        remote.replace(Arrays.asList(track("a")));

        remote.append(Arrays.asList(track("b"), track("c")));

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/b.mp3", "http://h/c.mp3"), uris(remote.queue()));
    }

    @Test
    public void appendToAnEmptyQueueStartsIt() throws Exception {
        remote.append(Arrays.asList(track("a")));

        assertEquals(Arrays.asList("http://h/a.mp3"), uris(remote.queue()));
    }

    @Test
    public void onlyTracksNotSeenBeforeAreReadFromTheDevice() throws Exception {
        remote.replace(Arrays.asList(track("a"), track("b")));
        remote.queue();
        int before = control.calls;

        remote.queue(); // the same queue: only the id array is asked for

        assertEquals(before + 1, control.calls);
    }

    @Test
    public void aLongQueueIsReadInBatches() throws Exception {
        List<OhRemote.Track> tracks = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            tracks.add(track("t" + i));
        }
        OhRemote fresh = new OhRemote(control, millis -> {
        });
        remote.replace(tracks);

        List<OhQueue.Entry> queue = fresh.queue(); // a controller that has seen nothing yet

        assertEquals(120, queue.size());
        assertEquals("http://h/t0.mp3", queue.get(0).getUri());
        assertEquals("http://h/t119.mp3", queue.get(119).getUri());
    }

    @Test
    public void deleteTakesATrackOutOfTheQueue() throws Exception {
        List<Long> ids = remote.replace(Arrays.asList(track("a"), track("b"), track("c")));

        remote.deleteId(ids.get(1));

        assertEquals(Arrays.asList("http://h/a.mp3", "http://h/c.mp3"), uris(remote.queue()));
    }

    @Test
    public void deletingAnUnknownTrackIsReportedWithTheDevicesCode() throws Exception {
        try {
            remote.deleteId(4711);
            fail();
        } catch (OhControlException e) {
            assertEquals(OhError.ID_NOT_FOUND, e.getCode());
        }
    }

    @Test
    public void transportCommandsReachTheDevice() throws Exception {
        List<Long> ids = remote.replace(Arrays.asList(track("a"), track("b")));

        remote.play();
        remote.pause();
        remote.next();
        remote.previous();
        remote.stop();
        remote.seekId(ids.get(1));
        remote.seekIndex(0);
        remote.seekSecondAbsolute(42);
        remote.setRepeat(true);
        remote.setShuffle(true);

        List<String> tail = device.calls.subList(device.calls.size() - 10, device.calls.size());
        assertEquals(Arrays.asList("play", "pause", "next", "previous", "stop", "seek " + ids.get(1), "seek " + ids.get(0), "abs 42",
                "repeat true", "shuffle true"), tail);
    }

    @Test
    public void theStateIsReadFromTheDevice() throws Exception {
        long id = remote.replace(Arrays.asList(track("a"))).get(0);
        core.setTransportState(OhTransportState.PLAYING);
        core.setCurrentId(id);
        core.setRepeat(true);
        core.setShuffle(false);
        core.setTrack("http://h/a.mp3", "<meta/>", 200);
        core.setSeconds(17);
        core.setVolumePercent(35);
        core.setMuted(true);

        OhRemote.State state = remote.readState();

        assertEquals(OhTransportState.PLAYING, state.transportState);
        assertEquals(id, state.id);
        assertTrue(state.repeat);
        assertEquals(false, state.shuffle);
        assertEquals(17, state.seconds);
        assertEquals(200, state.durationSeconds);
        assertEquals("http://h/a.mp3", state.uri);
        assertEquals("<meta/>", state.metadata);
        assertEquals(35, state.volumePercent);
        assertTrue(state.muted);
    }

    @Test
    public void volumeAndMuteReachTheDevice() throws Exception {
        remote.setVolumePercent(60);
        remote.setMute(true);

        assertTrue(device.calls.contains("volume 60"));
        assertTrue(device.calls.contains("mute true"));
    }

    @Test
    public void aDeviceThatDoesNotAnswerThrowsWithTheNoAnswerCode() {
        control.unreachable = true;

        try {
            remote.readState();
            fail();
        } catch (OhControlException e) {
            assertEquals(OhControlException.NO_ANSWER, e.getCode());
        }
    }

    @Test
    public void hostileTrackListsAreIgnored() throws Exception {
        OhRemote hostile = new OhRemote((service, action, args) -> {
            if (action.equals("IdArray")) {
                return java.util.Collections.singletonMap("Array", de.yaacc.openhome.OhIds.encodeIdArray(Arrays.asList(1L, 2L)));
            }
            return java.util.Collections.singletonMap("TrackList",
                    "<!DOCTYPE x [<!ENTITY a \"aaaa\">]><TrackList><Entry><Id>1</Id><Uri>&a;</Uri></Entry></TrackList>");
        }, millis -> {
        });

        assertEquals(0, hostile.queue().size());
    }

    @Test
    public void entriesWithBadIdsAreSkipped() throws Exception {
        OhRemote odd = new OhRemote((service, action, args) -> {
            if (action.equals("IdArray")) {
                return java.util.Collections.singletonMap("Array", de.yaacc.openhome.OhIds.encodeIdArray(Arrays.asList(1L, 2L)));
            }
            return java.util.Collections.singletonMap("TrackList", "<TrackList><Entry><Id>0</Id><Uri>x</Uri></Entry>"
                    + "<Entry><Id>abc</Id><Uri>y</Uri></Entry><Entry><Id>2</Id><Uri>http://h/ok.mp3</Uri><Metadata/></Entry></TrackList>");
        }, millis -> {
        });

        assertEquals(Arrays.asList("http://h/ok.mp3"), uris(odd.queue()));
    }

    private static OhRemote hostileDevice(java.util.function.BiFunction<String, java.util.Map<String, String>, java.util.Map<String, String>> answers,
                                          List<String> log) {
        return new OhRemote((service, action, args) -> {
            log.add(action);
            return answers.apply(action, args);
        }, millis -> {
        });
    }

    @Test
    public void aDeeplyNestedAnswerIsHarmless() throws Exception {
        StringBuilder xml = new StringBuilder("<TrackList><Entry><Id>1</Id><Uri>http://h/a.mp3</Uri><Metadata>");
        for (int i = 0; i < 100_000; i++) {
            xml.append("<a>");
        }
        for (int i = 0; i < 100_000; i++) {
            xml.append("</a>");
        }
        xml.append("</Metadata></Entry></TrackList>");
        OhRemote remote = hostileDevice((action, args) -> action.equals("IdArray")
                ? java.util.Collections.singletonMap("Array", de.yaacc.openhome.OhIds.encodeIdArray(Arrays.asList(1L)))
                : java.util.Collections.singletonMap("TrackList", xml.toString()), new ArrayList<>());

        assertEquals(0, remote.queue().size()); // refused as a whole, and no Error
    }

    @Test
    public void entriesThatDoNotFitTheCacheAreNotReadAgainOnEveryPoll() throws Exception {
        List<Long> ids = new ArrayList<>();
        StringBuilder list = new StringBuilder("<TrackList>");
        String meta = "m".repeat(16000);
        for (long i = 1; i <= 1000; i++) {
            ids.add(i);
            list.append("<Entry><Id>").append(i).append("</Id><Uri>http://h/").append(i).append("</Uri><Metadata>").append(meta)
                    .append("</Metadata></Entry>");
        }
        list.append("</TrackList>");
        List<String> log = new ArrayList<>();
        OhRemote remote = hostileDevice((action, args) -> action.equals("IdArray")
                ? java.util.Collections.singletonMap("Array", de.yaacc.openhome.OhIds.encodeIdArray(ids))
                : java.util.Collections.singletonMap("TrackList", list.toString()), log);

        remote.queue();
        long first = log.stream().filter("ReadList"::equals).count();
        remote.queue();
        remote.queue();

        assertEquals(first, log.stream().filter("ReadList"::equals).count());
    }

    @Test
    public void aDeviceWithThousandsOfIdsIsCutOff() throws Exception {
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 10_000; i++) {
            many.add(i);
        }
        List<String> log = new ArrayList<>();
        OhRemote remote = hostileDevice((action, args) -> action.equals("IdArray")
                ? java.util.Collections.singletonMap("Array", de.yaacc.openhome.OhIds.encodeIdArray(many))
                : java.util.Collections.singletonMap("TrackList", "<TrackList/>"), log);

        remote.queue();

        long reads = log.stream().filter("ReadList"::equals).count();
        assertTrue("reads " + reads, reads <= 20);
    }

    @Test
    public void idsTheDeviceDoesNotGiveAreNotAskedForAgainAndUnaskedEntriesAreIgnored() throws Exception {
        List<String> log = new ArrayList<>();
        OhRemote remote = hostileDevice((action, args) -> action.equals("IdArray")
                ? java.util.Collections.singletonMap("Array", de.yaacc.openhome.OhIds.encodeIdArray(Arrays.asList(1L, 2L)))
                : java.util.Collections.singletonMap("TrackList", "<TrackList><Entry><Id>1</Id><Uri>http://h/1.mp3</Uri><Metadata/></Entry>"
                + "<Entry><Id>99</Id><Uri>http://h/unasked.mp3</Uri><Metadata/></Entry></TrackList>"), log);

        assertEquals(Arrays.asList("http://h/1.mp3"), uris(remote.queue()));
        remote.queue();
        remote.queue();

        assertEquals(1, log.stream().filter("ReadList"::equals).count()); // id 2 is not asked for again
    }

    @Test
    public void anOversizedEntryIsDropped() throws Exception {
        String huge = "m".repeat(de.yaacc.openhome.OhQueue.MAX_METADATA_LENGTH + 1);
        OhRemote remote = hostileDevice((action, args) -> action.equals("IdArray")
                ? java.util.Collections.singletonMap("Array", de.yaacc.openhome.OhIds.encodeIdArray(Arrays.asList(1L, 2L)))
                : java.util.Collections.singletonMap("TrackList", "<TrackList><Entry><Id>1</Id><Uri>http://h/1.mp3</Uri><Metadata>" + huge
                + "</Metadata></Entry><Entry><Id>2</Id><Uri>http://h/2.mp3</Uri><Metadata/></Entry></TrackList>"), new ArrayList<>());

        assertEquals(Arrays.asList("http://h/2.mp3"), uris(remote.queue()));
    }

    @Test
    public void positionsBeyondAnyRealValueAreClamped() throws Exception {
        core.setTrack("http://h/a.mp3", "", 1);
        OhRemote remote = hostileDevice((action, args) -> {
            if (action.equals("Time")) {
                java.util.Map<String, String> time = new java.util.HashMap<>();
                time.put("Seconds", "4000000000");
                time.put("Duration", "4000000000");
                return time;
            }
            return java.util.Collections.singletonMap("Value", "0");
        }, new ArrayList<>());

        OhRemote.State state = remote.readState();

        assertTrue(state.seconds * 1000L > 0);
        assertTrue(state.durationSeconds <= 8_640_000L);
    }

    @Test
    public void moreTracksThanTheQueueTakesAreLeftOut() throws Exception {
        List<OhRemote.Track> tracks = new ArrayList<>();
        for (int i = 0; i < OhRemote.MAX_TRACKS + 20; i++) {
            tracks.add(new OhRemote.Track("http://h/" + i, ""));
        }

        List<Long> ids = remote.replace(tracks);

        assertEquals(OhRemote.MAX_TRACKS, ids.size());
        assertEquals(OhRemote.MAX_TRACKS, remote.idArray().size());
        assertEquals(0, remote.append(Arrays.asList(track("x"))).size()); // full: nothing more goes in, and no error
    }

    @Test
    public void aDeviceThatIsFullBeforeTheLimitStopsTheAppendQuietly() throws Exception {
        OhRemote small = new OhRemote((service, action, args) -> {
            if (action.equals("Insert")) {
                throw new OhControlException(OhError.PLAYLIST_FULL, "full");
            }
            return java.util.Collections.singletonMap("Array", "");
        }, millis -> {
        });

        assertEquals(0, small.append(Arrays.asList(track("a"), track("b"))).size());
    }

    @Test
    public void theSourcesOfTheDeviceCanBeListedAndSelected() throws Exception {
        List<OhSources.Source> sources = remote.sources();

        assertEquals(1, sources.size());
        assertEquals("Playlist", sources.get(0).name);
        assertEquals(0, remote.currentSourceIndex());
        remote.selectSource(0);
        assertEquals("0", core.stateValue("product", "SourceIndex"));
    }

    @Test
    public void selectingASourceWakesTheDeviceFirst() throws Exception {
        List<String> log = new ArrayList<>();
        int[] standby = {0};
        OhRemote sleepy = new OhRemote((service, action, args) -> {
            log.add(action);
            return java.util.Collections.singletonMap("Value", action.equals("Standby") ? (standby[0]++ < 2 ? "1" : "0") : "0");
        }, millis -> log.add("sleep"));

        sleepy.selectSource(2);

        assertTrue(log.indexOf("SetStandby") < log.indexOf("SetSourceIndex"));
        assertTrue(log.contains("sleep"));
    }

    @Test
    public void aDeviceWithHundredsOfSourcesIsCutOff() throws Exception {
        StringBuilder xml = new StringBuilder("<SourceList>");
        for (int i = 0; i < 500; i++) {
            xml.append("<Source><Name>s").append(i).append("</Name><Type>Radio</Type><Visible>true</Visible></Source>");
        }
        xml.append("</SourceList>");

        assertEquals(OhSources.MAX_SOURCES, OhSources.parse(xml.toString()).size());
    }

    @Test
    public void theAppPicksTheSourceByItsPositionNotByName() throws Exception {
        String xml = "<SourceList><Source><Name>Radio</Name><Type>Radio</Type><Visible>true</Visible></Source>"
                + "<Source><Name>Mine</Name><Type>Playlist</Type><Visible>false</Visible></Source></SourceList>";

        List<OhSources.Source> sources = OhSources.parse(xml);

        assertEquals(1, OhSources.findPlaylist(sources).index);
        assertEquals(false, sources.get(1).visible);
    }

    @Test
    public void standbyCanBeReadAndSet() throws Exception {
        assertEquals(false, remote.standby());

        remote.setStandby(true);
        assertEquals(true, remote.standby());
        remote.setStandby(false);

        assertEquals(false, remote.standby());
    }

    @Test
    public void theVolumeAndMuteCanBeReadAndSetOnTheirOwn() throws Exception {
        remote.setVolumePercent(35);
        remote.setMute(true);

        assertEquals(35, remote.volumePercent());
        assertEquals(true, remote.muted());
    }
}
