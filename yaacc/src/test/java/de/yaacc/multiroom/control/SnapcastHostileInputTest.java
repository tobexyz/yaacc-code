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
package de.yaacc.multiroom.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * What a hostile or broken server may send at the model and message level: nothing here may throw an {@link Error} or an
 * unchecked exception out of the library, and nothing may use unbounded memory or time. (The socket-level cases, a 2 MiB
 * line, a flood and a dripping sender, are in {@code SnapcastRpcTest} and {@code SnapcastLineReaderTest}.)
 */
public class SnapcastHostileInputTest {

    private static String repeat(String unit, int times) {
        StringBuilder text = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            text.append(unit);
        }
        return text.toString();
    }

    /** Runs the check on a thread with a small stack: an unguarded recursion would fail here, not only on a phone. */
    private static void onSmallStack(final Runnable check) throws Exception {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                check.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "hostile-small-stack", 256 * 1024);
        thread.start();
        thread.join(60_000);
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static void assertTypedFailure(String text) {
        try {
            SnapcastMessage.parseLine(text);
            fail("accepted a hostile line");
        } catch (SnapcastException expected) {
            assertTrue(expected.getKind().toString(),
                    expected.getKind() == SnapcastException.Kind.MALFORMED || expected.getKind() == SnapcastException.Kind.LIMIT);
        }
    }

    @Test
    public void nesting10000DeepInAMessageGivesATypedException() throws Exception {
        onSmallStack(() -> {
            assertTypedFailure(repeat("[", 10_000) + repeat("]", 10_000));
            assertTypedFailure("{\"result\":" + repeat("{\"a\":", 10_000) + "1" + repeat("}", 10_000) + "}");
            try {
                SnapcastServerStatus.parseResult("{\"server\":" + repeat("[", 10_000) + repeat("]", 10_000) + "}");
                fail();
            } catch (SnapcastException expected) {
                assertEquals(SnapcastException.Kind.MALFORMED, expected.getKind());
            }
        });
    }

    @Test
    public void aBatchOf100000ElementsGivesATypedException() throws Exception {
        onSmallStack(() -> assertTypedFailure("[" + repeat("{\"jsonrpc\":\"2.0\",\"method\":\"A.B\"},", 99_999)
                + "{\"jsonrpc\":\"2.0\",\"method\":\"A.B\"}]"));
    }

    @Test
    public void aBatchOf65ElementsGivesATypedException() {
        assertTypedFailure("[" + repeat("{\"jsonrpc\":\"2.0\",\"method\":\"A.B\"},", 64)
                + "{\"jsonrpc\":\"2.0\",\"method\":\"A.B\"}]");
    }

    @Test
    public void aBatchOf64IsFine() throws Exception {
        assertEquals(64, SnapcastMessage.parseLine("[" + repeat("{\"jsonrpc\":\"2.0\",\"method\":\"A.B\"},", 63)
                + "{\"jsonrpc\":\"2.0\",\"method\":\"A.B\"}]").size());
    }

    @Test
    public void anEmptyBatchGivesATypedException() {
        assertTypedFailure("[]");
    }

    @Test
    public void aLineOf2MiBGivesATypedException() {
        String line = "{\"jsonrpc\":\"2.0\",\"method\":\"A.B\",\"params\":{\"pad\":\"" + repeat("x", 2 * 1024 * 1024) + "\"}}";

        assertTypedFailure(line);
    }

    @Test
    public void hugeStringsInAStatusAreTruncatedNotKept() throws Exception {
        String huge = repeat("n", 400_000);
        String text = "{\"server\":{\"groups\":[{\"id\":\"" + huge + "\",\"name\":\"" + huge + "\",\"stream_id\":\"" + huge
                + "\",\"clients\":[{\"id\":\"c\",\"host\":{\"name\":\"" + huge + "\"},\"config\":{\"name\":\"" + huge + "\"}}]}],"
                + "\"streams\":[{\"id\":\"" + huge + "\",\"status\":\"" + huge + "\",\"uri\":{\"raw\":\"" + huge
                + "\",\"path\":\"" + huge + "\",\"query\":{\"" + huge + "\":\"" + huge + "\"}}}],"
                + "\"server\":{\"host\":{\"name\":\"" + huge + "\"},\"snapserver\":{\"version\":\"" + huge + "\"}}}}";

        SnapcastServerStatus status = SnapcastServerStatus.parseResult(text);

        int cap = SnapcastLimits.MAX_STRING_LENGTH;
        SnapcastGroup group = status.getGroups().get(0);
        assertTrue(group.getId().length() <= cap);
        assertTrue(group.getName().length() <= cap);
        assertTrue(group.getStreamId().length() <= cap);
        assertTrue(group.getClients().get(0).getName().length() <= cap);
        assertTrue(group.getClients().get(0).getHostName().length() <= cap);
        SnapcastStream stream = status.getStreams().get(0);
        assertTrue(stream.getId().length() <= cap);
        assertTrue(stream.getStatus().length() <= cap);
        assertTrue(stream.getRawUri().length() <= cap);
        assertTrue(stream.getPath().length() <= cap);
        for (java.util.Map.Entry<String, String> entry : stream.getQuery().entrySet()) {
            assertTrue(entry.getKey().length() <= cap);
            assertTrue(entry.getValue().length() <= cap);
        }
        assertTrue(status.getInfo().getHostName().length() <= cap);
        assertTrue(status.getInfo().getVersion().length() <= cap);
    }

    @Test
    public void outOfRangeNumbersWrittenAsTextAreClamped() throws Exception {
        String[] values = {"1e999", "-1e999", "99999999999999999999999999", "-99999999999999999999999999", "1.5e300",
                "-0.0", "0.1e-400", "2147483648", "-2147483649", "9223372036854775808"};
        for (String value : values) {
            String text = "{\"server\":{\"groups\":[{\"id\":\"g\",\"clients\":[{\"id\":\"c\",\"config\":{\"latency\":" + value
                    + ",\"volume\":{\"percent\":" + value + "}}}]}]}}";

            SnapcastClient client = SnapcastServerStatus.parseResult(text).findClient("c");

            assertTrue(value, client.getVolume().getPercent() >= 0 && client.getVolume().getPercent() <= 100);
            assertTrue(value, client.getLatencyMs() >= 0 && client.getLatencyMs() <= SnapcastLimits.MAX_LATENCY_MS);
        }
    }

    @Test
    public void aNumberWithAHugeExponentIsFastAndClamped() throws Exception {
        String text = "{\"server\":{\"groups\":[{\"id\":\"g\",\"clients\":[{\"id\":\"c\",\"config\":{\"latency\":1e2147483647,"
                + "\"volume\":{\"percent\":-1e2147483647}}}]}]}}";
        long start = System.nanoTime();
        SnapcastClient client;
        try {
            client = SnapcastServerStatus.parseResult(text).findClient("c");
        } catch (SnapcastException typed) {
            assertEquals(SnapcastException.Kind.MALFORMED, typed.getKind()); // refusing it is fine, too
            client = null;
        }

        assertTrue("took " + (System.nanoTime() - start) / 1_000_000 + " ms", (System.nanoTime() - start) < 2_000_000_000L);
        if (client != null) {
            assertEquals(10_000, client.getLatencyMs());
            assertEquals(0, client.getVolume().getPercent());
        }
    }

    @Test
    public void controlCharactersAndOddUnicodeInNamesDoNotBreakParsing() throws Exception {
        String name = "a\\u0000b\\u0007\\n\\r\\t\\u2028\\ud800 \\u202e<script>";
        String text = "{\"server\":{\"groups\":[{\"id\":\"g\",\"name\":\"" + name + "\",\"clients\":[]}]}}";

        SnapcastServerStatus status = SnapcastServerStatus.parseResult(text);

        assertEquals(1, status.getGroups().size());
    }

    @Test
    public void messagesOfEveryOddShapeGiveTypedResultsOrExceptions() throws Exception {
        String[] samples = {
                "{}", "{\"jsonrpc\":\"2.0\"}", "{\"id\":1}", "{\"id\":{},\"result\":{}}", "{\"id\":[],\"result\":{}}",
                "{\"id\":1,\"result\":[]}", "{\"id\":1,\"result\":5}", "{\"id\":1,\"error\":5}", "{\"id\":1,\"error\":[]}",
                "{\"id\":1,\"error\":{\"code\":\"x\",\"message\":{}}}", "{\"method\":5}", "{\"method\":\"\"}",
                "{\"method\":\"A.B\",\"params\":5}", "{\"method\":\"A.B\",\"params\":[1,2]}", "{\"method\":null}",
                "[null]", "[5]", "[[]]", "[\"x\"]", "[{}, null, 5]", "{\"id\":1,\"result\":null}",
                "{\"id\":1e999,\"result\":{}}", "{\"id\":99999999999999999999999999,\"result\":{}}"
        };
        for (String sample : samples) {
            try {
                SnapcastMessage.parseLine(sample);
            } catch (SnapcastException typed) {
                // fine: a typed exception is an allowed answer
            }
        }
    }

    @Test
    public void methodNamesAreShortAndShapedLikeServiceCalls() throws Exception {
        assertTrue(SnapcastMessage.parseLine("{\"jsonrpc\":\"2.0\",\"method\":\"Client.OnConnect\",\"params\":{}}")
                .get(0).getType() == SnapcastMessage.Type.NOTIFICATION);
        assertTypedFailure("{\"jsonrpc\":\"2.0\",\"method\":\"" + repeat("A", 500) + "\",\"params\":{}}");
        assertTypedFailure("{\"jsonrpc\":\"2.0\",\"method\":\"has space\",\"params\":{}}");
    }

    @Test
    public void aNotificationFloodAgainstTheUpdaterStaysCheap() throws Exception {
        final int[] reReads = {0};
        final long[] now = {0};
        SnapcastModelUpdater updater = new SnapcastModelUpdater(() -> now[0], () -> reReads[0]++);
        updater.setStatus(SnapcastFixtures.status("status_three_groups.json"));
        JSONObject params = new JSONObject().put("id", "nobody").put("volume", new JSONObject().put("percent", 5));

        for (int i = 0; i < 200_000; i++) {
            updater.apply("Client.OnVolumeChanged", params);
        }

        assertEquals("one re-read at most within one instant", 1, reReads[0]);
        assertEquals(SnapcastFixtures.status("status_three_groups.json"), updater.getStatus());
    }
}
