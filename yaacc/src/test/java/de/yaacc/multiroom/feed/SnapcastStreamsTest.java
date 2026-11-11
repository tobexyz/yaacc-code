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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastOperations;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/** {@link SnapcastStreams} against a simulated snapserver (the status, AddStream, RemoveStream and SetStream of it). */
public class SnapcastStreamsTest {

    private static final String PHONE = "Pixel 8";
    private static final long IDLE_MILLIS = 10 * 60 * 1000L;
    private static final String FORMAT = "48000:16:2";

    private final SimulatedSnapcastServer server = new SimulatedSnapcastServer();
    private final MemoryStore store = new MemoryStore();
    private final long[] now = {1_000};
    private final MonotonicClock clock = () -> now[0];

    @After
    public void closeServer() {
        server.close();
    }

    private static final class MemoryStore implements SnapcastStreams.MarkerStore {
        String value;
        int saves;

        @Override
        public String load() {
            return value;
        }

        @Override
        public void save(String marker) {
            value = marker;
            saves++;
        }
    }

    private SnapcastStreams streams() {
        return streams(PHONE);
    }

    private SnapcastStreams streams(String phoneName) {
        return new SnapcastStreams(new SnapcastOperations(server), phoneName, store, clock, IDLE_MILLIS, new Random(1));
    }

    private SnapcastStreams ready() {
        SnapcastStreams streams = streams();
        assertEquals(SnapcastStreams.Status.READY, streams.ensure().getStatus());
        return streams;
    }

    // ---- name, marker, uri

    @Test
    public void theNameIsYaaccPhoneNameAndTheMarker() {
        SnapcastStreams streams = streams();

        assertTrue(streams.getMarker(), Pattern.matches("[0-9a-f]{8}", streams.getMarker()));
        assertEquals("YAACC Pixel 8 " + streams.getMarker(), streams.getStreamName());
    }

    @Test
    public void theMarkerIsGeneratedOnceStoredAndReused() {
        SnapcastStreams first = streams();
        SnapcastStreams second = streams("Another phone name");

        assertEquals(first.getMarker(), second.getMarker());
        assertEquals(first.getMarker(), store.value);
        assertEquals("saved once", 1, store.saves);
    }

    @Test
    public void aStoredValueThatIsNoMarkerIsReplaced() {
        for (String bad : new String[]{"", "xyz", "1234567", "123456789", "ABCDEF12", "12345 78", "../../etc"}) {
            store.value = bad;
            SnapcastStreams streams = streams();
            assertTrue(bad + " -> " + streams.getMarker(), Pattern.matches("[0-9a-f]{8}", streams.getMarker()));
            assertEquals(streams.getMarker(), store.value);
        }
    }

    @Test
    public void twoInstallsGetDifferentMarkers() {
        MemoryStore other = new MemoryStore();
        SnapcastStreams a = new SnapcastStreams(new SnapcastOperations(server), PHONE, store, clock, IDLE_MILLIS,
                new java.security.SecureRandom());
        SnapcastStreams b = new SnapcastStreams(new SnapcastOperations(server), PHONE, other, clock, IDLE_MILLIS,
                new java.security.SecureRandom());

        assertFalse(a.getMarker().equals(b.getMarker()));
    }

    @Test
    public void aStoreThatCanNotSaveStillGivesAMarker() {
        SnapcastStreams streams = new SnapcastStreams(new SnapcastOperations(server), PHONE,
                new SnapcastStreams.MarkerStore() {
                    @Override
                    public String load() {
                        return null;
                    }

                    @Override
                    public void save(String marker) {
                        throw new IllegalStateException("disk full");
                    }
                }, clock, IDLE_MILLIS, new Random(3));

        assertTrue(Pattern.matches("[0-9a-f]{8}", streams.getMarker()));
    }

    @Test
    public void thePhoneNameIsReducedToLettersDigitsSpaceDashAndUnderscore() {
        assertEquals("Pixel 8", SnapcastStreams.sanitizePhoneName("Pixel 8"));
        assertEquals("Tobi_s Pixel", SnapcastStreams.sanitizePhoneName("Tobi's Pixel"));
        assertEquals("a_mode_client", SnapcastStreams.sanitizePhoneName("a&mode=client"));
        assertEquals("a_b_c", SnapcastStreams.sanitizePhoneName("a%b+c"));
        assertEquals("spaced out", SnapcastStreams.sanitizePhoneName("  spaced   out  "));
        assertEquals("Büro-Handy_2", SnapcastStreams.sanitizePhoneName("Büro-Handy_2"));
        assertEquals("ab_cd_", SnapcastStreams.sanitizePhoneName("ab\ncd\u0000"));
        assertEquals("abc_def", SnapcastStreams.sanitizePhoneName("abc‮def"));
        assertEquals("Phone", SnapcastStreams.sanitizePhoneName(null));
        assertEquals("Phone", SnapcastStreams.sanitizePhoneName(""));
        assertEquals("Phone", SnapcastStreams.sanitizePhoneName("   "));
        assertEquals("Phone", SnapcastStreams.sanitizePhoneName("&&&"));
    }

    @Test
    public void thePhoneNameIsCutAtThirtyTwoCharactersWithoutBreakingASurrogatePair() {
        StringBuilder plain = new StringBuilder();
        StringBuilder astral = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            plain.append('x');
            astral.appendCodePoint(0x1D49C); // a letter outside the BMP
        }

        assertEquals(32, SnapcastStreams.sanitizePhoneName(plain.toString()).length());
        String cut = SnapcastStreams.sanitizePhoneName(astral.toString());
        assertEquals(32, cut.codePointCount(0, cut.length()));
        assertTrue(Character.isLetter(cut.codePointBefore(cut.length())));
    }

    @Test
    public void theUriHasTheDocumentedShape() {
        assertEquals("tcp://0.0.0.0:4953?name=YAACC%20Pixel%208%201a2b3c4d&mode=server&sampleformat=48000:16:2&codec=flac",
                SnapcastStreams.buildUri(4953, "YAACC Pixel 8 1a2b3c4d"));
    }

    @Test
    public void aNameWithQueryCharactersStaysInsideTheNameValue() {
        String uri = SnapcastStreams.buildUri(4953, "YAACC a&mode=client 1a2b3c4d");

        assertFalse(uri.contains("a&mode"));
        Map<String, String> query = SimulatedSnapcastServer.parseQuery(uri);
        assertEquals("YAACC a&mode=client 1a2b3c4d", query.get("name"));
        assertEquals("server", query.get("mode"));
        assertEquals(4, query.size());
        assertEquals(FORMAT, query.get("sampleformat"));
    }

    @Test
    public void percentAndPlusAreEncodedToo() {
        String uri = SnapcastStreams.buildUri(4953, "x%41+y z");

        assertTrue(uri, uri.contains("name=x%2541%2By%20z&"));
        assertEquals("x%41+y z", SimulatedSnapcastServer.parseQuery(uri).get("name"));
    }

    @Test
    public void everythingButUnreservedCharactersIsPercentEncodedAsUtf8() {
        assertEquals("AZaz09-._~", SnapcastStreams.percentEncode("AZaz09-._~"));
        assertEquals("%26%3D%3F%23%2F%3A%40%20%22%27%3C%3E", SnapcastStreams.percentEncode("&=?#/:@ \"'<>"));
        assertEquals("%C3%A4%E2%82%AC%F0%9D%92%9C", SnapcastStreams.percentEncode("ä€𝒜"));
        assertEquals("%0A%00%7F", SnapcastStreams.percentEncode("\n\u0000\u007f"));
    }

    @Test
    public void aPortOutsideTheRangeIsRefusedByTheUriBuilder() {
        try {
            SnapcastStreams.buildUri(0, "x");
            fail("port 0");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        try {
            SnapcastStreams.buildUri(65536, "x");
            fail("port 65536");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    @Test
    public void theHostilePhoneNameNeverReachesTheServerAsAQueryParameter() {
        SnapcastStreams streams = streams("a&mode=client");

        assertEquals(SnapcastStreams.Status.READY, streams.ensure().getStatus());

        assertEquals("server", server.streamQuery(streams.getStreamName(), "mode"));
        assertEquals(1, server.addUris().size());
        Map<String, String> query = SimulatedSnapcastServer.parseQuery(server.addUris().get(0));
        assertEquals(streams.getStreamName(), query.get("name"));
        assertEquals(4, query.size());
    }

    // ---- ensure: adding

    @Test
    public void ensureAddsOneTcpStreamInServerModeInTheExpectedFormat() {
        SnapcastStreams streams = streams();

        SnapcastStreams.EnsureResult result = streams.ensure();

        assertEquals(SnapcastStreams.Status.READY, result.getStatus());
        assertTrue(result.isReady());
        assertFalse(result.isAdopted());
        assertEquals(streams.getStreamName(), result.getStreamId());
        assertEquals(4953, result.getPort());
        assertNull(result.getHintKey());
        assertEquals(1, server.streamCount());
        assertEquals("server", server.streamQuery(result.getStreamId(), "mode"));
        assertEquals(FORMAT, server.streamQuery(result.getStreamId(), "sampleformat"));
        assertEquals(4953, server.streamPort(result.getStreamId()));
    }

    @Test
    public void ensureSkipsThePortsThatTheStatusNamesWhateverTheHost() {
        server.addTcpStream("other one", 4953, "server", FORMAT);
        server.addTcpStream("other two", "evil.example.invalid:4954", "server", FORMAT);
        server.addTcpStream("other three", "[::1]:4955", "client", FORMAT);

        SnapcastStreams.EnsureResult result = streams().ensure();

        assertEquals(SnapcastStreams.Status.READY, result.getStatus());
        assertEquals(4956, result.getPort());
        assertEquals(1, server.callCount("Stream.AddStream"));
    }

    @Test
    public void aRefusedPortTriesTheNextOne() {
        server.takePort(4953);
        server.takePort(4954);

        SnapcastStreams.EnsureResult result = streams().ensure();

        assertEquals(SnapcastStreams.Status.READY, result.getStatus());
        assertEquals(4955, result.getPort());
        assertEquals(3, server.callCount("Stream.AddStream"));
    }

    @Test
    public void atMostTenPortsAreTriedThenTheServersReasonIsReportedWithTheHint() {
        for (int port = 4953; port <= 4962; port++) {
            server.takePort(port);
        }

        SnapcastStreams.EnsureResult result = streams().ensure();

        assertEquals(SnapcastStreams.Status.REFUSED, result.getStatus());
        assertFalse(result.isReady());
        assertEquals(10, server.callCount("Stream.AddStream"));
        assertTrue(result.getReason(), result.getReason().contains("Address already in use"));
        assertEquals(SnapcastStreams.HINT_STATIC_SOURCE, result.getHintKey());
        assertEquals(0, server.streamCount());
    }

    @Test
    public void whenEveryPortOfTheRangeIsNamedNothingIsAdded() {
        for (int port = 4953; port <= 4962; port++) {
            server.addTcpStream("taken " + port, port, "server", FORMAT);
        }

        SnapcastStreams.EnsureResult result = streams().ensure();

        assertEquals(SnapcastStreams.Status.NO_FREE_PORT, result.getStatus());
        assertEquals(0, server.callCount("Stream.AddStream"));
        assertEquals(10, server.streamCount());
        assertEquals(SnapcastStreams.HINT_STATIC_SOURCE, result.getHintKey());
    }

    @Test
    public void anotherRefusalIsNotRetriedOnAnotherPort() {
        server.failAddStream(SimulatedSnapcastServer.error(-32602, "Invalid params", "streamUri scheme not allowed"));

        SnapcastStreams.EnsureResult result = streams().ensure();

        assertEquals(SnapcastStreams.Status.REFUSED, result.getStatus());
        assertEquals(1, server.callCount("Stream.AddStream"));
        assertTrue(result.getReason(), result.getReason().contains("scheme not allowed"));
        assertEquals(SnapcastStreams.HINT_STATIC_SOURCE, result.getHintKey());
    }

    @Test
    public void unauthorizedGivesItsOwnStatusAndHint() {
        server.failNextCall(SimulatedSnapcastServer.error(401, "Unauthorized", null));

        SnapcastStreams.EnsureResult result = streams().ensure();

        assertEquals(SnapcastStreams.Status.UNAUTHORIZED, result.getStatus());
        assertEquals(SnapcastStreams.HINT_AUTHORIZATION, result.getHintKey());
    }

    @Test
    public void aLostConnectionGivesFailedAndNeverThrows() {
        server.failNextCall(new SnapcastException(SnapcastException.Kind.NOT_CONNECTED, 0, "not connected", null, null));

        SnapcastStreams.EnsureResult result = streams().ensure();

        assertEquals(SnapcastStreams.Status.FAILED, result.getStatus());
        assertNull(result.getHintKey());
    }

    @Test
    public void aRefusalReasonIsDisplayTextOfLimitedLength() {
        StringBuilder hostile = new StringBuilder("bad\n‮\u0000text ");
        for (int i = 0; i < 2000; i++) {
            hostile.append('x');
        }
        server.failAddStream(SimulatedSnapcastServer.error(-32603, "Internal error", hostile.toString()));

        SnapcastStreams.EnsureResult result = streams().ensure();

        assertEquals(SnapcastStreams.Status.REFUSED, result.getStatus());
        assertTrue(result.getReason().length() <= 200);
        for (int i = 0; i < result.getReason().length(); i++) {
            char c = result.getReason().charAt(i);
            assertTrue("control or bidi character in the reason", c >= 0x20 && c != 0x7f && c != 0x202e);
        }
    }

    @Test
    public void anAnswerWithAnotherStreamIdIsRefusedAndNothingIsRecordedOrTouched() {
        server.addPipeStream("Default");
        server.answerAddStreamWith("Default");
        SnapcastStreams streams = streams();

        SnapcastStreams.EnsureResult result = streams.ensure();

        assertEquals(SnapcastStreams.Status.REFUSED, result.getStatus());
        assertEquals(SnapcastStreams.HINT_STATIC_SOURCE, result.getHintKey());
        assertTrue(streams.shutdown());
        assertTrue("the user's stream stays", server.hasStream("Default"));
        assertEquals(0, server.callCount("Stream.RemoveStream"));
    }

    // ---- ensure: one stream per phone, adopting

    @Test
    public void aSecondEnsureAddsNoSecondStream() {
        SnapcastStreams streams = streams();
        SnapcastStreams.EnsureResult first = streams.ensure();

        SnapcastStreams.EnsureResult second = streams.ensure();

        assertEquals(SnapcastStreams.Status.READY, second.getStatus());
        assertEquals(first.getStreamId(), second.getStreamId());
        assertEquals(first.getPort(), second.getPort());
        assertTrue(second.isAdopted());
        assertEquals(1, server.callCount("Stream.AddStream"));
        assertEquals(1, server.streamCount());
    }

    @Test
    public void afterARestartTheStreamWithTheMarkerIsAdoptedAndNotAddedAgain() {
        SnapcastStreams.EnsureResult first = streams().ensure();

        SnapcastStreams afterRestart = streams();
        SnapcastStreams.EnsureResult second = afterRestart.ensure();

        assertEquals(SnapcastStreams.Status.READY, second.getStatus());
        assertTrue(second.isAdopted());
        assertEquals(first.getPort(), second.getPort());
        assertEquals(1, server.callCount("Stream.AddStream"));
    }

    @Test
    public void aStreamNamedLikeOursWithoutTheMarkerIsNeverAdoptedOrTouched() {
        SnapcastStreams streams = streams();
        server.addTcpStream("YAACC Pixel 8", 4953, "server", FORMAT);
        server.addTcpStream("YAACC Pixel 8 deadbeef", 4954, "server", FORMAT);
        server.addTcpStream("YAACC " + streams.getMarker(), 4955, "server", FORMAT);
        server.addTcpStream("yaacc pixel 8 " + streams.getMarker(), 4956, "server", FORMAT);

        SnapcastStreams.EnsureResult result = streams.ensure();

        assertEquals(SnapcastStreams.Status.READY, result.getStatus());
        assertFalse(result.isAdopted());
        assertEquals(4957, result.getPort());
        assertEquals(0, server.callCount("Stream.RemoveStream"));
        assertTrue(server.hasStream("YAACC Pixel 8"));
        assertTrue(server.hasStream("YAACC Pixel 8 deadbeef"));
        assertTrue(server.hasStream("YAACC " + streams.getMarker()));
        assertTrue(server.hasStream("yaacc pixel 8 " + streams.getMarker()));
    }

    @Test
    public void aStreamOfOursWithTheWrongShapeIsReplacedWhenNobodyUsesIt() {
        String[][] shapes = {
                {"0.0.0.0:4953", "client", FORMAT},          // client mode
                {"0.0.0.0:4953", null, FORMAT},              // no mode at all
                {"0.0.0.0:4953", "server", "44100:16:2"},    // another format
                {"192.0.2.99:4953", "server", FORMAT},       // bound to one address of the server
                {"evil.example.invalid:4953", "server", FORMAT},
                {"0.0.0.0:80", "server", FORMAT},            // a port outside YAACC's range
                {"0.0.0.0", "server", FORMAT},               // no port
                {"0.0.0.0:99999", "server", FORMAT},         // an impossible port
        };
        for (String[] shape : shapes) {
            SimulatedSnapcastServer fresh = new SimulatedSnapcastServer();
            SnapcastStreams streams = new SnapcastStreams(new SnapcastOperations(fresh), PHONE, store, clock,
                    IDLE_MILLIS, new Random(1));
            fresh.addTcpStream(streams.getStreamName(), shape[0], shape[1], shape[2]);

            SnapcastStreams.EnsureResult result = streams.ensure();

            assertEquals(shape[0] + " " + shape[1] + " " + shape[2], SnapcastStreams.Status.READY, result.getStatus());
            assertFalse(result.isAdopted());
            assertEquals(1, fresh.callCount("Stream.RemoveStream"));
            assertEquals("server", fresh.streamQuery(streams.getStreamName(), "mode"));
            assertEquals(FORMAT, fresh.streamQuery(streams.getStreamName(), "sampleformat"));
            fresh.close();
        }
    }

    @Test
    public void aSchemeOtherThanTcpIsNotAdoptedEither() {
        SnapcastStreams streams = streams();
        server.addPipeStream(streams.getStreamName());

        SnapcastStreams.EnsureResult result = streams.ensure();

        assertEquals(SnapcastStreams.Status.READY, result.getStatus());
        assertFalse(result.isAdopted());
        assertEquals("server", server.streamQuery(streams.getStreamName(), "mode"));
        assertEquals(1, server.callCount("Stream.RemoveStream"));
    }

    @Test
    public void aStreamOfOursWithTheWrongShapeThatAGroupUsesIsLeftAloneAndReportedAsAConflict() {
        SnapcastStreams streams = streams();
        server.addTcpStream(streams.getStreamName(), 4953, "client", FORMAT);
        server.addGroup("g1", streams.getStreamName(), "c1");

        SnapcastStreams.EnsureResult result = streams.ensure();

        assertEquals(SnapcastStreams.Status.CONFLICT, result.getStatus());
        assertEquals(0, server.callCount("Stream.RemoveStream"));
        assertEquals(0, server.callCount("Stream.AddStream"));
        assertTrue(server.hasStream(streams.getStreamName()));
    }

    // ---- hostile status answers

    @Test
    public void sixtyFourHostileStreamsAreHandledWithoutRemovingAnythingThatIsNotOurs() {
        SnapcastStreams streams = streams();
        for (int i = 0; i < 60; i++) {
            server.addTcpStream("YAACC fake " + i + " 0000000" + (i % 10), "evil" + i + ".example.invalid:" + (60_000 + i),
                    "server", FORMAT);
        }
        server.addTcpStream("YAACC Pixel 8", 1, "server", FORMAT);
        server.addPipeStream("YAACC " + streams.getMarker()); // the marker alone is not our name
        server.addPipeStream("a \n b ‮ c");
        server.addGroup("g1", "YAACC fake 3 00000003", "c1");

        SnapcastStreams.EnsureResult result = streams.ensure();

        assertEquals(SnapcastStreams.Status.READY, result.getStatus());
        assertEquals(4953, result.getPort());
        assertEquals(0, server.callCount("Stream.RemoveStream"));
        assertEquals(64, server.streamCount());
    }

    @Test
    public void streamsWithNoUsefulUriAreIgnoredWhenChoosingAPort() throws Exception {
        server.addRawStream(new JSONObject("{\"id\":\"no uri\",\"status\":\"idle\"}"));
        server.addRawStream(new JSONObject("{\"id\":\"odd\",\"uri\":{\"host\":\"[::1]:abc\",\"scheme\":5}}"));
        server.addRawStream(new JSONObject("{\"id\":\"huge\",\"uri\":{\"host\":\"0.0.0.0:4953999\"}}"));

        SnapcastStreams.EnsureResult result = streams().ensure();

        assertEquals(SnapcastStreams.Status.READY, result.getStatus());
        assertEquals("none of them names a usable port", 4953, result.getPort());
    }

    // ---- moving a group and restoring it

    @Test
    public void useForMovesTheGroupAndReleaseRestoresItsPreviousStream() {
        server.addPipeStream("Default");
        server.addGroup("g1", "Default", "c1");
        SnapcastStreams streams = ready();

        assertEquals(SnapcastStreams.UseResult.OK, streams.useFor("g1"));
        assertEquals(streams.getStreamName(), server.groupStream("g1"));
        assertEquals(SnapcastStreams.ReleaseResult.RESTORED, streams.release("g1"));
        assertEquals("Default", server.groupStream("g1"));
    }

    @Test
    public void releaseLeavesAGroupAloneThatTheUserMovedElsewhere() {
        server.addPipeStream("Default");
        server.addPipeStream("Radio");
        server.addGroup("g1", "Default", "c1");
        SnapcastStreams streams = ready();
        streams.useFor("g1");
        // the user picks another stream in another controller
        try {
            server.call("Group.SetStream", new JSONObject().put("id", "g1").put("stream_id", "Radio"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertEquals(SnapcastStreams.ReleaseResult.LEFT_ALONE, streams.release("g1"));
        assertEquals("Radio", server.groupStream("g1"));
    }

    @Test
    public void releaseDoesNothingWhenThePreviousStreamIsGone() {
        server.addPipeStream("Default");
        server.addGroup("g1", "Default", "c1");
        SnapcastStreams streams = ready();
        streams.useFor("g1");
        try {
            server.call("Stream.RemoveStream", new JSONObject().put("id", "Default"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertEquals(SnapcastStreams.ReleaseResult.LEFT_ALONE, streams.release("g1"));
        assertEquals(streams.getStreamName(), server.groupStream("g1"));
    }

    @Test
    public void releaseOfAGroupYaaccNeverMovedTouchesNothing() {
        server.addPipeStream("Default");
        server.addGroup("g1", "Default", "c1");
        server.addGroup("g2", "Default", "c2");
        SnapcastStreams streams = ready();
        streams.useFor("g1");

        assertEquals(SnapcastStreams.ReleaseResult.LEFT_ALONE, streams.release("g2"));
        assertEquals(SnapcastStreams.ReleaseResult.LEFT_ALONE, streams.release("unknown"));
        assertEquals("Default", server.groupStream("g2"));
        assertEquals(streams.getStreamName(), server.groupStream("g1"));
    }

    @Test
    public void useForTwiceMovesOnceAndKeepsTheFirstPreviousStream() {
        server.addPipeStream("Default");
        server.addGroup("g1", "Default", "c1");
        SnapcastStreams streams = ready();

        streams.useFor("g1");
        streams.useFor("g1");

        assertEquals(1, server.callCount("Group.SetStream"));
        streams.release("g1");
        assertEquals("Default", server.groupStream("g1"));
    }

    @Test
    public void useForReportsWhyItCouldNotMove() {
        server.addGroup("g1", "", "c1");
        SnapcastStreams streams = streams();

        assertEquals("ensure first", SnapcastStreams.UseResult.NOT_READY, streams.useFor("g1"));
        streams.ensure();
        assertEquals(SnapcastStreams.UseResult.GROUP_NOT_FOUND, streams.useFor("no such group"));
        server.failNextCall(new SnapcastException(SnapcastException.Kind.TIMEOUT, 0, "timeout", null, null));
        assertEquals(SnapcastStreams.UseResult.FAILED, streams.useFor("g1"));
        assertEquals(SnapcastStreams.UseResult.OK, streams.useFor("g1"));
    }

    @Test
    public void useForWithoutAPreviousStreamRestoresNothing() {
        server.addGroup("g1", "", "c1");
        SnapcastStreams streams = ready();

        assertEquals(SnapcastStreams.UseResult.OK, streams.useFor("g1"));
        assertEquals(SnapcastStreams.ReleaseResult.LEFT_ALONE, streams.release("g1"));
        assertEquals("the group stays where it is", streams.getStreamName(), server.groupStream("g1"));
    }

    @Test
    public void aStreamThatVanishedFromTheServerMakesUseForAskForANewEnsure() {
        server.addGroup("g1", "", "c1");
        SnapcastStreams streams = ready();
        try {
            server.call("Stream.RemoveStream", new JSONObject().put("id", streams.getStreamName()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertEquals(SnapcastStreams.UseResult.NOT_READY, streams.useFor("g1"));
        assertEquals(SnapcastStreams.Status.READY, streams.ensure().getStatus());
        assertEquals(SnapcastStreams.UseResult.OK, streams.useFor("g1"));
    }

    // ---- crash recovery

    @Test
    public void leftoversThatCarryTheMarkerAndAreUnusedAreRemovedWhenEnsureRuns() {
        SnapcastStreams streams = streams();
        String marker = streams.getMarker();
        server.addTcpStream("YAACC Old phone " + marker, 4953, "server", FORMAT);   // a leftover, unused
        server.addTcpStream("YAACC Used phone " + marker, 4954, "server", FORMAT);  // a leftover, a group uses it
        server.addPipeStream("YAACC Pipe " + marker);                               // the marker but not tcp
        server.addTcpStream("YAACC Other " + "cafebabe", 4955, "server", FORMAT);   // somebody else's marker
        server.addGroup("g1", "YAACC Used phone " + marker, "c1");

        SnapcastStreams.EnsureResult result = streams.ensure();

        assertEquals(SnapcastStreams.Status.READY, result.getStatus());
        assertFalse(server.hasStream("YAACC Old phone " + marker));
        assertTrue(server.hasStream("YAACC Used phone " + marker));
        assertTrue(server.hasStream("YAACC Pipe " + marker));
        assertTrue(server.hasStream("YAACC Other cafebabe"));
        assertTrue(server.hasStream(streams.getStreamName()));
    }

    @Test
    public void removeLeftoversNeverRemovesTheStreamInUse() {
        SnapcastStreams streams = ready();
        server.addGroup("g1", streams.getStreamName(), "c1");

        assertEquals(0, streams.removeLeftovers());
        assertTrue(server.hasStream(streams.getStreamName()));
    }

    @Test
    public void removeLeftoversWorksBeforeTheFirstEnsureToo() {
        SnapcastStreams streams = streams();
        server.addTcpStream("YAACC Old phone " + streams.getMarker(), 4953, "server", FORMAT);

        assertEquals(1, streams.removeLeftovers());
        assertEquals(0, server.streamCount());
    }

    // ---- idle cleanup and shutdown

    @Test
    public void theStreamIsRemovedAfterTheIdleTimeoutMeasuredByOurOwnLastAudio() {
        server.addPipeStream("Default");
        server.addGroup("g1", "Default", "c1");
        SnapcastStreams streams = ready();
        streams.useFor("g1");

        now[0] += IDLE_MILLIS - 1;
        assertFalse("not yet", streams.removeIfIdle());
        streams.noteAudio(); // audio was written: the timer starts again
        now[0] += IDLE_MILLIS - 1;
        assertFalse("the audio postponed it", streams.removeIfIdle());
        assertTrue(server.hasStream(streams.getStreamName()));

        now[0] += 1;
        assertTrue(streams.removeIfIdle());

        assertFalse(server.hasStream(streams.getStreamName()));
        assertEquals("the group got its stream back before the stream went", "Default", server.groupStream("g1"));
    }

    @Test
    public void theServersIdleStatusDoesNotDecideAnything() throws Exception {
        SnapcastStreams streams = ready();
        // digital silence makes the server call the stream idle while data flows; our own clock is what counts
        assertEquals("idle", new JSONObject(server.call("Server.GetStatus", null).toString())
                .getJSONObject("server").getJSONArray("streams").getJSONObject(0).getString("status"));
        now[0] += IDLE_MILLIS - 1;

        assertFalse(streams.removeIfIdle());
        assertTrue(server.hasStream(streams.getStreamName()));
    }

    @Test
    public void idleCleanupDoesNothingWithoutAStream() {
        assertFalse(streams().removeIfIdle());
        assertEquals(0, server.callCount("Stream.RemoveStream"));
    }

    @Test
    public void shutdownRestoresEveryRecordedGroupAndRemovesOnlyOurStream() {
        server.addPipeStream("Default");
        server.addPipeStream("Radio");
        server.addGroup("g1", "Default", "c1");
        server.addGroup("g2", "Radio", "c2");
        server.addGroup("g3", "Default", "c3");
        SnapcastStreams streams = ready();
        streams.useFor("g1");
        streams.useFor("g2");

        assertTrue(streams.shutdown());

        assertEquals("Default", server.groupStream("g1"));
        assertEquals("Radio", server.groupStream("g2"));
        assertEquals("Default", server.groupStream("g3"));
        assertFalse(server.hasStream(streams.getStreamName()));
        assertTrue(server.hasStream("Default"));
        assertTrue(server.hasStream("Radio"));
        assertEquals(1, server.callCount("Stream.RemoveStream"));
    }

    @Test
    public void shutdownWithoutAStreamIsHarmless() {
        assertTrue(streams().shutdown());
        assertEquals(0, server.calls().size());
    }

    @Test
    public void shutdownDoesNotRemoveAStreamThatLooksDifferentNowAndKeepsTheRecord() {
        SnapcastStreams streams = ready();
        String name = streams.getStreamName();
        try {
            // the server replaced our stream by a user's pipe with the same id
            server.call("Stream.RemoveStream", new JSONObject().put("id", name));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        server.addPipeStream(name);

        assertTrue(streams.shutdown());

        assertTrue("a pipe stream is not ours to remove", server.hasStream(name));
        assertEquals(1, server.callCount("Stream.RemoveStream"));
    }

    @Test
    public void shutdownReportsFailureAndCanBeRepeated() {
        SnapcastStreams streams = ready();
        server.failNextCall(new SnapcastException(SnapcastException.Kind.IO, 0, "io", null, null));

        assertFalse(streams.shutdown());
        assertTrue("the record is kept, so a second try works", streams.shutdown());
        assertFalse(server.hasStream(streams.getStreamName()));
    }

    @Test
    public void aFailedRestoreKeepsTheStreamSoThatTheGroupIsNotLeftOnAStreamThatIsGone() {
        server.addPipeStream("Default");
        server.addGroup("g1", "Default", "c1");
        SnapcastStreams streams = ready();
        streams.useFor("g1");
        server.failNextCallOf("Group.SetStream",
                new SnapcastException(SnapcastException.Kind.IO, 0, "io", null, null));

        assertFalse(streams.shutdown());

        assertTrue("the stream stays while the group is still on it", server.hasStream(streams.getStreamName()));
        assertEquals(streams.getStreamName(), server.groupStream("g1"));
        assertEquals(0, server.callCount("Stream.RemoveStream"));

        assertTrue("the second call restores and then removes", streams.shutdown());
        assertEquals("Default", server.groupStream("g1"));
        assertFalse(server.hasStream(streams.getStreamName()));
    }

    @Test
    public void aFailedRestoreKeepsTheStreamForIdleCleanupToo() {
        server.addPipeStream("Default");
        server.addGroup("g1", "Default", "c1");
        SnapcastStreams streams = ready();
        streams.useFor("g1");
        server.failNextCallOf("Group.SetStream",
                new SnapcastException(SnapcastException.Kind.TIMEOUT, 0, "timeout", null, null));
        now[0] += IDLE_MILLIS;

        assertFalse(streams.removeIfIdle());

        assertTrue(server.hasStream(streams.getStreamName()));
        assertEquals(streams.getStreamName(), server.groupStream("g1"));
    }

    @Test
    public void aGroupThatTheUserMovedOntoTheStreamKeepsItAlive() {
        server.addPipeStream("Default");
        server.addGroup("g1", "Default", "c1");
        server.addGroup("g2", "Default", "c2");
        SnapcastStreams streams = ready();
        streams.useFor("g1");
        try {
            // another controller puts g2 on our stream; YAACC never recorded it
            server.call("Group.SetStream", new JSONObject().put("id", "g2").put("stream_id", streams.getStreamName()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertFalse("the stream is still in use", streams.shutdown());

        assertTrue(server.hasStream(streams.getStreamName()));
        assertEquals(streams.getStreamName(), server.groupStream("g2"));
        assertEquals("the group YAACC moved was restored", "Default", server.groupStream("g1"));
        try {
            server.call("Group.SetStream", new JSONObject().put("id", "g2").put("stream_id", "Default"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue("nobody uses it any more", streams.shutdown());
        assertFalse(server.hasStream(streams.getStreamName()));
    }

    @Test
    public void ensureLooksAtTheGroupsAgainBeforeItRemovesALeftover() {
        SnapcastStreams streams = streams();
        String leftover = "YAACC Old phone " + streams.getMarker();
        server.addTcpStream(leftover, 4953, "server", FORMAT);
        server.addGroup("g1", "", "c1");
        // a group is put on the leftover between ensure's first look and the removal
        server.beforeNextCallOf("Stream.AddStream", () -> {
            try {
                server.call("Group.SetStream", new JSONObject().put("id", "g1").put("stream_id", leftover));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertEquals(SnapcastStreams.Status.READY, streams.ensure().getStatus());

        assertTrue("a leftover that a group uses now is not removed", server.hasStream(leftover));
    }

    @Test
    public void recordsOfGroupsThatAreGoneDoNotPileUp() {
        server.addPipeStream("Default");
        SnapcastStreams streams = ready();
        for (int i = 0; i < 200; i++) {
            server.addGroup("group" + i, "Default", "client" + i);
            assertEquals(SnapcastStreams.UseResult.OK, streams.useFor("group" + i));
            // the group disappears with its client (the server derives groups from clients)
            server.removeGroup("group" + i);
        }

        assertTrue("bounded: " + streams.recordedGroupCount(), streams.recordedGroupCount() <= 2);
        assertNotNull(streams.getStreamName());
    }
}
